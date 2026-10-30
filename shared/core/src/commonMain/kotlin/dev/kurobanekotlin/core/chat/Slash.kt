package dev.kurobanekotlin.core.chat

/**
 * A `/name` line typed at the start of the composer. `see /help` and `/usr/local` stay ordinary text:
 * the slash has to be first, and the name cannot contain another slash.
 */
data class SlashCommand(val name: String, val arg: String) {
    companion object {
        private val COMMAND = Regex("""^/[^\s/]*(?:\s|$)""")

        fun parse(text: String): SlashCommand? {
            val trimmed = text.trim()
            if (!COMMAND.containsMatchIn(trimmed)) return null
            val body = trimmed.removePrefix("/")
            val name = body.takeWhile { !it.isWhitespace() }.lowercase()
            if (name.isEmpty()) return SlashCommand("", body.drop(name.length).trim())
            return SlashCommand(name, body.drop(name.length).trim())
        }
    }
}

data class SlashSuggestion(val text: String, val description: String, val group: String)

/** What the app does with a parsed command. Nothing here is sent to the model. */
sealed interface SlashRoute {
    data object Help : SlashRoute
    data object NewChat : SlashRoute
    data object Abort : SlashRoute
    data object OpenModels : SlashRoute
    data class SetModel(val model: String) : SlashRoute
    data object OpenSettings : SlashRoute
    data object OpenMenu : SlashRoute
    data object Status : SlashRoute
    data class Rename(val name: String) : SlashRoute
    data class OpenPage(val page: String) : SlashRoute
    data class SetAdvanced(val advanced: Boolean) : SlashRoute
    data class NeedsArgument(val hint: String) : SlashRoute
    data object Unknown : SlashRoute
}

/**
 * Commands the Android app handles itself. ZeroClaw's web chat does not treat a leading slash as a
 * command, so these never go out as a message unless the person chooses "Send as a message".
 */
object Slash {
    private data class Spec(
        val name: String,
        val group: String,
        val description: String,
        val argument: String? = null,
        val aliases: Set<String> = emptySet(),
    )

    private val specs = listOf(
        Spec("help", "Chat", "List these commands", aliases = setOf("?")),
        Spec("new", "Chat", "Start a new chat"),
        Spec("stop", "Chat", "Stop the reply", aliases = setOf("abort")),
        Spec("rename", "Chat", "Rename this chat", argument = "name"),
        Spec("model", "Model", "Choose this agent's model", argument = "model id"),
        Spec("status", "Model", "Show the model the gateway reports for this agent"),
        Spec("settings", "App", "Open settings"),
        Spec("menu", "App", "Open the sidebar"),
        Spec("simple", "App", "Hide the advanced pages"),
        Spec("advanced", "App", "Show jobs, config, and the other advanced pages"),
        Spec("jobs", "Gateway", "Scheduled jobs", aliases = setOf("cron")),
        Spec("agents", "Gateway", "Agents"),
        Spec("tools", "Gateway", "Tools"),
        Spec("skills", "Gateway", "Skills"),
        Spec("memory", "Gateway", "Memory"),
        Spec("usage", "Gateway", "Usage"),
        Spec("plugins", "Gateway", "Plugins"),
        Spec("personality", "Gateway", "Personality files"),
        Spec("config", "Gateway", "Gateway config"),
        Spec("devices", "Gateway", "Devices"),
        Spec("dashboard", "Gateway", "Dashboard"),
    )

    private val byName: Map<String, Spec> = buildMap {
        specs.forEach { spec ->
            put(spec.name, spec)
            spec.aliases.forEach { put(it, spec) }
        }
    }

    fun suggestions(draft: String): List<SlashSuggestion> {
        val command = SlashCommand.parse(draft) ?: return emptyList()
        val prefix = command.name
        return specs.filter { spec ->
            prefix.isEmpty() || spec.name.startsWith(prefix) || spec.aliases.any { it.startsWith(prefix) }
        }.map { SlashSuggestion("/${it.name}", it.description, it.group) }
    }

    fun route(command: SlashCommand): SlashRoute {
        if (command.name.isEmpty()) return SlashRoute.Help
        val spec = byName[command.name] ?: return SlashRoute.Unknown
        val arg = command.arg
        return when (spec.name) {
            "help" -> SlashRoute.Help
            "new" -> SlashRoute.NewChat
            "stop" -> SlashRoute.Abort
            "model" -> if (arg.isBlank()) SlashRoute.OpenModels else SlashRoute.SetModel(arg)
            "settings" -> SlashRoute.OpenSettings
            "menu" -> SlashRoute.OpenMenu
            "status" -> SlashRoute.Status
            "rename" -> if (arg.isBlank()) SlashRoute.NeedsArgument("Type /rename and the new name.") else SlashRoute.Rename(arg)
            "simple" -> SlashRoute.SetAdvanced(false)
            "advanced" -> SlashRoute.SetAdvanced(true)
            else -> SlashRoute.OpenPage(spec.name)
        }
    }

    fun helpText(): String = specs.joinToString("\n") { spec ->
        val usage = if (spec.argument == null) "/${spec.name}" else "/${spec.name} <${spec.argument}>"
        "$usage — ${spec.description}"
    }
}
