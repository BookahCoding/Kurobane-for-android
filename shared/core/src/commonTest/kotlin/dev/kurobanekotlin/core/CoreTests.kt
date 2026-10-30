package dev.kurobanekotlin.core

import dev.kurobanekotlin.core.chat.ChatStream
import dev.kurobanekotlin.core.chat.ChatUiState
import dev.kurobanekotlin.core.chat.WsEvent
import dev.kurobanekotlin.core.chat.endsTurn
import dev.kurobanekotlin.core.chat.reduce
import dev.kurobanekotlin.core.config.AgentInfo
import dev.kurobanekotlin.core.connection.PairResult
import dev.kurobanekotlin.core.connection.parsePairResponse
import dev.kurobanekotlin.core.update.UpdateAvailable
import dev.kurobanekotlin.core.update.UpdateChecker
import dev.kurobanekotlin.core.update.UpdateCheckFailed
import dev.kurobanekotlin.core.update.UpdateUpToDate
import kotlinx.serialization.json.jsonObject
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
    @Test fun bareTailscaleIpDefaultsToHttp() {
        assertEquals("http://100.64.0.2:42617", GatewayUrl.normalize("100.64.0.2:42617").getOrThrow())
        assertEquals("http://192.168.1.5:42617", GatewayUrl.normalize("192.168.1.5:42617").getOrThrow())
    }
    @Test fun cleartextOnlyForPrivateRanges() {
        for (ok in listOf("100.64.0.1", "100.127.255.254", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.9", "127.0.0.1",
            "localhost", "box.tail1234.ts.net", "[fd7a:115c:a1e0::1]", "[::1]"))
            assertTrue(GatewayUrl.isCleartextAllowed(ok), "$ok should allow http")
        for (bad in listOf("100.63.255.255", "100.128.0.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "example.com",
            "evil.ts.net.example.com", "[2001:db8::1]", "1.2.3", "10.0.0.256"))
            assertFalse(GatewayUrl.isCleartextAllowed(bad), "$bad must need https")
    }
    @Test fun explicitHttpToPublicHostIsRejected() {
        assertFailsWith<IllegalArgumentException> { GatewayUrl.normalize("http://example.com:42617").getOrThrow() }
        assertEquals("https://example.com", GatewayUrl.normalize("https://example.com/").getOrThrow())
        assertEquals("http://box.tail1.ts.net:42617", GatewayUrl.normalize("http://box.tail1.ts.net:42617").getOrThrow())
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
        ]}""").jsonObject
        val sessions = SessionsParser.parse(obj)
        assertEquals(2, sessions.size)
        assertEquals("abc", sessions[0].sessionId)
        assertEquals("gw_abc", sessions[0].sessionKey)
        assertEquals(7, sessions[0].messageCount)
        assertEquals("Morning chat", sessions[0].displayName)
        assertEquals("def", sessions[1].displayName) // falls back to id when unnamed
    }

    @Test fun emptyWhenNoSessionsKey() {
        assertEquals(emptyList(), SessionsParser.parse(json.parseToJsonElement("{}").jsonObject))
    }
}

class AgentsParserTests {
    @Test fun readsAgentsFromConfigMap() {
        val config = Json.parseToJsonElement("""{"agents":{
            "default":{"enabled":true,"channels":["telegram.bot"],"model_provider":"zai.main"},
            "coder":{"enabled":false,"channels":[],"model_provider":""}
        }}""").jsonObject
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
        assertFalse(ReleaseVersion.tryParseTag(null))
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

    @Test fun digestIsParsedAndDraftsRejected() = runTest {
        val hex = "a".repeat(64)
        val body = """{"tag_name":"v0.6.0","draft":false,"prerelease":false,
            "assets":[{"name":"kurobane-android.apk","browser_download_url":"https://github.com/$repo/releases/download/v0.6.0/kurobane-android.apk","size":9,"digest":"sha256:$hex"}]}"""
        assertEquals(hex, assertIs<UpdateAvailable>(UpdateChecker(client(body), repo, ReleaseVersion(0, 5, 0)).check()).release.sha256)
        val draft = """{"tag_name":"v0.7.0","draft":true,"prerelease":false,"assets":[]}"""
        assertIs<UpdateCheckFailed>(UpdateChecker(client(draft), repo, ReleaseVersion(0, 5, 0)).check())
    }

    @Test fun missingReleaseIsReportedAsNoRelease() = runTest {
        val r = UpdateChecker(client("{}", HttpStatusCode.NotFound), repo, ReleaseVersion(0, 5, 0)).check()
        assertEquals(dev.kurobanekotlin.core.update.UpdateFailure.NoRelease, assertIs<UpdateCheckFailed>(r).kind)
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

class PairResponseTests {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun successReturnsToken() {
        val r = parsePairResponse(200, obj("""{"paired":true,"persisted":true,"token":"zc_abc","message":"x"}"""))
        assertEquals("zc_abc", assertIs<PairResult.Ok>(r).token)
    }
    @Test fun wrongCodeAndRateLimitSurfaceGatewayMessage() {
        assertEquals("Invalid pairing code", assertIs<PairResult.Error>(parsePairResponse(403, obj("""{"error":"Invalid pairing code"}"""))).message)
        assertEquals("Too many auth attempts. Try again in 30s.",
            assertIs<PairResult.Error>(parsePairResponse(429, obj("""{"error":"Too many auth attempts. Try again in 30s."}"""))).message)
        assertIs<PairResult.Error>(parsePairResponse(500, obj("""{"paired":false,"error":"Device registry error"}""")))
    }
    @Test fun okStatusWithoutTokenIsAnError() {
        assertIs<PairResult.Error>(parsePairResponse(200, obj("""{"paired":true}""")))
        assertIs<PairResult.Error>(parsePairResponse(200, null))
    }
}

class ChatReducerTests {
    @Test fun turnFoldsChunksIntoOneAssistantMessage() {
        var st = ChatUiState(running = true)
        st = st.reduce(WsEvent.Chunk("Hel")).reduce(WsEvent.Chunk("lo"))
        assertEquals("Hello", st.streaming)
        st = st.reduce(WsEvent.Done("Hello", "glm", "p", 0.0123, 42))
        assertEquals(listOf(dev.kurobanekotlin.core.chat.ChatMessage("assistant", "Hello")), st.messages)
        assertFalse(st.running); assertEquals("", st.streaming)
        assertEquals("Done · glm · 42 tokens · \$0.0123", st.status)
    }
    @Test fun doneWithoutChunksUsesFullResponse() {
        val st = ChatUiState(running = true).reduce(WsEvent.Done("whole", null, null, null, null))
        assertEquals("whole", st.messages.single().content)
    }
    @Test fun errorAndAbortEndTheTurn() {
        val e = ChatUiState(running = true, streaming = "x").reduce(WsEvent.Error("boom", "C"))
        assertFalse(e.running); assertEquals("boom", e.error); assertEquals("", e.streaming)
        assertFalse(ChatUiState(running = true).reduce(WsEvent.Aborted).running)
        assertTrue(WsEvent.Aborted.endsTurn())
    }
    @Test fun approvalRequestIsHeldUntilAnswered() {
        val req = WsEvent.ApprovalRequest("r1", "shell", "ls", 30)
        assertEquals(req, ChatUiState().reduce(req).pendingApproval)
    }
}

class AliasResolutionTests {
    private val agents = listOf(
        AgentInfo("coder", false, "", emptyList()),
        AgentInfo("main", true, "", emptyList()),
    )
    @Test fun keepsEnabledRequestedAlias() = assertEquals("main", AgentsParser.resolveAlias(agents, "main"))
    @Test fun fallsBackToFirstEnabledWhenDefaultMissing() = assertEquals("main", AgentsParser.resolveAlias(agents, "default"))
    @Test fun disabledRequestedFallsBack() = assertEquals("main", AgentsParser.resolveAlias(agents, "coder"))
    @Test fun nullWhenNoAgents() = assertNull(AgentsParser.resolveAlias(emptyList(), "default"))
}
