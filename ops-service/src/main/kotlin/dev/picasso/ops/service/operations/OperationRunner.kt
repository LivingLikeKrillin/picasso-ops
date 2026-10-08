package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryWrite
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 응답 없음 뒤 재조회의 판정.
 *
 * @param applied 이 조작이 반영됐는가
 * @param observed 재조회에서 본 대상의 모습. 확인 행의 `target_response` 에 `observed` 로 남는다. 대상이 없으면 널이다
 */
data class Recheck(val applied: Boolean, val observed: JsonNode?)

/**
 * 조작 한 번을 registry 에 보내고 조작 기록에 남긴다(스펙 §7.1·§9). 기체 조작과 어댑터 조작이 같이 쓴다.
 *
 * 응답 없음(연결 실패·시간 초과·5xx)은 거절과 다르다. 요청이 닿았는지 모르므로 «응답 없음» 을 남기고 다시 읽어,
 * 반영 여부를 같은 요청 id 의 새 행으로 붙인다. 재시도는 하지 않는다. 재시도는 새 요청 id 를 받는 새 조작이며
 * 사람이 정한다.
 *
 * 재조회는 [requeryDelay] 뒤 한 번이다. 시간 초과 직후에는 registry 가 아직 커밋 중일 수 있어 바로 읽으면
 * «반영 안 됨» 을 잘못 남길 수 있다. 재조회도 실패하면 확인 행을 붙이지 않고, 화면이 목록에서 확인하게 한다.
 */
class OperationRunner(
    private val log: OperationLog,
    private val clock: Clock,
    private val requeryDelay: Duration,
    private val json: ObjectMapper,
) {

    /**
     * @param target 조작 기록의 대상 칸. 예: `robot humanoid-01`
     * @param rejection registry 가 400/404/409 등으로 답했을 때 대응표로 옮긴다(스펙 §7.4)
     * @param recheck 응답 없음 뒤 다시 읽어 반영 여부를 가른다. 읽지 못하면 [RegistryCall.Ok] 가 아닌 값을 낸다
     */
    fun run(
        actor: Actor,
        target: String,
        request: ObjectNode,
        reason: String?,
        rejection: (status: Int, body: JsonNode?, checkedAt: Instant) -> Finding,
        recheck: () -> RegistryCall<Recheck>,
        call: () -> RegistryWrite,
    ): OperationOutcome {
        val id = UUID.randomUUID()
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String?) =
            log.append(id, actor, target, requestJson, reason, result, response)

        val write = call()
        if (write is RegistryWrite.NoResponse) {
            record(OperationResult.NO_RESPONSE, json.writeValueAsString(json.createObjectNode().put("cause", write.cause)))
            return confirm(id, recheck, ::record, status = null)
        }
        write as RegistryWrite.Answered
        val response = responseJson(write)
        return when {
            write.status in 200..299 -> {
                record(OperationResult.SUCCEEDED, response)
                OperationOutcome(id, OperationResult.SUCCEEDED, null, null, false, write.status)
            }
            write.status == 401 -> {
                record(OperationResult.REJECTED, response)
                OperationOutcome(id, OperationResult.REJECTED, null, null, true, write.status)
            }
            // registry 가 응답했지만 반영 여부를 알 수 없다. 응답 없음과 같이 다룬다(스펙 §7.4).
            write.status >= 500 -> {
                record(OperationResult.NO_RESPONSE, response)
                confirm(id, recheck, ::record, write.status)
            }
            else -> {
                record(OperationResult.REJECTED, response)
                val finding = rejection(write.status, parse(write.body), clock.instant())
                OperationOutcome(id, OperationResult.REJECTED, null, finding, false, write.status)
            }
        }
    }

    /** 응답 없음 뒤 재조회(스펙 §9). 읽지 못하면 행을 붙이지 않고 확인 결과를 널로 둔다. */
    private fun confirm(
        id: UUID,
        recheck: () -> RegistryCall<Recheck>,
        record: (OperationResult, String?) -> Unit,
        status: Int?,
    ): OperationOutcome {
        if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
        val seen = recheck()
        if (seen !is RegistryCall.Ok) return OperationOutcome(id, OperationResult.NO_RESPONSE, null, null, false, status)
        val confirmation = if (seen.value.applied) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
        val observed = json.createObjectNode()
        observed.set<JsonNode>("observed", seen.value.observed ?: json.nullNode())
        record(confirmation, json.writeValueAsString(observed))
        return OperationOutcome(id, OperationResult.NO_RESPONSE, confirmation, null, false, status)
    }

    private fun parse(body: String): JsonNode? = runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject }

    /** 조작 기록의 `target_response` 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다. */
    private fun responseJson(write: RegistryWrite.Answered): String {
        val node = json.createObjectNode().put("status", write.status)
        val body = parse(write.body)
        if (body != null) node.set<JsonNode>("body", body) else node.put("body", write.body)
        return json.writeValueAsString(node)
    }
}
