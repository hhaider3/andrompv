package dev.andrompv

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata as Media3Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Publishes the mpv player as a media session: the system media notification,
 * lock screen, Bluetooth/headset buttons and, when enabled, background audio.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = MpvSessionPlayer(applicationContext.player)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_play) }
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build().also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Mirrors [PlayerController] into Media3's player model; the controller stays the source of truth. */
@OptIn(UnstableApi::class)
private class MpvSessionPlayer(private val controller: PlayerController) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val listener: () -> Unit = { invalidateState() }

    init {
        controller.addSessionListener(listener)
    }

    override fun getState(): State {
        val items = controller.items
        val currentIndex = items.indexOfFirst { it.id == controller.currentId }
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_STOP,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_BACK,
            COMMAND_SEEK_FORWARD,
            COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SET_SPEED_AND_PITCH,
            COMMAND_SET_REPEAT_MODE,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
            COMMAND_RELEASE
        ).build()
        val playlist = items.map { item ->
            val isCurrent = item.id == controller.currentId
            val seconds = if (isCurrent && controller.duration > 0) controller.duration else item.metadata?.duration
            MediaItemData.Builder(item.id)
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(item.id)
                        .setMediaMetadata(
                            Media3Metadata.Builder()
                                .setTitle(item.title)
                                .setArtist(if (item.groupKind == GroupKind.STANDALONE) "andrompv" else item.groupTitle)
                                .setDisplayTitle(item.title)
                                .build()
                        )
                        .build()
                )
                .setDurationUs(seconds?.takeIf { it > 0 }?.let { (it * 1_000_000).toLong() } ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val playbackState = when {
            currentIndex < 0 -> STATE_IDLE
            controller.isLoading -> STATE_BUFFERING
            controller.hasFile -> STATE_READY
            else -> STATE_IDLE
        }
        val step = controller.settings.seekStep * 1000L
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .apply { if (currentIndex >= 0) setCurrentMediaItemIndex(currentIndex) }
            .setPlaybackState(playbackState)
            .setPlayWhenReady(!controller.isPaused, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(controller.isLoading)
            .setContentPositionMs { (controller.engine.position * 1000).toLong() }
            .setPlaybackParameters(PlaybackParameters(controller.speed.toFloat()))
            .setRepeatMode(
                when (controller.repeat) {
                    RepeatMode.OFF -> REPEAT_MODE_OFF
                    RepeatMode.ALL -> REPEAT_MODE_ALL
                    RepeatMode.ONE -> REPEAT_MODE_ONE
                }
            )
            .setSeekBackIncrementMs(step)
            .setSeekForwardIncrementMs(step)
            .setMaxSeekToPreviousPositionMs(3000)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        controller.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = controller.items.getOrNull(mediaItemIndex)
        when {
            target != null && target.id != controller.currentId -> controller.playItem(target.id)
            positionMs != C.TIME_UNSET -> controller.seek(positionMs / 1000.0)
            else -> controller.seek(0.0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        controller.setSpeed(playbackParameters.speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val wanted = when (repeatMode) {
            REPEAT_MODE_ONE -> RepeatMode.ONE
            REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        while (controller.repeat != wanted) controller.cycleRepeat()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.setPlaying(false)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        controller.removeSessionListener(listener)
        return Futures.immediateVoidFuture()
    }
}
