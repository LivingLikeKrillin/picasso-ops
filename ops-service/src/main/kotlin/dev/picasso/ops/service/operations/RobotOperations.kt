package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.registry.RobotSource
import dev.picasso.ops.service.registry.RobotWrites
import dev.picasso.ops.service.registry.map
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
 * 기체 선언·퇴역·복귀(스펙 §7.2). 모든 조작을 조작 기록에 남긴다(스펙 §7.1). 기록과 응답 없음 뒤 재조회는
 * [OperationRunner] 가 한다. 여기서는 조작마다 보낼 것과 «반영됨» 의 판정을 정한다.
 */
class RobotOperations(
    private val writes: RobotWrites,
    private val reads: RobotSource,
    log: OperationLog,
    private val siteId: String,
    clock: Clock,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {
    private val runner = OperationRunner(log, clock, requeryDelay, json)

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

    /** 확인 행에는 재조회에서 본 그 기체의 출처와 원장 상태를 남겨, 그 행만 읽어도 판정 근거가 보이게 한다. */
    private fun run(
        op: RobotOp,
        actor: Actor,
        robotId: String,
        request: ObjectNode,
        reason: String?,
        applied: (List<RegistryRobot>) -> Boolean,
        call: () -> RegistryWrite,
    ): OperationOutcome = runner.run(
        actor, "robot $robotId", request, reason,
        rejection = { status, body, at -> Rejections.of(op, status, body, at, robotId) },
        recheck = {
            reads.robots(siteId).map { robots ->
                val seen = robots.firstOrNull { it.robotId == robotId }
                Recheck(applied(robots), seen?.let { json.createObjectNode().put("origin", it.origin).put("status", it.status) })
            }
        },
        call = call,
    )

    private fun statusOf(robots: List<RegistryRobot>, robotId: String): String? = robots.firstOrNull { it.robotId == robotId }?.status
}
