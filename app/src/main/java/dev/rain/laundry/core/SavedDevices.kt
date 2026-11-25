package dev.rain.laundry.core

import com.google.gson.JsonParser

/** A device kind is deliberately small: the platform can change categories, but not the workflow family. */
enum class SavedDeviceKind(val wire: String) {
    LAUNDRY("laundry"),
    DRYER("dryer"),
    WATER("water");

    companion object {
        fun fromWire(value: String): SavedDeviceKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The only cached device fields. Modes, prices, remaining time, payment routes and authorization
 * state are intentionally absent: they must be fetched again after an explicit selection.
 */
data class SavedDevice(
    val owner: String = "",
    val kind: String = "",
    val deviceId: String = "",
    val codeKey: String = "",
    val codeValue: String = "",
    val name: String = "",
    val note: String = "",
    val lastUsedAt: Long = 0L
) {
    val savedKind: SavedDeviceKind? get() = SavedDeviceKind.fromWire(kind)

    fun code(): DeviceCode? = DeviceCode(codeKey, codeValue).takeIf { SavedDevices.validCode(it) }
}

object SavedDevices {
    const val LIMIT = 20
    const val MAX_NOTE_LENGTH = 40
    const val MAX_NAME_LENGTH = 80
    const val MAX_DEVICE_ID_LENGTH = 128
    const val MAX_JSON_LENGTH = 200_000

    private val codeKeys = setOf("IMEI", "NQT", "SN")
    private val codeValuePattern = Regex("[A-Za-z0-9_.-]{4,96}")

    fun kindOf(scanned: ScannedDevice): SavedDeviceKind = when (scanned) {
        is ScannedDevice.Laundry -> SavedDeviceKind.LAUNDRY
        is ScannedDevice.Dryer -> SavedDeviceKind.DRYER
        is ScannedDevice.Water -> SavedDeviceKind.WATER
    }
    fun permitsLaundryModeAvailabilityCheck(kind: SavedDeviceKind?): Boolean = kind == SavedDeviceKind.LAUNDRY

    /** Build a record only from the platform's freshly verified scan result and canonical parsed code. */
    fun fromScan(owner: String, code: DeviceCode, scanned: ScannedDevice, now: Long): SavedDevice {
        val kind = kindOf(scanned)
        val id = when (scanned) {
            is ScannedDevice.Laundry -> scanned.device.id
            is ScannedDevice.Dryer -> scanned.device.id
            is ScannedDevice.Water -> scanned.device.id
        }
        val name = when (scanned) {
            is ScannedDevice.Laundry -> scanned.device.name
            is ScannedDevice.Dryer -> scanned.device.name
            is ScannedDevice.Water -> scanned.device.name
        }
        return SavedDevice(
            owner = owner,
            kind = kind.wire,
            deviceId = id,
            codeKey = code.key,
            codeValue = code.value,
            name = cleanText(name, MAX_NAME_LENGTH),
            note = "",
            lastUsedAt = now
        )
    }

    /** The platform must still return the same workflow family and stable device ID. */
    fun matches(saved: SavedDevice, scanned: ScannedDevice): Boolean {
        val kind = saved.savedKind ?: return false
        if (kind != kindOf(scanned)) return false
        return when (scanned) {
            is ScannedDevice.Laundry -> saved.deviceId == scanned.device.id
            is ScannedDevice.Dryer -> saved.deviceId == scanned.device.id
            is ScannedDevice.Water -> saved.deviceId == scanned.device.id
        }
    }

    /**
     * A response may be persisted only while the owner, token and foreground revision that started
     * the request are still current. This keeps an old scan from writing into a newer account.
     */
    fun acceptsStoredResponse(
        expectedOwner: String,
        expectedToken: String,
        currentOwner: String,
        currentToken: String,
        recordOwner: String,
        revisionCurrent: Boolean
    ): Boolean = expectedOwner.isNotBlank() && expectedToken.isNotBlank() &&
        expectedOwner == currentOwner && expectedToken == currentToken &&
        recordOwner == expectedOwner && revisionCurrent

    fun validCode(code: DeviceCode): Boolean = code.key in codeKeys && code.value.matches(codeValuePattern)

    fun isValid(owner: String, record: SavedDevice): Boolean {
        if (owner.isBlank() || record.owner != owner) return false
        record.savedKind ?: return false
        if (!validDeviceId(record.deviceId)) return false
        if (!validCode(DeviceCode(record.codeKey, record.codeValue))) return false
        return record.lastUsedAt > 0L
    }

    /**
     * Deduplicate by kind + stable device ID, keep the latest verified code/name, preserve a user
     * note when a fresh scan supplies none, and retain only the 20 most recent records.
     */
    fun merge(owner: String, records: List<SavedDevice>, candidate: SavedDevice? = null): List<SavedDevice> {
        if (owner.isBlank()) return emptyList()
        val all = ArrayList<SavedDevice>(records.size + 1)
        if (candidate != null) all += candidate
        all += records
        val byKey = LinkedHashMap<String, SavedDevice>()
        for (raw in all) {
            val record = raw.copy(
                name = cleanText(raw.name, MAX_NAME_LENGTH),
                note = normalizeNote(raw.note)
            )
            if (!isValid(owner, record)) continue
            val key = record.kind + ":" + record.deviceId
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = record
                continue
            }
            byKey[key] = when {
                record.lastUsedAt > existing.lastUsedAt -> record.withFallback(existing)
                record.lastUsedAt < existing.lastUsedAt -> existing.withFallback(record)
                else -> existing.withFallback(record)
            }
        }
        return byKey.values
            .sortedWith(compareByDescending<SavedDevice> { it.lastUsedAt }.thenByDescending { it.deviceId })
            .take(LIMIT)
    }

    fun sanitize(owner: String, records: List<SavedDevice>): List<SavedDevice> = merge(owner, records)

    /**
     * One business entry may only show its own workflow family. The total history stays shared and
     * capped at [LIMIT]; this view filter never rewrites or clears saved records.
     */
    fun ofKind(records: List<SavedDevice>, kind: SavedDeviceKind): List<SavedDevice> =
        records.filter { it.savedKind == kind }

    fun normalizeNote(note: String): String = cleanText(note, MAX_NOTE_LENGTH)

    /** Decode defensively so malformed or legacy JSON is skipped rather than partially trusted. */
    fun decode(raw: String): List<SavedDevice> {
        if (raw.length > MAX_JSON_LENGTH) return emptyList()
        val array = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val value = element.asJsonObject
            fun string(key: String): String? = value.get(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            fun number(key: String): Long? = runCatching {
                value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            }.getOrNull()
            val owner = string("owner") ?: return@mapNotNull null
            val kind = string("kind") ?: return@mapNotNull null
            val deviceId = string("deviceId") ?: return@mapNotNull null
            val codeKey = string("codeKey") ?: return@mapNotNull null
            val codeValue = string("codeValue") ?: return@mapNotNull null
            val lastUsedAt = number("lastUsedAt") ?: return@mapNotNull null
            SavedDevice(
                owner = owner,
                kind = kind,
                deviceId = deviceId,
                codeKey = codeKey,
                codeValue = codeValue,
                name = string("name").orEmpty(),
                note = string("note").orEmpty(),
                lastUsedAt = lastUsedAt
            )
        }
    }

    private fun SavedDevice.withFallback(other: SavedDevice) = copy(
        name = name.ifBlank { other.name },
        note = note.ifBlank { other.note }
    )

    private fun validDeviceId(value: String) = value.isNotBlank() &&
        value.length <= MAX_DEVICE_ID_LENGTH && value.none { it.isISOControl() || it.isWhitespace() }

    private fun cleanText(value: String, max: Int) = value
        .filterNot { it.isISOControl() }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(max)
}
