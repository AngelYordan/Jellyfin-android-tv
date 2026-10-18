package org.jellyfin.androidtv.ui.playback

import java.net.URI

/** A loading player or a single empty JSON-RPC response is not a playback stop. */
internal class KodiPlaybackStopDetector {

	private var playbackObserved = false
	private var noPlayerSinceMs: Long? = null

	fun reset() {
		playbackObserved = false
		noPlayerSinceMs = null
	}

	fun observeReadyPlayer() {
		playbackObserved = true
		noPlayerSinceMs = null
	}

	fun observeUnavailablePlayer() {
		// Require continuous successful JSON-RPC observations, not a network outage.
		noPlayerSinceMs = null
	}

	fun observeNoPlayer(nowMs: Long): Boolean {
		if (!playbackObserved) return false
		val sinceMs = noPlayerSinceMs ?: nowMs.also { noPlayerSinceMs = it }
		return nowMs - sinceMs >= STOP_CONFIRMATION_MS
	}

	companion object {
		private const val STOP_CONFIRMATION_MS = 6_000L

		fun isReadyPlayer(
			expectedStreamUrl: String?,
			playingFile: String?,
			positionMs: Long?,
			durationMs: Long?,
		): Boolean {
			if (expectedStreamUrl == null || playingFile == null || positionMs == null || positionMs < 0 || durationMs == null || durationMs <= 0) {
				return false
			}
			return runCatching {
				val expected = URI(expectedStreamUrl)
				val playing = URI(playingFile.substringBefore('|'))
				expected.scheme.equals(playing.scheme, ignoreCase = true) &&
					expected.host.equals(playing.host, ignoreCase = true) &&
					expected.port == playing.port &&
					expected.path.equals(playing.path, ignoreCase = true)
			}.getOrDefault(false)
		}
	}
}
