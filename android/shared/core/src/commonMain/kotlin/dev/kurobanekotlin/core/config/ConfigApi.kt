package dev.kurobanekotlin.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentInfo(val alias: String, val enabled: Boolean, val modelProvider: String, val channels: List<String>)

/** Reads the agent list from GET /api/config's `agents` map. Pure and unit-tested. */
object AgentsParser {
    fun parse(config: JsonObject): List<AgentInfo> {
        val agents = (config["agents"] as? JsonObject) ?: return emptyList()
        return agents.entries.map { (alias, v) ->
            val a = v.jsonObject
            AgentInfo(
                alias = alias,
                enabled = a["enabled"]?.jsonPrimitive?.content != "false",
                modelProvider = a["model_provider"]?.jsonPrimitive?.content ?: "",
                channels = (a["channels"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
            )
        }.sortedBy { it.alias.lowercase() }
    }
}

/** True for the gateway's masked-secret placeholder; the client must never write it back. */
fun isMaskedSecret(value: String): Boolean = value.contains("****") || value.contains("***")
