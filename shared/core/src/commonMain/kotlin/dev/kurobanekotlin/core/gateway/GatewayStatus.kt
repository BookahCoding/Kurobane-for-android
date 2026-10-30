package dev.kurobanekotlin.core.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Display model for GET /api/status (see the gateway's `api::handle_api_status`). */
data class GatewayStatus(
    val version: String,
    val modelProvider: String?,
    val model: String,
    val temperature: Double?,
    val uptimeSeconds: Long,
    val gatewayPort: Int,
    val locale: String,
    val memoryBackend: String,
    val paired: Boolean,
    val agentAlias: String?,
    val channels: List<String>,
)

/** Parses GET /api/status into [GatewayStatus]. Pure and unit-tested. */
object StatusParser {
    fun parse(obj: JsonObject): GatewayStatus {
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        fun n(k: String) = (obj[k] as? JsonPrimitive)?.content
        val channels = (obj["channels"] as? JsonObject)?.keys?.toList().orEmpty()
        return GatewayStatus(
            version = s("version") ?: "",
            modelProvider = s("model_provider"),
            model = s("model") ?: "",
            temperature = n("temperature")?.toDoubleOrNull(),
            uptimeSeconds = n("uptime_seconds")?.toLongOrNull() ?: 0,
            gatewayPort = n("gateway_port")?.toIntOrNull() ?: 0,
            locale = s("locale") ?: "",
            memoryBackend = s("memory_backend") ?: "",
            paired = (obj["paired"] as? JsonPrimitive)?.content == "true",
            agentAlias = s("agent_alias"),
            channels = channels,
        )
    }

    /** 0 → "0s"; 90 → "1m 30s"; 3700 → "1h 1m"; 90000 → "1d 1h". Pure and unit-tested. */
    fun formatUptime(totalSeconds: Long): String {
        if (totalSeconds < 0) return "0s"
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
