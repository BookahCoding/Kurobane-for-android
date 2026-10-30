package dev.kurobanekotlin.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One tool spec from GET /api/tools (optionally scoped with `?agent=`). */
data class GatewayTool(
    val name: String,
    val description: String,
)

/** Parses GET /api/tools' `tools` array. Pure and unit-tested. */
object ToolsParser {
    fun parse(obj: JsonObject): List<GatewayTool> {
        val arr = (obj["tools"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            GatewayTool(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }.sortedBy { it.name.lowercase() }
    }
}
