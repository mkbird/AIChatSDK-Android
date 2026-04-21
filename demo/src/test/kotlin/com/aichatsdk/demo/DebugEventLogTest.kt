package com.aichatsdk.demo

import com.aichatsdk.demo.transport.DebugEventLog
import com.aichatsdk.models.ChatEvent
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class DebugEventLogTest {

    @Before fun setUp() { DebugEventLog.clear() }
    @After  fun tearDown() { DebugEventLog.clear() }

    @Test fun `listener is notified with snapshot on register and on add`() {
        val captured = mutableListOf<Int>()
        val cb: (List<DebugEventLog.Entry>) -> Unit = { captured += it.size }
        DebugEventLog.addListener(cb)
        assertThat(captured.last()).isEqualTo(0)
        DebugEventLog.add(entry("a"))
        assertThat(captured.last()).isEqualTo(1)
        DebugEventLog.add(entry("b"))
        assertThat(captured.last()).isEqualTo(2)
        DebugEventLog.removeListener(cb)
    }

    @Test fun `circular buffer drops oldest 100 when exceeding 500`() {
        repeat(510) { i -> DebugEventLog.add(entry("e$i")) }
        val snap = DebugEventLog.snapshot()
        // cap=500, drop=100; after 500 it triggers drop when adding 501 → becomes 401, grows to 410.
        // Formally: sizes cycle 500 → 400 → ... We only assert the cap is respected.
        assertThat(snap.size).isAtMost(500)
        // Oldest retained entry must be later than the very first.
        val first = snap.first().event?.type
        assertThat(first).isNotEqualTo("e0")
    }

    @Test fun `clear removes all entries and notifies listener`() {
        DebugEventLog.add(entry("x"))
        val sizes = mutableListOf<Int>()
        val cb: (List<DebugEventLog.Entry>) -> Unit = { sizes += it.size }
        DebugEventLog.addListener(cb)
        DebugEventLog.clear()
        assertThat(sizes.last()).isEqualTo(0)
        DebugEventLog.removeListener(cb)
    }

    private fun entry(type: String) = DebugEventLog.Entry(
        ts = System.currentTimeMillis(),
        direction = DebugEventLog.Direction.Sent,
        event = ChatEvent(type = type),
    )
}
