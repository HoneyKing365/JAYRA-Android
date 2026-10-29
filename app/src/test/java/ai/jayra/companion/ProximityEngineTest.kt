package ai.jayra.companion

import ai.jayra.companion.service.ProximityEngine
import ai.jayra.companion.service.ProximityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityEngineTest {

    @Test
    fun sustainedWeakSignalCommitsAway() {
        val e = ProximityEngine()
        var t = 0L
        var snap = e.onSample(-50, 10.0, t)
        assertEquals(ProximityState.NEAR, snap.state)
        repeat(10) {
            t += 1000
            snap = e.onSample(-85, 20.0, t)
        }
        assertEquals(ProximityState.AWAY, snap.state)
    }

    @Test
    fun shortBlipDoesNotTriggerAway() {
        val e = ProximityEngine()
        var t = 0L
        repeat(5) { e.onSample(-50, 10.0, t); t += 1000 }
        repeat(3) { e.onSample(-95, 10.0, t); t += 1000 }   // brief dip, hold time not reached
        val snap = e.onSample(-50, 10.0, t)                   // recovered
        assertEquals(ProximityState.NEAR, snap.state)
    }

    @Test
    fun highLatencyAloneCausesAway() {
        val e = ProximityEngine()
        var snap = e.onSample(null, 150.0, 0)
        assertEquals(ProximityState.TRANSITIONING_AWAY, snap.state)
        for (t in 1000L..5000L step 1000L) snap = e.onSample(null, 150.0, t)
        assertEquals(ProximityState.AWAY, snap.state)
    }

    @Test
    fun returningNearNeedsThreeSecondsOfHold() {
        val e = ProximityEngine()
        e.forceState(ProximityState.AWAY)
        var snap = e.onSample(-50, 10.0, 0)
        assertEquals(ProximityState.TRANSITIONING_NEAR, snap.state)
        snap = e.onSample(-50, 10.0, 2000)
        assertEquals(ProximityState.TRANSITIONING_NEAR, snap.state)
        snap = e.onSample(-50, 10.0, 3000)
        assertEquals(ProximityState.NEAR, snap.state)
    }

    @Test
    fun compositeScoreSpansZeroToHundred() {
        val strong = ProximityEngine().onSample(-55, 15.0, 0)
        val weak = ProximityEngine().onSample(-90, 130.0, 0)
        assertEquals(100, strong.score)
        assertEquals(0, weak.score)
        assertTrue(strong.score > weak.score)
    }

    @Test
    fun hostForcedStateOverridesLocalState() {
        val e = ProximityEngine()
        e.forceState(ProximityState.AWAY, 22)
        assertEquals(ProximityState.AWAY, e.snapshot.value.state)
        assertEquals(22, e.snapshot.value.score)
    }
}
