package com.wendigo.suzgec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wendigo.suzgec.core.DnsServer
import com.wendigo.suzgec.core.FilterRules
import com.wendigo.suzgec.core.Upstreams
import java.util.concurrent.Executors

class FilterService : Service() {

    private var server: DnsServer? = null
    private var started = false
    private var generation = 0

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "suzgec-load").apply { isDaemon = true }
    }

    @Volatile
    private var rules: FilterRules = FilterRules.EMPTY

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (running) {
                updateNotification()
                handler.postDelayed(this, 15000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Stats.init(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        if (!ensureForeground()) return START_NOT_STICKY

        when (action) {
            ACTION_RELOAD -> reloadServer()
            else -> if (server == null && !loading) reloadServer()
        }

        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 15000)
        return START_STICKY
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
            CrashLog.append(this, "FGS", e.toString())
            try {
                stopSelf()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun reloadServer() {
        val myGeneration = ++generation
        loading = true
        lastError = null
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

            val old = server
            server = null
            running = false
            boundPort = 0
            try {
                old?.stop()
            } catch (_: Exception) {
            }

            val upstream = Upstreams.create(store.upstreamKey, store.customDoh)
            val s = DnsServer(
                port = store.port,
                zeroAddressMode = store.blockMode == Store.MODE_ZERO,
                upstream = upstream,
                rulesProvider = { rules },
                onResult = { host, blocked -> Stats.onQuery(host, blocked) },
                onError = { message -> lastError = message }
            )
            try {
                s.start()
                if (myGeneration != generation) {
                    try {
                        s.stop()
                    } catch (_: Exception) {
                    }
                    return@execute
                }
                rules = loaded.rules
                ruleCount = loaded.kept
                sourceCount = loaded.sources
                server = s
                running = true
                boundPort = s.boundPort
                lastError = null
            } catch (e: Exception) {
                running = false
                lastError = "Port ${store.port} açılamadı. Başka bir uygulama kullanıyor olabilir."
                CrashLog.append(this, "PORT", e.toString())
                try {
                    s.stop()
                } catch (_: Exception) {
                }
            }
            loading = false
            handler.post { updateNotification() }
        }
    }

    private fun shutdown() {
        generation++
        loading = false
        handler.removeCallbacks(tick)
        server?.stop()
        server = null
        running = false
        boundPort = 0
        Stats.flush()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        generation++
        loading = false
        handler.removeCallbacks(tick)
        server?.stop()
        server = null
        running = false
        boundPort = 0
        Stats.flush()
        try {
            worker.shutdownNow()
        } catch (_: Exception) {
        }
        super.onDestroy()
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
            Intent(this, FilterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            loading -> getString(R.string.notif_title_loading)
            running -> getString(R.string.notif_title_running)
            else -> getString(R.string.notif_title_error)
        }
        val text = when {
            loading -> getString(R.string.notif_text_loading)
            running -> getString(
                R.string.notif_text_running,
                boundPort,
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

    companion object {
        private const val CHANNEL_ID = "suzgec_status"
        private const val NOTIF_ID = 220

        const val ACTION_START = "com.wendigo.suzgec.START"
        const val ACTION_STOP = "com.wendigo.suzgec.STOP"
        const val ACTION_RELOAD = "com.wendigo.suzgec.RELOAD"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var boundPort: Int = 0
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

        fun start(context: Context) {
            Store(context).serviceWanted = true
            val intent = Intent(context, FilterService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                lastError = "Servis başlatılamadı: ${e.message}"
                CrashLog.append(context, "START", e.toString())
            }
        }

        fun stop(context: Context, clearWanted: Boolean = true) {
            if (clearWanted) Store(context).serviceWanted = false
            val intent = Intent(context, FilterService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun reload(context: Context) {
            val intent = Intent(context, FilterService::class.java).setAction(ACTION_RELOAD)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                lastError = "Listeler yenilenemedi: ${e.message}"
                CrashLog.append(context, "RELOAD", e.toString())
            }
        }
    }
}
