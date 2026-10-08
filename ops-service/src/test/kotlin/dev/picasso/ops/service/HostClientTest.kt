package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostSkillFit
import dev.picasso.ops.service.host.HostWrite
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 실행 호스트 REST 클라이언트(S3a 스펙 §8). 호스트는 JDK HttpServer 대역이다. */
class HostClientTest {

    private var server: HttpServer? = null
    private val json = ObjectMapper()
    private val order = json.createObjectNode().put("jobOrderId", "JO-1").put("workMasterId", "InspectAsset")

    /** 경로마다 받은 요청(방법, Content-Type, 본문)을 남긴다. */
    private val seen = mutableMapOf<String, Triple<String, String?, String>>()

    private fun serve(vararg routes: Pair<String, Pair<Int, String>>): HostClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, answer) ->
            s.createContext(path) { exchange ->
                seen[path] = Triple(
                    exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                val bytes = answer.second.toByteArray()
                exchange.sendResponseHeaders(answer.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        s.start()
        server = s
        return HostClient("http://127.0.0.1:${s.address.port}/")
    }

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `판정은 작업 지시 본문과 기체 id 를 JSON 으로 보내고 판정 행을 읽는다`() {
        val client = serve(
            "/host/eligibility" to (
                200 to """{"robots":[{"robotId":"quadruped-01","skillFit":"MISSING","missingSkills":["pick_place"],
                    "runningExecutionId":null,"passed":false,"reasons":["모자란 스킬: pick_place"],"later":1}]}"""
                ),
        )
        val row = assertIs<HostCall.Ok<List<HostEligibility>>>(
            client.eligibility(order, listOf("quadruped-01")),
        ).value.single()
        assertEquals(HostSkillFit.MISSING, row.skillFit)
        assertEquals(listOf("pick_place"), row.missingSkills)
        assertEquals(false, row.passed)
        val (method, contentType, body) = seen.getValue("/host/eligibility")
        assertEquals("POST", method)
        assertEquals("application/json", contentType)
        assertEquals(json.readTree("""{"jobOrder":{"jobOrderId":"JO-1","workMasterId":"InspectAsset"},"robotIds":["quadruped-01"]}"""), json.readTree(body))
    }

    @Test
    fun `판정이 200 아님이나 모양 어긋남이면 모름이다`() {
        val client = serve("/host/eligibility" to (400 to """{"error":"BAD_REQUEST","detail":"x"}"""))
        assertEquals(HostCall.Silent("HTTP 400"), client.eligibility(order, listOf("r1")))
        stop()
        val odd = serve("/host/eligibility" to (200 to """{"robots":[{"robotId":"r1","skillFit":"MAYBE","passed":true}]}"""))
        assertIs<HostCall.Silent>(odd.eligibility(order, listOf("r1")))
    }

    @Test
    fun `제출은 후보를 실어 보내고 상태 코드와 본문을 그대로 돌려준다`() {
        val client = serve("/host/job-orders" to (200 to """{"result":"UNASSIGNED"}"""))
        assertEquals(HostWrite.Answered(200, """{"result":"UNASSIGNED"}"""), client.submit(order, listOf("humanoid-01", "quadruped-01")))
        assertEquals(
            json.readTree("""{"jobOrder":{"jobOrderId":"JO-1","workMasterId":"InspectAsset"},"candidates":["humanoid-01","quadruped-01"]}"""),
            json.readTree(seen.getValue("/host/job-orders").third),
        )
    }

    @Test
    fun `닿지 않는 호스트는 판정과 조회가 모름이고 제출이 응답 없음이다`() {
        val client = serve()
        stop()
        server = null
        assertIs<HostCall.Silent>(client.eligibility(order, listOf("r1")))
        assertIs<HostCall.Silent>(client.executions())
        assertIs<HostCall.Silent>(client.cell())
        assertIs<HostWrite.NoResponse>(client.submit(order, listOf("r1")))
    }

    @Test
    fun `실행 목록과 셀은 객체 본문 그대로이고 200 아님과 객체 아님과 해석 불가는 모름이다`() {
        val client = serve(
            "/host/executions" to (200 to """{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""),
            "/host/cell" to (200 to """{"cell":null}"""),
        )
        assertEquals(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""), (client.executions() as HostCall.Ok).value)
        assertEquals(json.readTree("""{"cell":null}"""), (client.cell() as HostCall.Ok).value)
        stop()
        val broken = serve("/host/executions" to (200 to "[]"), "/host/cell" to (503 to ""))
        assertIs<HostCall.Silent>(broken.executions())
        assertEquals(HostCall.Silent("HTTP 503"), broken.cell())
        stop()
        assertIs<HostCall.Silent>(serve("/host/executions" to (200 to "not json")).executions())
    }

    @Test
    fun `현장 시간값 적용 상태는 GET host site-timings 본문 그대로이고 200 아님과 닿지 않음은 모름이다`() {
        val body = """{"applied":{"version":2,"evidenceBeforeSeconds":30},"readError":null}"""
        val client = serve("/host/site-timings" to (200 to body))
        assertEquals(json.readTree(body), assertIs<HostCall.Ok<*>>(client.siteTimings()).value)
        assertEquals("GET", seen.getValue("/host/site-timings").first)
        stop()
        assertEquals(HostCall.Silent("HTTP 404"), serve("/host/site-timings" to (404 to "")).siteTimings())
        stop()
        val gone = serve("/host/site-timings" to (200 to body))
        stop()
        assertIs<HostCall.Silent>(gone.siteTimings())
    }

    @Test
    fun `호스트 주소 형식이 틀리면 기동에서 멈춘다`() {
        assertFailsWith<IllegalArgumentException> { HostClient("127.0.0.1:8785") }
        assertEquals("http://127.0.0.1:8785", HostClient.checkBaseUrl("http://127.0.0.1:8785/"))
    }
}
