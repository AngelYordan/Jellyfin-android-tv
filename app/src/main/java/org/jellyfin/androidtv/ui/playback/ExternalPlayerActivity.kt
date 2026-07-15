package org.jellyfin.androidtv.ui.playback

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Activity that, once opened, opens the first item of the [VideoQueueManager.getCurrentVideoQueue] list in an external media player app.
 * Once returned it will notify the server of item completion.
 */
class ExternalPlayerActivity : FragmentActivity() {
	companion object {
		const val EXTRA_POSITION = "position"
		private const val EXTERNAL_PLAYBACK_PROGRESS_INTERVAL_MS = 10_000L

		// https://mx.j2inter.com/api
		private const val API_MX_TITLE = "title"
		private const val API_MX_SEEK_POSITION = "position"
		private const val API_MX_FILENAME = "filename"
		private const val API_MX_RESULT_POSITION = "position"
		private const val API_MX_SUBS = "subs"
		private const val API_MX_SUBS_NAME = "subs.name"
		private const val API_MX_SUBS_FILENAME = "subs.filename"

		private const val API_EXTERNAL_SECURE_URI = "secure_uri"
		private const val API_EXTERNAL_RETURN_RESULT = "return_result"

		// https://wiki.videolan.org/Android_Player_Intents/
		private const val API_VLC_SUBTITLES = "subtitles_location"
		private const val API_VLC_RESULT_POSITION = "extra_position"
		private const val API_VLC_RESULT_DURATION = "extra_duration"

		// https://www.vimu.tv/player-api
		private const val API_VIMU_TITLE = "forcename"
		private const val API_VIMU_SEEK_POSITION = "startfrom"
		private const val API_VIMU_RESUME = "forceresume"
		private const val API_VIMU_RESULT_ID = "net.gtvbox.videoplayer.result"
		private const val API_VIMU_RESULT_ERROR = 4

		// The extra keys used by various video players to read the end position
		private val resultPositionExtras = arrayOf(API_MX_RESULT_POSITION, API_VLC_RESULT_POSITION)
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val api by inject<ApiClient>()

	private val playVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		Timber.i("External player returned resultCode=${result.resultCode}")
		cancelExternalPlaybackProgressJob()
		videoQueueManager.setCurrentMediaPosition(videoQueueManager.getCurrentMediaPosition() + 1)

		if (result.isError) {
			Toast.makeText(this, R.string.video_error_unknown_error, Toast.LENGTH_LONG).show()
			onItemFinished(result.data, failed = true)
		} else {
			onItemFinished(result.data)
		}
	}

	private val ActivityResult.isError get() = when (data?.action) {
		API_VIMU_RESULT_ID -> resultCode == API_VIMU_RESULT_ERROR
		else -> false
	}

	private var currentItem: Pair<BaseItemDto, MediaSourceInfo>? = null
	private var externalPlaybackStartElapsedMs = 0L
	private var externalPlaybackStartPosition = Duration.ZERO
	private var externalPlaybackProgressJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val position = intent.getLongExtra(EXTRA_POSITION, 0).milliseconds
		playNext(position)
	}

	private fun playNext(position: Duration = Duration.ZERO) {
		val currentPosition = videoQueueManager.getCurrentMediaPosition()
		val item = videoQueueManager.getCurrentVideoQueue().getOrNull(currentPosition) ?: return finish()
		val mediaSource = item.mediaSources?.firstOrNull { it.id?.toUUIDOrNull() == item.id }

		if (mediaSource == null) {
			Toast.makeText(this, R.string.msg_no_playable_items, Toast.LENGTH_LONG).show()
			finish()
		} else {
			playItem(item, mediaSource, position)
		}
	}

	private fun playItem(item: BaseItemDto, mediaSource: MediaSourceInfo, position: Duration) {
		val url = api.videosApi.getVideoStreamUrl(
			itemId = item.id,
			mediaSourceId = mediaSource.id,
			static = true,
		)

		val title = item.getDisplayName(this)
		val fileName = mediaSource.path?.let { File(it).name }
		val externalSubtitles = mediaSource.mediaStreams
			?.filter { it.type == MediaStreamType.SUBTITLE && it.isExternal }
			?.sortedWith(compareBy<MediaStream> { it.isDefault }.thenBy { it.index })
			.orEmpty()

		val subtitleUrls = externalSubtitles.map { mediaStream ->
			// We cannot use the DeliveryUrl as that is only populated when using the playback info API, which we skip as we'll always direct
			// play when using external players. We need to infer the subtitle format based on its path (similar to how the server
			// calculates it)
			val format = mediaStream.path?.substringAfterLast('.', missingDelimiterValue = mediaStream.codec.orEmpty()) ?: "srt"
			api.subtitleApi.getSubtitleUrl(
				routeItemId = item.id,
				routeMediaSourceId = mediaSource.id.toString(),
				routeIndex = mediaStream.index,
				routeFormat = format,
			)
		}.toTypedArray()
		val subtitleNames = externalSubtitles.map { it.displayTitle ?: it.title.orEmpty() }.toTypedArray()
		val subtitleLanguages = externalSubtitles.map { it.language.orEmpty() }.toTypedArray()

		Timber.i(
			"Starting item ${item.id} from $position with ${subtitleUrls.size} external subtitles"
		)

		val playIntent = Intent(Intent.ACTION_VIEW).apply {
			val mediaType = when (item.mediaType) {
				MediaType.VIDEO -> "video/*"
				MediaType.AUDIO -> "audio/*"
				else -> null
			}

			setDataAndTypeAndNormalize(url.toUri(), mediaType)

			putExtra(API_MX_SEEK_POSITION, position.inWholeMilliseconds.toInt())
			putExtra(API_EXTERNAL_RETURN_RESULT, true)
			putExtra(API_MX_TITLE, title)
			putExtra(API_MX_FILENAME, fileName)
			putExtra(API_EXTERNAL_SECURE_URI, true)
			putExtra(API_MX_SUBS, subtitleUrls)
			putExtra(API_MX_SUBS_NAME, subtitleNames)
			putExtra(API_MX_SUBS_FILENAME, subtitleLanguages)

			if (subtitleUrls.isNotEmpty()) putExtra(API_VLC_SUBTITLES, subtitleUrls.first().toString())

			putExtra(API_VIMU_SEEK_POSITION, position.inWholeMilliseconds.toInt())
			putExtra(API_VIMU_RESUME, false)
			putExtra(API_VIMU_TITLE, title)
		}

		if (playIntent.resolveActivity(packageManager) == null) {
			Toast.makeText(this, R.string.no_player_message, Toast.LENGTH_LONG).show()
			finish()
			return
		}

		lifecycleScope.launch {
			externalPlaybackStartElapsedMs = SystemClock.elapsedRealtime()
			externalPlaybackStartPosition = position

			val playbackStartReported = reportExternalPlaybackStart(item, mediaSource, position)
			if (playbackStartReported) startExternalPlaybackProgressJob(item, mediaSource)

			try {
				currentItem = item to mediaSource
				Timber.i("Launching external player for item ${item.id}")
				playVideoLauncher.launch(playIntent)
			} catch (_: ActivityNotFoundException) {
				cancelExternalPlaybackProgressJob()
				runCatching {
					withContext(Dispatchers.IO) {
						api.playStateApi.reportPlaybackStopped(
							PlaybackStopInfo(
								itemId = item.id,
								mediaSourceId = mediaSource.id,
								positionTicks = position.inWholeTicks,
								failed = true,
							)
						)
					}
				}.onFailure { error -> Timber.w(error, "Failed to report external playback stop after launch failure") }

				Toast.makeText(this@ExternalPlayerActivity, R.string.no_player_message, Toast.LENGTH_LONG).show()
				finish()
			}
		}
	}

	private suspend fun reportExternalPlaybackStart(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo,
		position: Duration
	): Boolean {
		Timber.i("External playback start item=${item.id} name=${item.name} position=${position.inWholeMilliseconds}")
		val result = runCatching {
			withContext(Dispatchers.IO) {
				api.playStateApi.reportPlaybackStart(
					PlaybackStartInfo(
						itemId = item.id,
						mediaSourceId = mediaSource.id,
						positionTicks = position.inWholeTicks,
						canSeek = (mediaSource.runTimeTicks ?: item.runTimeTicks ?: 0L) > 0L,
						isPaused = false,
						playMethod = PlayMethod.DIRECT_PLAY,
						liveStreamId = mediaSource.liveStreamId,
						audioStreamIndex = mediaSource.defaultAudioStreamIndex,
						subtitleStreamIndex = mediaSource.defaultSubtitleStreamIndex,
						isMuted = false,
						repeatMode = RepeatMode.REPEAT_NONE,
						playbackOrder = PlaybackOrder.DEFAULT,
					)
				)
			}
		}

		result
			.onSuccess { Timber.i("External playback start reported successfully") }
			.onFailure { error -> Timber.w(error, "Failed to report external playback start event") }

		return result.isSuccess
	}

	private fun startExternalPlaybackProgressJob(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo
	) {
		cancelExternalPlaybackProgressJob()
		externalPlaybackProgressJob = lifecycleScope.launch {
			while (isActive) {
				delay(EXTERNAL_PLAYBACK_PROGRESS_INTERVAL_MS)
				reportExternalPlaybackProgress(item, mediaSource, getEstimatedExternalPlaybackPosition())
			}
		}
	}

	private fun cancelExternalPlaybackProgressJob() {
		if (externalPlaybackProgressJob != null) {
			externalPlaybackProgressJob?.cancel()
			externalPlaybackProgressJob = null
			Timber.i("External playback progress job cancelled")
		}
	}

	private fun getEstimatedExternalPlaybackPosition(): Duration {
		val elapsedMs = (SystemClock.elapsedRealtime() - externalPlaybackStartElapsedMs).coerceAtLeast(0L)
		return externalPlaybackStartPosition + elapsedMs.milliseconds
	}

	private suspend fun reportExternalPlaybackProgress(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo,
		position: Duration
	) {
		runCatching {
			withContext(Dispatchers.IO) {
				api.playStateApi.reportPlaybackProgress(
					PlaybackProgressInfo(
						itemId = item.id,
						mediaSourceId = mediaSource.id,
						positionTicks = position.inWholeTicks,
						canSeek = (mediaSource.runTimeTicks ?: item.runTimeTicks ?: 0L) > 0L,
						isPaused = false,
						playMethod = PlayMethod.DIRECT_PLAY,
						liveStreamId = mediaSource.liveStreamId,
						audioStreamIndex = mediaSource.defaultAudioStreamIndex,
						subtitleStreamIndex = mediaSource.defaultSubtitleStreamIndex,
						isMuted = false,
						repeatMode = RepeatMode.REPEAT_NONE,
						playbackOrder = PlaybackOrder.DEFAULT,
					)
				)
			}
		}
			.onSuccess { Timber.i("External playback progress reported position=${position.inWholeMilliseconds}") }
			.onFailure { error -> Timber.w(error, "Failed to report external playback progress position=${position.inWholeMilliseconds}") }
	}

	private fun Bundle.getDurationExtra(key: String): Duration? {
		@Suppress("DEPRECATION") val value = get(key)
		return if (value is Number) value.toLong().milliseconds
		else null
	}

	private fun onItemFinished(result: Intent?, failed: Boolean = false) {
		if (currentItem == null) {
			Toast.makeText(this@ExternalPlayerActivity, R.string.video_error_unknown_error, Toast.LENGTH_LONG).show()
			finish()
			return
		}

		val (item, mediaSource) = currentItem!!
		val extras = result?.extras ?: Bundle.EMPTY

		val returnedPosition = resultPositionExtras.firstNotNullOfOrNull { key -> extras.getDurationExtra(key) }
		val returnedDuration = extras.getDurationExtra(API_VLC_RESULT_DURATION)
		val estimatedPosition = getEstimatedExternalPlaybackPosition()
		Timber.i(
			"External player returned extra_position=${returnedPosition?.inWholeMilliseconds} " +
				"extra_duration=${returnedDuration?.inWholeMilliseconds}"
		)

		val endPosition = if (returnedPosition != null && returnedPosition > Duration.ZERO) {
			returnedPosition
		} else {
			Timber.i("Using estimated external playback position=${estimatedPosition.inWholeMilliseconds}")
			estimatedPosition
		}

		val runtime = (mediaSource.runTimeTicks ?: item.runTimeTicks)?.ticks
		val shouldPlayNext = !failed && runtime != null && endPosition >= (runtime * 0.9)

		lifecycleScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					api.playStateApi.reportPlaybackStopped(
						PlaybackStopInfo(
							itemId = item.id,
							mediaSourceId = mediaSource.id,
							positionTicks = endPosition.inWholeTicks,
							failed = failed,
						)
					)
				}
			}.onFailure { error ->
				Timber.w(error, "Failed to report playback stop event")
				Toast.makeText(this@ExternalPlayerActivity, R.string.video_error_unknown_error, Toast.LENGTH_LONG).show()
			}.onSuccess {
				Timber.i("External playback stop reported position=${endPosition.inWholeMilliseconds} completed=$shouldPlayNext")
			}

			dataRefreshService.lastPlayback = Instant.now()
			when (item.type) {
				BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
				BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
				else -> Unit
			}

			if (shouldPlayNext) playNext()
			else finish()
		}
	}
}
