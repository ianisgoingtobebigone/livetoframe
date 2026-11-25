package app.livetoframe.personal

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

internal data class MotionScanRecord(
    val generation: Long,
    val size: Long,
    val candidate: MotionCandidate?,
)

internal class MotionScanIndex(private val version: String) {
    private val records = mutableMapOf<Long, MotionScanRecord>()

    operator fun get(id: Long, generation: Long, size: Long): MotionScanRecord? =
        records[id]?.takeIf { it.generation == generation && it.size == size }

    fun put(id: Long, record: MotionScanRecord): Boolean {
        if (record.generation <= 0 || record.size <= 0) return false
        records[id] = record
        return true
    }

    fun retain(visibleIds: Set<Long>): Boolean = records.keys.retainAll(visibleIds)

    fun write(output: DataOutputStream) {
        output.writeUTF(version)
        output.writeInt(records.size)
        for ((id, record) in records) {
            output.writeLong(id)
            output.writeLong(record.generation)
            output.writeLong(record.size)
            output.writeLong(if (record.candidate == null) -1L else record.candidate.estimatedBytes ?: 0L)
        }
    }

    companion object {
        fun read(version: String, input: DataInputStream): MotionScanIndex {
            val index = MotionScanIndex(version)
            if (input.readUTF() != version) return index
            val count = input.readInt()
            if (count !in 0..1_000_000) throw IOException("Invalid motion index size")
            repeat(count) {
                val id = input.readLong()
                val generation = input.readLong()
                val size = input.readLong()
                val estimate = input.readLong()
                val candidate = when {
                    estimate == -1L -> null
                    estimate in 0 until size -> MotionCandidate(estimate.takeIf { it > 0 })
                    else -> throw IOException("Invalid motion index entry")
                }
                index.put(id, MotionScanRecord(generation, size, candidate))
            }
            return index
        }
    }
}
