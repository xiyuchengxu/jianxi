package dev.rain.laundry.keepalive

import org.junit.Assert.*
import org.junit.Test

class KeepAlivePolicyTest {
    private class MemoryStore(var value: KeepAlivePreference = KeepAlivePreference()) : KeepAliveStore {
        var writes = 0
        var failAt = -1
        override fun read() = value
        override fun write(value: KeepAlivePreference) {
            writes++
            if (writes == failAt) error("disk unavailable")
            this.value = value
        }
    }
    private class Whitelist(var present: Boolean = false) : SelfIdleWhitelist {
        var additions = 0
        var removals = 0
        var queryFails = false
        var addReplyFails = false
        var ignoreAdd = false
        var removeReplyFails = false
        override fun containsSelf(): Boolean {
            if (queryFails) error("disconnected")
            return present
        }
        override fun addSelf() {
            additions++
            if (!ignoreAdd) present = true
            if (addReplyFails) error("reply lost after mutation")
        }
        override fun removeSelf() {
            removals++
            if (removeReplyFails) error("permission revoked")
            present = false
        }
    }
    private fun fails(action: () -> Unit) {
        assertTrue(runCatching(action).isFailure)
    }

    @Test fun initialStateDoesNotRequestOrModifyAnything() {
        assertEquals(KeepAlivePreference(false, false), MemoryStore().read())
    }

    @Test fun preservesAnEntryThatExistedBeforeOptIn() {
        val store = MemoryStore()
        val whitelist = Whitelist(present = true)
        KeepAlivePolicy.enable(store, whitelist)
        assertEquals(KeepAlivePreference(true, false), store.value)
        KeepAlivePolicy.disable(store, whitelist)
        assertTrue(whitelist.present)
        assertEquals(0, whitelist.additions)
        assertEquals(0, whitelist.removals)
        assertEquals(KeepAlivePreference(), store.value)
    }

    @Test fun restoresOnlyTheEntryIntroducedByThisFeature() {
        val store = MemoryStore()
        val whitelist = Whitelist()
        KeepAlivePolicy.enable(store, whitelist)
        assertEquals(KeepAlivePreference(true, true), store.value)
        KeepAlivePolicy.disable(store, whitelist)
        assertFalse(whitelist.present)
        assertEquals(1, whitelist.additions)
        assertEquals(1, whitelist.removals)
        assertEquals(KeepAlivePreference(), store.value)
    }

    @Test fun aFailedJournalWritePreventsTheSystemMutation() {
        val store = MemoryStore().apply { failAt = 1 }
        val whitelist = Whitelist()
        fails { KeepAlivePolicy.enable(store, whitelist) }
        assertEquals(0, whitelist.additions)
        assertEquals(KeepAlivePreference(), store.value)
    }

    @Test fun lostAdditionReplyLeavesARecoverableJournal() {
        val store = MemoryStore()
        val whitelist = Whitelist().apply { addReplyFails = true }
        fails { KeepAlivePolicy.enable(store, whitelist) }
        assertTrue(whitelist.present)
        assertEquals(KeepAlivePreference(false, true), store.value)
        whitelist.addReplyFails = false
        KeepAlivePolicy.disable(store, whitelist)
        assertFalse(whitelist.present)
        assertEquals(KeepAlivePreference(), store.value)
    }

    @Test fun aSuccessExitWithoutReadbackDoesNotClaimEnabled() {
        val store = MemoryStore()
        val whitelist = Whitelist().apply { ignoreAdd = true }
        fails { KeepAlivePolicy.enable(store, whitelist) }
        assertEquals(KeepAlivePreference(false, true), store.value)
        KeepAlivePolicy.disable(store, whitelist)
        assertEquals(0, whitelist.removals)
        assertEquals(KeepAlivePreference(), store.value)
    }

    @Test fun aFinalWriteFailureCanStillRestoreTheSystemChange() {
        val store = MemoryStore().apply { failAt = 2 }
        val whitelist = Whitelist()
        fails { KeepAlivePolicy.enable(store, whitelist) }
        assertEquals(KeepAlivePreference(false, true), store.value)
        assertTrue(whitelist.present)
        KeepAlivePolicy.disable(store, whitelist)
        assertFalse(whitelist.present)
    }

    @Test fun disconnectWhileDisablingPersistsOptOutAndKeepsRestorationPending() {
        val store = MemoryStore(KeepAlivePreference(true, true))
        fails { KeepAlivePolicy.disable(store, null) }
        assertEquals(KeepAlivePreference(false, true), store.value)
        val reconnected = Whitelist(present = true)
        KeepAlivePolicy.disable(store, reconnected)
        assertEquals(KeepAlivePreference(), store.value)
        assertFalse(reconnected.present)
    }

    @Test fun revokedPermissionOnRemovalCanBeRetried() {
        val store = MemoryStore(KeepAlivePreference(true, true))
        val whitelist = Whitelist(present = true).apply { removeReplyFails = true }
        fails { KeepAlivePolicy.disable(store, whitelist) }
        assertEquals(KeepAlivePreference(false, true), store.value)
        assertTrue(whitelist.present)
        whitelist.removeReplyFails = false
        KeepAlivePolicy.disable(store, whitelist)
        assertEquals(KeepAlivePreference(), store.value)
        assertFalse(whitelist.present)
    }

    @Test fun optOutWithoutOurChangeNeedsNoPrivilegeOrBackend() {
        val store = MemoryStore(KeepAlivePreference(true, false))
        KeepAlivePolicy.disable(store, null)
        assertEquals(KeepAlivePreference(), store.value)
    }

    @Test fun aFailedInitialQueryNeverChangesSettingsOrClaimsOwnership() {
        val store = MemoryStore()
        val whitelist = Whitelist().apply { queryFails = true }
        fails { KeepAlivePolicy.enable(store, whitelist) }
        assertEquals(0, store.writes)
        assertEquals(0, whitelist.additions)
        assertEquals(KeepAlivePreference(), store.value)
    }

    @Test fun repeatedEnablePreservesOwnershipUntilRestoration() {
        val store = MemoryStore()
        val whitelist = Whitelist()
        KeepAlivePolicy.enable(store, whitelist)
        KeepAlivePolicy.enable(store, whitelist)
        assertEquals(KeepAlivePreference(true, true), store.value)
        assertEquals(1, whitelist.additions)
        KeepAlivePolicy.disable(store, whitelist)
        assertEquals(1, whitelist.removals)
    }
}
