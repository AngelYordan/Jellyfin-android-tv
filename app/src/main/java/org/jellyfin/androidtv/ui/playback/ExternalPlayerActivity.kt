package org.jellyfin.androidtv.ui.playback

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Activity that, once opened, opens the first item of the [VideoQueueManager.getCurrentVideoQueue] list in an external media player app.
 * Once returned it will notify the server of item completion.
 */
class ExternalPlayerActivity : FragmentActivity() {
	companion object {
		const val EXTRA_POSITION = "position"
		const val EXTRA_EXTERNAL_PLAYER_PACKAGE = "external_player_package"
		private const val EXTERNAL_PLAYBACK_PROGRESS_INTERVAL_MS = 5_000L
		private const val KODI_PLAYBACK_PROGRESS_INTERVAL_MS = 2_000L
		private const val KODI_PACKAGE = "org.xbmc.kodi"
		private const val KODI_JSON_RPC_HOST = "127.0.0.1"
		private const val KODI_JSON_RPC_PORT = 9090
		private const val KODI_JSON_RPC_TIMEOUT_MS = 1_500

		// https://mx.j2inter.com/api
		private const val API_MX_TITLE = "title"
		private const val API_MX_SEEK_POSITION = "position"
		private const val API_MX_FILENAME = "filename"
		private const val API_MX_RESULT_POSITION = "position"
		private const val API_MX_SUBS = "subs"
		private const val API_MX_SUBS_NAME = "subs.name"
		private const val API_MX_SUBS_FILENAME = "subs.filename"
		private const val API_KODI_START_POSITION = "start_position"
		private const val API_KODI_ITEM_POSITION = "android.media.intent.extra.ITEM_POSITION"

		private const val API_EXTERNAL_SECURE_URI = "secure_uri"
		private const val API_EXTERNAL_RETURN_RESULT = "return_result"

		// https://wiki.videolan.org/Android_Player_Intents/
		private const val API_VLC_FROM_START = "from_start"
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
		private val vlcPackageNames = setOf(
			"org.videolan.vlc",
			"org.videolan.vlc.debug",
			"org.videolan.vlc.betav7neon",
		)
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val api by inject<ApiClient>()
	private val kodiJson = Json { ignoreUnknownKeys = true }

	private val playVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		Timber.i("External player returned resultCode=${result.resultCode}")
		if (externalPlaybackStopHandled) {
			Timber.i("External playback stop already handled; ignoring returned result")
			finish()
			return@registerForActivityResult
		}
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
	private var externalPlaybackStartPositionMs = 0L
	private var trackedExternalPositionMs: Long? = null
	private var lastProgressElapsedMs = 0L
	private var lastKnownVlcState: Int? = null
	private var lastKnownKodiState: Int? = null
	private var lastKnownKodiPositionMs: Long? = null
	private var kodiPlaybackObserved = false
	private var kodiJsonRpcUnavailableLogged = false
	private var kodiMediaSessionUnavailableLogged = false
	private var kodiJsonRpcRequestId = 0
	private var externalPlayerPackage: String? = null
	private var externalPlaybackProgressJob: Job? = null
	private var externalPlaybackStopHandled = false

	private data class VlcPlaybackSnapshot(
		val state: Int,
		val stateName: String,
		val positionMs: Long?,
		val lastPositionUpdateTimeMs: Long?,
	)

	private data class KodiPlaybackSnapshot(
		val state: Int,
		val stateName: String,
		val positionMs: Long?,
		val lastPositionUpdateTimeMs: Long?,
		val source: KodiPositionSource,
	)

	private enum class KodiPositionSource {
		JSON_RPC,
		MEDIA_SESSION,
	}

	private data class TrackedExternalPlaybackPosition(
		val positionMs: Long,
		val paused: Boolean,
		val stopped: Boolean = false,
	)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		externalPlayerPackage = intent.getStringExtra(EXTRA_EXTERNAL_PLAYER_PACKAGE)
		Timber.i("ExternalPlayerActivity externalPlayerPackage=$externalPlayerPackage")
		val positionMs = intent.getLongExtra(EXTRA_POSITION, 0).coerceAtLeast(0)
		Timber.i("ExternalPlayerActivity received startPositionTicks=${positionMs * 10000}")
		if (externalPlayerPackage == KODI_PACKAGE) {
			Timber.i("ExternalPlayerActivity received Kodi startTicks=${positionMs * 10000} startMs=$positionMs")
		}
		val position = positionMs.milliseconds
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
			putExtra(API_VLC_FROM_START, position == Duration.ZERO)

			putExtra(API_VIMU_SEEK_POSITION, position.inWholeMilliseconds.toInt())
			putExtra(API_VIMU_RESUME, false)
			putExtra(API_VIMU_TITLE, title)
		}

		val requestedPackage = externalPlayerPackage
		if (requestedPackage == KODI_PACKAGE) {
			val positionMs = position.inWholeMilliseconds.coerceAtLeast(0)
			playIntent.putExtra(API_MX_SEEK_POSITION, positionMs)
			playIntent.putExtra(API_KODI_START_POSITION, positionMs)
			playIntent.putExtra(API_KODI_ITEM_POSITION, positionMs)
			Timber.i("Kodi intent extras positionMs=$positionMs")
		}

		if (requestedPackage != null) {
			val installed = isPackageInstalled(requestedPackage)
			if (requestedPackage == KODI_PACKAGE) Timber.i("Kodi installed=$installed")
			if (!installed) {
				Toast.makeText(this, R.string.msg_kodi_not_installed, Toast.LENGTH_LONG).show()
				finish()
				return
			}

			playIntent.setPackage(requestedPackage)
		}

		if (playIntent.resolveActivity(packageManager) == null) {
			Toast.makeText(this, R.string.no_player_message, Toast.LENGTH_LONG).show()
			finish()
			return
		}

		lifecycleScope.launch {
			val now = SystemClock.elapsedRealtime()
			externalPlaybackStartElapsedMs = now
			externalPlaybackStartPosition = position
			externalPlaybackStartPositionMs = position.inWholeMilliseconds
			trackedExternalPositionMs = externalPlaybackStartPositionMs
			lastProgressElapsedMs = now
			lastKnownVlcState = null
			lastKnownKodiState = null
			lastKnownKodiPositionMs = if (requestedPackage == KODI_PACKAGE) externalPlaybackStartPositionMs else null
			kodiPlaybackObserved = false
			kodiJsonRpcUnavailableLogged = false
			kodiMediaSessionUnavailableLogged = false
			externalPlaybackStopHandled = false

			val playbackStartReported = reportExternalPlaybackStart(item, mediaSource, position)
			if (requestedPackage == KODI_PACKAGE) Timber.i("Kodi tracking started item=${item.id} startMs=$externalPlaybackStartPositionMs")
			if (playbackStartReported) startExternalPlaybackProgressJob(item, mediaSource)

			try {
				currentItem = item to mediaSource
				if (requestedPackage == KODI_PACKAGE) Timber.i("External Kodi playback launched")
				else Timber.i("Launching external player for item ${item.id}")
				playVideoLauncher.launch(playIntent)
				if (requestedPackage == KODI_PACKAGE) scheduleKodiMediaSessionSeek(position)
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

	private fun isPackageInstalled(packageName: String): Boolean = try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
		} else {
			@Suppress("DEPRECATION")
			packageManager.getPackageInfo(packageName, 0)
		}
		true
	} catch (_: PackageManager.NameNotFoundException) {
		false
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
				val isKodiPlayback = externalPlayerPackage == KODI_PACKAGE
				delay(if (isKodiPlayback) KODI_PLAYBACK_PROGRESS_INTERVAL_MS else EXTERNAL_PLAYBACK_PROGRESS_INTERVAL_MS)
				val position = if (isKodiPlayback) updateTrackedKodiPlaybackPosition()
				else updateTrackedExternalPlaybackPosition()

				if (isKodiPlayback && position.stopped) {
					Timber.i("Kodi stopped detected finalPositionMs=${position.positionMs}")
					onItemFinished(null, forcedEndPosition = position.positionMs.milliseconds)
					return@launch
				}

				reportExternalPlaybackProgress(item, mediaSource, position.positionMs.milliseconds, position.paused)
				if (isKodiPlayback) Timber.i("Kodi progress reported positionMs=${position.positionMs}")
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

	private fun getPauseAwareTrackedExternalPlaybackPositionForStop(): Duration? {
		val trackedPositionMs = trackedExternalPositionMs ?: return null
		val now = SystemClock.elapsedRealtime()
		val vlcState = queryVlcPlaybackState()
		val state = vlcState?.state ?: lastKnownVlcState
		val positionMs = if (state == PlaybackState.STATE_PLAYING) {
			trackedPositionMs + (now - lastProgressElapsedMs).coerceAtLeast(0L)
		} else {
			trackedPositionMs
		}
		val vlcPositionMs = vlcState?.positionMs
		val finalPositionMs = if (vlcPositionMs != null && vlcPositionMs > 0) max(positionMs, vlcPositionMs)
		else positionMs

		return finalPositionMs.milliseconds
	}

	private suspend fun getKodiTrackedExternalPlaybackPositionForStop(): Duration? {
		queryKodiPlaybackState()?.let { kodiState ->
			val positionMs = kodiState.positionMs
			if (positionMs != null && positionMs > 0) {
				lastKnownKodiPositionMs = positionMs
				trackedExternalPositionMs = positionMs
			}
			lastKnownKodiState = kodiState.state
		}

		val positionMs = lastKnownKodiPositionMs
			?: trackedExternalPositionMs
			?: externalPlaybackStartPositionMs.takeIf { it > 0 }
			?: return null

		return positionMs.milliseconds
	}

	private fun updateTrackedExternalPlaybackPosition(): TrackedExternalPlaybackPosition {
		val now = SystemClock.elapsedRealtime()
		val elapsedMs = (now - lastProgressElapsedMs).coerceAtLeast(0L)
		val currentPositionMs = trackedExternalPositionMs ?: getEstimatedExternalPlaybackPosition().inWholeMilliseconds
		val vlcState = queryVlcPlaybackState()

		val updatedPositionMs = when (vlcState?.state) {
			PlaybackState.STATE_PLAYING -> {
				val updatedPosition = currentPositionMs + elapsedMs
				Timber.i("External playback playing; advancing tracked position to $updatedPosition")
				updatedPosition
			}

			PlaybackState.STATE_PAUSED,
			PlaybackState.STATE_BUFFERING,
			PlaybackState.STATE_STOPPED,
			PlaybackState.STATE_NONE,
				-> {
				Timber.i("External playback paused; not advancing tracked position")
				currentPositionMs
			}

			null -> {
				Timber.w("VLC media session unavailable; falling back to estimated external playback position")
				currentPositionMs + elapsedMs
			}

			else -> {
				Timber.w("Unknown VLC media session state=${vlcState.stateName}; falling back to estimated external playback position")
				currentPositionMs + elapsedMs
			}
		}

		val vlcPositionMs = vlcState?.positionMs
		val trackedPositionMs = if (vlcPositionMs != null && vlcPositionMs > 0) max(updatedPositionMs, vlcPositionMs)
		else updatedPositionMs

		trackedExternalPositionMs = trackedPositionMs
		lastProgressElapsedMs = now
		lastKnownVlcState = vlcState?.state

		return TrackedExternalPlaybackPosition(
			positionMs = trackedPositionMs,
			paused = when (vlcState?.state) {
				PlaybackState.STATE_PAUSED,
				PlaybackState.STATE_BUFFERING,
				PlaybackState.STATE_STOPPED,
				PlaybackState.STATE_NONE,
					-> true

				else -> false
			}
		)
	}

	private suspend fun updateTrackedKodiPlaybackPosition(): TrackedExternalPlaybackPosition {
		val now = SystemClock.elapsedRealtime()
		val elapsedMs = (now - lastProgressElapsedMs).coerceAtLeast(0L)
		val currentPositionMs = trackedExternalPositionMs ?: getEstimatedExternalPlaybackPosition().inWholeMilliseconds
		val kodiState = queryKodiPlaybackState()

		if (kodiState == null) {
			val updatedPositionMs = currentPositionMs + elapsedMs
			trackedExternalPositionMs = updatedPositionMs
			lastProgressElapsedMs = now
			return TrackedExternalPlaybackPosition(
				positionMs = updatedPositionMs,
				paused = false,
			)
		}

		val kodiPositionSource = when (kodiState.source) {
			KodiPositionSource.JSON_RPC -> "JSON-RPC"
			KodiPositionSource.MEDIA_SESSION -> "MediaSession"
		}
		Timber.i("Kodi $kodiPositionSource state=${kodiState.stateName} positionMs=${kodiState.positionMs}")
		val statePositionMs = kodiState.positionMs
		val updatedPositionMs = when (kodiState.state) {
			PlaybackState.STATE_PLAYING -> {
				kodiPlaybackObserved = true
				statePositionMs ?: (currentPositionMs + elapsedMs)
			}

			PlaybackState.STATE_PAUSED,
			PlaybackState.STATE_BUFFERING,
				-> {
				kodiPlaybackObserved = true
				statePositionMs ?: currentPositionMs
			}

			PlaybackState.STATE_STOPPED,
			PlaybackState.STATE_NONE,
				-> {
				if (statePositionMs != null && statePositionMs > 0) statePositionMs
				else lastKnownKodiPositionMs ?: currentPositionMs
			}

			else -> statePositionMs ?: (currentPositionMs + elapsedMs)
		}

		if (updatedPositionMs > 0) lastKnownKodiPositionMs = updatedPositionMs
		trackedExternalPositionMs = updatedPositionMs
		lastProgressElapsedMs = now
		lastKnownKodiState = kodiState.state

		return TrackedExternalPlaybackPosition(
			positionMs = updatedPositionMs,
			paused = when (kodiState.state) {
				PlaybackState.STATE_PAUSED,
				PlaybackState.STATE_BUFFERING,
				PlaybackState.STATE_STOPPED,
				PlaybackState.STATE_NONE,
					-> true

				else -> false
			},
			stopped = kodiPlaybackObserved && kodiState.state in setOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE),
		)
	}

	private fun queryVlcPlaybackState(): VlcPlaybackSnapshot? {
		val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
		val notificationListener = ComponentName(this, ExternalPlayerNotificationListenerService::class.java)

		return try {
			mediaSessionManager.getActiveSessions(notificationListener).firstNotNullOfOrNull { controller ->
				val packageName = controller.packageName
				if (!isVlcPackage(packageName)) return@firstNotNullOfOrNull null

				val playbackState = controller.playbackState ?: return@firstNotNullOfOrNull null
				val positionMs = playbackState.position.takeIf { it > 0 }
				val snapshot = VlcPlaybackSnapshot(
					state = playbackState.state,
					stateName = playbackState.stateName,
					positionMs = positionMs,
					lastPositionUpdateTimeMs = playbackState.lastPositionUpdateTime.takeIf { it > 0 },
				)
				Timber.i("VLC media session state=${snapshot.stateName} position=${snapshot.positionMs}")
				snapshot
			}
		} catch (error: SecurityException) {
			Timber.w(error, "Cannot access VLC media session; falling back to estimated external playback position")
			null
		}
	}

	private suspend fun queryKodiPlaybackState(): KodiPlaybackSnapshot? =
		queryKodiJsonRpcPlaybackState() ?: queryKodiMediaSessionPlaybackState()

	private suspend fun queryKodiJsonRpcPlaybackState(): KodiPlaybackSnapshot? = withContext(Dispatchers.IO) {
		runCatching {
			val activeResponse = kodiJsonRpcRequest(
				"""{"jsonrpc":"2.0","method":"Player.GetActivePlayers","id":${nextKodiJsonRpcRequestId()}}"""
			) ?: return@runCatching null
			val activePlayers = activeResponse["result"]?.jsonArray ?: return@runCatching null
			val activePlayer = activePlayers
				.mapNotNull { it as? JsonObject }
				.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "video" }
				?: activePlayers.mapNotNull { it as? JsonObject }.firstOrNull()

			if (activePlayer == null) {
				if (kodiPlaybackObserved) {
					return@runCatching KodiPlaybackSnapshot(
						state = PlaybackState.STATE_STOPPED,
						stateName = playbackStateName(PlaybackState.STATE_STOPPED),
						positionMs = lastKnownKodiPositionMs,
						lastPositionUpdateTimeMs = null,
						source = KodiPositionSource.JSON_RPC,
					)
				}
				return@runCatching null
			}

			val playerId = activePlayer["playerid"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
			val playerType = activePlayer["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
			Timber.i("Kodi JSON-RPC active playerId=$playerId type=$playerType")
			val propertiesResponse = kodiJsonRpcRequest(
				"""{"jsonrpc":"2.0","method":"Player.GetProperties","params":{"playerid":$playerId,"properties":["time","totaltime","speed","percentage"]},"id":${nextKodiJsonRpcRequestId()}}"""
			) ?: return@runCatching null
			val properties = propertiesResponse["result"]?.jsonObject ?: return@runCatching null
			val positionMs = properties["time"]?.jsonObject?.toKodiMilliseconds()
			val speed = properties["speed"]?.jsonPrimitive?.intOrNull ?: 0
			val state = if (speed > 0) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
			Timber.i("Kodi JSON-RPC positionMs=$positionMs speed=$speed")

			KodiPlaybackSnapshot(
				state = state,
				stateName = playbackStateName(state),
				positionMs = positionMs,
				lastPositionUpdateTimeMs = null,
				source = KodiPositionSource.JSON_RPC,
			)
		}.onFailure { error ->
			logKodiJsonRpcUnavailable(error)
		}.getOrNull()
	}

	private fun queryKodiMediaSessionPlaybackState(): KodiPlaybackSnapshot? {
		val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
		if (mediaSessionManager == null) {
			logKodiMediaSessionUnavailable()
			return null
		}
		val notificationListener = ComponentName(this, ExternalPlayerNotificationListenerService::class.java)

		return try {
			val controller = mediaSessionManager.getActiveSessions(notificationListener).firstOrNull { it.packageName == KODI_PACKAGE }
			val playbackState = controller?.playbackState
			if (playbackState == null) {
				logKodiMediaSessionUnavailable()
				null
			} else {
				KodiPlaybackSnapshot(
					state = playbackState.state,
					stateName = playbackState.stateName,
					positionMs = playbackState.position.takeIf { it > 0 },
					lastPositionUpdateTimeMs = playbackState.lastPositionUpdateTime.takeIf { it > 0 },
					source = KodiPositionSource.MEDIA_SESSION,
				)
			}
		} catch (error: SecurityException) {
			if (!kodiMediaSessionUnavailableLogged) {
				Timber.w(error, "Kodi MediaSession unavailable; using estimated external tracking")
				kodiMediaSessionUnavailableLogged = true
			}
			null
		}
	}

	private fun kodiJsonRpcRequest(request: String): JsonObject? {
		Socket().use { socket ->
			socket.connect(InetSocketAddress(KODI_JSON_RPC_HOST, KODI_JSON_RPC_PORT), KODI_JSON_RPC_TIMEOUT_MS)
			socket.soTimeout = KODI_JSON_RPC_TIMEOUT_MS
			val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
			writer.write(request)
			writer.write("\r\n")
			writer.flush()

			val response = readKodiJsonRpcResponse(BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)))
				?: return null
			return kodiJson.parseToJsonElement(response).jsonObject
		}
	}

	private fun readKodiJsonRpcResponse(reader: BufferedReader): String? {
		val response = StringBuilder()
		var started = false
		var depth = 0
		var inString = false
		var escaped = false

		while (true) {
			val code = reader.read()
			if (code < 0) break
			val char = code.toChar()
			if (!started) {
				if (char.isWhitespace()) continue
				if (char != '{' && char != '[') continue
				started = true
				depth = 1
				response.append(char)
				continue
			}

			response.append(char)
			if (escaped) {
				escaped = false
				continue
			}
			if (char == '\\' && inString) {
				escaped = true
				continue
			}
			if (char == '"') {
				inString = !inString
				continue
			}
			if (inString) continue

			when (char) {
				'{', '[' -> depth++
				'}', ']' -> {
					depth--
					if (depth <= 0) return response.toString()
				}
			}
		}

		return response.toString().takeIf { it.isNotBlank() }
	}

	private fun JsonObject.toKodiMilliseconds(): Long {
		val hours = this["hours"]?.jsonPrimitive?.longOrNull ?: 0
		val minutes = this["minutes"]?.jsonPrimitive?.longOrNull ?: 0
		val seconds = this["seconds"]?.jsonPrimitive?.longOrNull ?: 0
		val milliseconds = this["milliseconds"]?.jsonPrimitive?.longOrNull ?: 0
		return (((hours * 60 + minutes) * 60 + seconds) * 1000 + milliseconds).coerceAtLeast(0)
	}

	@Synchronized
	private fun nextKodiJsonRpcRequestId(): Int {
		kodiJsonRpcRequestId += 1
		return kodiJsonRpcRequestId
	}

	private fun logKodiJsonRpcUnavailable(error: Throwable) {
		if (kodiJsonRpcUnavailableLogged) return
		Timber.w(error, "Kodi JSON-RPC unavailable; falling back to MediaSession")
		kodiJsonRpcUnavailableLogged = true
	}

	private fun logKodiMediaSessionUnavailable() {
		if (kodiMediaSessionUnavailableLogged) return
		Timber.i("Kodi MediaSession unavailable; using estimated external tracking")
		kodiMediaSessionUnavailableLogged = true
	}

	private fun isVlcPackage(packageName: String): Boolean =
		packageName in vlcPackageNames || packageName.contains("videolan", ignoreCase = true)

	private fun scheduleKodiMediaSessionSeek(position: Duration) {
		val resumeMs = position.inWholeMilliseconds.coerceAtLeast(0)
		if (resumeMs <= 0) return

		lifecycleScope.launch {
			delay(2_000L)
			val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
			val notificationListener = ComponentName(this@ExternalPlayerActivity, ExternalPlayerNotificationListenerService::class.java)
			val controller = try {
				mediaSessionManager?.getActiveSessions(notificationListener)?.firstOrNull { it.packageName == KODI_PACKAGE }
			} catch (error: SecurityException) {
				Timber.w(error, "Kodi MediaSession seek unavailable; Kodi may ignore intent position")
				null
			}

			if (controller == null) {
				Timber.i("Kodi MediaSession seek unavailable; Kodi may ignore intent position")
				return@launch
			}

			controller.transportControls.seekTo(resumeMs)
			Timber.i("Kodi MediaSession seekTo resumeMs=$resumeMs")
		}
	}

	private val PlaybackState.stateName
		get() = playbackStateName(state)

	private fun playbackStateName(state: Int) = when (state) {
			PlaybackState.STATE_NONE -> "NONE"
			PlaybackState.STATE_STOPPED -> "STOPPED"
			PlaybackState.STATE_PAUSED -> "PAUSED"
			PlaybackState.STATE_PLAYING -> "PLAYING"
			PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
			PlaybackState.STATE_REWINDING -> "REWINDING"
			PlaybackState.STATE_BUFFERING -> "BUFFERING"
			PlaybackState.STATE_ERROR -> "ERROR"
			PlaybackState.STATE_CONNECTING -> "CONNECTING"
			PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
			PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
			PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
			else -> "UNKNOWN_$state"
		}

	private suspend fun reportExternalPlaybackProgress(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo,
		position: Duration,
		paused: Boolean
	) {
		runCatching {
			withContext(Dispatchers.IO) {
				api.playStateApi.reportPlaybackProgress(
					PlaybackProgressInfo(
						itemId = item.id,
						mediaSourceId = mediaSource.id,
						positionTicks = position.inWholeTicks,
						canSeek = (mediaSource.runTimeTicks ?: item.runTimeTicks ?: 0L) > 0L,
						isPaused = paused,
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
			.onSuccess { Timber.i("External playback progress reported position=${position.inWholeMilliseconds} paused=$paused") }
			.onFailure { error -> Timber.w(error, "Failed to report external playback progress position=${position.inWholeMilliseconds}") }
	}

	private fun Bundle.getDurationExtra(key: String): Duration? {
		@Suppress("DEPRECATION") val value = get(key)
		return if (value is Number) value.toLong().milliseconds
		else null
	}

	private fun onItemFinished(result: Intent?, failed: Boolean = false, forcedEndPosition: Duration? = null) {
		if (externalPlaybackStopHandled) {
			Timber.i("External playback stop already handled; ignoring duplicate stop")
			return
		}
		externalPlaybackStopHandled = true
		cancelExternalPlaybackProgressJob()

		if (currentItem == null) {
			Toast.makeText(this@ExternalPlayerActivity, R.string.video_error_unknown_error, Toast.LENGTH_LONG).show()
			finish()
			return
		}

		val (item, mediaSource) = currentItem!!
		lifecycleScope.launch {
			finishExternalPlaybackItem(item, mediaSource, result, failed, forcedEndPosition)
		}
	}

	private suspend fun finishExternalPlaybackItem(
		item: BaseItemDto,
		mediaSource: MediaSourceInfo,
		result: Intent?,
		failed: Boolean,
		forcedEndPosition: Duration?,
	) {
		val extras = result?.extras ?: Bundle.EMPTY

		val returnedPosition = resultPositionExtras.firstNotNullOfOrNull { key -> extras.getDurationExtra(key) }
		val returnedDuration = extras.getDurationExtra(API_VLC_RESULT_DURATION)
		val isKodiPlayback = externalPlayerPackage == KODI_PACKAGE
		val kodiTrackedPosition = if (isKodiPlayback) getKodiTrackedExternalPlaybackPositionForStop() else null
		val pauseAwareTrackedPosition = if (isKodiPlayback) null else getPauseAwareTrackedExternalPlaybackPositionForStop()
		val wallClockFallbackPosition = getEstimatedExternalPlaybackPosition()
		Timber.i(
			"External player returned extra_position=${returnedPosition?.inWholeMilliseconds} " +
				"extra_duration=${returnedDuration?.inWholeMilliseconds}"
		)

		val endPosition = when {
			forcedEndPosition != null -> forcedEndPosition

			kodiTrackedPosition != null -> {
				Timber.i("External playback stop using Kodi tracked position=${kodiTrackedPosition.inWholeMilliseconds}")
				kodiTrackedPosition
			}

			returnedPosition != null && returnedPosition > Duration.ZERO -> {
				Timber.i("External playback stop using VLC extra_position=${returnedPosition.inWholeMilliseconds}")
				returnedPosition
			}

			pauseAwareTrackedPosition != null -> {
				Timber.i("External playback stop using pause-aware tracked position=${pauseAwareTrackedPosition.inWholeMilliseconds}")
				pauseAwareTrackedPosition
			}

			else -> {
				Timber.i("External playback stop using wall-clock fallback position=${wallClockFallbackPosition.inWholeMilliseconds}")
				wallClockFallbackPosition
			}
		}
		if (isKodiPlayback) Timber.i("Kodi stopped detected finalPositionMs=${endPosition.inWholeMilliseconds}")

		val runtime = (mediaSource.runTimeTicks ?: item.runTimeTicks)?.ticks
		val shouldPlayNext = !failed && runtime != null && endPosition >= (runtime * 0.9)

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
			if (isKodiPlayback) Timber.i("Kodi PlaybackStop reported positionTicks=${endPosition.inWholeTicks}")
			Timber.i("External playback stop reported position=${endPosition.inWholeMilliseconds} completed=$shouldPlayNext")
		}

		dataRefreshService.lastPlayback = Instant.now()
		dataRefreshService.lastExternalPlaybackPackage = externalPlayerPackage
		when (item.type) {
			BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
			BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
			else -> Unit
		}

		if (shouldPlayNext) playNext()
		else finish()
	}
}
