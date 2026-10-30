package dev.kurobanekotlin.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One model the picker can offer. [providerRef] is a `type.alias` slot, or null for a typed id. */
data class ModelChoice(val model: String, val providerRef: String?, val label: String)

/** One PUT /api/config/prop. [value] is the plain string the gateway should store. */
data class ConfigWrite(val path: String, val value: String)

/**
 * The writes a confirmed model pick performs. [changesProvider] is true only when
 * `agents.<alias>.model_provider` is among them.
 */
data class ModelPlan(val writes: List<ConfigWrite>, val changesProvider: Boolean, val refusal: String? = null)

/**
 * Models named in GET /api/config, and the config paths a pick writes.
 * The agent model is the field the config screen already edits. When the current provider slot
 * also stores `model`, that field is updated to the same id, because status resolves the slot.
 * A different provider is a separate write and must be confirmed as such.
 */
object ModelChoices {
    fun fromConfig(config: JsonObject, agentAlias: String): List<ModelChoice> {
        val agent = ((config["agents"] as? JsonObject)?.get(agentAlias) as? JsonObject)
        val current = text(agent, "model_provider").ifBlank { null }
        val out = LinkedHashMap<String, ModelChoice>()
        fun add(model: String, provider: String?, label: String) {
            val id = model.trim()
            if (id.isBlank() || isMaskedSecret(id)) return
            val key = "${provider.orEmpty()}|$id"
            if (key !in out) out[key] = ModelChoice(id, provider, label)
        }
        add(text(agent, "model"), current, "This agent")
        strings(agent?.get("fallback_models")).forEach { add(it, current, "Fallback") }
        val models = ((config["providers"] as? JsonObject)?.get("models") as? JsonObject)
        models?.forEach { (type, aliases) ->
            val map = aliases as? JsonObject ?: return@forEach
            map.forEach { (alias, bodyEl) ->
                val body = bodyEl as? JsonObject ?: return@forEach
                val ref = "$type.$alias"
                val label = if (ref == current) "This provider" else ref
                add(text(body, "model"), ref, label)
                strings(body["fallback_models"]).forEach { add(it, ref, label) }
            }
        }
        (config["model_routes"] as? JsonArray)?.forEach { el ->
            val row = el as? JsonObject ?: return@forEach
            val provider = text(row, "provider").ifBlank { null }
            add(text(row, "model"), provider, "Route")
        }
        return out.values.toList()
    }

    fun plan(config: JsonObject, agentAlias: String, currentProvider: String?, choice: ModelChoice): ModelPlan {
        if (agentAlias.isBlank() || agentAlias.contains('.')) {
            return ModelPlan(emptyList(), false, "This agent alias cannot be written as a config path.")
        }
        val model = choice.model.trim()
        if (model.isBlank() || isMaskedSecret(model)) {
            return ModelPlan(emptyList(), false, "That model id is empty or looks like a masked secret.")
        }
        val writes = mutableListOf<ConfigWrite>()
        val nextProvider = choice.providerRef?.trim()?.takeIf { it.isNotEmpty() }
        val switching = nextProvider != null && nextProvider != currentProvider?.trim()?.takeIf { it.isNotEmpty() }
        if (switching) writes += ConfigWrite("agents.$agentAlias.model_provider", nextProvider)
        writes += ConfigWrite("agents.$agentAlias.model", model)
        if (!switching) {
            val slot = currentProvider?.trim()?.takeIf { it.isNotEmpty() } ?: nextProvider
            slotModelPath(config, slot)?.let { writes += ConfigWrite(it, model) }
        }
        return ModelPlan(writes, switching)
    }

    /** `providers.models.<type>.<alias>.model` when that object already has a model string. */
    private fun slotModelPath(config: JsonObject, providerRef: String?): String? {
        val ref = providerRef?.trim().orEmpty()
        val dot = ref.indexOf('.')
        if (dot <= 0 || dot == ref.lastIndex) return null
        val type = ref.substring(0, dot)
        val alias = ref.substring(dot + 1)
        if (type.contains('.') || alias.contains('.')) return null
        val body = ((((config["providers"] as? JsonObject)?.get("models") as? JsonObject)
            ?.get(type) as? JsonObject)?.get(alias) as? JsonObject) ?: return null
        val model = body["model"] as? JsonPrimitive ?: return null
        if (model.contentOrNull == null) return null
        return "providers.models.$type.$alias.model"
    }

    private fun text(obj: JsonObject?, key: String): String =
        (obj?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun strings(el: kotlinx.serialization.json.JsonElement?): List<String> = when (el) {
        is JsonPrimitive -> listOfNotNull(el.contentOrNull)
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }
}
