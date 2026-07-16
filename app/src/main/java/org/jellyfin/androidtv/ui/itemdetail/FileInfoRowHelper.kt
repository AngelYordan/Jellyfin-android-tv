package org.jellyfin.androidtv.ui.itemdetail

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType
import java.util.Locale

private const val MAX_AUDIO_TRACKS = 6
private const val MAX_SUBTITLE_TRACKS = 8

fun buildFileInfoCards(context: Context, item: BaseItemDto): List<String> {
	if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.EPISODE) return emptyList()

	val mediaSources = item.mediaSources.orEmpty()
	if (mediaSources.isNotEmpty()) {
		return mediaSources.mapIndexedNotNull { index, mediaSource ->
			formatMediaSource(context, item, mediaSource, index, mediaSources.size)
		}
	}

	val itemStreams = item.mediaStreams.orEmpty()
	if (item.container.isNullOrBlank() && item.path.isNullOrBlank() && itemStreams.isEmpty()) return emptyList()

	return listOfNotNull(formatMediaDetails(context, item, null, itemStreams, null))
}

private fun formatMediaSource(
	context: Context,
	item: BaseItemDto,
	mediaSource: MediaSourceInfo,
	index: Int,
	versionCount: Int,
): String? {
	val streams = mediaSource.mediaStreams.orEmpty().ifEmpty { item.mediaStreams.orEmpty() }
	val versionIndex = if (versionCount > 1) index else null

	return formatMediaDetails(context, item, mediaSource, streams, versionIndex)
}

private fun formatMediaDetails(
	context: Context,
	item: BaseItemDto,
	mediaSource: MediaSourceInfo?,
	streams: List<MediaStream>,
	versionIndex: Int?,
): String? {
	val lines = mutableListOf<String>()
	var hasContent = false

	if (versionIndex != null) lines += context.getString(R.string.lbl_file_info_version, versionIndex + 1)

	fun addLabeledLine(labelRes: Int, value: String?) {
		if (value.isNullOrBlank()) return
		lines += "${context.getString(labelRes)}: $value"
		hasContent = true
	}

	fun addSectionSpacing() {
		if (hasContent && lines.lastOrNull()?.isNotEmpty() == true) lines += ""
	}

	addLabeledLine(R.string.lbl_file_info_file, getSafeFileName(mediaSource?.path ?: item.path))
	addLabeledLine(R.string.lbl_file_info_container, formatContainer(mediaSource?.container ?: item.container))
	addLabeledLine(R.string.lbl_file_info_size, mediaSource?.size?.let(::formatFileSize))
	addLabeledLine(R.string.lbl_file_info_bitrate, mediaSource?.bitrate?.let(::formatBitrate))

	streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.let { videoStream ->
		formatVideoStream(videoStream)?.let {
			addSectionSpacing()
			lines += "${context.getString(R.string.lbl_file_info_video)}: $it"
			hasContent = true
		}
	}

	val audioStreams = streams.filter { it.type == MediaStreamType.AUDIO }
	if (audioStreams.isNotEmpty()) {
		addSectionSpacing()
		lines += "${context.getString(R.string.lbl_file_info_audio)}:"
		audioStreams.take(MAX_AUDIO_TRACKS).forEachIndexed { index, stream ->
			lines += "${index + 1}. ${formatAudioStream(context, stream)}"
		}
		if (audioStreams.size > MAX_AUDIO_TRACKS) {
			lines += context.getString(R.string.lbl_file_info_more_tracks, audioStreams.size - MAX_AUDIO_TRACKS)
		}
		hasContent = true
	}

	val subtitleStreams = streams.filter { it.type == MediaStreamType.SUBTITLE }
	if (subtitleStreams.isNotEmpty()) {
		addSectionSpacing()
		lines += "${context.getString(R.string.lbl_file_info_subtitles)}:"
		subtitleStreams.take(MAX_SUBTITLE_TRACKS).forEachIndexed { index, stream ->
			lines += "${index + 1}. ${formatSubtitleStream(context, stream)}"
		}
		if (subtitleStreams.size > MAX_SUBTITLE_TRACKS) {
			lines += context.getString(R.string.lbl_file_info_more_tracks, subtitleStreams.size - MAX_SUBTITLE_TRACKS)
		}
		hasContent = true
	}

	return if (hasContent) lines.joinToString("\n") else null
}

private fun formatVideoStream(stream: MediaStream): String? {
	val parts = listOfNotNull(
		formatCodec(stream.codec),
		formatResolution(stream),
		formatVideoRange(stream),
		stream.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
		formatFrameRate(stream.realFrameRate ?: stream.averageFrameRate),
	)

	return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun formatAudioStream(context: Context, stream: MediaStream): String {
	val language = formatLanguage(stream.language)
	val details = listOfNotNull(
		formatAudioCodec(stream),
		formatChannels(stream),
		context.getString(R.string.lbl_file_info_default).takeIf { stream.isDefault },
		context.getString(R.string.lbl_file_info_forced).takeIf { stream.isForced },
	).joinToString(" ")

	return joinLanguageAndDetails(language, details).ifBlank {
		stream.displayTitle ?: stream.title ?: context.getString(R.string.lbl_file_info_audio)
	}
}

private fun formatSubtitleStream(context: Context, stream: MediaStream): String {
	val flags = listOfNotNull(
		context.getString(R.string.lbl_file_info_default).takeIf { stream.isDefault },
		context.getString(R.string.lbl_file_info_forced).takeIf { stream.isForced },
	)
	val language = listOfNotNull(formatLanguage(stream.language), *flags.toTypedArray()).joinToString(" ")
	val format = formatSubtitleCodec(stream.codec)

	return joinLanguageAndDetails(language, format).ifBlank {
		stream.displayTitle ?: stream.title ?: context.getString(R.string.lbl_file_info_subtitles)
	}
}

private fun joinLanguageAndDetails(language: String?, details: String?): String {
	return when {
		!language.isNullOrBlank() && !details.isNullOrBlank() -> "$language - $details"
		!language.isNullOrBlank() -> language
		!details.isNullOrBlank() -> details
		else -> ""
	}
}

private fun formatContainer(container: String?): String? {
	return container?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
}

private fun formatFileSize(bytes: Long): String? {
	if (bytes <= 0) return null

	val size = bytes.toDouble()
	return when {
		bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", size / 1_000_000_000.0)
		else -> String.format(Locale.US, "%.1f MB", size / 1_000_000.0)
	}
}

private fun formatBitrate(bitsPerSecond: Int): String? {
	if (bitsPerSecond <= 0) return null

	return when {
		bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
		bitsPerSecond >= 1_000 -> String.format(Locale.US, "%.0f Kbps", bitsPerSecond / 1_000.0)
		else -> "$bitsPerSecond bps"
	}
}

private fun formatCodec(codec: String?): String? {
	return when (codec?.trim()?.lowercase(Locale.US)) {
		null, "" -> null
		"h264", "avc" -> "H.264"
		"h265", "hevc" -> "HEVC"
		"vc1", "vc-1" -> "VC-1"
		"mpeg2video" -> "MPEG-2"
		"av1" -> "AV1"
		"truehd" -> "TrueHD"
		"dca", "dts" -> "DTS"
		"eac3" -> "EAC3"
		"ac3" -> "AC3"
		"aac" -> "AAC"
		"flac" -> "FLAC"
		"opus" -> "OPUS"
		else -> codec.uppercase(Locale.US)
	}
}

private fun formatAudioCodec(stream: MediaStream): String? {
	val combinedTitle = listOfNotNull(stream.profile, stream.displayTitle, stream.title)
		.joinToString(" ")

	return when {
		combinedTitle.contains("DTS-HD MA", ignoreCase = true) -> "DTS-HD MA"
		combinedTitle.contains("DTS-HD", ignoreCase = true) -> "DTS-HD"
		combinedTitle.contains("Atmos", ignoreCase = true) && stream.codec.equals("truehd", ignoreCase = true) -> "TrueHD Atmos"
		else -> formatCodec(stream.codec)
	}
}

private fun formatSubtitleCodec(codec: String?): String? {
	return when (codec?.trim()?.lowercase(Locale.US)) {
		null, "" -> null
		"subrip", "srt" -> "SRT"
		"pgs", "pgssub", "hdmv_pgs_subtitle" -> "PGS"
		"ass" -> "ASS"
		"ssa" -> "SSA"
		"webvtt", "vtt" -> "WEBVTT"
		else -> codec.uppercase(Locale.US)
	}
}

private fun formatResolution(stream: MediaStream): String? {
	val width = stream.width ?: return null
	val height = stream.height ?: return null
	if (width <= 0 || height <= 0) return null

	return "${width}x$height"
}

private fun formatVideoRange(stream: MediaStream): String? {
	if (!stream.videoDoViTitle.isNullOrBlank()) return stream.videoDoViTitle

	return when (stream.videoRangeType) {
		VideoRangeType.SDR -> "SDR"
		VideoRangeType.HDR10 -> "HDR10"
		VideoRangeType.HDR10_PLUS -> "HDR10+"
		VideoRangeType.HLG -> "HLG"
		VideoRangeType.DOVI -> "Dolby Vision"
		VideoRangeType.DOVI_WITH_HDR10 -> "Dolby Vision/HDR10"
		VideoRangeType.DOVI_WITH_HLG -> "Dolby Vision/HLG"
		VideoRangeType.DOVI_WITH_SDR -> "Dolby Vision/SDR"
		else -> null
	}
}

private fun formatFrameRate(frameRate: Float?): String? {
	if (frameRate == null || frameRate <= 0f) return null

	val formatted = String.format(Locale.US, "%.3f", frameRate)
		.trimEnd('0')
		.trimEnd('.')
	return "$formatted fps"
}

private fun formatChannels(stream: MediaStream): String? {
	if (!stream.channelLayout.isNullOrBlank()) return stream.channelLayout

	return when (stream.channels) {
		null -> null
		1 -> "1.0"
		2 -> "2.0"
		6 -> "5.1"
		8 -> "7.1"
		else -> "${stream.channels} ch"
	}
}

private fun formatLanguage(language: String?): String? {
	val normalized = language?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.US) ?: return null
	if (normalized == "und" || normalized == "unknown") return null

	val languageTag = iso639ToLanguageTag[normalized] ?: normalized
	val locale = Locale.forLanguageTag(languageTag)
	val displayName = locale.getDisplayLanguage(Locale.getDefault())

	return displayName
		.takeIf { it.isNotBlank() && !it.equals(languageTag, ignoreCase = true) }
		?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
		?: language.trim().uppercase(Locale.US)
}

private fun getSafeFileName(path: String?): String? {
	val cleanPath = path?.trim()?.takeIf { it.isNotBlank() }?.substringBefore('?') ?: return null
	val slashIndex = maxOf(cleanPath.lastIndexOf('/'), cleanPath.lastIndexOf('\\'))

	return cleanPath.substring(slashIndex + 1).takeIf { it.isNotBlank() }
}

private val iso639ToLanguageTag = mapOf(
	"eng" to "en",
	"spa" to "es",
	"esl" to "es",
	"fra" to "fr",
	"fre" to "fr",
	"deu" to "de",
	"ger" to "de",
	"ita" to "it",
	"por" to "pt",
	"jpn" to "ja",
	"kor" to "ko",
	"zho" to "zh",
	"chi" to "zh",
	"rus" to "ru",
	"und" to "",
)
