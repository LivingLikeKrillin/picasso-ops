package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.SiteTimings
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.host.timings.SiteTimingsReader
import dev.picasso.ops.host.timings.SiteTimingsView
import dev.picasso.ops.service.settings.SiteSettingsRange
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3c 완료 판정의 통합 쪽(S3c 스펙 §3·§7.1·§10). 엔지니어가 운영 서비스 REST 로 시간값을 바꾸면 실행 호스트가 ops 의 현재 버전
 * 뷰를 읽어 다음 pump 부터 적용하고, 설비 대기 기한을 넘겨 봉인된 `SIGNAL_DEADLINE` 인시던트가 봉인 라운드의 설정 버전과 시간값을
 * 싣는다. 인시던트는 실행 호스트 `GET /host/incidents` 로 읽는다(S3c JSON 계약 §8).
 *
 * 순서가 있다. 앞 시험의 설정 버전과 실행을 뒤 시험이 이어받는다. 활성 임무 버전은 `ARRIVAL_WAIT` 템플릿 하나(버전 1)다.
 *
 * ## 시계
 *
 * 호스트의 읽기 주기는 실제 시간 1초이고([SiteTimingsReader.READ_PERIOD]), 기한은 현장 가상 시계로 잰다. 설정을 바꾼 뒤에는
 * 호스트가 그 버전을 적용했다고 보일 때까지 실제 시간으로만 기다리고([awaitApplied]), 그 뒤에야 가상 시계를 민다. 그래서 봉인
 * 라운드의 버전이 정해진다. 설비 대기의 기한 판정 자체는 임무 정의의 120초이고 시간값을 쓰지 않는다(스펙 §11). 이 시험이 증명하는
 * 것은 봉인 라운드의 버전과 값이 실리는 것까지다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SiteTimingsTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private val FIRST_SLOTS = listOf("RACK-204.S01", "RACK-204.S02")
        private val SECOND_SLOTS = listOf("RACK-204.S03", "RACK-204.S04")

        /** 설비 대기 단위의 기한(`ARRIVAL_WAIT` 템플릿의 `deadlineSeconds`). */
        private val DEADLINE: Duration = Duration.ofSeconds(120)

        /** 호스트가 새 버전을 적용하기를 기다리는 상한(실제 시간). 읽기 주기 1초의 몇 배다. */
        private val APPLY_WAIT: Duration = Duration.ofSeconds(5)

        /** 시간값 넷(앞 폭, 뒤 폭, inDoubtGrace, stallWindow). 버전 1 은 picasso 기본값이다. */
        private val V1 = listOf(30L, 15L, 60L, 300L)
        private val V2 = listOf(40L, 20L, 90L, 600L)
        private val V3 = listOf(45L, 25L, 120L, 900L)
        private val FIELDS = listOf("evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds")

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }

        private fun rack(slots: List<String>): String =
            """{"workMasterId":"$WORK_MASTER","slots":[${slots.joinToString(",") { "\"$it\"" }}],""" +
                """"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `기동 직후 현장 설정 버전 1 은 시간값 기본값이고 실행 호스트가 버전 1 을 적용했다`() {
        val view = stack.get("/api/site-settings")
        assertEquals(1L to 90, view["current"]["version"].asLong() to view["current"]["connectionThresholdSeconds"].asInt(), "$view")
        assertEquals(V1, values(view["current"]), "$view")

        val host = hostTimings()
        assertEquals(1L, host["applied"]["version"].asLong(), "$host")
        assertEquals(V1, values(host["applied"]), "$host")
        assertTrue(host["readError"].isNull && host["rejected"].isNull, "$host")
        // 운영 서비스가 호스트 적용 상태를 대신 읽어 화면에 싣는다(S3c JSON 계약 §3).
        assertEquals(1L, view["hostTimings"]["applied"]["version"].asLong(), "$view")
    }

    @Test
    @Order(2)
    fun `엔지니어가 시간값을 바꾸면 버전 2 이고 실행 호스트가 수 초 안에 버전 2 를 적용한다`() {
        val reply = change(1, V2, "정체 표시 늦춤")
        assertEquals(200 to "SUCCEEDED", reply.status to reply.body!!["result"].asText(), "${reply.body}")
        val view = stack.get("/api/site-settings")
        assertEquals(2L, view["current"]["version"].asLong(), "$view")
        assertEquals(V2, values(view["current"]), "$view")
        // 빠진 연결 기준 시간은 기준 버전의 값이다.
        assertEquals(90, view["current"]["connectionThresholdSeconds"].asInt(), "$view")

        val host = awaitApplied(2)
        assertEquals(V2, values(host["applied"]), "$host")
    }

    @Test
    @Order(3)
    fun `랙 도착 대기 버전으로 낸 작업 지시가 신호 없이 기한을 넘기면 SIGNAL_DEADLINE 인시던트가 설정 버전 2 와 그 값을 싣는다`() {
        activateArrivalWait()
        // 가상 시계를 밀기 전에 호스트의 적용 버전을 확인한다. 봉인 라운드의 버전이 2 로 정해진다.
        awaitApplied(2)

        val (jobOrderId, executionId) = submit(rack(FIRST_SLOTS))
        val waitFrom = stack.site.now()
        driver.push(ExecutionDriver.STEP)
        assertWaiting(executionId)

        // rack_present 를 켜지 않는다. 진행기가 기한 120초를 넘겨 실행이 ABORTED 로 정착한다.
        val settled = driver.drive(executionId)
        assertEquals("ABORTED" to 1, settled["physicalState"].asText() to settled["missionVersion"].asInt(), "$settled")

        val incidents = incidents()
        assertEquals(1, incidents["total"].asInt(), "$incidents")
        val incident = deadlineIncident(incidents, executionId)
        assertEquals(
            listOf(jobOrderId, HUMANOID, "rack-arrival", "SIGNAL"),
            listOf(incident["jobOrderId"].asText(), incident["robotId"].asText(), incident["unitId"].asText(), incident["route"].asText()),
            "$incident",
        )
        assertEquals(1, incident["missionVersion"].asInt(), "$incident")
        assertTrue(Duration.between(waitFrom, Instant.parse(incident["at"].asText())) >= DEADLINE, "$incident")
        assertEquals(2L, incident["siteSettingsVersion"].asLong(), "$incident")
        assertEquals(V2, values(incident), "$incident")
    }

    @Test
    @Order(4)
    fun `둘째 작업 지시가 기다리는 중에 버전 3 으로 바꾸면 기한을 넘긴 인시던트는 버전 3 을 든다`() {
        val (_, executionId) = submit(rack(SECOND_SLOTS))
        driver.push(ExecutionDriver.STEP)
        assertWaiting(executionId)

        // 기다리는 중에 바꾸고, 호스트가 적용했다고 보일 때까지 실제 시간으로만 기다린다. 가상 시계는 멈춰 있다.
        val reply = change(2, V3, "판정 유예 늘림")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText(), "${reply.body}")
        awaitApplied(3)

        val settled = driver.drive(executionId)
        assertEquals("ABORTED", settled["physicalState"].asText(), "$settled")

        val incidents = incidents()
        assertEquals(2, incidents["total"].asInt(), "$incidents")
        val incident = deadlineIncident(incidents, executionId)
        assertEquals(incident, incidents["incidents"].first(), "최신부터: $incidents")
        assertEquals(3L, incident["siteSettingsVersion"].asLong(), "$incident")
        assertEquals(V3, values(incident), "$incident")
        // 앞 인시던트는 봉인 때의 버전 2 그대로다.
        val earlier = incidents["incidents"][1]
        assertEquals(2L to V2, earlier["siteSettingsVersion"].asLong() to values(earlier), "$earlier")
    }

    @Test
    @Order(5)
    fun `범위 밖 시간값은 칸 이름을 실은 SETTING_OUT_OF_RANGE 이고 운영 서비스의 범위 사본은 picasso 범위와 같다`() {
        val reply = stack.send(
            "PUT", "/api/site-settings", "engineer",
            body = """{"baseVersion":3,"inDoubtGraceSeconds":9,"stallWindowSeconds":3601,"reason":"범위 밖"}""",
        )
        assertEquals(400 to "SETTING_OUT_OF_RANGE", reply.status to reply.body!!["error"].asText(), "${reply.body}")
        assertEquals(
            "inDoubtGraceSeconds 9초는 범위 밖이다. 10~600초여야 한다; stallWindowSeconds 3601초는 범위 밖이다. 30~3600초여야 한다",
            reply.body["detail"].asText(),
        )
        assertEquals(3L, stack.get("/api/site-settings")["current"]["version"].asLong())

        // 주인은 picasso 다(스펙 T3). 운영 서비스 main 은 picasso 를 쓰지 못해 사본을 두고, 여기서 맞댄다(T6).
        val picasso = listOf(
            SiteSettingsRange.EVIDENCE_BEFORE to SiteTimings.EVIDENCE_WINDOW_BEFORE_SECONDS,
            SiteSettingsRange.EVIDENCE_AFTER to SiteTimings.EVIDENCE_WINDOW_AFTER_SECONDS,
            SiteSettingsRange.IN_DOUBT_GRACE to SiteTimings.IN_DOUBT_GRACE_SECONDS,
            SiteSettingsRange.STALL_WINDOW to SiteTimings.STALL_WINDOW_SECONDS,
        ).map { (field, range) -> field to (range.first to range.last) }
        val copy = SiteSettingsRange.FIELDS.filter { it.first != SiteSettingsRange.CONNECTION_THRESHOLD }
            .map { (field, range) -> field to (range.first.toLong() to range.last.toLong()) }
        assertEquals(picasso, copy)
        // 화면이 받는 range 칸도 같은 값이다.
        val range = stack.get("/api/site-settings")["range"]
        val served = picasso.map { (field, _) ->
            val name = field.replaceFirstChar(Char::uppercase)
            field to (range["min$name"].asLong() to range["max$name"].asLong())
        }
        assertEquals(picasso, served, "$range")
    }

    @Test
    @Order(6)
    fun `실제 현재 버전 뷰의 칸은 실행 호스트가 읽는 뷰 상수의 칸과 이름과 형이 같다`() {
        // 호스트 시험 세트는 이 상수로 대역 뷰를 만든다(S3c 스펙 §7.1). 실제 뷰와 갈라지면 대역 뷰 위의 호스트 시험이 헛돈다.
        val (schema, table) = SiteTimingsView.NAME.split(".")
        val actual = PostgresSupport.queryAll(
            "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = '$schema' AND table_name = '$table' ORDER BY ordinal_position",
        ) { it.getString(1) to it.getString(2) }
        assertEquals(SiteTimingsView.COLUMNS.map { it.name to it.type }, actual)
    }

    /** 시간값 넷을 [FIELDS] 순서로 낸다. 운영 서비스 버전 행, 호스트 적용 값, 호스트 인시던트의 칸 이름이 같다. */
    private fun values(node: JsonNode): List<Long> = FIELDS.map { node[it].asLong() }

    private fun change(base: Long, values: List<Long>, reason: String): E2eStack.Reply {
        val body = json.createObjectNode().put("baseVersion", base).put("reason", reason)
        FIELDS.zip(values).forEach { (field, value) -> body.put(field, value) }
        return stack.send("PUT", "/api/site-settings", "engineer", body = body.toString())
    }

    private fun hostTimings(): JsonNode = E2eStack.read("${stack.hostUrl}/host/site-timings")

    private fun incidents(): JsonNode = E2eStack.read("${stack.hostUrl}/host/incidents")

    /** 실행 호스트가 [version] 을 적용했다고 보일 때까지 실제 시간으로 기다린다. 가상 시계는 밀지 않는다. */
    private fun awaitApplied(version: Long): JsonNode {
        val deadline = Instant.now().plus(APPLY_WAIT)
        var seen = hostTimings()
        while (seen["applied"].isNull || seen["applied"]["version"].asLong() != version) {
            check(Instant.now().isBefore(deadline)) { "실행 호스트가 ${APPLY_WAIT.seconds}초 안에 버전 $version 을 적용하지 않았다: $seen" }
            Thread.sleep(100)
            seen = hostTimings()
        }
        return seen
    }

    /** 실행의 첫 단위인 랙 도착 대기가 돌고 있다. */
    private fun assertWaiting(executionId: String) {
        val waiting = driver.execution(executionId)
        assertEquals("RUNNING", waiting["physicalState"].asText(), "$waiting")
        val first = waiting["units"].first()
        assertEquals(Triple("rack-arrival", "equipment_wait", "RUNNING"), Triple(first["unitId"].asText(), first["skillType"].asText(), first["state"].asText()), "$waiting")
    }

    private fun deadlineIncident(incidents: JsonNode, executionId: String): JsonNode =
        incidents["incidents"].single { it["executionId"].asText() == executionId && it["failureClass"].asText() == "SIGNAL_DEADLINE" }

    /** S3b 흐름으로 `ARRIVAL_WAIT` 템플릿을 초안 저장, 모의 실행, 활성화한다. 새 스택이라 버전 1 이다. */
    private fun activateArrivalWait() {
        val templates = stack.get("/api/missions/templates/$WORK_MASTER")["templates"]
            .associate { it["id"].asText() to it["definition"].asText() }
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", templates.getValue("ARRIVAL_WAIT")).toString())
        val draftId = saved["outcome"]["draft"]["draftId"].asLong()
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"랙 도착 대기 도입"}""")
        assertEquals("ACTIVATED" to 1, activated["outcome"]["result"].asText() to activated["outcome"]["version"].asInt(), "$activated")
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    /** 운영자 모드로 작업 지시를 내고 작업 지시 id 와 실행 id 를 낸다. 배정 기체는 `pick_place` 를 가진 humanoid-01 하나다. */
    private fun submit(form: String): Pair<String, String> {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return reply.body["jobOrderId"].asText() to outcome["executionId"].asText()
    }
}
