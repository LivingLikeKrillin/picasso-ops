package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostSignals
import dev.picasso.ops.service.host.HostWrite
import java.util.UUID

/**
 * 임무 버전·신호 조작 시험의 대역 세트. 기체 목록(registry)은 [JobOrderBench] 의 것을 쓰고, 호스트의 임무 REST 와 신호
 * 조작을 인터페이스 대역으로 끼운다.
 *
 * 기본은 기체 둘(humanoid-01, quadruped-01)이 다 시운전 완료이고, 호스트가 쓰기마다 정상 결과로 답하는 것이다(초안 저장은
 * 초안 3, 검증은 PASSED, 모의 실행은 PASSED, 활성화는 ACTIVATED 버전 1, 신호는 바뀐 신호). 시험이 칸을 바꿔 한 칸씩
 * 어긋나게 한다.
 */
class MissionBench {

    val base = JobOrderBench()
    private val json = jacksonObjectMapper()

    var overview: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"workMasterId":"$WM","active":{"version":null,"source":"CODE","detail":null},"versions":[],"drafts":[]}"""))
    var templates: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"workMasterId":"$WM","templates":[]}"""))
    var saveAnswer: HostWrite = HostWrite.Answered(200, DRAFT_SAVED)
    var validateAnswer: HostWrite = HostWrite.Answered(200, judgment("PASSED"))
    var mockRunAnswer: HostWrite = HostWrite.Answered(200, judgment("PASSED", extra = ""","mockRun":{"mockRunId":5,"draftId":3,"passed":true}"""))
    var activateAnswer: HostWrite = HostWrite.Answered(200, judgment("ACTIVATED", extra = ""","version":1,"activated":{"version":1}"""))
    var signalAnswer: HostWrite = HostWrite.Answered(200, """{"name":"rack_present","location":"RACK-204","kind":"BOOLEAN","safety":false,"value":"true","observedAt":"2026-10-08T00:02:00Z"}""")

    /** 요청 id 를 받아 재조회 응답을 낸다. 기본은 못 읽음이다. */
    var requery: (UUID) -> HostRequery = { HostRequery.Silent("응답 없음: ConnectException") }

    /** 호스트가 받은 호출. 조작 이름과 인자. */
    val calls = mutableListOf<Call>()

    data class Call(
        val op: String,
        val workMasterId: String? = null,
        val draftId: Long? = null,
        val robotIds: List<String>? = null,
        val requestId: UUID? = null,
        val actor: String? = null,
        val reason: String? = null,
        val definition: String? = null,
        val name: String? = null,
        val value: String? = null,
    )

    /** 재조회한 요청 id. */
    val requeried = mutableListOf<UUID>()

    inner class FakeHost : HostMissions, HostSignals {
        override fun overview(workMasterId: String) = this@MissionBench.overview.also { calls += Call("overview", workMasterId) }

        override fun templates(workMasterId: String) = this@MissionBench.templates.also { calls += Call("templates", workMasterId) }

        override fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID): HostWrite {
            calls += Call("saveDraft", workMasterId = workMasterId, definition = definition, actor = actor, requestId = requestId)
            return saveAnswer
        }

        override fun validate(draftId: Long, robotIds: List<String>): HostWrite {
            calls += Call("validate", draftId = draftId, robotIds = robotIds)
            return validateAnswer
        }

        override fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): HostWrite {
            calls += Call("mockRun", draftId = draftId, robotIds = robotIds, requestId = requestId)
            return mockRunAnswer
        }

        override fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): HostWrite {
            calls += Call("activate", draftId = draftId, robotIds = robotIds, requestId = requestId, actor = actor, reason = reason)
            return activateAnswer
        }

        override fun missionRequest(requestId: UUID): HostRequery {
            requeried += requestId
            return requery(requestId)
        }

        override fun writeSignal(name: String, value: String): HostWrite {
            calls += Call("writeSignal", name = name, value = value)
            return signalAnswer
        }
    }

    val host = FakeHost()

    /** 호스트가 받은 쓰기 호출(읽기 둘을 뺀 것). */
    fun writes(): List<Call> = calls.filter { it.op !in setOf("overview", "templates") }

    /** 셀 스냅숏 본문. [signals] 는 이름과 값의 쌍이다. */
    fun cellWith(vararg signals: Pair<String, String>): HostCall<JsonNode> {
        val rows = signals.joinToString(",") { (name, value) ->
            """{"name":"$name","location":null,"kind":"BOOLEAN","safety":false,"value":"$value","observedAt":null}"""
        }
        return HostCall.Ok(json.readTree("""{"cell":{"presentations":[],"slots":[],"signals":[$rows]}}"""))
    }

    companion object {
        const val WM = "PrepareSequencedRack"

        const val DRAFT_SAVED =
            """{"draft":{"draftId":3,"workMasterId":"$WM","definition":"{}","savedBy":"lee","requestId":"x","savedAt":"t","lastMockRun":null}}"""

        /** 호스트 거부 하나(S3b JSON 계약 §3.1). */
        fun refusal(
            kind: String = "SIGNAL_NOT_IN_SPEC",
            nodeId: String? = "rack-arrival",
            observed: String = "rack_ready",
            owner: String = "ENGINEER",
            basisVersion: String = "null",
        ) = """{"kind":"$kind","nodeId":${nodeId?.let { "\"$it\"" } ?: "null"},"observed":"$observed",
            "expected":"신호 사양의 이름 중 하나(guard_closed, lot_code, rack_present)","checkedAt":"2026-10-08T00:00:01Z",
            "basisVersion":$basisVersion,"owner":"$owner","nextAction":"신호 이름을 고치거나 신호 사양에 더한다"}"""

        /** 판정 본문(검증·모의 실행·활성화 공통 칸). */
        fun judgment(result: String, refusals: List<String> = emptyList(), unknown: String = "null", extra: String = "") =
            """{"result":"$result","draftId":3,"workMasterId":"$WM","checkedAt":"2026-10-08T00:00:01Z",
               "refusals":[${refusals.joinToString(",")}],"unknown":$unknown,"inputs":{"signals":null,"siteSkills":null,
               "robotIds":[],"unknownRobots":[]}$extra}"""

        const val UNKNOWN_SKILLS =
            """{"inputs":["SITE_SKILLS"],"robots":["ghost-01"],"detail":"기체 케이퍼빌리티를 못 물어봐 현장 스킬을 못 읽었다(ghost-01)"}"""
    }
}
