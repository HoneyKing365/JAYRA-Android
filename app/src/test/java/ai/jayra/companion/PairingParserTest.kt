package ai.jayra.companion

import ai.jayra.companion.data.PairingException
import ai.jayra.companion.data.PairingParser
import ai.jayra.companion.security.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingParserTest {
    private val token = "abcdEFGH1234ijkl5678"

    @Test fun parses_lan_pairing_link() {
        val p = PairingParser.parse("http://192.168.1.105:3000/#mobileBridgeToken=$token")
        assertEquals("192.168.1.105", p.host)
        assertEquals(3000, p.port)
        assertFalse(p.secure)
        assertEquals(token, p.bridgeToken)
        assertNull(p.gatewayToken)
        assertEquals("ws://192.168.1.105:3000/mobile-bridge", p.wsUrl("/mobile-bridge"))
    }

    @Test fun keeps_gateway_token_when_present() {
        val p = PairingParser.parse("http://10.0.0.7:3000/#token=gatewayTOKEN123456&mobileBridgeToken=$token")
        assertEquals("gatewayTOKEN123456", p.gatewayToken)
        assertEquals(token, p.bridgeToken)
    }

    @Test fun rejects_missing_or_weak_token() {
        assertThrows(PairingException::class.java) { PairingParser.parse("http://192.168.1.5:3000/") }
        assertThrows(PairingException::class.java) { PairingParser.parse("http://192.168.1.5:3000/#mobileBridgeToken=short") }
    }

    @Test fun refuses_cleartext_to_public_hosts_but_allows_tls() {
        assertThrows(PairingException::class.java) { PairingParser.parse("http://8.8.8.8:3000/#mobileBridgeToken=$token") }
        assertTrue(PairingParser.parse("https://jayra.example.com:443/#mobileBridgeToken=$token").secure)
    }

    @Test fun toString_never_leaks_tokens() {
        val p = PairingParser.parse("http://192.168.1.105:3000/#mobileBridgeToken=$token")
        assertFalse(p.toString().contains(token))
    }

    @Test fun private_network_policy() {
        assertTrue(NetworkPolicy.isPrivateHost("192.168.0.2"))
        assertTrue(NetworkPolicy.isPrivateHost("10.1.2.3"))
        assertTrue(NetworkPolicy.isPrivateHost("172.16.0.1"))
        assertTrue(NetworkPolicy.isPrivateHost("172.31.255.255"))
        assertFalse(NetworkPolicy.isPrivateHost("172.32.0.1"))
        assertFalse(NetworkPolicy.isPrivateHost("8.8.8.8"))
        assertFalse(NetworkPolicy.isPrivateHost("example.com"))
        assertTrue(NetworkPolicy.isPrivateHost("my-laptop.local"))
    }
}
