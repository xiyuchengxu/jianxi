package dev.rain.laundry.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
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
    private fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        if (!preferences.edit().putString(name, data).commit()) throw ClientError("无法保存本地会话，请检查存储空间")
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
    fun saveOrder(order: LocalOrder) { put("order_" + order.owner, Gson().toJson(order)) }
    fun order(): LocalOrder? = get("order_" + owner())?.let { raw ->
        runCatching { Gson().fromJson(raw, LocalOrder::class.java) }.getOrNull()?.takeIf { it.owner == owner() }
    }
    fun saveWater(session: WaterSession) { put("water_" + session.owner, Gson().toJson(session)) }
    fun water(): WaterSession? = get("water_" + owner())?.let { raw ->
        runCatching { Gson().fromJson(raw, WaterSession::class.java) }.getOrNull()?.takeIf { it.owner == owner() }
    }
}
