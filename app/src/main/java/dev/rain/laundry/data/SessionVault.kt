package dev.rain.laundry.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.rain.laundry.core.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionVault(context: Context) {
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
    fun login(phone: String, token: String) {
        put("owner", WireProtocol.sha256(phone)); put("phone_label", phone.take(3) + " **** " + phone.takeLast(4)); put("token", token)
    }
    fun logout() { preferences.edit().remove("token").remove("owner").remove("phone_label").apply() }
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

    companion object { private val orderLock = Any() }
}
