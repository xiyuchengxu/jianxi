package dev.rain.laundry.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.rain.laundry.core.*
import java.math.BigDecimal

/** Contracts verified against the local original APK, 1.144.0. No copied script credentials. */
class PointsApi(private val api: LaundryApi) : PointsGateway {
    override suspend fun balance(): String {
        val data = api.post("user/balance", emptyMap()).objectValue()
        return decimal(data.get("integral")) ?: throw PointsDataInvalid()
    }

    override suspend fun signedInToday(): Boolean =
        api.post("signin/todaySignIn", emptyMap()).objectValue().boolean("isSignIn")

    override suspend fun signInActivity(): String =
        api.post("signin/signInAcList", emptyMap()).objectValue().string("id")
            ?.takeIf { it.isNotBlank() && it.length <= 200 } ?: throw PointsDataInvalid()

    override suspend fun signIn(activityId: String) {
        require(activityId.isNotBlank() && activityId.length <= 200)
        val result = api.post("signin/doUserSignIn", mapOf("activityId" to activityId)).objectValue()
        if (!result.boolean("signInResult")) throw PointsResultUnknown()
    }

    override suspend fun tasks(): List<PointsTask> {
        val data = api.post("task/list", emptyMap()).objectValue()
        val items = data.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw PointsDataInvalid()
        if (items.size() > 2000) throw PointsDataInvalid()
        return items.map { item ->
            val task = item.objectValue()
            val code = task.string("taskCode")?.takeIf { it.isNotBlank() } ?: throw PointsDataInvalid()
            PointsTask(id = code, name = task.string("title").orEmpty(),
                completed = task.integer("completedFreq")?.takeIf { it >= 0 },
                limit = task.integer("dailyTaskLimit")?.takeIf { it > 0 },
                done = task.integer("completedStatus") == 1,
                description = task.string("subTitle").orEmpty())
        }
    }

    private fun JsonElement.objectValue(): JsonObject =
        takeIf { it.isJsonObject }?.asJsonObject ?: throw PointsDataInvalid()

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    private fun JsonObject.boolean(name: String): Boolean = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
    }?.asBoolean ?: throw PointsDataInvalid()

    private fun JsonObject.integer(name: String): Int? {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        return value.toString().takeIf { it.matches(Regex("-?[0-9]+")) }?.toIntOrNull()
    }

    private fun decimal(value: JsonElement?): String? {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        val raw = value.asString
        if (raw.length > 64 || !raw.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) return null
        return runCatching { BigDecimal(raw).stripTrailingZeros().toPlainString() }.getOrNull()
    }
}
