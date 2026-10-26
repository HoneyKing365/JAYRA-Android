package ai.jayra.companion

import ai.jayra.companion.net.Backoff
import ai.jayra.companion.security.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffAndRedactorTest {
    @Test fun backoff_doubles_then_caps_at_30s() {
        val d = (0..8).map { Backoff.delayMs(it, jitter = 0) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L), d)
    }

    @Test fun redactor_scrubs_secrets_and_caps_length() {
        val out = Redactor.redact("mail me at a.b@example.com code 482913 key sk_live_abc123def456ghi789jkl")
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("482913"))
        assertFalse(out.contains("sk_live"))
        assertTrue(Redactor.redact("x".repeat(1000)).length <= 256)
    }
}
