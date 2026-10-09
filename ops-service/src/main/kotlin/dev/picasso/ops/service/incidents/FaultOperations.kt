package dev.picasso.ops.service.incidents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostFaults
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRejection
import java.time.Duration
import java.util.UUID

/**
 * 장애 주입의 200 응답(S4a 스펙 §7). 신호 조작의 응답과 같은 자리에 같은 칸을 둔다.
 *
 * @param result 조작 기록의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 늘 널이다. 장애 주입은 재조회하지 않는다(확인할 칸이 없다). 다른 조작 응답과 모양을 맞추려고 둔다
 * @param fault 현장의 200 본문(S4a JSON 계약 §1.2). 성공일 때만 있다
 * @param rejection 현장이나 호스트가 4xx 로 막았을 때만 있다. 현장의 오류 이름(`NO_RUNNING_TASK`, `UNKNOWN_ROBOT`,
 *   `UNSUPPORTED_FAULT`, `BAD_REQUEST` 등)이 그대로 온다
 */
data class FaultInjectionOutcome(
    val requestId: UUID,
    val robotId: String,
    val kind: String,
    val state: String?,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val fault: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 장애 주입(S4a 스펙 §7, T1·T2). 엔지니어 모드의 조작이며(모드 검사는 컨트롤러의 몫) 호스트를 거쳐 현장에 닿고 조작 기록을
 * 직접 쓴다. 사유는 조작 기록의 사유 칸에 남는다.
 *
 * 종류와 값의 검사(받는 종류 둘, 연결 상태 셋)와 진행 중 태스크 찾기는 현장이 하고 운영 서비스는 그 거부를 그대로 넘긴다.
 * 거부는 조작 기록에 거부로 남고 응답 칸의 본문에 현장의 오류 이름이 있다.
 *
 * 호스트가 안 닿거나 5xx(현장이 안 닿은 503 `CELL_SILENT` 포함)면 «응답 없음» 만 남긴다. 장애 주입은 재조회하지 않는다.
 * 주입의 효과(태스크 상태, 연결 상태)는 다른 조작과 섞여 이 요청의 반영을 가를 칸이 없기 때문이다. 그래서 실행기는 재조회
 * 지연 없이 쓴다.
 */
class FaultOperations(
    private val faults: HostFaults,
    log: OperationLog,
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, Duration.ZERO, json)

    /** [reason] 은 컨트롤러가 앞뒤 공백을 깎고 비어 있지 않음을 본 값이다. */
    fun inject(actor: Actor, robotId: String, kind: String, state: String?, reason: String): FaultInjectionOutcome {
        val request = json.createObjectNode().put("op", OP).put("robotId", robotId).put("kind", kind).put("state", state)
        val body = json.createObjectNode().put("robotId", robotId).put("kind", kind)
        if (state != null) body.put("state", state)
        val ran = runner.run(
            actor, robotId, request, reason,
            accepted = { answer -> if (answer.path("robotId").isTextual) OperationResult.SUCCEEDED else null },
            recheck = { null },
        ) { faults.injectFault(body) }
        return FaultInjectionOutcome(ran.requestId, robotId, kind, state, ran.result, ran.confirmation, ran.body, ran.rejection)
    }

    companion object {
        const val OP = "INJECT_FAULT"
    }
}
