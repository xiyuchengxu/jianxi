package dev.rain.laundry.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.rain.laundry.core.*
import dev.rain.laundry.notifications.LaundryReminderRecord
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionVault(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("laundry_vault", Context.MODE_PRIVATE)
    private val alias = "simple_laundry_session_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun putAll(values: Map<String, String>) {
        val encrypted = values.mapValues { encrypt(it.value) }
        val editor = preferences.edit()
        encrypted.forEach { (name, data) -> editor.putString(name, data) }
        if (!editor.commit()) throw ClientError("无法保存本地会话，请检查存储空间")
    }
    private fun put(name: String, value: String) {
        putAll(mapOf(name to value))
    }
    private fun get(name: String): String? {
        val raw = preferences.getString(name, null) ?: return null
        return try {
            val parts = raw.split('.', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
    fun token(): String = get("token").orEmpty()
    fun owner(): String = get("owner").orEmpty()
    fun phoneLabel(): String = get("phone_label").orEmpty()
    fun login(phone: String, token: String) = synchronized(orderLock) {
        putAll(mapOf("owner" to WireProtocol.sha256(phone),
            "phone_label" to phone.take(3) + " **** " + phone.takeLast(4), "token" to token))
        // Session changes and live-notification posts share orderLock. An old post cannot
        // reappear after the old session's notification has been removed.
        runCatching { dev.rain.laundry.notifications.LaundryLiveMonitor.stop(appContext) }
        Unit
    }
    fun logout() = synchronized(orderLock) {
        preferences.edit().remove("token").remove("owner").remove("phone_label").commit()
        runCatching { dev.rain.laundry.notifications.LaundryLiveMonitor.stop(appContext) }
        Unit
    }
    /** The current workflow pointer is independent of the bounded browsing history. */
    fun saveOrder(order: LocalOrder) = synchronized(orderLock) {
        val current = readCurrent(order.owner)
        val history = OrderHistory.merge(order.owner, ordersFor(order.owner), order)
        val values = mutableMapOf("orders_" + order.owner to Gson().toJson(history))
        if (current == null || OrderHistory.sameOrder(current, order) ||
            (current.phase.finished && order.createdAt >= current.createdAt)) {
            values["order_" + order.owner] = Gson().toJson(order)
        }
        putAll(values)
    }
    /** Explicit promotion is only used after the view model checks unfinished-order conflicts. */
    fun selectOrder(order: LocalOrder) = synchronized(orderLock) {
        if (order.owner != owner()) throw ClientError("订单不属于当前账号")
        val current = readCurrent(order.owner)
        if (current != null && !current.phase.finished && !OrderHistory.sameOrder(current, order))
            throw ClientError("请先处理当前未完成的订单")
        put("order_" + order.owner, Gson().toJson(order))
    }
    private fun readCurrent(owner: String): LocalOrder? = get("order_" + owner)?.let { raw ->
        runCatching { Gson().fromJson(raw, LocalOrder::class.java) }.getOrNull()?.takeIf { it.owner == owner }
    }
    fun order(): LocalOrder? = synchronized(orderLock) {
        val owner = owner().takeIf { it.isNotBlank() } ?: return@synchronized null
        readCurrent(owner) ?: ordersFor(owner).let { orders -> orders.firstOrNull { !it.phase.finished } ?: orders.firstOrNull() }
    }
    fun orders(): List<LocalOrder> = synchronized(orderLock) {
        val owner = owner().takeIf { it.isNotBlank() } ?: return@synchronized emptyList()
        ordersFor(owner)
    }
    private fun ordersFor(owner: String): List<LocalOrder> {
        val stored = get("orders_" + owner)?.let { raw ->
            runCatching { Gson().fromJson<List<LocalOrder>>(raw, object : TypeToken<List<LocalOrder>>() {}.type) }.getOrNull()
        }.orEmpty()
        // The legacy current record and latest water bill migrate without replacing a newer saved bill state.
        val settlement = readWater(owner)?.settlementOrder?.takeIf { bill -> stored.none { OrderHistory.sameOrder(it, bill) } }
        val merged = OrderHistory.merge(owner, listOfNotNull(settlement) + stored, readCurrent(owner))
        if (merged != stored) put("orders_" + owner, Gson().toJson(merged))
        return merged
    }
    fun saveWater(session: WaterSession) { put("water_" + session.owner, Gson().toJson(session)) }
    private fun readWater(owner: String): WaterSession? = get("water_" + owner)?.let { raw ->
        runCatching { Gson().fromJson(raw, WaterSession::class.java) }.getOrNull()?.takeIf { it.owner == owner }
    }
    fun water(): WaterSession? = readWater(owner())

    /**
     * Saved devices are a separate owner-scoped encrypted list. They survive logout, but are only
     * readable while the same account has a live token, so switching accounts cannot leak them.
     */
    fun savedDevices(): List<SavedDevice> = synchronized(orderLock) {
        val account = owner().takeIf { it.isNotBlank() && token().isNotBlank() }
            ?: return@synchronized emptyList()
        SavedDevices.merge(account, readSavedDevices(account))
    }

    private fun readSavedDevices(account: String): List<SavedDevice> =
        get("devices_$account")?.let { raw -> SavedDevices.decode(raw) }.orEmpty()

    /** Recheck owner/token/revision under the same lock as the write; a stale scan is discarded. */
    fun saveSavedDevice(candidate: SavedDevice, expectedOwner: String, expectedToken: String,
        isCurrent: () -> Boolean = { true }
    ): Boolean = synchronized(orderLock) {
        if (!SavedDevices.isValid(expectedOwner, candidate)) return@synchronized false
        if (!SavedDevices.acceptsStoredResponse(expectedOwner, expectedToken, owner(), token(),
                candidate.owner, isCurrent())) return@synchronized false
        val merged = SavedDevices.merge(expectedOwner, readSavedDevices(expectedOwner), candidate)
        put("devices_$expectedOwner", Gson().toJson(merged))
        true
    }

    fun deleteSavedDevice(record: SavedDevice): Boolean = synchronized(orderLock) {
        val account = owner().takeIf { it.isNotBlank() && token().isNotBlank() }
            ?: return@synchronized false
        if (record.owner != account) return@synchronized false
        val existing = readSavedDevices(account)
        val updated = existing.filterNot { it.kind == record.kind && it.deviceId == record.deviceId }
        if (updated.size == existing.size) return@synchronized false
        put("devices_$account", Gson().toJson(SavedDevices.merge(account, updated)))
        true
    }

    fun renameSavedDevice(record: SavedDevice, note: String): Boolean = synchronized(orderLock) {
        val account = owner().takeIf { it.isNotBlank() && token().isNotBlank() }
            ?: return@synchronized false
        if (record.owner != account) return@synchronized false
        val existing = readSavedDevices(account)
        val normalized = SavedDevices.normalizeNote(note)
        var found = false
        val updated = existing.map {
            if (it.kind == record.kind && it.deviceId == record.deviceId) {
                found = true
                it.copy(note = normalized)
            } else it
        }
        if (!found) return@synchronized false
        if (updated != existing) put("devices_$account", Gson().toJson(SavedDevices.merge(account, updated)))
        true
    }

    /** Reminder intent and delivery claims survive history truncation and process recreation. */
    fun laundryReminders(): List<LaundryReminderRecord> = synchronized(orderLock) {
        val account = owner().takeIf { it.isNotBlank() && token().isNotBlank() }
            ?: return@synchronized emptyList()
        readLaundryReminders(account)
    }
    private fun readLaundryReminders(account: String): List<LaundryReminderRecord> =
        get("laundry_reminders_$account")?.let { raw ->
            runCatching { Gson().fromJson<List<LaundryReminderRecord>>(raw,
                object : TypeToken<List<LaundryReminderRecord>>() {}.type).orEmpty()
                .filter { it.order.owner == account && it.order.isLaundry } }.getOrNull()
        }.orEmpty()

    fun updateLaundryReminders(expectedOwner: String,
        transform: (List<LaundryReminderRecord>) -> List<LaundryReminderRecord>
    ): List<LaundryReminderRecord>? = synchronized(orderLock) {
        if (expectedOwner.isBlank() || owner() != expectedOwner || token().isBlank()) return@synchronized null
        val updated = transform(readLaundryReminders(expectedOwner))
            .filter { it.order.owner == expectedOwner && it.order.isLaundry }
        put("laundry_reminders_$expectedOwner", Gson().toJson(updated))
        updated
    }

    /** Serialize live-notification start/cancel and state reads with account changes and posts. */
    internal fun <T> withLaundryStateLock(block: () -> T): T = synchronized(orderLock) { block() }

    /** A stale worker reply cannot overwrite a later foreground update or another login session. */
    fun withLaundrySession(expectedOwner: String, expectedToken: String, isCurrent: () -> Boolean,
        block: () -> Boolean
    ): Boolean = synchronized(orderLock) {
        if (owner() != expectedOwner || token() != expectedToken || expectedToken.isBlank() || !isCurrent()) false
        else block()
    }

    /** A stale worker reply cannot overwrite a later foreground update or another login session. */
    fun updateLaundryOrder(expectedOwner: String, expectedToken: String, previous: LocalOrder,
        updated: LocalOrder, isCurrent: () -> Boolean = { true }
    ): Boolean = synchronized(orderLock) {
        if (expectedOwner.isBlank() || expectedToken.isBlank() || owner() != expectedOwner ||
            token() != expectedToken || previous.owner != expectedOwner ||
            !OrderHistory.sameOrder(previous, updated) || !isCurrent()) return@synchronized false
        val current = readCurrent(expectedOwner)
        val stored = current?.takeIf { OrderHistory.sameOrder(it, previous) }
            ?: ordersFor(expectedOwner).firstOrNull { OrderHistory.sameOrder(it, previous) }
        if (stored != null) {
            if (stored != previous) return@synchronized false
            saveOrder(updated)
        } else {
            // An armed cycle can outlive the 20-record history without re-inserting an old order.
            val reminders = readLaundryReminders(expectedOwner)
            val record = reminders.firstOrNull { OrderHistory.sameOrder(it.order, previous) }
                ?: return@synchronized false
            if (record.order != previous) return@synchronized false
            put("laundry_reminders_$expectedOwner", Gson().toJson(reminders.map {
                if (it.key == record.key) it.copy(order = updated) else it
            }))
        }
        true
    }

    /** Serialize foreground/worker delivery with login/logout, claiming before the external post. */
    internal fun deliverLaundryReminder(expectedOwner: String, reminderKey: String, nowMs: Long,
        canPost: () -> Boolean, post: (LocalOrder) -> Unit
    ): Boolean = synchronized(orderLock) {
        if (expectedOwner.isBlank() || owner() != expectedOwner || token().isBlank()) return@synchronized false
        val records = readLaundryReminders(expectedOwner)
        val record = records.firstOrNull { it.key == reminderKey } ?: return@synchronized false
        val completedAt = record.completedAtMs ?: return@synchronized false
        if (record.notificationClaimed || completedAt > nowMs || nowMs - completedAt > 24 * 60 * 60 * 1000L)
            return@synchronized false
        if (!canPost()) return@synchronized false
        // canPost may call Android services; recheck the account immediately before persisting the claim.
        if (owner() != expectedOwner || token().isBlank()) return@synchronized false
        put("laundry_reminders_$expectedOwner", Gson().toJson(records.map {
            if (it.key == reminderKey) it.copy(notificationClaimed = true) else it
        }))
        post(record.order)
        true
    }

    companion object { private val orderLock = Any() }
}
