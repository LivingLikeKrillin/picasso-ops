package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSkillFit
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.host.HostWrites
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.settings.SiteSettingsValues
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 배정 가능·제출 시험의 대역 세트. registry 읽기와 실행 호스트를 기존 시험처럼 인터페이스 대역으로 끼운다.
 *
 * 기본은 기체 둘(humanoid-01, quadruped-01)이 다 시운전 완료·신선이고, 호스트가 둘 다 통과로 판정하고, 제출에 ACCEPTED 로
 * 답하는 것이다. 시험이 칸을 바꿔 한 칸씩 어긋나게 한다.
 */
class JobOrderBench {

    val at: Instant = Instant.parse("2026-10-08T00:00:00Z")
    val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
    private val json = jacksonObjectMapper()

    var robots: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(listOf(robot(HUMANOID), robot(QUADRUPED)))
    var bindings: RegistryCall<List<RegistryBinding>> = RegistryCall.Ok(listOf(binding(HUMANOID), binding(QUADRUPED)))

    /** 기체 id 목록을 받아 호스트 판정을 낸다. 기본은 모두 통과다. */
    var judge: (List<String>) -> HostCall<List<HostEligibility>> = { ids -> HostCall.Ok(ids.map(::pass)) }
    var answer: HostWrite = HostWrite.Answered(200, accepted(HUMANOID))
    var executions: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""))
    var cell: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"cell":null}"""))

    /** 호스트가 받은 판정 요청(작업 지시 본문, 기체 id). */
    val judged = mutableListOf<Pair<ObjectNode, List<String>>>()

    /** 호스트가 받은 제출(작업 지시 본문, 후보). */
    val submitted = mutableListOf<Pair<ObjectNode, List<String>>>()

    /** 호스트 대역. 읽기와 제출이 이 벤치의 칸을 쓴다. */
    inner class FakeHost : HostReads, HostWrites {
        override fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>> {
            judged += jobOrder to robotIds
            return judge(robotIds)
        }

        override fun executions(): HostCall<JsonNode> = this@JobOrderBench.executions

        override fun cell(): HostCall<JsonNode> = this@JobOrderBench.cell

        override fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite {
            submitted += jobOrder to candidates
            return answer
        }
    }

    val host = FakeHost()

    val robotList = RobotListService(
        { robots },
        { RegistryCall.Ok(Unit) },
        SITE,
        clock,
        { SiteSettingsValues(SETTINGS_VERSION, Duration.ofSeconds(90)) },
        commissioning = object : CommissioningSource {
            override fun bindings(siteId: String) = bindings

            override fun software(siteId: String): RegistryCall<List<RegistrySoftware>> = RegistryCall.Ok(emptyList())
        },
    )

    val eligibility = JobOrderEligibility(robotList, host, clock)

    /** 실행 목록 본문. [jobOrderIds] 마다 humanoid-01 의 실행이 하나씩 있다. */
    fun executionsWith(vararg jobOrderIds: String): HostCall<JsonNode> {
        val rows = jobOrderIds.mapIndexed { i, id ->
            """{"executionId":"exec-${i + 1}","jobOrderId":"$id","workMasterId":"InspectAsset","missionVersion":null,
               "robotId":"$HUMANOID","physicalState":"RUNNING","units":[],"jobResponse":null}"""
        }
        return HostCall.Ok(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[${rows.joinToString(",")}]}"""))
    }

    companion object {
        const val SITE = "site-01"
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val SETTINGS_VERSION = 3L

        /** 기준 90초 안에 보고한 시운전 원장 상태의 기체. */
        fun robot(id: String, status: String = "CONFIRMED", lastReportedAt: String? = "2026-10-07T23:59:50Z") =
            RegistryRobot(robotId = id, siteId = SITE, status = status, lastReportedAt = lastReportedAt)

        fun binding(id: String, siteNames: String = "CONFIRMED") = RegistryBinding(
            robotId = id, vendor = "v", model = "m", profileRevisionId = 2, revision = 1, adapterName = "acme/fleet",
            adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames,
            adapterVersionId = 7, boundBy = "engineer/kim", boundAt = "t",
        )

        fun pass(id: String) = HostEligibility(id, HostSkillFit.FIT, emptyList(), null, true, emptyList())

        fun missing(id: String, vararg skills: String) = HostEligibility(
            id, HostSkillFit.MISSING, skills.toList(), null, false, listOf("모자란 스킬: ${skills.joinToString(", ")}"),
        )

        fun running(id: String, executionId: String) =
            HostEligibility(id, HostSkillFit.FIT, emptyList(), executionId, false, listOf("도는 실행이 있다: $executionId"))

        fun accepted(robotId: String) =
            """{"result":"ACCEPTED","executionId":"exec-1","robotId":"$robotId","rejectionReason":null,"refusals":[],"excluded":[]}"""

        val INSPECT_FORM = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}"""
    }
}
