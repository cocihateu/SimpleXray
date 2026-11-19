package com.simplexray.re.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class RelayConnectionSnapshot(
    val id: String,
    val network: String,
    val sourceIp: String,
    val sourcePort: Int,
    val destinationHost: String,
    val destinationIp: String,
    val destinationPort: Int,
    val groupTag: String,
    val leafTag: String,
    val startTimestampMs: Long,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val ruleId: String = "default",
)

internal class RelayConnectionEntry(
    val network: String,
    val sourceIp: String,
    val sourcePort: Int,
    val groupTag: String,
    private val closeTransport: () -> Unit,
) {
    val id = UUID.randomUUID().toString()
    val startedAt = System.currentTimeMillis()
    val closed = AtomicBoolean(false)
    val uploadBytes = AtomicLong()
    val downloadBytes = AtomicLong()
    @Volatile var leafTag = ""
    @Volatile var destinationHost = ""
    @Volatile var destinationIp = ""
    @Volatile var destinationPort = 0
    @Volatile var ruleId = "default"

    fun close() {
        if (closed.compareAndSet(false, true)) closeTransport()
    }

    fun snapshot() = RelayConnectionSnapshot(
        id, network, sourceIp, sourcePort, destinationHost, destinationIp, destinationPort,
        groupTag, leafTag, startedAt, uploadBytes.get(), downloadBytes.get(), ruleId,
    )
}

/** Live connections owned by the fallback relay; counters describe client payload. */
internal object RelayConnectionRegistry {
    private val entries = ConcurrentHashMap<String, RelayConnectionEntry>()
    fun register(entry: RelayConnectionEntry) { entries[entry.id] = entry }
    fun unregister(id: String) { entries.remove(id) }
    fun all(): List<RelayConnectionSnapshot> = entries.values.map { it.snapshot() }
    fun close(id: String): Boolean = entries.remove(id)?.let { it.close(); true } ?: false
    fun closeAll() { entries.keys.toList().forEach(::close) }
}
