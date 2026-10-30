package dev.kurobanekotlin.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class AgentInfo(val alias: String, val enabled: Boolean, val modelProvider: String, val channels: List<String>)

/** Reads the agent list from GET /api/config's `agents` map. Pure and unit-tested. */
object AgentsParser {
    fun parse(config: JsonObject): List<AgentInfo> {
        val agents = (config["agents"] as? JsonObject) ?: return emptyList()
        return agents.entries.map { (alias, v) ->
            val a = v as? JsonObject ?: JsonObject(emptyMap())
            AgentInfo(
                alias = alias,
                enabled = (a["enabled"] as? JsonPrimitive)?.content != "false",
                modelProvider = (a["model_provider"] as? JsonPrimitive)?.contentOrNull ?: "",
                channels = (a["channels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }.sortedBy { it.alias.lowercase() }
    }

    /**
     * The gateway has no default agent: `/ws/chat` is refused unless `agent_alias` names a configured
     * `[agents.<alias>]`. Keeps [requested] when it exists and is enabled, else the first enabled agent;
     * null when the list is empty (the caller then keeps what the user typed and shows the gateway's error).
     */
    fun resolveAlias(agents: List<AgentInfo>, requested: String): String? =
        agents.firstOrNull { it.alias == requested && it.enabled }?.alias
            ?: agents.firstOrNull { it.enabled }?.alias
}

/** True for the gateway's masked-secret placeholder; the client must never write it back. */
fun isMaskedSecret(value: String): Boolean = value.contains("****") || value.contains("***")
