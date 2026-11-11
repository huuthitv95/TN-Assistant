package vn.tn.assistant.actions

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import vn.tn.assistant.TnMediaAccessService
import java.util.Locale

/** TN-owned MediaSession transport; no vendor keys, hidden API or legacy runtime. */
internal class TnMediaControls(private val context: Context, private val active: () -> Boolean) {
    fun execute(payload: JSONObject, details: JSONObject): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Media confirmation must run off the UI thread" }
        val command = command(payload.optString("command").ifBlank { payload.optString("action") })
            ?: return outcome(details, "unsupported", "media_command_not_implemented")
        if (!running()) return "cancelled"
        if (!TnMediaAccessService.hasAccess(context)) return outcome(details, "failed", "notification_access_required")
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val listener = ComponentName(context, TnMediaAccessService::class.java)
        // Android's first controller has highest priority, matching original l/d0.
        // Do not switch to another player if this player's action is unsupported.
        val controller = manager.getActiveSessions(listener).firstOrNull()
            ?: return outcome(details, "failed", "no_active_media_session")
        val before = controller.playbackState ?: return outcome(details, "failed", "media_state_unavailable")
        details.put("media_command", command.name.lowercase(Locale.ROOT))
            .put("media_package", controller.packageName).put("media_state_before", before.state)
            .put("transport_dispatched", false)
        val required = when (command) {
            Command.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            Command.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
            Command.PLAY -> PlaybackState.ACTION_PLAY
            Command.REPLAY -> PlaybackState.ACTION_SEEK_TO
            Command.PAUSE -> PlaybackState.ACTION_PAUSE
        }
        if (before.actions and required != required) return outcome(details, "unsupported", "media_action_not_supported")
        if ((command == Command.PLAY && before.state == PlaybackState.STATE_PLAYING) ||
            (command == Command.PAUSE && before.state == PlaybackState.STATE_PAUSED)) {
            details.put("media_state_observed", before.state)
            return outcome(details, "success", "media_state_already_satisfied")
        }
        val track = track(controller.metadata)
        if (!running()) return "cancelled"
        val controls = controller.transportControls
        when (command) {
            Command.NEXT -> controls.skipToNext()
            Command.PREVIOUS -> { controls.seekTo(0); if (!running()) return "cancelled"; controls.skipToPrevious() }
            Command.PLAY -> controls.play()
            Command.REPLAY -> controls.seekTo(0)
            // Original STOP is pause, preserving the track and its position.
            Command.PAUSE -> controls.pause()
        }
        details.put("transport_dispatched", true)
        val deadline = SystemClock.elapsedRealtime() + 2500
        var confirmedSince = 0L
        var confirmedKey: Triple<Int, Long, List<String>?>? = null
        while (running()) {
            if (!TnMediaAccessService.hasAccess(context)) return outcome(details, "failed", "notification_access_revoked")
            if (manager.getActiveSessions(listener).none { it.sessionToken == controller.sessionToken })
                return outcome(details, "failed", "media_session_ended")
            val after = controller.playbackState
            if (after != null) {
                details.put("media_state_observed", after.state)
                if (after.state == PlaybackState.STATE_ERROR) return outcome(details, "failed", "media_player_error")
                val nowTrack = track(controller.metadata)
                val confirmed = when (command) {
                    Command.PLAY -> after.state == PlaybackState.STATE_PLAYING
                    Command.PAUSE -> after.state == PlaybackState.STATE_PAUSED
                    Command.REPLAY -> after.position in 0..1500 &&
                        (track == null || nowTrack == track) &&
                        (before.position in 0..1500 || after.lastPositionUpdateTime > before.lastPositionUpdateTime)
                    Command.NEXT, Command.PREVIOUS -> {
                        (track != null && nowTrack != null && nowTrack != track) ||
                            (before.activeQueueItemId != MediaSession.QueueItem.UNKNOWN_ID.toLong() && after.activeQueueItemId != MediaSession.QueueItem.UNKNOWN_ID.toLong() &&
                                after.activeQueueItemId != before.activeQueueItemId)
                    }
                }
                // Metadata and PlaybackState are separate Binder updates. A new
                // title can arrive just before STATE_ERROR; require a stable,
                // usable state instead of acknowledging that transient pair.
                if (confirmed && after.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)) {
                    val key = Triple(after.state, after.activeQueueItemId, nowTrack)
                    if (key != confirmedKey) {
                        confirmedKey = key
                        confirmedSince = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - confirmedSince >= 100) {
                        return outcome(details, "success", "media_state_confirmed")
                    }
                } else { confirmedKey = null; confirmedSince = 0 }
            }
            if (SystemClock.elapsedRealtime() >= deadline) return outcome(details, "failed", "media_result_unconfirmed")
            Thread.sleep(25)
        }
        return "cancelled"
    }

    private fun running() = !Thread.currentThread().isInterrupted && active()
    private fun outcome(details: JSONObject, result: String, reason: String): String {
        details.put("reason", reason)
        return result
    }

    // Compare in memory only. Track titles, IDs and account data never enter receipts.
    private fun track(metadata: MediaMetadata?): List<String>? {
        if (metadata == null) return null
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (!id.isNullOrBlank()) return listOf(id)
        val fields = listOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM)
            .map { metadata.getString(it).orEmpty() }
        return fields.takeIf { it.any(String::isNotBlank) }
    }

    private enum class Command { NEXT, PREVIOUS, PLAY, REPLAY, PAUSE }
    private fun command(value: String) = when (value.trim().lowercase(Locale.ROOT)) {
        "music_control_next", "next", "song.next", "song.next_song" -> Command.NEXT
        "music_control_prev", "prev", "previous", "song.previous", "song.previous_song" -> Command.PREVIOUS
        "music_control_resume", "resume", "play", "song.resume", "song.resume_or_random_song" -> Command.PLAY
        "music_control_replay", "replay", "song.replay" -> Command.REPLAY
        "music_control_stop", "stop", "pause", "song.pause" -> Command.PAUSE
        else -> null
    }
}
