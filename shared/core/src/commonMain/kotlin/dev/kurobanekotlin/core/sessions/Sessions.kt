package dev.kurobanekotlin.core.sessions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GatewaySession(
    val sessionId: String,
    val sessionKey: String,
    val createdAt: String,
    val lastActivity: String,
    val messageCount: Int,
    val agentAlias: String?,
    val name: String?,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: sessionId
}

/** Parses GET /api/sessions into display models. Pure and unit-tested. */
object SessionsParser {
    fun parse(obj: JsonObject): List<GatewaySession> {
        val arr = (obj["sessions"] as? JsonArray) ?: return emptyList()
        return arr.map { it.jsonObject }.map { o ->
            GatewaySession(
                sessionId = o["session_id"]?.jsonPrimitive?.content ?: "",
                sessionKey = o["session_key"]?.jsonPrimitive?.content ?: o["session_id"]?.jsonPrimitive?.content ?: "",
                createdAt = o["created_at"]?.jsonPrimitive?.content ?: "",
                lastActivity = o["last_activity"]?.jsonPrimitive?.content ?: "",
                messageCount = o["message_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                agentAlias = o["agent_alias"]?.jsonPrimitive?.contentOrNull,
                name = o["name"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}
