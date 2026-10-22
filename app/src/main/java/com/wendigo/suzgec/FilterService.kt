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

class FilterService : Service() {

    private var server: DnsServer? = null
    private var started = false

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

        ensureForeground()

        when (action) {
            ACTION_RELOAD -> startServer()
            else -> if (server == null) startServer()
        }

        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 15000)
        return START_STICKY
    }

    private fun ensureForeground() {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            started = true
        } catch (_: Exception) {
        }
    }

    private fun startServer() {
        server?.stop()
        server = null
        running = false
        boundPort = 0
        lastError = null

        val store = Store(this)
        val loaded = try {
            FilterStore(this).load(store.useBundled, store.userLists)
        } catch (e: Exception) {
            lastError = "Liste yüklenemedi: ${e.message}"
            updateNotification()
            return
        }
        rules = loaded.rules
        ruleCount = loaded.kept
        sourceCount = loaded.sources

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
            server = s
            running = true
            boundPort = s.boundPort
        } catch (e: Exception) {
            running = false
            lastError = "Port ${store.port} açılamadı. Başka bir uygulama kullanıyor olabilir."
            try {
                s.stop()
            } catch (_: Exception) {
            }
        }
        updateNotification()
    }

    private fun shutdown() {
        handler.removeCallbacks(tick)
        server?.stop()
        server = null
        running = false
        boundPort = 0
        Store(this).serviceWanted = false
        Stats.flush()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        server?.stop()
        server = null
        running = false
        boundPort = 0
        Stats.flush()
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

        val title = if (running) {
            getString(R.string.notif_title_running)
        } else {
            getString(R.string.notif_title_error)
        }
        val text = when {
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

        fun start(context: Context) {
            Store(context).serviceWanted = true
            val intent = Intent(context, FilterService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            Store(context).serviceWanted = false
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
            } catch (_: Exception) {
            }
        }
    }
}
