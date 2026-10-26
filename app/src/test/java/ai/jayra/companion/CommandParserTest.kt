package ai.jayra.companion

import ai.jayra.companion.protocol.AppId
import ai.jayra.companion.protocol.CommandParser
import ai.jayra.companion.protocol.FinderMode
import ai.jayra.companion.protocol.MediaOp
import ai.jayra.companion.protocol.PhoneCommand
import ai.jayra.companion.protocol.Priority
import ai.jayra.companion.protocol.ProtocolException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandParserTest {
    private fun parse(json: String) = CommandParser.parse(JSONObject(json))

    @Test fun notification_defaults_to_normal_priority() {
        val c = parse("""{"action":"TRIGGER_NOTIFICATION","title":"Hi","body":"There"}""")
        assertEquals(PhoneCommand.TriggerNotification("Hi", "There", Priority.NORMAL), c)
    }

    @Test fun notification_rejects_bad_lengths_and_priority() {
        assertThrows(ProtocolException::class.java) { parse("""{"action":"TRIGGER_NOTIFICATION","title":"","body":"x"}""") }
        val long = "x".repeat(1001)
        assertThrows(ProtocolException::class.java) { parse("""{"action":"TRIGGER_NOTIFICATION","title":"t","body":"$long"}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"TRIGGER_NOTIFICATION","title":"t","body":"b","priority":"urgent"}""") }
    }

    @Test fun launch_app_only_accepts_fixed_ids() {
        assertEquals(PhoneCommand.LaunchApp(AppId.CAMERA), parse("""{"action":"LAUNCH_APP","appId":"camera"}"""))
        assertThrows(ProtocolException::class.java) { parse("""{"action":"LAUNCH_APP","appId":"com.evil.app"}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"LAUNCH_APP"}""") }
    }

    @Test fun whatsapp_draft_requires_e164_and_message() {
        assertEquals(
            PhoneCommand.WhatsappDraft("+14155552671", "On my way"),
            parse("""{"action":"WHATSAPP_DRAFT","phoneNumber":"+14155552671","message":"On my way"}"""),
        )
        assertThrows(ProtocolException::class.java) { parse("""{"action":"WHATSAPP_DRAFT","phoneNumber":"14155552671","message":"x"}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"WHATSAPP_DRAFT","phoneNumber":"+0155552671","message":"x"}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"WHATSAPP_DRAFT","phoneNumber":"+14155552671","message":""}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"WHATSAPP_DRAFT","phoneNumber":"+14155552671","message":"${"x".repeat(2001)}"}""") }
    }

    @Test fun media_volume_requires_integer_in_range() {
        assertEquals(PhoneCommand.MediaControl(MediaOp.VOLUME, 40), parse("""{"action":"MEDIA_CONTROL","operation":"volume","volume":40}"""))
        assertThrows(ProtocolException::class.java) { parse("""{"action":"MEDIA_CONTROL","operation":"volume"}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"MEDIA_CONTROL","operation":"volume","volume":101}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"MEDIA_CONTROL","operation":"volume","volume":10.5}""") }
        assertEquals(PhoneCommand.MediaControl(MediaOp.NEXT, null), parse("""{"action":"MEDIA_CONTROL","operation":"next"}"""))
    }

    @Test fun finder_defaults_and_bounds() {
        assertEquals(PhoneCommand.RingtoneFinder(FinderMode.RING, 30), parse("""{"action":"TRIGGER_RINGTONE_FINDER"}"""))
        assertEquals(PhoneCommand.RingtoneFinder(FinderMode.VIBRATE, 5), parse("""{"action":"TRIGGER_RINGTONE_FINDER","mode":"vibrate","durationSec":5}"""))
        assertThrows(ProtocolException::class.java) { parse("""{"action":"TRIGGER_RINGTONE_FINDER","durationSec":4}""") }
        assertThrows(ProtocolException::class.java) { parse("""{"action":"TRIGGER_RINGTONE_FINDER","durationSec":61}""") }
    }

    @Test fun query_status_and_unknown_actions() {
        assertEquals(PhoneCommand.QueryPhoneStatus, parse("""{"action":"QUERY_PHONE_STATUS"}"""))
        assertThrows(ProtocolException::class.java) { parse("""{"action":"WIPE_PHONE"}""") }
    }
}
