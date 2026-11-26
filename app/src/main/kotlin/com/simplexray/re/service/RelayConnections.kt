package com.simplexray.re.service

import com.simplexray.re.common.config.GroupMemberRef
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
        private set
    private var groupPath = emptySet<String>()
    private var attempt: Attempt? = null
    private var progress = 0L
    @Volatile var destinationHost = ""
    @Volatile var destinationIp = ""
    @Volatile var destinationPort = 0
    @Volatile var ruleId = "default"

    interface Attempt {
        fun isStalled(): Boolean
        fun retire()
    }

    @Synchronized fun select(leaf: String, path: Set<String>, selected: Attempt? = null) {
        leafTag = leaf
        groupPath = path
        attempt = selected
    }

    class Sample(val attempt: Attempt, val progress: Long)

    @Synchronized fun sample(group: String, member: GroupMemberRef): Sample? {
        val matchesMember = if (member.isGroup) member.tag in groupPath else leafTag == member.tag
        if (closed.get() || !matchesMember || (group != groupTag && group !in groupPath)) return null
        return attempt?.takeIf { it.isStalled() }?.let { Sample(it, progress) }
    }

    @Synchronized fun recordUpload(size: Int) {
        uploadBytes.addAndGet(size.toLong())
        // Queued TCP bytes count as traffic; exchange progress comes from the relay's upstream I/O.
        if (network == "udp") progress++
    }

    @Synchronized fun recordForwarded() {
        progress++
    }

    @Synchronized fun recordDownload(size: Int) {
        downloadBytes.addAndGet(size.toLong())
        progress++
    }

    @Synchronized fun closeIfStalled(sample: Sample) {
        if (!closed.get() && attempt === sample.attempt && progress == sample.progress && sample.attempt.isStalled()) {
            sample.attempt.retire()
        }
    }

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
