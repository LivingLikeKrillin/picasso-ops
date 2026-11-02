package dev.picasso.ops.host.web

import dev.picasso.ops.host.retire.RegistryRetirements
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 퇴역 기체 하나와 그 사유. */
data class RetiredRobotView(val robotId: String, val reason: String)

/**
 * `GET /host/retirements` 의 본문. 칸의 뜻은 `RetirementState` 에 있다. [declared] 가 거짓이면 registry 주소가 비어 미들웨어에
 * 퇴역 포트를 붙이지 않은 호스트이고 나머지 칸은 비어 있다. [retired] 는 기체 id 순이다.
 */
data class RetirementsView(
    val declared: Boolean,
    val retired: List<RetiredRobotView>,
    val readAt: Instant?,
    val lastReadAt: Instant?,
    val readError: String?,
)

/** 미들웨어에 비춘 기체 퇴역의 지금. 읽기만 한다. */
@RestController
class RetirementController(private val retirements: RegistryRetirements) {

    @GetMapping("/host/retirements")
    fun retirements(): RetirementsView {
        val state = retirements.state()
        return RetirementsView(
            declared = retirements.declared,
            retired = state.retired.toSortedMap().map { (robotId, reason) -> RetiredRobotView(robotId, reason) },
            readAt = state.readAt,
            lastReadAt = state.lastReadAt,
            readError = state.readError,
        )
    }
}
