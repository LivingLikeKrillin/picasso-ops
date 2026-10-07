package dev.picasso.ops.service.robots

import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RobotSource
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** 화면 전체 상태(스펙 §7.4). 기체별이 아니라 전역이다. */
enum class RegistryState { OK, REGISTRY_SILENT, REGISTRY_UNAUTHORIZED }

/**
 * `GET /api/robots` 의 답.
 *
 * @param robots 널이면 «모름»(한 번도 읽지 못했다). 빈 목록은 «없음». 둘을 접지 않는다(스펙 §9).
 * @param robotsAsOf [robots] 를 registry 에서 읽은 시각. [registry] 가 [RegistryState.OK] 가 아니면 직전 값의 시각이다.
 */
data class RobotListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val robots: List<RegistryRobot>?,
    val robotsAsOf: Instant?,
)

/** 기체 목록. registry 가 답하지 않으면 목록을 비우지 않고 직전 값과 시각을 보인다(스펙 §9). */
class RobotListService(
    private val source: RobotSource,
    private val siteId: String,
    private val clock: Clock,
) {
    private data class Known(val robots: List<RegistryRobot>, val at: Instant)

    private val last = AtomicReference<Known?>(null)

    fun read(): RobotListView {
        val now = clock.instant()
        return when (val call = source.robots(siteId)) {
            is RegistryCall.Ok -> {
                last.set(Known(call.value, now))
                RobotListView(RegistryState.OK, now, call.value, now)
            }
            is RegistryCall.Silent -> stale(RegistryState.REGISTRY_SILENT, now)
            RegistryCall.Unauthorized -> stale(RegistryState.REGISTRY_UNAUTHORIZED, now)
        }
    }

    private fun stale(state: RegistryState, now: Instant): RobotListView {
        val known = last.get()
        return RobotListView(state, now, known?.robots, known?.at)
    }
}
