package org.jellyfin.androidtv.ui.playback

import android.content.Context
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * Utility class to launch the playback UI for an item.
 */
class PlaybackLauncher(
	private val mediaManager: MediaManager,
	private val videoQueueManager: VideoQueueManager,
	private val navigationRepository: NavigationRepository,
	private val userPreferences: UserPreferences,
) {
	private val BaseItemDto.supportsExternalPlayer
		get() = when (type) {
			BaseItemKind.MOVIE,
			BaseItemKind.EPISODE,
			BaseItemKind.VIDEO,
			BaseItemKind.SERIES,
			BaseItemKind.SEASON,
			BaseItemKind.RECORDING,
			BaseItemKind.TV_CHANNEL,
			BaseItemKind.PROGRAM,
				-> true

			else -> false
		}

	@JvmOverloads
	fun launch(
		context: Context,
		items: List<BaseItemDto>,
		position: Int? = null,
		replace: Boolean = false,
		itemsPosition: Int = 0,
		shuffle: Boolean = false,
	) {
		val isAudio = items.any { it.mediaType == MediaType.AUDIO }

		if (isAudio) {
			mediaManager.playNow(context, items, itemsPosition, shuffle)
			navigationRepository.navigate(Destinations.nowPlaying)
		} else {
			val items = if (shuffle) items.shuffled() else items

			videoQueueManager.setCurrentVideoQueue(items.toList())
			videoQueueManager.setCurrentMediaPosition(itemsPosition)

			if (items.isEmpty()) return

			val startPositionMs = position?.coerceAtLeast(0) ?: 0
			val startPositionTicks = startPositionMs * 10000L
			val launchItem = items.getOrNull(itemsPosition) ?: items.first()
			Timber.i("Launching playback item=${launchItem.id} finalStartPositionTicks=$startPositionTicks")

			if (userPreferences[UserPreferences.useExternalPlayer] && items.all { it.supportsExternalPlayer }) {
				Timber.i("Launching external playback item=${launchItem.id} finalStartPositionTicks=$startPositionTicks")
				context.startActivity(ActivityDestinations.externalPlayer(context, startPositionMs.milliseconds))
			} else if (userPreferences[UserPreferences.playbackRewriteVideoEnabled]) {
				val destination = Destinations.videoPlayerNew(startPositionMs)
				navigationRepository.navigate(destination, replace)
			} else {
				val destination = Destinations.videoPlayer(startPositionMs)
				navigationRepository.navigate(destination, replace)
			}
		}
	}

	@JvmOverloads
	fun launchExternalPlayerPackage(
		context: Context,
		items: List<BaseItemDto>,
		externalPlayerPackage: String,
		position: Int = 0,
		itemsPosition: Int = 0,
		shuffle: Boolean = false,
	) {
		val items = if (shuffle) items.shuffled() else items

		videoQueueManager.setCurrentVideoQueue(items.toList())
		videoQueueManager.setCurrentMediaPosition(itemsPosition)

		if (items.isEmpty()) return

		val startPositionMs = position.coerceAtLeast(0)
		val startPositionTicks = startPositionMs * 10000L
		val launchItem = items.getOrNull(itemsPosition) ?: items.first()
		if (externalPlayerPackage == "org.xbmc.kodi") {
			Timber.i("Launching Kodi playback requestedStartTicks=$startPositionTicks requestedStartMs=$startPositionMs")
		} else {
			Timber.i("Launching external playback with package=$externalPlayerPackage positionTicks=$startPositionTicks item=${launchItem.id}")
		}
		context.startActivity(
			ActivityDestinations.externalPlayer(
				context,
				startPositionMs.milliseconds,
				externalPlayerPackage = externalPlayerPackage,
			)
		)
	}
}
