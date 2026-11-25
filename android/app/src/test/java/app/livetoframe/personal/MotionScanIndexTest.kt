package app.livetoframe.personal

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionScanIndexTest {
    @Test fun reloadsTenThousandRecordsAndInvalidatesChangedMedia() {
        val index = MotionScanIndex("v1")
        val motion = MotionScanRecord(17, 10000, MotionCandidate(1000))
        val still = motion.copy(candidate = null)
        val unknownEstimate = motion.copy(candidate = MotionCandidate(null))
        for (id in 1L..10000L) {
            index.put(id, when (id % 3) {
                0L -> motion
                1L -> still
                else -> unknownEstimate
            })
        }
        val bytes = ByteArrayOutputStream().apply { index.write(DataOutputStream(this)) }.toByteArray()
        val restored = MotionScanIndex.read("v1", DataInputStream(bytes.inputStream()))
        for (id in 1L..10000L) assertEquals(index[id, 17, 10000], restored[id, 17, 10000])
        assertEquals(still, restored[1, 17, 10000])
        assertEquals(unknownEstimate, restored[2, 17, 10000])
        assertEquals(motion, restored[3, 17, 10000])
        assertNull(restored[3, 18, 10000])
        assertNull(restored[3, 17, 20000])
        assertNull(MotionScanIndex.read("v2", DataInputStream(bytes.inputStream()))[3, 17, 10000])
    }

    @Test fun dropsDeletedRowsAndDoesNotPersistRowsWithoutStableRevisions() {
        val index = MotionScanIndex("v1")
        assertTrue(index.put(1, MotionScanRecord(3, 100, MotionCandidate(10))))
        assertTrue(index.put(2, MotionScanRecord(4, 100, null)))
        assertFalse(index.put(3, MotionScanRecord(0, 100, null)))
        assertFalse(index.put(4, MotionScanRecord(5, 0, null)))
        assertTrue(index.retain(setOf(2)))
        assertNull(index[1, 3, 100])
        assertNull(index[3, 0, 100])
        assertNull(index[4, 5, 0])
        assertEquals(MotionScanRecord(4, 100, null), index[2, 4, 100])
    }
}
