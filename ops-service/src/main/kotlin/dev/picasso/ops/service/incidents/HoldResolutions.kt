package dev.picasso.ops.service.incidents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostIncidents
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 운영자 판단의 200 응답(S4a 스펙 §7, §8.3).
 *
 * @param result 조작 기록의 결과. `Resolved` 는 성공, `NotHeld`·`Refused` 는 거부, 호스트가 안 닿으면 응답 없음이다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param outcome 호스트 판단 결과 이름 그대로(`Resolved`, `NotHeld`, `Refused`). 호스트가 200 으로 답했을 때만 있다
 * @param answer 호스트의 200 본문(S4a JSON 계약 §5.2) 그대로
 * @param rejection 호스트가 4xx 로 막았을 때만 있다(본문이 틀린 400 `BAD_REQUEST`. 운영 서비스가 먼저 검사해 정상 흐름에서는
 *   나오지 않는다)
 */
data class HoldResolveOutcome(
    val requestId: UUID,
    val executionId: String,
    val unitId: String,
    val decision: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: String?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 운영자 보류 해소(S4a 스펙 §7, T4·T6). 운영자 모드의 조작이며(모드 검사는 컨트롤러의 몫) 승인자는 행위자(`X-Ops-User`)다.
 * 호스트는 그 id 로 `Approver(id, PERSON)` 을 만든다(ADR 43: 기본값을 두지 않는다). 사유는 picasso 인시던트에 자리가 없어
 * 조작 기록의 사유 칸이 유일한 자리다.
 *
 * 결과는 호스트 200 본문의 `result` 로 가른다. `Resolved` 는 성공, `NotHeld`·`Refused` 는 거부로 남기고 응답 칸의 본문에
 * 원래 이름이 있다(S3a 작업 지시의 UNASSIGNED 선례). 모르는 이름은 본문을 못 읽은 것과 같이 응답 없음이다.
 *
 * 응답 없음 뒤 재조회(S4a JSON 계약 §5.4): 호스트는 요청 id 를 저장하지 않으므로 인시던트로 대조한다. 요청을 보내기 직전의
 * 실제 시각을 정해 두고, 같은 실행·단위의 인시던트 가운데 하나라도 판단이 붙었고 판단자가 행위자이며 결정이 같고 판단의
 * 실제 시각(`wallClockAt`)이 그 시각 이후이면 반영됨이다. 같은 사람이 같은 단위를 앞서 판단한 기록은 시각 조건이 거른다.
 * «가장 최근 인시던트» 와 «보류가 아님» 은 보지 않는다. 재작업 뒤 대기가 기한을 다시 넘기면 새 보류가 서서 반영된 재작업을
 * 반영 안 됨으로 읽기 때문이다. 판단 시각 `at` 은 호스트 시계(통합 시험에서는 가상 시각)라 쓰지 않는다.
 *
 * 판단은 인스턴스를 싣는다(S4b 스펙 T8·T9). 화면은 인시던트 상세의 `instanceId` 를 보내고, 호스트는 지금 인스턴스가 아니면
 * 409 `INSTANCE_MISMATCH` 로 막는다. 그 거부는 다른 호스트 4xx 와 같이 거부로 남고 원래 이름이 응답 칸에 있다. 재조회는 목록의
 * `instanceId` 가 요청한 인스턴스와 같을 때만 `incidents` 를 본다. 다르면 호스트가 그 사이 재기동한 것이고 `exec-N` 을 다시
 * 세므로 새 인스턴스의 같은 실행 id 를 잘못 맞출 수 있다. 그때는 `earlier` 에서 요청한 인스턴스의 사본만 본다.
 */
class HoldResolutions(
    private val host: HostIncidents,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val clock: Clock = Clock.systemUTC(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    /** [decision] 은 컨트롤러가 둘 중 하나임을, [instanceId]·[reason] 은 비어 있지 않음을 본 값이다. */
    fun resolve(
        actor: Actor,
        executionId: String,
        unitId: String,
        decision: String,
        instanceId: String,
        reason: String,
    ): HoldResolveOutcome {
        val request = json.createObjectNode()
            .put("op", OP)
            .put("executionId", executionId)
            .put("unitId", unitId)
            .put("decision", decision)
            .put("approverId", actor.user)
            .put("instanceId", instanceId)
        var sentAt: Instant? = null
        val ran = runner.run(
            actor, "$executionId/$unitId", request, reason,
            accepted = { body -> resultOf(body.path("result").takeIf { it.isTextual }?.asText()) },
            recheck = { sentAt?.let { at -> recheck(executionId, unitId, decision, instanceId, actor.user, at) } },
        ) { requestId ->
            sentAt = clock.instant()
            host.resolve(executionId, unitId, decision, actor.user, instanceId, requestId)
        }
        val outcome = ran.body?.path("result")?.takeIf { it.isTextual }?.asText()
        return HoldResolveOutcome(
            ran.requestId, executionId, unitId, decision, ran.result, ran.confirmation, outcome, ran.body, ran.rejection,
        )
    }

    /**
     * 목록을 못 읽으면 확인하지 못한 것이라 널이다. 반영 안 됨이면 관측으로 그 단위의 가장 최근 인시던트를 남긴다(없으면 널).
     * 목록의 인스턴스가 [instanceId] 면 `incidents` 를, 아니면 `earlier` 의 그 인스턴스 사본을 본다.
     */
    private fun recheck(
        executionId: String,
        unitId: String,
        decision: String,
        instanceId: String,
        approver: String,
        sentAt: Instant,
    ): HostRecheck? {
        val body = (host.incidents(REQUERY_LIMIT) as? HostCall.Ok)?.value ?: return null
        val current = body.path("instanceId").takeIf { it.isTextual }?.asText() == instanceId
        val listed = body.path(if (current) "incidents" else "earlier").takeIf { it.isArray } ?: return null
        val unit = listed.filter {
            (current || it.path("instanceId").asText() == instanceId) &&
                it.path("executionId").asText() == executionId && it.path("unitId").asText() == unitId
        }
        val match = unit.firstOrNull { incident ->
            val resolution = incident.path("resolution").takeIf { it.isObject } ?: return@firstOrNull false
            resolution.path("decidedBy").path("id").asText() == approver &&
                resolution.path("decision").asText() == decision &&
                instant(resolution.path("wallClockAt"))?.let { it >= sentAt } == true
        }
        return if (match != null) HostRecheck(true, match) else HostRecheck(false, unit.firstOrNull())
    }

    private fun instant(node: JsonNode): Instant? =
        node.takeIf { it.isTextual }?.let { runCatching { Instant.parse(it.asText()) }.getOrNull() }

    companion object {
        const val OP = "RESOLVE_OPERATOR_HOLD"

        /** picasso `ResolveOutcome` 이름 그대로(S4a JSON 계약 §5.2). */
        const val RESOLVED = "Resolved"
        const val NOT_HELD = "NotHeld"
        const val REFUSED = "Refused"

        /** 판단 종류 둘(picasso `OperatorDecision`). */
        val DECISIONS: Set<String> = setOf("CONFIRM_DONE", "REWORK")

        /** 재조회가 읽는 인시던트 수. 호스트가 받는 최대다(S4a JSON 계약 §3). */
        const val REQUERY_LIMIT = 500

        fun resultOf(name: String?): OperationResult? = when (name) {
            RESOLVED -> OperationResult.SUCCEEDED
            NOT_HELD, REFUSED -> OperationResult.REJECTED
            else -> null
        }
    }
}
