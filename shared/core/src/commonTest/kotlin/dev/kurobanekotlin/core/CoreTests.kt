package dev.kurobanekotlin.core

import dev.kurobanekotlin.core.chat.ChatStream
import dev.kurobanekotlin.core.config.AgentsParser
import dev.kurobanekotlin.core.config.isMaskedSecret
import dev.kurobanekotlin.core.gateway.GatewayUrl
import dev.kurobanekotlin.core.sessions.SessionsParser
import dev.kurobanekotlin.core.update.ReleaseVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayUrlTests {
    @Test fun addsHttpsWhenMissing() {
        assertEquals("https://zc.example.com", GatewayUrl.normalize("zc.example.com").getOrThrow())
    }
    @Test fun keepsHttpAndPort() {
        assertEquals("http://100.64.0.2:42617", GatewayUrl.normalize("http://100.64.0.2:42617/").getOrThrow())
    }
    @Test fun rejectsEmptyAndBadScheme() {
        assertFailsWith<IllegalArgumentException> { GatewayUrl.normalize("  ").getOrThrow() }
        assertFailsWith<IllegalArgumentException> { GatewayUrl.normalize("ftp://x").getOrThrow() }
    }
    @Test fun wsBaseMapsSchemes() {
        assertEquals("wss://zc.example.com", GatewayUrl.wsBase("https://zc.example.com"))
        assertEquals("ws://100.64.0.2:42617", GatewayUrl.wsBase("http://100.64.0.2:42617"))
    }
}

class SessionsParserTests {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesSessionEntries() {
        val obj = json.parseToJsonElement("""{"sessions":[
            {"session_id":"abc","session_key":"gw_abc","created_at":"2026-01-01T10:00:00Z","last_activity":"2026-01-02T11:30:00Z","message_count":7,"agent_alias":"default","name":"Morning chat"},
            {"session_id":"def","session_key":"gw_def","created_at":"2026-01-03T09:00:00Z","last_activity":"2026-01-03T09:05:00Z","message_count":2,"channel_id":"telegram.bot"}
        ]}""").let { kotlinx.serialization.json.jsonObject(it) }
        val sessions = SessionsParser.parse(obj)
        assertEquals(2, sessions.size)
        assertEquals("abc", sessions[0].sessionId)
        assertEquals("gw_abc", sessions[0].sessionKey)
        assertEquals(7, sessions[0].messageCount)
        assertEquals("Morning chat", sessions[0].displayName)
        assertEquals("def", sessions[1].displayName) // falls back to id when unnamed
    }

    @Test fun emptyWhenNoSessionsKey() {
        assertEquals(emptyList(), SessionsParser.parse(json.parseToJsonElement("{}").let { kotlinx.serialization.json.jsonObject(it) }))
    }
}

class AgentsParserTests {
    @Test fun readsAgentsFromConfigMap() {
        val config = Json.parseToJsonElement("""{"agents":{
            "default":{"enabled":true,"channels":["telegram.bot"],"model_provider":"zai.main"},
            "coder":{"enabled":false,"channels":[],"model_provider":""}
        }}""").let { kotlinx.serialization.json.jsonObject(it) }
        val agents = AgentsParser.parse(config)
        assertEquals(2, agents.size)
        assertEquals("coder", agents[0].alias)
        assertFalse(agents[0].enabled)
        assertEquals("default", agents[1].alias)
        assertEquals("zai.main", agents[1].modelProvider)
        assertEquals(listOf("telegram.bot"), agents[1].channels)
    }

    @Test fun maskedSecretsDetected() {
        assertTrue(isMaskedSecret("****"))
        assertTrue(isMaskedSecret("***masked***"))
        assertFalse(isMaskedSecret("sk-real-key"))
    }
}

class ChatStreamClassifyTests {
    private val conn = dev.kurobanekotlin.core.connection.GatewayConnection(baseUrl = "http://gw.local")
    private val stream = ChatStream(HttpClient(MockEngine { throw NotImplementedError() }), conn, "s")

    @Test fun classifiesEveryKnownFrame() {
        fun frame(type: String, vararg kv: Pair<String, String>) = buildJsonObject {
            put("type", type)
            kv.forEach { (k, v) -> put(k, v) }
        }
        assertIs<WsEvent.Chunk>(stream.classify(frame("chunk", "content" to "hello"))).let { assertEquals("hello", it.content) }
        assertIs<WsEvent.ToolCall>(stream.classify(frame("tool_call", "id" to "t1", "name" to "shell", "args" to "{}"))).let { assertEquals("shell", it.name) }
        assertIs<WsEvent.ApprovalRequest>(stream.classify(frame("approval_request", "request_id" to "r1", "tool" to "shell", "arguments_summary" to "rm", "timeout_secs" to "30"))).let {
            assertEquals("r1", it.requestId); assertEquals(30, it.timeoutSecs)
        }
        assertIs<WsEvent.Error>(stream.classify(frame("error", "message" to "boom", "code" to "X"))).let { assertEquals("boom", it.message) }
        assertIs<WsEvent.Done>(stream.classify(frame("done", "full_response" to "all", "model" to "glm-5", "tokens_used" to "42"))).let {
            assertEquals("all", it.fullResponse); assertEquals(42L, it.tokensUsed)
        }
        assertIs<WsEvent.Aborted>(stream.classify(frame("aborted")))
        assertIs<WsEvent.Other>(stream.classify(frame("cron_result", "x" to "1")))
    }
}

class UpdateVersionTests {
    @Test fun strictTagsOnly() {
        assertTrue(ReleaseVersion.tryParseTag("v1.2.3"))
        assertFalse(ReleaseVersion.tryParseTag("1.2.3"))
        assertFalse(ReleaseVersion.tryParseTag("v1.2.3-rc1"))
        assertFalse(ReleaseVersion.tryParseTag("v01.2.3"))
        assertNull(ReleaseVersion.tryParseTag(null))
    }
    @Test fun numericCompareNotLexical() {
        assertTrue(ReleaseVersion.parseTag("v0.10.0") > ReleaseVersion.parseTag("v0.9.0"))
        assertEquals("v0.2.0", ReleaseVersion(0, 2, 0).tag)
    }
    @Test fun currentParsesInformationalVersion() {
        assertEquals(ReleaseVersion(0, 5, 0), ReleaseVersion.parseCurrent("0.5.0+abc123"))
        assertEquals(ReleaseVersion(1, 2, 3), ReleaseVersion.parseCurrent("1.2.3.0"))
        assertNull(ReleaseVersion.parseCurrent("junk"))
    }
}

class UpdateCheckerTests {
    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK) = HttpClient(MockEngine { req ->
        respond(ByteReadChannel(body), status, headersOf("Content-Type", "application/json"))
    }) { install(ContentNegotiation) { json() } }

    private val repo = "BookahCoding/Kurobane-for-android"

    @Test fun newerReleaseIsAvailableWithExactApkUrl() = runTest {
        val body = """{"tag_name":"v0.6.0","draft":false,"prerelease":false,"body":"notes",
            "assets":[{"name":"kurobane-android.apk","browser_download_url":"https://github.com/$repo/releases/download/v0.6.0/kurobane-android.apk","size":12345}]}"""
        val r = UpdateChecker(client(body), repo, ReleaseVersion(0, 5, 0)).check()
        val avail = assertIs<UpdateAvailable>(r)
        assertEquals(ReleaseVersion(0, 6, 0), avail.release.version)
        assertEquals("https://github.com/$repo/releases/download/v0.6.0/kurobane-android.apk", avail.release.apkUrl)
    }

    @Test fun sameOrOlderIsUpToDate() = runTest {
        val body = """{"tag_name":"v0.5.0","draft":false,"prerelease":false,"assets":[]}"""
        assertIs<UpdateUpToDate>(UpdateChecker(client(body), repo, ReleaseVersion(0, 5, 0)).check())
    }

    @Test fun prereleaseAndWrongApkUrlRejected() = runTest {
        val pre = """{"tag_name":"v0.6.0-rc1","draft":false,"prerelease":true,"assets":[]}"""
        assertIs<UpdateCheckFailed>(UpdateChecker(client(pre), repo, ReleaseVersion(0, 5, 0)).check())
        val evil = """{"tag_name":"v0.6.0","draft":false,"prerelease":false,
            "assets":[{"name":"kurobane-android.apk","browser_download_url":"https://evil.example/kurobane-android.apk","size":1}]}"""
        assertIs<UpdateCheckFailed>(UpdateChecker(client(evil), repo, ReleaseVersion(0, 5, 0)).check())
    }

    @Test fun offlineMapsToOffline() = runTest {
        val failing = HttpClient(MockEngine { throw java.io.IOException("down") })
        val r = UpdateChecker(failing, repo, ReleaseVersion(0, 5, 0)).check()
        assertIs<UpdateCheckFailed>(r)
        assertEquals(dev.kurobanekotlin.core.update.UpdateFailure.Offline, r.kind)
    }
}
