package com.aichatsdk.demo.transport

import com.aichatsdk.models.ChatEvent

/**
 * In-memory event log used by both real and mock transports. Mirrors iOS
 * DebugEventLogView's circular buffer (cap 500, drop-oldest 100).
 */
object DebugEventLog {
    enum class Direction { Sent, Received }
    data class Entry(
        val ts: Long,
        val direction: Direction,
        val event: ChatEvent?,
        val isAudio: Boolean = false,
        val audioBytes: Int = 0,
    )

    private val _entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<(List<Entry>) -> Unit>()
    private const val CAP = 500
    private const val DROP = 100

    @Synchronized
    fun add(entry: Entry) {
        _entries.addLast(entry)
        if (_entries.size > CAP) repeat(DROP) { _entries.removeFirst() }
        val snapshot = _entries.toList()
        listeners.forEach { it(snapshot) }
    }

    @Synchronized fun snapshot(): List<Entry> = _entries.toList()

    @Synchronized
    fun addListener(cb: (List<Entry>) -> Unit) {
        listeners += cb
        cb(_entries.toList())
    }

    @Synchronized fun removeListener(cb: (List<Entry>) -> Unit) { listeners -= cb }

    @Synchronized fun clear() {
        _entries.clear()
        listeners.forEach { it(emptyList()) }
    }
}
