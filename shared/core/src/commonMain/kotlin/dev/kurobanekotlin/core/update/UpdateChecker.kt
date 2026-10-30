package dev.kurobanekotlin.core.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class UpdateFailure { Offline, AuthRequired, RateLimited, NoRelease, Malformed, Http }

/** Strict MAJOR.MINOR.PATCH. Pre-release tags are never parsed, so never offered. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = "$major.$minor.$patch"
    val tag: String get() = "v$this"

    companion object {
        private val tagRe = Regex("^v(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})$")
        fun tryParseTag(tag: String?): Boolean = tag != null && tagRe.matches(tag)
        fun parseTag(tag: String): ReleaseVersion {
            val m = tagRe.matchEntire(tag) ?: error("not a strict tag: $tag")
            return ReleaseVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        fun parseCurrent(text: String): ReleaseVersion? {
            val core = text.substringBefore('+').substringBefore('-')
            val m = Regex("^([0-9]{1,6})\\.([0-9]{1,6})\\.([0-9]{1,6})(\\.[0-9]{1,6})?$").matchEntire(core) ?: return null
            return ReleaseVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }
}

data class UpdateRelease(val version: ReleaseVersion, val tag: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

sealed interface UpdateCheckResult
data class UpdateUpToDate(val latest: ReleaseVersion) : UpdateCheckResult
data class UpdateAvailable(val release: UpdateRelease) : UpdateCheckResult
data class UpdateCheckFailed(val kind: UpdateFailure, val message: String) : UpdateCheckResult

/**
 * Read-only check against GET /repos/{repo}/releases/latest on GitHub (anonymous).
 * Only strict vMAJOR.MINOR.PATCH stable releases with the expected APK asset are offered; never a downgrade.
 */
class UpdateChecker(private val http: HttpClient, private val repo: String, private val current: ReleaseVersion) {
    suspend fun check(): UpdateCheckResult {
        val resp = try {
            http.get("https://api.github.com/repos/$repo/releases/latest") {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                header("User-Agent", "Kurobane-Android-Updater/${current}")
            }
        } catch (e: Exception) {
            return UpdateCheckFailed(UpdateFailure.Offline, "Could not reach GitHub. Check your connection.")
        }
        if (resp.status.value !in 200..299) {
            return when (resp.status.value) {
                404 -> UpdateCheckFailed(UpdateFailure.NoRelease, "No published release was found.")
                401, 403 -> UpdateCheckFailed(UpdateFailure.AuthRequired, "GitHub requires sign-in for this repository.")
                429 -> UpdateCheckFailed(UpdateFailure.RateLimited, "GitHub's anonymous rate limit is used up. Try again later.")
                else -> UpdateCheckFailed(UpdateFailure.Http, "GitHub answered HTTP ${resp.status.value}.")
            }
        }
        val obj = runCatching { Json.parseToJsonElement(resp.bodyAsText()).jsonObject }.getOrNull()
            ?: return UpdateCheckFailed(UpdateFailure.Malformed, "The release answer was not valid JSON.")
        if (obj["draft"]?.jsonPrimitive?.content == "true" || obj["prerelease"]?.jsonPrimitive?.content == "true")
            return UpdateCheckFailed(UpdateFailure.NoRelease, "The latest release is not a stable release.")
        val tag = obj["tag_name"]?.jsonPrimitive?.content
        if (!ReleaseVersion.tryParseTag(tag)) return UpdateCheckFailed(UpdateFailure.Malformed, "The release tag is not vMAJOR.MINOR.PATCH.")
        val version = ReleaseVersion.parseTag(tag!!)
        if (version <= current) return UpdateUpToDate(version)
        val assets = (obj["assets"] as? JsonArray) ?: JsonArray(emptyList())
        val apk = assets.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.content == "kurobane-android.apk" &&
                it.jsonObject["browser_download_url"]?.jsonPrimitive?.content ==
                    "https://github.com/$repo/releases/download/$tag/kurobane-android.apk"
        } ?: return UpdateCheckFailed(UpdateFailure.Malformed, "The release has no kurobane-android.apk at the expected address.")
        val url = apk.jsonObject["browser_download_url"]!!.jsonPrimitive.content
        val size = apk.jsonObject["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return UpdateAvailable(UpdateRelease(version, tag, url, size, "https://github.com/$repo/releases/tag/$tag"))
    }
}
