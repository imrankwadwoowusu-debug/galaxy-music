package com.music.bitchord.data.debrid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Keeps the local torrent seeder alive, visible, and user-stop-able. */
class DebridTorrentUploadService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val queue = Channel<Request>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var current: Request? = null

    private data class Request(val mediaId: String, val title: String, val artist: String)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "TorBox cache upload", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Seeding completed BitChord cache files to your TorBox account" },
        )
        scope.launch {
            while (true) {
                val request = withTimeoutOrNull(IDLE_STOP_MS) { queue.receive() } ?: break
                current = request
                notifyProgress()
                try {
                    // Let Media3 close its final cache span before taking the
                    // immutable, complete-cache snapshot.
                    delay(1_500)
                    DebridCache.uploadCompletedAddonCache(
                        this@DebridTorrentUploadService,
                        request.mediaId,
                        request.title,
                        request.artist,
                    )
                } catch (e: Exception) {
                    TrackLog.w(TAG, "TorBox cache upload failed for '${request.title}': ${e.message}")
                } finally {
                    queued.remove(request.mediaId)
                    current = null
                    notifyProgress()
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        if (intent?.action == ACTION_STOP) {
            queue.close()
            scope.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_MEDIA_ID)
        if (id != null && queued.add(id)) {
            queue.trySend(
                Request(
                    mediaId = id,
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                ),
            )
        }
        return START_NOT_STICKY
    }

    private fun promote() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val item = current
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DebridTorrentUploadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(item?.let { "Caching ${it.title} to TorBox" } ?: "Preparing TorBox cache")
            .setContentText(item?.artist ?: "Seeding from this device until TorBox finishes")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        queue.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BitChord"
        private const val CHANNEL = "debrid-torrent-upload"
        private const val NOTIFICATION_ID = 0x8176
        private const val IDLE_STOP_MS = 8_000L
        private const val ACTION_UPLOAD = "com.music.bitchord.debrid.UPLOAD_CACHE"
        private const val ACTION_STOP = "com.music.bitchord.debrid.STOP_UPLOAD"
        private const val EXTRA_MEDIA_ID = "media_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"

        fun enqueue(context: Context, mediaId: String, title: String, artist: String) {
            if (!DebridCache.needsLocalTorrentUpload(mediaId)) return
            val intent = Intent(context, DebridTorrentUploadService::class.java)
                .setAction(ACTION_UPLOAD)
                .putExtra(EXTRA_MEDIA_ID, mediaId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
