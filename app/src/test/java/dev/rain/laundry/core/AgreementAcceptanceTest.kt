package dev.rain.laundry.core

import org.junit.Assert.*
import org.junit.Test

class AgreementAcceptanceTest {
    private class Storage : AgreementStorage {
        var value: String? = null
        var timestamp: Long? = null
        var failWrite = false
        var writes = 0
        override fun acceptedRevision(): String? = value
        override fun record(revision: String, acceptedAt: Long): Boolean {
            writes++
            if (failWrite) return false
            value = revision
            timestamp = acceptedAt
            return true
        }
    }

    @Test fun firstLaunchIsBlockedWithoutAcknowledgement() {
        assertFalse(AgreementAcceptance(Storage(), "current").isAccepted())
    }

    @Test fun onlyTheExactRequestedSentenceIsValid() {
        assertTrue(AgreementPolicy.validConfirmation("我已阅读免责声明，保证遵守用户协议"))
        for (invalid in listOf("", "我已阅读免责声明", "我已阅读免责声明,保证遵守用户协议",
            "我已阅读免责声明，保证遵守用户守则", " ${AgreementPolicy.CONFIRMATION}", "${AgreementPolicy.CONFIRMATION}。")) {
            assertFalse(invalid, AgreementPolicy.validConfirmation(invalid))
        }
    }

    @Test fun incorrectAcknowledgementNeverWritesAConsentGrant() {
        val storage = Storage()
        val acceptance = AgreementAcceptance(storage, "current")
        assertFalse(acceptance.accept("同意"))
        assertEquals(0, storage.writes)
        assertFalse(acceptance.isAccepted())
    }

    @Test fun correctAcknowledgementRecordsTheDocumentRevisionAndTime() {
        val storage = Storage()
        val acceptance = AgreementAcceptance(storage, "current") { 12345L }
        assertTrue(acceptance.accept(AgreementPolicy.CONFIRMATION))
        assertEquals("current", storage.value)
        assertEquals(12345L, storage.timestamp)
        assertTrue(acceptance.isAccepted())
    }

    @Test fun aFailedSaveDoesNotUnlockUse() {
        val storage = Storage().apply { failWrite = true }
        val acceptance = AgreementAcceptance(storage, "current")
        assertFalse(acceptance.accept(AgreementPolicy.CONFIRMATION))
        assertFalse(acceptance.isAccepted())
    }

    @Test fun reopeningTheSameDocumentsRetainsTheGrant() {
        val storage = Storage()
        assertTrue(AgreementAcceptance(storage, "current").accept(AgreementPolicy.CONFIRMATION))
        assertTrue(AgreementAcceptance(storage, "current").isAccepted())
    }

    @Test fun changedDocumentsRequireAnotherAcknowledgement() {
        val storage = Storage()
        AgreementAcceptance(storage, "old").accept(AgreementPolicy.CONFIRMATION)
        assertFalse(AgreementAcceptance(storage, "new").isAccepted())
    }

    @Test fun eachDocumentAffectsTheStoredRevision() {
        val current = AgreementPolicy.revision("disclaimer", "rules")
        assertEquals(current, AgreementPolicy.revision("disclaimer", "rules"))
        assertNotEquals(current, AgreementPolicy.revision("changed disclaimer", "rules"))
        assertNotEquals(current, AgreementPolicy.revision("disclaimer", "changed rules"))
    }
}
