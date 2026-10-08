package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostWrite
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실행 호스트 클라이언트의 임무 REST 와 신호 조작 전달(S3b 스펙 §7, T9·T10). 호스트는 JDK HttpServer 대역이다. */
class HostMissionsClientTest {

    private var server: HttpServer? = null

    /** 늦게 답하는 처리기가 서로를 막지 않게 요청마다 스레드를 쓴다. */
    private val pool = Executors.newCachedThreadPool()
    private val json = ObjectMapper()
    private val requestId = UUID.fromString("6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a")

    /** 받은 요청(방법, 인코딩된 경로, Content-Type, 본문). */
    private val seen = mutableListOf<Seen>()

    private data class Seen(val method: String, val rawPath: String, val contentType: String?, val body: String)

    /** 경로 앞부분마다 (상태 코드, 본문, 늦출 시간)으로 답한다. */
    private fun serve(vararg routes: Pair<String, Triple<Int, String, Duration>>, requestTimeout: Duration? = null, mockRunTimeout: Duration? = null): HostClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, answer) ->
            s.createContext(path) { exchange ->
                seen += Seen(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                Thread.sleep(answer.third)
                val bytes = answer.second.toByteArray()
                runCatching {
                    exchange.sendResponseHeaders(answer.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        s.executor = pool
        s.start()
        server = s
        val base = "http://127.0.0.1:${s.address.port}"
        return HostClient(
            base,
            requestTimeout = requestTimeout ?: HostClient.REQUEST_TIMEOUT,
            mockRunTimeout = mockRunTimeout ?: HostClient.MOCK_RUN_TIMEOUT,
        )
    }

    private fun ok(body: String, delay: Duration = Duration.ZERO) = Triple(200, body, delay)

    fun stop() {
        server?.stop(0)
    }

    @AfterTest
    fun close() {
        stop()
        pool.shutdownNow()
    }

    @Test
    fun `임무 쓰기 넷은 요청 id 와 사용자와 기체 목록을 실어 보내고 상태 코드와 본문을 그대로 돌려준다`() {
        val client = serve("/host/missions/" to ok("""{"result":"PASSED"}"""))
        val robots = listOf("humanoid-01", "quadruped-01")
        assertEquals(HostWrite.Answered(200, """{"result":"PASSED"}"""), client.saveDraft("PrepareSequencedRack", "{ 깨진", "lee", requestId))
        client.validate(3, robots)
        client.mockRun(3, robots, requestId)
        client.activate(3, "lee", "랙 도착 대기 도입", robots, requestId)
        assertEquals(
            listOf(
                "/host/missions/PrepareSequencedRack/drafts",
                "/host/missions/drafts/3/validate",
                "/host/missions/drafts/3/mock-run",
                "/host/missions/drafts/3/activate",
            ),
            seen.map { it.rawPath },
        )
        assertEquals(setOf("POST" to "application/json"), seen.map { it.method to it.contentType }.toSet())
        val id = requestId.toString()
        assertEquals(
            listOf(
                """{"definition":"{ 깨진","actor":"lee","requestId":"$id"}""",
                """{"robotIds":["humanoid-01","quadruped-01"]}""",
                """{"robotIds":["humanoid-01","quadruped-01"],"requestId":"$id"}""",
                """{"actor":"lee","reason":"랙 도착 대기 도입","robotIds":["humanoid-01","quadruped-01"],"requestId":"$id"}""",
            ).map(json::readTree),
            seen.map { json.readTree(it.body) },
        )
    }

    @Test
    fun `임무 읽기 둘은 객체 본문 그대로이고 200 아님은 모름이다`() {
        val client = serve(
            "/host/missions/templates/" to ok("""{"workMasterId":"PrepareSequencedRack","templates":[]}"""),
            "/host/missions/PrepareSequencedRack" to ok("""{"workMasterId":"PrepareSequencedRack","versions":[]}"""),
        )
        assertEquals(json.readTree("""{"workMasterId":"PrepareSequencedRack","versions":[]}"""), (client.overview("PrepareSequencedRack") as HostCall.Ok).value)
        assertEquals(json.readTree("""{"workMasterId":"PrepareSequencedRack","templates":[]}"""), (client.templates("PrepareSequencedRack") as HostCall.Ok).value)
        assertEquals(listOf("GET", "GET"), seen.map { it.method })
        stop()
        val refusing = serve("/host/missions/" to Triple(400, """{"error":"UNKNOWN_WORK_MASTER","detail":"x"}""", Duration.ZERO))
        assertEquals(HostCall.Silent("HTTP 400"), refusing.overview("InspectAsset"))
    }

    @Test
    fun `재조회는 남은 행이면 찾음, 404 REQUEST_NOT_FOUND 면 없음, 그 밖은 못 읽음이다`() {
        val found = serve("/host/missions/requests/" to ok("""{"requestId":"$requestId","draft":null,"mockRun":{"mockRunId":5},"version":null}"""))
        assertEquals(5, assertIs<HostRequery.Found>(found.missionRequest(requestId)).body["mockRun"]["mockRunId"].asInt())
        assertEquals("/host/missions/requests/$requestId", seen.single().rawPath)
        stop()
        val missing = serve("/host/missions/requests/" to Triple(404, """{"error":"REQUEST_NOT_FOUND","detail":"없다"}""", Duration.ZERO))
        assertEquals(HostRequery.NotFound, missing.missionRequest(requestId))
        stop()
        // 처리 중이라는 응답은 행이 없다는 응답이 아니다.
        val busy = serve("/host/missions/requests/" to Triple(409, """{"error":"REQUEST_IN_PROGRESS","detail":"처리 중"}""", Duration.ZERO))
        assertEquals(HostRequery.Silent("호스트가 그 요청을 아직 처리 중이다"), busy.missionRequest(requestId))
        stop()
        // 경로가 없는 서버의 404 는 호스트의 판단이 아니다.
        val elsewhere = serve("/other" to ok("{}"))
        assertEquals(HostRequery.Silent("HTTP 404"), elsewhere.missionRequest(requestId))
        stop()
        assertIs<HostRequery.Silent>(serve("/host/missions/requests/" to ok("[]")).missionRequest(requestId))
        stop()
        server = null
        assertIs<HostRequery.Silent>(elsewhere.missionRequest(requestId))
    }

    @Test
    fun `신호 조작은 이름을 경로 조각으로 인코딩해 값을 문자열로 보내고 현장의 응답을 그대로 돌려준다`() {
        val client = serve("/host/cell/signals/" to Triple(403, """{"error":"SAFETY_SIGNAL_READ_ONLY","detail":"x"}""", Duration.ZERO))
        assertEquals(
            HostWrite.Answered(403, """{"error":"SAFETY_SIGNAL_READ_ONLY","detail":"x"}"""),
            client.writeSignal("guard closed/x", "true"),
        )
        val request = seen.single()
        assertEquals("/host/cell/signals/guard%20closed%2Fx", request.rawPath)
        assertEquals(json.readTree("""{"value":"true"}"""), json.readTree(request.body))
    }

    @Test
    fun `모의 실행만 요청 제한이 길다`() {
        assertEquals(Duration.ofSeconds(60), HostClient.MOCK_RUN_TIMEOUT)
        assertEquals(Duration.ofSeconds(5), HostClient.REQUEST_TIMEOUT)
        // 제한을 줄여 같은 비율로 본다. 호스트가 1초 뒤에 답하면 모의 실행은 받고 활성화는 응답 없음이다.
        val client = serve(
            "/host/missions/" to ok("""{"result":"PASSED"}""", Duration.ofSeconds(1)),
            requestTimeout = Duration.ofMillis(300),
            mockRunTimeout = Duration.ofSeconds(5),
        )
        assertEquals(HostWrite.Answered(200, """{"result":"PASSED"}"""), client.mockRun(3, listOf("humanoid-01"), requestId))
        assertIs<HostWrite.NoResponse>(client.activate(3, "lee", "r", listOf("humanoid-01"), requestId))
        assertIs<HostWrite.NoResponse>(client.validate(3, listOf("humanoid-01")))
        assertIs<HostWrite.NoResponse>(client.saveDraft("PrepareSequencedRack", "{}", "lee", requestId))
    }

    @Test
    fun `닿지 않는 호스트는 임무 읽기가 모름이고 쓰기가 응답 없음이다`() {
        val client = serve()
        stop()
        server = null
        assertIs<HostCall.Silent>(client.overview("PrepareSequencedRack"))
        assertIs<HostWrite.NoResponse>(client.mockRun(3, emptyList(), requestId))
        assertIs<HostWrite.NoResponse>(client.writeSignal("rack_present", "true"))
    }
}
