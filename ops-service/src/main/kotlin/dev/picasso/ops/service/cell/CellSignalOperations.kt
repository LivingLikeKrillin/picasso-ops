package dev.picasso.ops.service.cell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSignals
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import java.time.Duration
import java.util.UUID

/**
 * 신호 조작의 200 응답(S3b 스펙 §7). 작업 지시 제출·임무 조작의 응답과 같은 자리에 같은 칸을 둔다.
 *
 * @param name 조작 기록의 대상(신호 이름)
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param signal 바뀐 신호(현장 셀 대역의 200 본문, S3b JSON 계약 §2). 성공일 때만 있다
 * @param rejection 현장 셀 대역이나 호스트가 4xx 로 막았을 때만 있다(안전 신호 쓰기 403 `SAFETY_SIGNAL_READ_ONLY`,
 *   모르는 신호 404 `UNKNOWN_SIGNAL`, 틀린 값 400 `SIGNAL_VALUE_INVALID`)
 */
data class SignalWriteOutcome(
    val requestId: UUID,
    val name: String,
    val value: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val signal: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 셀 대역 신호 조작(S3b 스펙 §7, 결정 3). 사람이 PLC 역할을 하는 정상 조작이며 운영자·엔지니어 두 모드가 한다(모드 검사는
 * 컨트롤러의 몫). 호스트를 거쳐 현장 셀 대역에 쓰고 조작 기록을 직접 쓴다.
 *
 * 값 검사(BOOLEAN 은 `"true"`·`"false"`)와 안전 신호 쓰기 거부는 현장 셀 대역이 하고 운영 서비스는 그 거부를 그대로
 * 넘긴다(ADR 32: 안전 계통은 소프트웨어 계층에 통합하지 않는다). 거부는 조작 기록에 거부로 남는다.
 *
 * 호스트가 안 닿거나 5xx(현장이 안 닿은 503 `CELL_SILENT` 포함)면 «응답 없음» 을 남기고 `GET /host/cell` 을 다시 읽어
 * 그 신호의 값이 보낸 값과 같으면 반영됨, 다르면 반영 안 됨을 붙인다(T9). 셀 대역 스냅숏이나 그 신호 목록을 못 읽으면 확인
 * 행을 붙이지 않는다. 스냅숏의 신호 목록에 그 이름이 없으면 현장이 모르는 신호라 반영 안 됨이다.
 *
 * 대조는 값만 본다. 같은 값을 다시 쓴 조작이 현장에 닿지 않았어도 반영됨으로 보인다. 신호 조작은 값을 그 값으로 두는
 * 조작이라, 재조회 시점에 값이 그 값이면 조작의 뜻은 이루어진 것이다. 바뀐 값은 호스트의 다음 pump(250ms 주기)부터
 * 보이므로 [requeryDelay] 는 그보다 길어야 한다.
 */
class CellSignalOperations(
    private val signals: HostSignals,
    private val reads: HostReads,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    fun write(actor: Actor, name: String, value: String): SignalWriteOutcome {
        val request = json.createObjectNode().put("op", OP).put("name", name).put("value", value)
        val ran = runner.run(
            actor, name, request, null,
            accepted = { body -> if (body.path("name").isTextual) OperationResult.SUCCEEDED else null },
            recheck = { recheck(name, value) },
        ) { signals.writeSignal(name, value) }
        return SignalWriteOutcome(ran.requestId, name, value, ran.result, ran.confirmation, ran.body, ran.rejection)
    }

    private fun recheck(name: String, value: String): HostRecheck? {
        val body = (reads.cell() as? HostCall.Ok)?.value ?: return null
        val listed = body.path("cell").path("signals").takeIf { it.isArray } ?: return null
        val signal = listed.firstOrNull { it.path("name").asText() == name } ?: return HostRecheck(false, null)
        return HostRecheck(signal.path("value").takeIf { it.isTextual }?.asText() == value, signal)
    }

    companion object {
        const val OP = "WRITE_CELL_SIGNAL"
    }
}
