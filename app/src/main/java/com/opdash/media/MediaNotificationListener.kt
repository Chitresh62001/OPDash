package com.opdash.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.opdash.connection.DashConnectionService
import com.opdash.logging.Logger

/**
 * Listens for media session changes system-wide and extracts track metadata
 * (title, artist, album) to forward to the Tripper Dash via the connection service.
 *
 * Requires the user to grant Notification Listener access in Settings.
 */
class MediaNotificationListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onActiveSessionsChanged(controllers)
        }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let { extractMetadata(it) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let {
                val isPlaying = it.state == PlaybackState.STATE_PLAYING
                DashConnectionService.isMusicPlaying = isPlaying
                Logger.d("Media playback state: ${if (isPlaying) "PLAYING" else "PAUSED"}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.d("MediaNotificationListener created.")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Logger.d("Notification listener connected. Registering media session listener.")

        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager

        val componentName = ComponentName(this, MediaNotificationListener::class.java)

        try {
            sessionManager?.addOnActiveSessionsChangedListener(
                sessionListener, componentName
            )

            // Check for already-active sessions
            val activeSessions = sessionManager?.getActiveSessions(componentName)
            onActiveSessionsChanged(activeSessions)
        } catch (e: SecurityException) {
            Logger.d("Security exception registering session listener: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Logger.d("Notification listener disconnected.")
        cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // We don't need individual notifications; metadata comes from MediaSession
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            Logger.d("No active media sessions.")
            DashConnectionService.isMusicPlaying = false
            return
        }

        // Detach from previous controller
        activeController?.unregisterCallback(mediaCallback)

        // Attach to the first active controller (usually the current music player)
        val controller = controllers[0]
        activeController = controller
        controller.registerCallback(mediaCallback)

        Logger.d("Attached to media session: ${controller.packageName}")

        // Read current state immediately
        controller.metadata?.let { extractMetadata(it) }
        controller.playbackState?.let {
            val isPlaying = it.state == PlaybackState.STATE_PLAYING
            DashConnectionService.isMusicPlaying = isPlaying
        }
    }

    private fun extractMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        DashConnectionService.currentTrackTitle = title
        DashConnectionService.currentTrackArtist = artist
        DashConnectionService.currentTrackAlbum = album

        Logger.d("Now Playing: \"$title\" by $artist ($album)")
    }

    private fun cleanup() {
        activeController?.unregisterCallback(mediaCallback)
        activeController = null
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        sessionManager = null
    }
}
