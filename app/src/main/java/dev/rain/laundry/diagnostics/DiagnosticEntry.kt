package dev.rain.laundry.diagnostics

import java.time.Instant

/** Fixed labels only. Account, device and request values have no place in this schema. */
enum class DiagnosticOperation {
    APP_FOREGROUND, SMS, LOGIN, DEVICE_LOOKUP, DEVICE_STATUS, MODE_LIST, QUOTE,
    ORDER_CREATE, ORDER_QUERY, ORDER_SYNC, ORDER_CANCEL,
    PAYMENT_CHECK, PAYMENT_SDK_RETURN, PAYMENT_ROUTE, PAYMENT_PREPARE, PAYMENT_SUBMIT, PAYMENT_QUERY,
    WATER_ROUTE, WATER_ROUTE_SELECT, WATER_BILL_ROUTE, WATER_AUTH_POLICY,
    WATER_START, WATER_QUERY, WATER_STOP, WATER_COMMAND_QUERY, WATER_SETTLEMENT,
    AUTHORIZATION_QUERY, AUTHORIZATION_OPEN
}

enum class DiagnosticStatus {
    SUCCESS, REJECTED, NETWORK_ERROR, SESSION_EXPIRED, INVALID_RESPONSE, CANCELLED, HTTP_ERROR, FAILED
}

data class DiagnosticEntry(
    val timeMs: Long,
    val operation: DiagnosticOperation,
    val status: DiagnosticStatus,
    val durationMs: Long? = null,
    val errorCode: Int? = null
)

internal object DiagnosticCodec {
    const val MAX_ENTRIES = 200
    const val MAX_SERIALIZED_CHARS = 65_536
    const val MAX_DURATION_MS = 3_600_000L
    private const val MAX_TIME_MS = 253_402_300_799_999L

    fun entry(now: Long, operation: DiagnosticOperation, status: DiagnosticStatus,
        durationMs: Long?, errorCode: Int?) = DiagnosticEntry(
        now.coerceIn(0, MAX_TIME_MS), operation, status,
        durationMs?.takeIf { it >= 0 }?.coerceAtMost(MAX_DURATION_MS),
        errorCode?.takeIf { it in 0..999_999 })

    fun append(entries: List<DiagnosticEntry>, value: DiagnosticEntry): List<DiagnosticEntry> =
        (entries.takeLast(MAX_ENTRIES - 1) + value)

    fun encode(entries: List<DiagnosticEntry>): String = buildString {
        append("v1\n")
        entries.takeLast(MAX_ENTRIES).forEach { value ->
            append(value.timeMs).append('|').append(value.operation.name).append('|').append(value.status.name)
                .append('|').append(value.durationMs ?: "-").append('|').append(value.errorCode ?: "-").append('\n')
        }
    }

    fun decode(raw: String?): List<DiagnosticEntry> {
        if (raw == null || raw.length > MAX_SERIALIZED_CHARS || !raw.startsWith("v1\n")) return emptyList()
        return raw.lineSequence().drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
            val fields = line.split('|')
            if (fields.size != 5) return@mapNotNull null
            val time = fields[0].toLongOrNull()?.takeIf { it in 0..MAX_TIME_MS } ?: return@mapNotNull null
            val operation = DiagnosticOperation.entries.firstOrNull { it.name == fields[1] } ?: return@mapNotNull null
            val status = DiagnosticStatus.entries.firstOrNull { it.name == fields[2] } ?: return@mapNotNull null
            val duration = if (fields[3] == "-") null else {
                fields[3].toLongOrNull()?.takeIf { it in 0..MAX_DURATION_MS } ?: return@mapNotNull null
            }
            val code = if (fields[4] == "-") null else {
                fields[4].toIntOrNull()?.takeIf { it in 0..999_999 } ?: return@mapNotNull null
            }
            DiagnosticEntry(time, operation, status, duration, code)
        }.toList().takeLast(MAX_ENTRIES)
    }

    fun export(entries: List<DiagnosticEntry>): String = buildString {
        append("Jianxi local diagnostics v1\n")
        append("time_utc\toperation\tstatus\tduration_ms\terror_code\n")
        // Round-trip through the fixed schema so this export can never contain arbitrary text.
        decode(encode(entries)).forEach { value ->
            append(Instant.ofEpochMilli(value.timeMs)).append('\t').append(value.operation.name)
                .append('\t').append(value.status.name).append('\t').append(value.durationMs ?: "-")
                .append('\t').append(value.errorCode ?: "-").append('\n')
        }
    }
}
