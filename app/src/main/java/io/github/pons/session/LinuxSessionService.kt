package io.github.pons.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps Linux alive and online while a game runs. Android cuts network and may kill
 * background apps, so the session runs inside a foreground service.
 */
class LinuxSessionService : Service() {

    /** Sessions run one at a time; a new request first stops the running one. */
    private val sessions = Executors.newSingleThreadExecutor { Thread(it, "pons-session") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LinuxSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val program = intent?.getStringExtra(EXTRA_PROGRAM) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification(program),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (LinuxSession.claim(applicationContext, program)) {
            // Background Steam is running: start the game inside it, on its own thread
            // (the session thread is busy running Steam). Counted like a session, so
            // Steam exiting does not stop the service under the game.
            pending.incrementAndGet()
            Thread({
                LinuxSession.join(applicationContext, program)
                if (pending.decrementAndGet() == 0) stopSelf()
            }, "pons-join").start()
            return START_NOT_STICKY
        }
        LinuxSession.stop()
        pending.incrementAndGet()
        sessions.execute {
            LinuxSession.start(applicationContext, program)
            if (pending.decrementAndGet() == 0) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LinuxSession.stop()
        sessions.shutdownNow()
        super.onDestroy()
    }

    private fun notification(program: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Linux running", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Pons: Linux is running")
            .setContentText(program.take(60))
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Sessions and joined games requested and not finished (busy even while starting). */
        private val pending = AtomicInteger()

        /** True from the moment a session is requested until everything has ended. */
        fun busy(): Boolean = pending.get() > 0

        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_PROGRAM = "program"
        private const val ACTION_STOP = "stop"

        fun start(context: Context, program: String) {
            context.startForegroundService(
                Intent(context, LinuxSessionService::class.java).putExtra(EXTRA_PROGRAM, program))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LinuxSessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
