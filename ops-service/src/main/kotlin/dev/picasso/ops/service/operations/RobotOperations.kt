package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.registry.RobotSource
import dev.picasso.ops.service.registry.RobotWrites
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 조작 한 번의 답. 화면은 이것으로 결과를 보인다.
 *
 * @param result 처음 남긴 행의 결과. 성공, 거절, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이며, 그때 화면은 반영되었을 수 있으나 확인하지 못했다고 보인다
 * @param rejection registry 가 400/404/409 로 답했을 때의 대응표 판정(스펙 §7.4)
 * @param unauthorized registry 가 401 로 답했다. 조작별이 아니라 화면 전체 상태로 보인다(스펙 §7.4)
 */
data class OperationOutcome(
    val requestId: UUID,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val rejection: Finding?,
    val unauthorized: Boolean,
    val registryStatus: Int?,
)

/**
 * 기체 선언·퇴역·복귀(스펙 §7.2). 모든 조작을 조작 기록에 남긴다(스펙 §7.1).
 *
 * 응답 없음(연결 실패·시간 초과·5xx)은 거절과 다르다. 요청이 닿았는지 모르므로 «응답 없음» 을 남기고 목록을
 * 다시 읽어, 반영 여부를 같은 요청 id 의 새 행으로 붙인다(스펙 §9). 재시도는 하지 않는다. 재시도는 새 요청 id 를
 * 받는 새 조작이며 사람이 정한다.
 *
 * 재조회는 [requeryDelay] 뒤 한 번이다. 시간 초과 직후에는 registry 가 아직 커밋 중일 수 있어 바로 읽으면
 * «반영 안 됨» 을 잘못 남길 수 있다. 재조회도 실패하면 확인 행을 붙이지 않고, 화면이 목록에서 확인하게 한다.
 */
class RobotOperations(
    private val writes: RobotWrites,
    private val reads: RobotSource,
    private val log: OperationLog,
    private val siteId: String,
    private val clock: Clock,
    private val requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {

    fun declare(actor: Actor, robotId: String, serialNumber: String, displayName: String?): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", RobotOp.DECLARE.name)
            .put("robot_id", robotId)
            .put("site", siteId)
            .put("serial_number", serialNumber)
            .put("display_name", displayName)
        // 선언이 반영된 기체는 선언으로 들어와 퇴역하지 않은 기체다. 목록에는 퇴역 기체와 발견된 기체도 있으므로
        // 그 기체가 목록에 있다는 것만으로는 이 선언이 반영됐다고 할 수 없다(registry 는 그 둘을 409 로 거절한다).
        // 이미 선언된 기체에 다시 선언하면 일련번호와 표시 이름의 갱신이므로 그 둘도 같아야 반영된 것이다.
        val declared = { robots: List<RegistryRobot> ->
            robots.firstOrNull { it.robotId == robotId }?.let {
                it.origin == "DECLARED" && it.status != "RETIRED" && it.serialNumber == serialNumber && it.displayName == displayName
            } == true
        }
        return run(RobotOp.DECLARE, actor, robotId, request, reason = null, applied = declared) {
            writes.declare(siteId, robotId, serialNumber, displayName, actor.header())
        }
    }

    fun retire(actor: Actor, robotId: String, reason: String): OperationOutcome {
        val request = json.createObjectNode().put("op", RobotOp.RETIRE.name).put("robot_id", robotId).put("reason", reason)
        return run(RobotOp.RETIRE, actor, robotId, request, reason, applied = { robots -> statusOf(robots, robotId) == "RETIRED" }) {
            writes.retire(robotId, reason, actor.header())
        }
    }

    fun reinstate(actor: Actor, robotId: String): OperationOutcome {
        val request = json.createObjectNode().put("op", RobotOp.REINSTATE.name).put("robot_id", robotId)
        return run(
            RobotOp.REINSTATE, actor, robotId, request, reason = null,
            applied = { robots -> statusOf(robots, robotId).let { it != null && it != "RETIRED" } },
        ) {
            writes.reinstate(robotId, actor.header())
        }
    }

    private fun run(
        op: RobotOp,
        actor: Actor,
        robotId: String,
        request: ObjectNode,
        reason: String?,
        applied: (List<RegistryRobot>) -> Boolean,
        call: () -> RegistryWrite,
    ): OperationOutcome {
        val id = UUID.randomUUID()
        val target = "robot $robotId"
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String?) =
            log.append(id, actor, target, requestJson, reason, result, response)

        val write = call()
        if (write is RegistryWrite.NoResponse) {
            record(OperationResult.NO_RESPONSE, json.writeValueAsString(json.createObjectNode().put("cause", write.cause)))
            return confirm(id, robotId, applied, ::record, status = null)
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
                confirm(id, robotId, applied, ::record, write.status)
            }
            else -> {
                record(OperationResult.REJECTED, response)
                val rejection = Rejections.of(op, write.status, parse(write.body), clock.instant(), robotId)
                OperationOutcome(id, OperationResult.REJECTED, null, rejection, false, write.status)
            }
        }
    }

    /**
     * 응답 없음 뒤 재조회(스펙 §9). 목록을 못 읽으면 행을 붙이지 않고 확인 결과를 널로 둔다.
     * 확인 행의 `registry_response` 에는 재조회에서 본 그 기체의 출처와 원장 상태를 남겨, 그 행만 읽어도 판정 근거가 보이게 한다.
     */
    private fun confirm(
        id: UUID,
        robotId: String,
        applied: (List<RegistryRobot>) -> Boolean,
        record: (OperationResult, String?) -> Unit,
        status: Int?,
    ): OperationOutcome {
        if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
        val robots = reads.robots(siteId)
        if (robots !is RegistryCall.Ok) return OperationOutcome(id, OperationResult.NO_RESPONSE, null, null, false, status)
        val confirmation = if (applied(robots.value)) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
        val seen = robots.value.firstOrNull { it.robotId == robotId }
        val observed = json.createObjectNode()
        if (seen == null) {
            observed.putNull("observed")
        } else {
            observed.putObject("observed").put("origin", seen.origin).put("status", seen.status)
        }
        record(confirmation, json.writeValueAsString(observed))
        return OperationOutcome(id, OperationResult.NO_RESPONSE, confirmation, null, false, status)
    }

    private fun statusOf(robots: List<RegistryRobot>, robotId: String): String? = robots.firstOrNull { it.robotId == robotId }?.status

    private fun parse(body: String): JsonNode? = runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject }

    /** 조작 기록의 `registry_response` 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다. */
    private fun responseJson(write: RegistryWrite.Answered): String {
        val node = json.createObjectNode().put("status", write.status)
        val body = parse(write.body)
        if (body != null) node.set<JsonNode>("body", body) else node.put("body", write.body)
        return json.writeValueAsString(node)
    }
}
