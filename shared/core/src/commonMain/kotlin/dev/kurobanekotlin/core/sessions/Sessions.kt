package dev.kurobanekotlin.core.sessions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
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

/**
* Short relative age for the session list ("2m ago"), or the date when older than a week. The gateway's
* `last_activity` is RFC3339 UTC, so the date is parsed by hand: multiplatform code has no java.time,
* and some backends emit offsets the standard parser refuses. Pure and unit-tested.
*/
fun formatLastActivity(rfc3339: String, nowEpochSeconds: Long = nowUtcSeconds()): String {
    if (rfc3339.isBlank()) return ""
    val epoch = parseRfc3339ToUtcSeconds(rfc3339) ?: return rfc3339
    val diff = (nowEpochSeconds - epoch).coerceAtLeast(0)
    val minutes = diff / 60
    val hours = diff / 3_600
    val days = diff / 86_400
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        days < 7 -> "$days d ago"
        else -> rfc3339.substringBefore('T')
    }
}

internal fun nowUtcSeconds(): Long = System.currentTimeMillis() / 1000

/** Days since 1970-01-01 for a (year, month, day) triple, using the civil-date algorithm. */
private fun epochDays(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/** Lenient RFC3339 → UTC epoch seconds; only the leading `YYYY-MM-DDTHH:MM:SS` part is used. */
internal fun parseRfc3339ToUtcSeconds(rfc3339: String): Long? {
    if (rfc3339.length < 19 || rfc3339.getOrNull(4) != '-' || rfc3339.getOrNull(7) != '-') return null
    val year = rfc3339.substring(0, 4).toIntOrNull() ?: return null
    val month = rfc3339.substring(5, 7).toIntOrNull() ?: return null
    val day = rfc3339.substring(8, 10).toIntOrNull() ?: return null
    val hour = rfc3339.substring(11, 13).toIntOrNull() ?: return null
    val minute = rfc3339.substring(14, 16).toIntOrNull() ?: return null
    val second = rfc3339.substring(17, 19).toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
    return epochDays(year, month, day) * 86_400 + hour * 3_600 + minute * 60 + second
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
