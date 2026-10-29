package ai.jayra.companion

import ai.jayra.companion.net.PairingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingParserTest {
    @Test
    fun bareEightDigitCode() {
        val p = PairingParser.parse(" 84920153 ")!!
        assertEquals("84920153", p.code)
        assertNull(p.host)
    }

    @Test
    fun jsonPayload() {
        val p = PairingParser.parse("""{"host":"192.168.1.105","port":3000,"code":"84920153"}""")!!
        assertEquals("192.168.1.105", p.host)
        assertEquals(3000, p.port)
        assertEquals("84920153", p.code)
    }

    @Test
    fun customSchemeUrl() {
        val p = PairingParser.parse("jayra://pair?host=10.0.0.7&port=3100&code=11112222")!!
        assertEquals("10.0.0.7", p.host)
        assertEquals(3100, p.port)
        assertEquals("11112222", p.code)
    }

    @Test
    fun httpUrlWithCode() {
        val p = PairingParser.parse("http://192.168.0.20:3000/pair?code=12345678")!!
        assertEquals("192.168.0.20", p.host)
        assertEquals(3000, p.port)
        assertEquals("12345678", p.code)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(PairingParser.parse("hello world"))
        assertNull(PairingParser.parse(""))
    }
}
