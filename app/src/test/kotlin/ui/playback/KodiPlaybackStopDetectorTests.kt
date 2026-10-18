package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KodiPlaybackStopDetectorTests : FunSpec({
	val expected = "http://server:8096/Videos/episode/stream?static=true&api_key=example"

	test("loading and MediaSession PLAYING without timing cannot arm stop detection") {
		val detector = KodiPlaybackStopDetector()
		KodiPlaybackStopDetector.isReadyPlayer(expected, null, null, null) shouldBe false
		KodiPlaybackStopDetector.isReadyPlayer(expected, expected, 0L, 0L) shouldBe false
		detector.observeNoPlayer(8_000) shouldBe false
		detector.observeNoPlayer(30_000) shouldBe false
		// The requested video starts only after the empty startup observations.
		KodiPlaybackStopDetector.isReadyPlayer(expected, expected, 0L, 680_513L) shouldBe true
		detector.observeReadyPlayer()
		detector.observeNoPlayer(32_000) shouldBe false
	}

	test("a different video or missing playback timing is not the requested player") {
		KodiPlaybackStopDetector.isReadyPlayer(expected, "http://server:8096/Videos/previous/stream", 100L, 1_000L) shouldBe false
		KodiPlaybackStopDetector.isReadyPlayer(expected, expected, null, 1_000L) shouldBe false
		KodiPlaybackStopDetector.isReadyPlayer(expected, expected, -1L, 1_000L) shouldBe false
		KodiPlaybackStopDetector.isReadyPlayer(expected, expected, 10L, null) shouldBe false
	}

	test("matching ignores request query parameters but requires the same server and path") {
		KodiPlaybackStopDetector.isReadyPlayer(expected, "http://SERVER:8096/Videos/episode/stream?static=true", 0L, 1_000L) shouldBe true
		KodiPlaybackStopDetector.isReadyPlayer(expected, "http://other:8096/Videos/episode/stream", 0L, 1_000L) shouldBe false
		KodiPlaybackStopDetector.isReadyPlayer(expected, "invalid URI %", 0L, 1_000L) shouldBe false
	}

	test("real playback stop requires six seconds of continuous empty observations") {
		val detector = KodiPlaybackStopDetector()
		detector.observeReadyPlayer()
		detector.observeNoPlayer(10_000) shouldBe false
		detector.observeNoPlayer(12_000) shouldBe false
		detector.observeNoPlayer(14_000) shouldBe false
		detector.observeNoPlayer(16_000) shouldBe true
	}

	test("a player that returns during a transient gap cancels the pending stop") {
		val detector = KodiPlaybackStopDetector()
		detector.observeReadyPlayer()
		detector.observeNoPlayer(10_000) shouldBe false
		detector.observeNoPlayer(14_000) shouldBe false
		detector.observeReadyPlayer()
		detector.observeNoPlayer(16_000) shouldBe false
		detector.observeNoPlayer(20_000) shouldBe false
	}

	test("an unavailable JSON-RPC query interrupts stop confirmation") {
		val detector = KodiPlaybackStopDetector()
		detector.observeReadyPlayer()
		detector.observeNoPlayer(10_000) shouldBe false
		detector.observeUnavailablePlayer()
		detector.observeNoPlayer(20_000) shouldBe false
		detector.observeNoPlayer(24_000) shouldBe false
		detector.observeNoPlayer(26_000) shouldBe true
	}

	test("a new episode resets the previous player's stop detection") {
		val detector = KodiPlaybackStopDetector()
		detector.observeReadyPlayer()
		detector.observeNoPlayer(10_000) shouldBe false
		detector.reset()
		detector.observeNoPlayer(30_000) shouldBe false
		detector.observeNoPlayer(60_000) shouldBe false
	}
})
