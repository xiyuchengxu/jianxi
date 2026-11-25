package dev.rain.laundry.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import dev.rain.laundry.core.WireProtocol
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed per-account cloud state.
 *
 * Only an opaque owner hash is used as a preference key. The base URL, device token, account id,
 * binding state and pending order records are inside one AES-GCM ciphertext. Pairing codes and
 * 喵码 are never written here.
 */
class CloudReminderVault(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val alias = "simple_laundry_cloud_reminder_v1"
    private val gson = Gson()

    fun load(owner: String): CloudStoredState = synchronized(lock) {
        if (owner.isBlank()) return@synchronized CloudStoredState("")
        loadHashed(WireProtocol.sha256(owner))?.takeIf { it.owner == owner }
            ?: CloudStoredState(owner)
    }

    /** Worker access by opaque key only; WorkManager input never contains the account owner. */
    internal fun loadHashed(ownerHash: String): CloudStoredState? = synchronized(lock) {
        if (!ownerHash.matches(Regex("[0-9a-f]{64}"))) return@synchronized null
        val raw = preferences.getString("account_$ownerHash", null) ?: return@synchronized null
        val decoded = runCatching {
            val parts = raw.split('.', limit = 2)
            if (parts.size != 2) return@runCatching null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull() ?: return@synchronized null
        runCatching { gson.fromJson(decoded, CloudStoredState::class.java) }
            .getOrNull()?.takeIf { it.owner.isNotBlank() }
            ?.normalizedForLoad()
    }

    fun save(state: CloudStoredState) = synchronized(lock) {
        if (state.owner.isBlank()) throw CloudReminderException("missing cloud owner")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(gson.toJson(state).toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP)
        if (!preferences.edit().putString(key(state.owner), sealed).commit())
            throw CloudReminderException("could not save encrypted cloud state")
    }

    fun binding(owner: String): CloudBinding? = load(owner).binding

    fun saveBinding(owner: String, binding: CloudBinding) = synchronized(lock) {
        require(binding.owner == owner)
        val state = load(owner)
        save(state.copy(binding = binding))
    }

    /** Keep the credential check and the caller's mutation in one critical section. */
    internal fun <T> withBinding(owner: String, expected: CloudBinding?,
        block: (CloudStoredState) -> T
    ): T? = synchronized(lock) {
        val state = load(owner)
        if (!state.binding.sameCredentialsAs(expected)) return@synchronized null
        block(state)
    }

    internal fun compareAndSetBinding(owner: String, expected: CloudBinding?,
        replacement: CloudBinding?, orders: Map<String, CloudOrderSyncRecord>? = null,
        deviceReports: Map<String, CloudDeviceReportRecord>? = null
    ): Boolean = synchronized(lock) {
        require(replacement == null || replacement.owner == owner)
        withBinding(owner, expected) { state ->
            save(state.copy(binding = replacement, orders = orders ?: state.orders,
                deviceReports = deviceReports ?: state.deviceReports))
            true
        } == true
    }

    /** Used by both the immediate submission and WorkManager; stale replies never touch a new binding. */
    internal fun updateOrdersIfBound(owner: String, expected: CloudBinding,
        transform: (Map<String, CloudOrderSyncRecord>) -> Map<String, CloudOrderSyncRecord>
    ): Boolean = synchronized(lock) {
        withBinding(owner, expected) { state ->
            val updated = transform(state.orders)
            if (updated != state.orders) save(state.copy(orders = updated))
            true
        } == true
    }

    /** Device-report evidence is separate from platform-mode order records. */
    internal fun updateDeviceReportsIfBound(owner: String, expected: CloudBinding,
        transform: (Map<String, CloudDeviceReportRecord>) -> Map<String, CloudDeviceReportRecord>
    ): Boolean = synchronized(lock) {
        withBinding(owner, expected) { state ->
            val updated = transform(state.deviceReports)
            if (updated != state.deviceReports) save(state.copy(deviceReports = updated))
            true
        } == true
    }

    fun deviceReportRecords(owner: String): List<CloudDeviceReportRecord> =
        load(owner).deviceReports.values.toList()

    fun deviceReport(owner: String, orderKey: String): CloudDeviceReportRecord? =
        load(owner).deviceReports[orderKey]

    fun putDeviceReport(owner: String, record: CloudDeviceReportRecord) = synchronized(lock) {
        val state = load(owner)
        save(state.copy(deviceReports = state.deviceReports + (record.orderKey to record)))
    }

    /** Explicit user unbind: remove the local binding; no remote success is implied by this call. */
    fun clearBinding(owner: String) = synchronized(lock) {
        val state = load(owner)
        save(state.copy(binding = null, orders = emptyMap(), deviceReports = emptyMap()))
    }

    /**
     * Local logout runs even when the remote request cannot be confirmed. Move the binding into an
     * encrypted retry record so the old device token can be used only for its own DELETE request.
     */
    fun detachForLogout(owner: String): CloudPendingUnbind? = synchronized(lock) {
        val state = load(owner)
        val binding = state.binding ?: return@synchronized null
        val pending = CloudPendingUnbind(binding.owner, binding.serverUrl, binding.deviceToken,
            binding.accountId, binding.deviceId, System.currentTimeMillis())
        val deduped = state.pendingUnbinds.filterNot { it.deviceId == pending.deviceId } + pending
        save(state.copy(binding = null, orders = emptyMap(), deviceReports = emptyMap(),
            pendingUnbinds = deduped))
        pending
    }

    fun pendingUnbinds(owner: String): List<CloudPendingUnbind> = load(owner).pendingUnbinds

    fun addPendingUnbind(owner: String, pending: CloudPendingUnbind) = synchronized(lock) {
        require(pending.owner == owner)
        val state = load(owner)
        save(state.copy(pendingUnbinds =
            state.pendingUnbinds.filterNot { it.deviceId == pending.deviceId } + pending))
    }

    internal fun pendingUnbindsHashed(ownerHash: String): List<CloudPendingUnbind> =
        loadHashed(ownerHash)?.pendingUnbinds.orEmpty()

    fun removePendingUnbind(owner: String, deviceId: String) = synchronized(lock) {
        val state = load(owner)
        save(state.copy(pendingUnbinds = state.pendingUnbinds.filterNot { it.deviceId == deviceId }))
    }

    internal fun removePendingUnbindHashed(ownerHash: String, deviceId: String) = synchronized(lock) {
        val state = loadHashed(ownerHash) ?: return@synchronized
        save(state.copy(pendingUnbinds = state.pendingUnbinds.filterNot { it.deviceId == deviceId }))
    }

    fun markUnbindAttempt(owner: String, deviceId: String, error: String?) = synchronized(lock) {
        val state = load(owner)
        save(state.copy(pendingUnbinds = state.pendingUnbinds.map {
            if (it.deviceId == deviceId) it.copy(attempts = it.attempts + 1,
                lastError = error?.take(180)) else it
        }))
    }

    internal fun markUnbindAttemptHashed(ownerHash: String, deviceId: String, error: String?) = synchronized(lock) {
        val state = loadHashed(ownerHash) ?: return@synchronized
        save(state.copy(pendingUnbinds = state.pendingUnbinds.map {
            if (it.deviceId == deviceId) it.copy(attempts = it.attempts + 1,
                lastError = error?.take(180)) else it
        }))
    }

    fun orderRecord(owner: String, orderKey: String): CloudOrderSyncRecord? =
        load(owner).orders[orderKey]

    fun orderRecords(owner: String): List<CloudOrderSyncRecord> = load(owner).orders.values.toList()

    fun putOrderRecord(owner: String, record: CloudOrderSyncRecord) = synchronized(lock) {
        val state = load(owner)
        save(state.copy(orders = state.orders + (record.orderKey to record)))
    }

    fun clearOrderRecords(owner: String) = synchronized(lock) {
        val state = load(owner)
        save(state.copy(orders = emptyMap()))
    }

    /** Only one running laundry order is in scope, so stale pending records cannot linger. */
    fun retainOnlyOrder(owner: String, orderKey: String) = synchronized(lock) {
        val state = load(owner)
        val retained = state.orders.filterKeys { it == orderKey }
        if (retained != state.orders) save(state.copy(orders = retained))
    }

    private fun key(owner: String) = "account_" + WireProtocol.sha256(owner)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    companion object {
        private const val FILE = "cloud_reminder_vault"
        private val lock = Any()
    }
}
