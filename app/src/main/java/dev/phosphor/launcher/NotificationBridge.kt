package dev.phosphor.launcher

import android.app.Notification
import android.app.ActivityOptions
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Process-local snapshots; notification contents never leave the device. */
class NotificationBridge : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = LinkedHashMap<MediaController, MediaController.Callback>()
    private var sessionManager: MediaSessionManager? = null
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        attachSessions(sessions.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        replaceNotices(runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList()))
        sessionManager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, NotificationBridge::class.java)
        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, component, handler)
            attachSessions(sessionManager?.getActiveSessions(component).orEmpty())
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            val notice = toNotice(sbn)
            synchronized(lock) { noticeSnapshot = (noticeSnapshot.filterNot { it.key == notice.key } + notice).sortedByDescending { it.time } }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) synchronized(lock) { noticeSnapshot = noticeSnapshot.filterNot { it.key == sbn.key } }
    }

    override fun onListenerDisconnected() {
        disconnect()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionListener) }
        controllers.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        controllers.clear()
        sessionManager = null
        if (instance === this) {
            instance = null
            synchronized(lock) { noticeSnapshot = emptyList(); selectedController = null }
        }
        handler.removeCallbacksAndMessages(null)
    }

    private fun replaceNotices(notices: List<StatusBarNotification>) {
        synchronized(lock) { noticeSnapshot = notices.map(::toNotice).sortedByDescending { it.time } }
    }

    private fun toNotice(sbn: StatusBarNotification): Notice {
        val notification = sbn.notification
        val extras = notification.extras
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: lines ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return Notice(sbn.key, app, title, text, sbn.postTime, notification.contentIntent)
    }

    private fun attachSessions(sessions: List<MediaController>) {
        controllers.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        controllers.clear()
        sessions.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = selectSession()
                override fun onMetadataChanged(metadata: MediaMetadata?) = selectSession()
                override fun onSessionDestroyed() {
                    controllers.remove(controller)?.let { runCatching { controller.unregisterCallback(it) } }
                    selectSession()
                }
            }
            runCatching { controller.registerCallback(callback, handler); controllers[controller] = callback }
        }
        selectSession()
    }

    private fun selectSession() {
        val selected = controllers.keys.firstOrNull {
            val state = it.playbackState?.state
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING
        } ?: controllers.keys.firstOrNull { it.metadata != null }
        synchronized(lock) { selectedController = selected }
    }

    companion object {
        private val lock = Any()
        @Volatile private var instance: NotificationBridge? = null
        private var noticeSnapshot: List<Notice> = emptyList()
        private var selectedController: MediaController? = null

        val connected: Boolean get() = instance != null
        val notifications: List<Notice> get() = synchronized(lock) { noticeSnapshot }
        val media: MediaState?
            get() {
                val controller = synchronized(lock) { selectedController } ?: return null
                return runCatching {
                    val metadata = controller.metadata
                    val playback = controller.playbackState
                    val playing = playback?.state == PlaybackState.STATE_PLAYING
                    val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
                    var position = playback?.position?.coerceAtLeast(0L) ?: 0L
                    if (playing && playback != null && playback.lastPositionUpdateTime > 0L) {
                        position += ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime).coerceAtLeast(0L) * playback.playbackSpeed).toLong()
                    }
                    if (duration > 0) position = position.coerceIn(0L, duration)
                    MediaState(
                        metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                        metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                        playing, position, duration
                    )
                }.getOrNull()
            }

        fun isEnabled(context: Context): Boolean {
            val name = ComponentName(context, NotificationBridge::class.java)
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == name } == true
        }

        fun dismiss(key: String) { runCatching { instance?.cancelNotification(key) } }

        // Called only by an explicit foreground tap. Android 14+ requires sender BAL opt-in.
        fun open(key: String): Boolean {
            val context = instance ?: return false
            val intent = notifications.firstOrNull { it.key == key }?.intent ?: return false
            return runCatching {
                val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle() else null
                intent.send(context, 0, null, null, null, null, options)
                true
            }.getOrDefault(false)
        }

        fun control(action: String) {
            val controller = synchronized(lock) { selectedController } ?: return
            runCatching {
                val controls = controller.transportControls
                when (action.lowercase()) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "toggle", "play/pause", "playpause" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                    "previous", "prev", "back" -> controls.skipToPrevious()
                    "next" -> controls.skipToNext()
                    "stop" -> controls.stop()
                    "rewind" -> controls.rewind()
                    "forward", "fast forward" -> controls.fastForward()
                }
            }
        }
    }
}
