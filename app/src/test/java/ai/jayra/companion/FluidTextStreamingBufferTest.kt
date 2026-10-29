package ai.jayra.companion

import ai.jayra.companion.net.FluidTextStreamingBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FluidTextStreamingBufferTest {

    @Test
    fun emitsAllTextInOrderAndFiresTurnEnd() = runTest {
        val ended = mutableListOf<String>()
        val buffer = FluidTextStreamingBuffer(backgroundScope, Random(7)) { ended += it }
        buffer.append("Hello world. ")
        buffer.append("Second one.")
        buffer.endTurn()
        advanceUntilIdle()
        assertEquals(listOf("Hello world. Second one."), ended)
        assertEquals("", buffer.text.value)
        assertFalse(buffer.isStreaming.value)
    }

    @Test
    fun consecutiveTurnsNeverMix() = runTest {
        val ended = mutableListOf<String>()
        val buffer = FluidTextStreamingBuffer(backgroundScope, Random(1)) { ended += it }
        buffer.append("first")
        buffer.endTurn()
        buffer.append("second")
        buffer.endTurn()
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), ended)
    }

    @Test
    fun typingTakesAtLeastTheMinimumPerCharacterDelay() = runTest {
        val buffer = FluidTextStreamingBuffer(backgroundScope, Random(3))
        buffer.append("abcdefghij")
        advanceUntilIdle()
        assertTrue("took ${currentTime}ms", currentTime >= 10 * FluidTextStreamingBuffer.MIN_DELAY_MS)
        assertEquals("abcdefghij", buffer.text.value)
    }

    @Test
    fun sentenceEndAddsAMicroPause() = runTest {
        val buffer = FluidTextStreamingBuffer(backgroundScope, Random(5))
        buffer.append("abcde. ghij") // 11 chars incl. one sentence boundary
        advanceUntilIdle()
        val floor = 11 * FluidTextStreamingBuffer.MIN_DELAY_MS + FluidTextStreamingBuffer.PAUSE_MIN_MS
        assertTrue("took ${currentTime}ms, expected >= ${floor}ms", currentTime >= floor)
    }

    @Test
    fun resetDropsQueuedText() = runTest {
        val ended = mutableListOf<String>()
        val buffer = FluidTextStreamingBuffer(backgroundScope, Random(2)) { ended += it }
        buffer.append("this will be dropped")
        buffer.reset()
        advanceUntilIdle()
        assertEquals("", buffer.text.value)
        assertTrue(ended.isEmpty())
    }
}
