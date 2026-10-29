package ai.jayra.companion

import ai.jayra.companion.util.NetUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetUtilTest {
    @Test
    fun privateRangesAreAccepted() {
        listOf("192.168.1.5", "10.1.2.3", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1", "DESKTOP-JAYRA", "pc.local")
            .forEach { assertTrue(it, NetUtil.isPrivateLan(it)) }
    }

    @Test
    fun publicHostsAreRejected() {
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "example.com", "300.1.1.1", "")
            .forEach { assertFalse(it, NetUtil.isPrivateLan(it)) }
    }

    @Test
    fun filenamesAreSanitized() {
        assertEquals("report_final_.pdf", NetUtil.sanitizeFilename("report final!.pdf"))
        assertEquals("passwd", NetUtil.sanitizeFilename("../../etc/passwd"))
    }
}
