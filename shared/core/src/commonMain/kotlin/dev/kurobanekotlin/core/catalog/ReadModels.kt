package dev.kurobanekotlin.core.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class CronJobInfo(
    val id: String,
    val name: String,
    val schedule: String,
    val kind: String,
    val enabled: Boolean,
    val nextRun: String,
    val lastStatus: String,
)

data class MemoryEntryInfo(val key: String, val content: String, val category: String)

data class ModelUsage(val model: String, val tokens: Long, val unpricedTokens: Long, val costUsd: Double)

data class CostSummaryInfo(
    val sessionUsd: Double,
    val dailyUsd: Double,
    val monthlyUsd: Double,
    val totalTokens: Long,
    val unpricedTokens: Long,
    val requestCount: Long,
    val byModel: List<ModelUsage>,
) {
    /** Tokens are shown as tokens. Money is omitted when the gateway left the usage unpriced. */
    fun explain(): String {
        val unpriced = unpricedTokens > 0 || (totalTokens > 0 && sessionUsd == 0.0 && dailyUsd == 0.0 && monthlyUsd == 0.0 && byModel.all { it.costUsd == 0.0 })
        val tokens = "$totalTokens tokens" + if (unpricedTokens > 0) " ($unpricedTokens unpriced)" else ""
        if (unpriced) return "$tokens. The gateway did not price them."
        if (totalTokens == 0L && sessionUsd == 0.0 && dailyUsd == 0.0 && monthlyUsd == 0.0) return "No usage recorded yet."
        return "$tokens · session ${money(sessionUsd)} · today ${money(dailyUsd)} · month ${money(monthlyUsd)}"
    }

    private fun money(usd: Double): String {
        val tenThousandths = kotlin.math.round(usd * 10_000).toLong()
        return "$${"${tenThousandths / 10_000}.${(kotlin.math.abs(tenThousandths) % 10_000).toString().padStart(4, '0')}"}"
    }
}

data class PluginInfo(val name: String, val version: String, val description: String)

data class SkillInfo(val name: String, val description: String, val origin: String)

data class SkillBundleInfo(val alias: String)

data class PersonalityFileInfo(val filename: String, val exists: Boolean, val mtimeMs: Long? = null)

data class DeviceInfo(val name: String, val type: String)

data class UploadPayload(val path: String, val marker: String)

/** Read-only gateway payloads. Parsers accept the shapes the 0.8.7 dashboard returns. */
object ReadModels {
    fun cron(obj: JsonObject): List<CronJobInfo> {
        val arr = array(obj, "jobs") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = str(o, "id") ?: return@mapNotNull null
            CronJobInfo(
                id = id,
                name = str(o, "name")?.takeIf { it.isNotBlank() } ?: str(o, "prompt")?.take(48) ?: str(o, "command")?.take(48) ?: id,
                schedule = scheduleText(o),
                kind = str(o, "job_type") ?: "",
                enabled = (o["enabled"] as? JsonPrimitive)?.content != "false",
                nextRun = str(o, "next_run") ?: "",
                lastStatus = str(o, "last_status") ?: "",
            )
        }
    }

    fun memory(obj: JsonObject): List<MemoryEntryInfo> {
        val arr = array(obj, "entries") ?: array(obj, "memories") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = str(o, "key") ?: str(o, "id") ?: return@mapNotNull null
            MemoryEntryInfo(key, str(o, "content") ?: "", categoryText(o["category"]))
        }
    }

    fun cost(obj: JsonObject): CostSummaryInfo {
        val c = (obj["cost"] as? JsonObject) ?: obj
        val models = modelList(c["by_model"])
        val unpriced = num(c, "unpriced_tokens") ?: models.sumOf { it.unpricedTokens }
        val tokens = num(c, "total_tokens") ?: models.sumOf { it.tokens }
        return CostSummaryInfo(
            sessionUsd = dbl(c, "session_cost_usd") ?: 0.0,
            dailyUsd = dbl(c, "daily_cost_usd") ?: 0.0,
            monthlyUsd = dbl(c, "monthly_cost_usd") ?: 0.0,
            totalTokens = tokens,
            unpricedTokens = unpriced,
            requestCount = num(c, "request_count") ?: 0L,
            byModel = models,
        )
    }

    fun plugins(obj: JsonObject): List<PluginInfo> {
        val arr = array(obj, "plugins") ?: array(obj, "installed") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val nested = (o["installed"] as? JsonObject) ?: (o["available"] as? JsonObject)
            val name = str(o, "name") ?: str(o, "id") ?: nested?.let { str(it, "name") ?: str(it, "id") } ?: return@mapNotNull null
            PluginInfo(
                name,
                str(o, "version") ?: nested?.let { str(it, "version") }.orEmpty(),
                str(o, "description") ?: nested?.let { str(it, "description") }.orEmpty(),
            )
        }
    }

    fun bundles(obj: JsonObject): List<SkillBundleInfo> {
        val arr = array(obj, "bundles") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            str(o, "alias")?.let { SkillBundleInfo(it) }
        }
    }

    fun skills(obj: JsonObject): List<SkillInfo> {
        val arr = array(obj, "skills") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = str(o, "name") ?: return@mapNotNull null
            SkillInfo(name, str(o, "description").orEmpty(), str(o, "origin").orEmpty())
        }
    }

    fun personality(obj: JsonObject): List<PersonalityFileInfo> {
        val arr = array(obj, "files") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = str(o, "filename") ?: str(o, "name") ?: return@mapNotNull null
            PersonalityFileInfo(
                name,
                (o["exists"] as? JsonPrimitive)?.content != "false",
                num(o, "mtime_ms"),
            )
        }
    }

    fun devices(obj: JsonObject): List<DeviceInfo> {
        val arr = array(obj, "devices") ?: array(obj, "paired") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = str(o, "device_name") ?: str(o, "name") ?: return@mapNotNull null
            DeviceInfo(name, str(o, "device_type") ?: str(o, "type") ?: "")
        }
    }

    fun runningIds(obj: JsonObject): Set<String> {
        val arr = array(obj, "running") ?: array(obj, "sessions") ?: array(obj, "ids") ?: return emptySet()
        return arr.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull
                is JsonObject -> str(el, "session_id") ?: str(el, "session_key") ?: str(el, "id")
                else -> null
            }
        }.toSet()
    }

    fun healthLine(obj: JsonObject): String {
        val status = str(obj, "status") ?: str(obj, "health")
        val ok = (obj["ok"] as? JsonPrimitive)?.content == "true" || (obj["healthy"] as? JsonPrimitive)?.content == "true" ||
            status.equals("ok", true) || status.equals("healthy", true)
        val detail = str(obj, "message") ?: str(obj, "detail")
        return when {
            ok && detail.isNullOrBlank() -> "Health check: ok"
            ok -> "Health check: ok — $detail"
            !status.isNullOrBlank() -> "Health check: $status"
            else -> "Health check returned no status."
        }
    }

    /** Text of a GET /api/config/prop body. Arrays are joined. Secrets without a value return null. */
    fun propText(obj: JsonObject): String? {
        if (obj["populated"] != null && obj["value"] == null) return null
        return elementText(obj["value"])?.takeIf { it.isNotBlank() && it != "null" }
    }

    fun upload(obj: JsonObject): UploadPayload? {
        val path = str(obj, "path") ?: return null
        val marker = str(obj, "marker") ?: return null
        if (path.isBlank() || marker.isBlank()) return null
        return UploadPayload(path, marker)
    }

    private fun scheduleText(o: JsonObject): String {
        val schedule = o["schedule"]
        return when (schedule) {
            is JsonPrimitive -> schedule.contentOrNull.orEmpty()
            is JsonObject -> str(schedule, "expr") ?: str(schedule, "expression") ?: str(schedule, "cron") ?: schedule.toString()
            else -> str(o, "expression").orEmpty()
        }
    }

    private fun array(obj: JsonObject, key: String) = obj[key] as? JsonArray

    private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull

    private fun num(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun dbl(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun categoryText(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonObject -> el.keys.firstOrNull().orEmpty()
        else -> ""
    }

    private fun elementText(el: JsonElement?): String? = when (el) {
        null -> null
        is JsonPrimitive -> el.contentOrNull
        is JsonArray -> el.mapNotNull { elementText(it) }.filter { it.isNotBlank() }.joinToString(", ")
        else -> el.toString()
    }

    private fun modelList(el: JsonElement?): List<ModelUsage> = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.let(::oneModel) }
        is JsonObject -> el.entries.mapNotNull { (key, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            oneModel(o, key)
        }
        else -> emptyList()
    }

    private fun oneModel(o: JsonObject, key: String? = null): ModelUsage = ModelUsage(
        model = str(o, "model") ?: key.orEmpty(),
        tokens = num(o, "total_tokens") ?: ((num(o, "input_tokens") ?: 0) + (num(o, "output_tokens") ?: 0)),
        unpricedTokens = num(o, "unpriced_tokens") ?: 0,
        costUsd = dbl(o, "cost_usd") ?: 0.0,
    )
}
