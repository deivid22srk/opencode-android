package com.deivid.opencode.server

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.deivid.opencode.MainActivity
import com.deivid.opencode.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [OpencodeProcess] lifecycle and streams
 * log output to any UI subscribers.
 */
class OpencodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var process: OpencodeProcess? = null
    private var watchJob: Job? = null

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    inner class LocalBinder : Binder() {
        val service: OpencodeService get() = this@OpencodeService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 4096)
                val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: "127.0.0.1"
                val password = intent.getStringExtra(EXTRA_PASSWORD)
                startInForeground(port, hostname)
                launchServer(port, hostname, password)
            }
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startInForeground(port: Int, hostname: String) {
        startForeground(
            NotificationIds.FOREGROUND_NOTIFICATION,
            buildNotification(
                getString(R.string.notif_title_starting),
                getString(R.string.notif_text_starting),
                port = port,
                hostname = hostname,
            )
        )
    }

    private fun launchServer(port: Int, hostname: String, password: String?) {
        val proc = OpencodeProcess(this).also { process = it }
        scope.launch {
            _events.emit(ServerEvent.Starting)
            val result = proc.start(
                port = port,
                hostname = hostname,
                password = password,
                onLog = { line -> scope.launch { _logs.emit(line) } },
            )
            result
                .onSuccess { url ->
                    _events.emit(ServerEvent.Started(url = url, port = port, hostname = hostname))
                    updateNotification(url)
                    watchExit(proc)
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to start opencode", e)
                    _events.emit(ServerEvent.Failed(e.message ?: "Unknown error"))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
        }
    }

    private fun watchExit(proc: OpencodeProcess) {
        watchJob = scope.launch(Dispatchers.IO) {
            // Poll for exit; opencode runs forever, so this only completes on
            // crash or external kill.
            while (proc.isRunning()) {
                Thread.sleep(500)
            }
            _events.emit(ServerEvent.Stopped("opencode exited (pid ${proc.pid})"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun stopServer() {
        watchJob?.cancel()
        process?.stop()
        process = null
        scope.launch { _events.emit(ServerEvent.Stopped("Stopped by user")) }
    }

    private fun buildNotification(
        title: String,
        text: String,
        port: Int = 4096,
        hostname: String = "127.0.0.1",
    ): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OpencodeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NotificationIds.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .addAction(0, getString(R.string.server_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(url: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(
            NotificationIds.FOREGROUND_NOTIFICATION,
            buildNotification(
                getString(R.string.notif_title_running),
                getString(R.string.notif_text_running, url),
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        process?.stop()
        process = null
        scope.cancel()
    }

    companion object {
        const val ACTION_START = "com.deivid.opencode.START"
        const val ACTION_STOP = "com.deivid.opencode.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_PASSWORD = "password"

        fun start(
            ctx: Context,
            port: Int,
            hostname: String,
            password: String?,
        ) {
            val intent = Intent(ctx, OpencodeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_HOSTNAME, hostname)
                if (!password.isNullOrBlank()) putExtra(EXTRA_PASSWORD, password)
            }
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, OpencodeService::class.java).apply { action = ACTION_STOP })
        }

        private const val TAG = "OpencodeService"
    }
}

sealed interface ServerEvent {
    data object Starting : ServerEvent
    data class Started(val url: String, val port: Int, val hostname: String) : ServerEvent
    data class Stopped(val reason: String) : ServerEvent
    data class Failed(val message: String) : ServerEvent
}

