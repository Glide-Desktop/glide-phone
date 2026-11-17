package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoUrlsTest {
    @Test
    fun seekIsAddedPerSite() {
        assertEquals("https://www.youtube.com/watch?v=abc&t=95", VideoUrls.withSeek("https://www.youtube.com/watch?v=abc", 95_400))
        assertEquals("https://vimeo.com/1?t=95s", VideoUrls.withSeek("https://vimeo.com/1", 95_000))
        assertEquals("https://www.twitch.tv/videos/1?t=01m35s", VideoUrls.withSeek("https://www.twitch.tv/videos/1", 95_000))
        assertEquals("https://example.com/v", VideoUrls.withSeek("https://example.com/v", 95_000))
        assertEquals("https://youtu.be/x", VideoUrls.withSeek("https://youtu.be/x", 0))
    }

    @Test
    fun existingParameterIsReplacedOnlyWhenItMatches() {
        assertEquals("https://youtu.be/x?t=10#c", VideoUrls.withSeek("https://youtu.be/x?t=3#c", 10_000))
        assertEquals("https://youtu.be/x?t=abc", VideoUrls.withSeek("https://youtu.be/x?t=abc", 10_000))
    }

    @Test
    fun tvLinksBecomePhoneLinks() {
        assertEquals("https://www.youtube.com/watch?v=a", VideoUrls.fromTvLink("https://www.youtube.com/tv#/watch?v=a"))
    }
}
