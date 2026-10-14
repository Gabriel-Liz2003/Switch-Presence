package dev.gabriel.switchpresence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Regression tests for Nintendo's published presence states (no private accounts). */
class SwitchGamePresenceTest {
    private fun sample(state: String, game: String? = "The Witcher 3"): String {
        val activity = if (game == null) "{}" else """{"name":"$game", "imageUri":"https://example.org/image.jpg"}"""
        return """{"friend":{"presence":{"state":"$state","platform":2,"game":$activity}}}"""
    }

    @Test fun playingStateShowsGame() {
        val result = SwitchGame.parse(sample("PLAYING"))
        assertEquals("The Witcher 3", result?.title)
        assertEquals("Nintendo Switch 2", result?.platform)
    }

    @Test fun onlineStateAlsoSupportsGame() {
        assertEquals("The Witcher 3", SwitchGame.parse(sample("ONLINE"))?.title)
    }

    @Test fun onlineWithoutGameDoesNotCreateActivity() {
        assertNull(SwitchGame.parse(sample("ONLINE", null)))
    }

    @Test fun offlineAndInactiveClearActivity() {
        assertNull(SwitchGame.parse(sample("OFFLINE")))
        assertNull(SwitchGame.parse(sample("INACTIVE")))
    }

    @Test fun unexpectedStateDoesNotPretendConsoleIsOffline() {
        assertThrows(IllegalStateException::class.java) {
            SwitchGame.parse(sample("NEW_STATE"))
        }
    }
}
