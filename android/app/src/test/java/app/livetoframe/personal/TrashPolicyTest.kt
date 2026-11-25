package app.livetoframe.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TrashPolicyTest {
    @Test fun neverReportsTrashSuccessWithoutBothCurrentAssets() {
        assertEquals(TrashStatus.NEEDS_CHECK, TrashPolicy.confirmedStatus(TrashStatus.IN_TRASH, null, true))
        assertEquals(TrashStatus.NEEDS_CHECK, TrashPolicy.confirmedStatus(TrashStatus.REQUESTED, true, false))
        assertEquals(TrashStatus.PRESENT, TrashPolicy.confirmedStatus(TrashStatus.REQUESTED, false, true))
        assertEquals(TrashStatus.IN_TRASH, TrashPolicy.confirmedStatus(TrashStatus.REQUESTED, true, true))
    }

    @Test fun recognizesMissingFinalStateAsAnInterruptedWriteOnlyWhenTargetExists() {
        assertTrue(SaveResumePolicy.needsReconcile(null, true))
        assertTrue(SaveResumePolicy.needsReconcile("待核验", true))
        assertTrue(SaveResumePolicy.needsReconcile("待核验", false))
        assertFalse(SaveResumePolicy.needsReconcile("副本回读通过", true))
        assertFalse(SaveResumePolicy.needsReconcile("处理失败", false))
        assertFalse(SaveResumePolicy.needsReconcile(null, false))
    }

    @Test fun resumesVerifiedOutputWithoutNeedingTrashEligibilityMetadata() {
        assertTrue(SaveResumePolicy.reuseVerifiedCopy(VERIFIED_COPY))
        assertFalse(SaveResumePolicy.reuseVerifiedCopy("待核验"))
        assertFalse(SaveResumePolicy.reuseVerifiedCopy("处理失败"))
    }

    @Test fun requiresVerifiedCopyAndItsRecordedIdentity() {
        assertTrue(TrashPolicy.verified(VERIFIED_COPY, true))
        assertFalse(TrashPolicy.verified(VERIFIED_COPY, false))
        assertFalse(TrashPolicy.verified("待核验", true))
        assertFalse(TrashPolicy.verified("处理失败", true))
    }

    @Test fun excludesModifiedFavoriteUnpublishedAndTrashedOriginals() {
        fun eligible(generation: Long = 20, size: Long = 100, favorite: Boolean = false,
            pending: Boolean = false, trashed: Boolean = false) =
            TrashPolicy.originalUnchanged(20, generation, 100, size, favorite, pending, trashed)
        assertTrue(eligible())
        assertFalse(eligible(generation = 21))
        assertFalse(eligible(size = 99))
        assertFalse(eligible(favorite = true))
        assertFalse(eligible(pending = true))
        assertFalse(eligible(trashed = true))
    }
}
