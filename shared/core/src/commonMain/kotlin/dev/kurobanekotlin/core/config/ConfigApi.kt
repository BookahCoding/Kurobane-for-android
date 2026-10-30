package dev.kurobanekotlin.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class AgentInfo(
    val alias: String,
    val enabled: Boolean,
    val modelProvider: String,
    val channels: List<String>,
    val model: String = "",
    val fallback: String = "",
)

/** Result of picking an agent alias. [substitutedKogarashi] is true only when [alias] replaced a missing name. */
data class AliasResolution(val alias: String?, val substitutedKogarashi: Boolean)

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
                model = (a["model"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                fallback = fallbackText(a["fallback_models"] ?: a["fallback_model"] ?: a["model_fallback"] ?: a["fallback"]),
            )
        }.sortedBy { it.alias.lowercase() }
    }

    /**
     * The gateway has no default agent. An exact enabled match wins. If [requested] is missing or
     * disabled and `kogarashi` is enabled, that alias is used. No other agent is substituted.
     * Null when nothing qualifies (the caller keeps what was typed and shows the gateway's error).
     */
    fun resolve(agents: List<AgentInfo>, requested: String): AliasResolution {
        agents.firstOrNull { it.alias == requested && it.enabled }?.let {
            return AliasResolution(it.alias, substitutedKogarashi = false)
        }
        val kogarashi = agents.firstOrNull { it.alias == "kogarashi" && it.enabled }?.alias
        return if (kogarashi != null) AliasResolution(kogarashi, substitutedKogarashi = true)
        else AliasResolution(null, substitutedKogarashi = false)
    }

    fun resolveAlias(agents: List<AgentInfo>, requested: String): String? = resolve(agents, requested).alias

    private fun fallbackText(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }.joinToString(", ")
        else -> ""
    }
}

/** True for the gateway's masked-secret placeholder; the client must never write it back. */
fun isMaskedSecret(value: String): Boolean = value.contains("****") || value.contains("***")
