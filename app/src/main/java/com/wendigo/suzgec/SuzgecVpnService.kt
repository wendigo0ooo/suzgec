package com.wendigo.suzgec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wendigo.suzgec.core.DnsEngine
import com.wendigo.suzgec.core.DnsWire
import com.wendigo.suzgec.core.FilterRules
import com.wendigo.suzgec.core.IpPacket
import com.wendigo.suzgec.core.Upstreams
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

class SuzgecVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null

    @Volatile
    private var engine: DnsEngine? = null
    private var started = false
    private var generation = 0

    @Volatile
    private var rules: FilterRules = FilterRules.EMPTY

    private val writeLock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "suzgec-vpn-load").apply { isDaemon = true }
    }
    private val dnsPool = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "suzgec-vpn-dns").apply { isDaemon = true }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (active) {
                updateNotification()
                handler.postDelayed(this, 15000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Stats.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            val clearWanted = intent?.getBooleanExtra(EXTRA_CLEAR_WANTED, true) ?: true
            shutdown(clearWanted)
            return START_NOT_STICKY
        }

        if (!ensureForeground()) return START_NOT_STICKY

        when (action) {
            ACTION_RELOAD -> {
                rebuildEngine()
                reloadRules()
            }
            else -> if (tunnel == null && !loading) startTunnel()
        }

        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 15000)
        return START_STICKY
    }

    override fun onRevoke() {
        val store = Store(this)
        stopTunnel()
        val anotherVpn = VpnStatus.snapshot(this).vpnActive
        if (store.serviceWanted && anotherVpn) {
            store.mode = Store.MODE_TUNNEL
            lastError = getString(R.string.vpn_revoked_fallback)
            try {
                FilterService.start(this)
            } catch (_: Exception) {
            }
        } else if (!anotherVpn) {
            store.serviceWanted = false
        }
        active = false
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        generation++
        loading = false
        handler.removeCallbacks(tick)
        stopTunnel()
        Stats.flush()
        try {
            worker.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            dnsPool.shutdownNow()
        } catch (_: Exception) {
        }
        active = false
        super.onDestroy()
    }

    private fun ensureForeground(): Boolean {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            started = true
            true
        } catch (e: Exception) {
            started = false
            lastError = "Bildirim başlatılamadı: ${e.message}"
            CrashLog.append(this, "VPN-FGS", e.toString())
            try {
                stopSelf()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun startTunnel() {
        val store = Store(this)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(DNS_V4, 24)
            .addDnsServer(DNS_V4)
            .addRoute(DNS_V4, 32)
            .addAddress(DNS_V6, 64)
            .addDnsServer(DNS_V6)
            .addRoute(DNS_V6, 128)
            .allowFamily(android.system.OsConstants.AF_INET)
            .allowFamily(android.system.OsConstants.AF_INET6)
            .setBlocking(true)

        builder.setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            CrashLog.append(this, "VPN-ESTABLISH", e.toString())
            null
        }
        if (descriptor == null) {
            active = false
            lastError = getString(R.string.vpn_establish_failed)
            CrashLog.append(this, "VPN", "establish() null")
            stopForegroundCompat()
            stopSelf()
            return
        }

        tunnel = descriptor
        input = FileInputStream(descriptor.fileDescriptor)
        output = FileOutputStream(descriptor.fileDescriptor)
        val created = DnsEngine(
            zeroAddressMode = store.blockMode == Store.MODE_ZERO,
            upstream = Upstreams.create(
                store.upstreamKey,
                store.customDoh,
                SocketProtectorImpl(this)
            ),
            rulesProvider = { rules },
            onResult = { host, blocked -> Stats.onQuery(host, blocked) },
            onError = { message -> lastError = message }
        )
        engine = created
        engineRef = created
        active = true
        lastError = null
        startReader()
        reloadRules()
    }

    private fun rebuildEngine() {
        if (tunnel == null) return
        val store = Store(this)
        val old = engine
        val created = DnsEngine(
            zeroAddressMode = store.blockMode == Store.MODE_ZERO,
            upstream = Upstreams.create(
                store.upstreamKey,
                store.customDoh,
                SocketProtectorImpl(this)
            ),
            rulesProvider = { rules },
            onResult = { host, blocked -> Stats.onQuery(host, blocked) },
            onError = { message -> lastError = message }
        )
        engine = created
        engineRef = created
        try {
            old?.close()
        } catch (_: Exception) {
        }
    }

    private fun startReader() {
        val stream = input ?: return
        val sink = output ?: return
        reader = Thread({
            val buffer = ByteArray(MTU + 256)
            while (active) {
                val read = try {
                    stream.read(buffer)
                } catch (_: Exception) {
                    if (!active) break
                    continue
                }
                if (read <= 0) continue
                val datagram = try {
                    IpPacket.parseUdp(buffer, read)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (datagram.destinationPort != DNS_PORT) continue
                dnsPool.execute {
                    val resolver = engine ?: return@execute
                    val answer = try {
                        resolver.handle(datagram.payload, datagram.payload.size)
                    } catch (e: Exception) {
                        CrashLog.append(this@SuzgecVpnService, "VPN-HANDLE", e.toString())
                        null
                    } ?: return@execute
                    val packet = try {
                        IpPacket.buildResponse(datagram, answer)
                    } catch (_: Exception) {
                        null
                    } ?: return@execute
                    if (packet.size > MTU) return@execute
                    synchronized(writeLock) {
                        try {
                            sink.write(packet)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }, "suzgec-vpn-tun").apply {
            isDaemon = true
            start()
        }
    }

    private fun reloadRules() {
        val myGeneration = ++generation
        loading = true
        updateNotification()
        worker.execute {
            val store = Store(this)
            val loaded = try {
                FilterStore(this).load(store.useBundled, store.userLists)
            } catch (e: Exception) {
                if (myGeneration == generation) {
                    lastError = "Liste yüklenemedi: ${e.message}"
                    CrashLog.append(this, "LISTE", e.toString())
                    loading = false
                    handler.post { updateNotification() }
                }
                return@execute
            }
            if (myGeneration != generation) return@execute
            rules = loaded.rules
            ruleCount = loaded.kept
            sourceCount = loaded.sources
            loading = false
            lastError = null
            handler.post { updateNotification() }
        }
    }

    private fun stopTunnel() {
        active = false
        try {
            reader?.interrupt()
        } catch (_: Exception) {
        }
        reader = null
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            tunnel?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        tunnel = null
        try {
            engine?.close()
        } catch (_: Exception) {
        }
        engine = null
        engineRef = null
        Stats.flush()
    }

    private fun shutdown(clearWanted: Boolean) {
        generation++
        loading = false
        handler.removeCallbacks(tick)
        if (clearWanted) Store(this).serviceWanted = false
        stopTunnel()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        started = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SuzgecVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            loading -> getString(R.string.notif_title_loading)
            active -> getString(R.string.notif_vpn_running)
            !lastError.isNullOrEmpty() -> getString(R.string.notif_title_error)
            else -> getString(R.string.notif_title_loading)
        }
        val text = when {
            loading -> getString(R.string.notif_text_loading)
            active -> getString(
                R.string.notif_vpn_text,
                ruleCount,
                Stats.todayBlockedCount()
            )
            !lastError.isNullOrEmpty() -> lastError ?: ""
            else -> getString(R.string.notif_text_idle)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun updateNotification() {
        if (!started) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private class SocketProtectorImpl(
        private val service: VpnService
    ) : com.wendigo.suzgec.core.SocketProtector {
        override fun protect(socket: java.net.Socket): Boolean = service.protect(socket)

        override fun protectDatagram(socket: java.net.DatagramSocket): Boolean =
            service.protect(socket)
    }

    companion object {
        private const val CHANNEL_ID = "suzgec_status"
        private const val NOTIF_ID = 221
        private const val MTU = 4096
        private const val DNS_PORT = 53

        const val DNS_V4 = "10.111.222.1"
        const val DNS_V6 = "fd00:53:75:7a::1"

        const val ACTION_START = "com.wendigo.suzgec.VPN_START"
        const val ACTION_STOP = "com.wendigo.suzgec.VPN_STOP"
        const val ACTION_RELOAD = "com.wendigo.suzgec.VPN_RELOAD"
        private const val EXTRA_CLEAR_WANTED = "clear_wanted"

        @Volatile
        var active: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var ruleCount: Int = 0
            private set

        @Volatile
        var sourceCount: Int = 0
            private set

        @Volatile
        var loading: Boolean = false
            private set

        @Volatile
        private var engineRef: DnsEngine? = null

        fun start(context: Context): Boolean {
            Store(context).serviceWanted = true
            val intent = Intent(context, SuzgecVpnService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                lastError = "VPN servisi başlatılamadı: ${e.message}"
                CrashLog.append(context, "VPN-START", e.toString())
                false
            }
        }

        fun stop(context: Context, clearWanted: Boolean = true) {
            if (clearWanted) Store(context).serviceWanted = false
            val intent = Intent(context, SuzgecVpnService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_CLEAR_WANTED, clearWanted)
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun testQuery(host: String): String? {
            val resolver = engineRef ?: return null
            val query = DnsWire.buildQuery(host)
            val answer = try {
                resolver.handle(query)
            } catch (_: Exception) {
                return null
            } ?: return null
            return when (val code = DnsWire.rcode(answer)) {
                0 -> if (DnsWire.readU16(answer, 6) > 0) "NOERROR" else "BOŞ"
                2 -> "SERVFAIL"
                3 -> "NXDOMAIN"
                else -> "RCODE $code"
            }
        }

        fun reload(context: Context) {
            val intent = Intent(context, SuzgecVpnService::class.java).setAction(ACTION_RELOAD)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                lastError = "Listeler yenilenemedi: ${e.message}"
                CrashLog.append(context, "VPN-RELOAD", e.toString())
            }
        }
    }
}
