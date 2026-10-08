package dev.picasso.ops.service.robots

import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.registry.RobotSource
import dev.picasso.ops.service.registry.TokenProbe
import dev.picasso.ops.service.settings.SettingsSource
import dev.picasso.ops.service.settings.SiteSettingsValues
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** 화면 전체 상태(스펙 §7.4). 기체별이 아니라 전역이다. */
enum class RegistryState { OK, REGISTRY_SILENT, REGISTRY_UNAUTHORIZED }

/**
 * 기체 한 대. 원장 상태는 [robot] 의 `status` 그대로이고, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3·§7.4).
 *
 * [binding]·[commissioning]·[software] 는 P2·S1d 스펙 §8.1 의 칸이다. 시운전 출처([CommissioningSource])를 붙이지 않은
 * 목록에서는 셋 다 널이다.
 *
 * @param binding 이 기체의 활성 바인딩. 없으면 널
 * @param software 소프트웨어 대조. 시운전을 막지 않고 보이기만 한다
 */
data class RobotView(
    val robot: RegistryRobot,
    val connection: Connection,
    val blockers: List<Finding>,
    val binding: RegistryBinding? = null,
    val commissioning: Commissioning? = null,
    val software: RegistrySoftware? = null,
)

/**
 * `GET /api/robots` 의 답.
 *
 * @param robots 널이면 «모름»(한 번도 읽지 못했다). 빈 목록은 «없음». 둘을 접지 않는다(스펙 §9).
 * @param robotsAsOf [robots] 를 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 값이다.
 * @param settingsVersion [robots] 의 연결 칸과 막힘을 판정한 현장 설정 버전(S2 스펙 §6.4). [robots] 가 널이면 널
 * @param connectionThresholdSeconds 그 버전의 연결 기준 시간(초)
 */
data class RobotListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val robots: List<RobotView>?,
    val robotsAsOf: Instant?,
    val settingsVersion: Long? = null,
    val connectionThresholdSeconds: Long? = null,
)

/**
 * 기체 목록. registry 가 답하지 않으면 목록을 비우지 않고 직전 값과 시각을 보인다(스펙 §9).
 *
 * 목록 읽기(`/diag/robots`)는 운영자 토큰 관문 밖이라 401 이 오지 않는다. 그래서 목록을 읽을 때마다
 * [token] 으로 관문 안의 읽기를 함께 불러, 토큰 불일치가 다음 폴링에 `OK` 로 덮이지 않게 한다.
 *
 * [commissioning] 을 붙이면 기체 → 바인딩 → 소프트웨어 대조를 함께 읽고 셋 다 읽혀야 새 값으로 바꾼다(P2·S1d 스펙
 * §8.1). 하나라도 못 읽으면 셋 다 직전 값이다. 다른 시각에 읽은 것을 섞으면 서로 맞지 않는 행이 보일 수 있다.
 *
 * 연결 칸의 기준 시간은 읽기마다 [settings] 에서 함께 읽는다(S2 스펙 §6.4). 스냅샷은 그 읽기의 현장 설정 버전을 쥐고,
 * 연결 칸과 막힘은 늘 그 버전 하나로 판정된다. 현장 설정을 못 읽으면 예외가 그대로 나가고 스냅샷은 바뀌지 않는다.
 * 기본값으로 대신 판정하면 어느 버전으로 판정했는지 말할 수 없다.
 */
class RobotListService(
    private val source: RobotSource,
    private val token: TokenProbe,
    private val siteId: String,
    private val clock: Clock,
    private val settings: SettingsSource,
    private val commissioning: CommissioningSource? = null,
) {
    private data class Known(
        val robots: List<RegistryRobot>,
        val bindings: List<RegistryBinding>?,
        val software: List<RegistrySoftware>?,
        val at: Instant,
        val settings: SiteSettingsValues,
    )

    private val last = AtomicReference<Known?>(null)

    fun read(): RobotListView {
        val now = clock.instant()
        val current = settings.current()
        val robots = source.robots(siteId)
        if (robots !is RegistryCall.Ok) {
            val state = if (robots == RegistryCall.Unauthorized) RegistryState.REGISTRY_UNAUTHORIZED else RegistryState.REGISTRY_SILENT
            return view(state, now, last.get())
        }
        val bindings = commissioning?.bindings(siteId)
        val software = if (bindings is RegistryCall.Ok) commissioning?.software(siteId) else null
        if (commissioning != null && (bindings !is RegistryCall.Ok || software !is RegistryCall.Ok)) {
            val state = if (bindings == RegistryCall.Unauthorized || software == RegistryCall.Unauthorized) {
                RegistryState.REGISTRY_UNAUTHORIZED
            } else {
                RegistryState.REGISTRY_SILENT
            }
            return view(state, now, last.get())
        }
        val fresh = Known(
            robots.value,
            (bindings as? RegistryCall.Ok)?.value,
            (software as? RegistryCall.Ok)?.value,
            now,
            current,
        )
        // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
        val known = last.updateAndGet { prev -> if (prev != null && prev.at.isAfter(now)) prev else fresh }!!
        val state = when (token.operatorToken()) {
            RegistryCall.Unauthorized -> RegistryState.REGISTRY_UNAUTHORIZED
            else -> RegistryState.OK
        }
        // 더 새 값을 남겼으면 확인 시각도 그 시각으로 맞춘다. 화면은 두 시각이 다르면 직전 값으로 보인다.
        return view(state, maxOf(now, known.at), known)
    }

    private fun view(state: RegistryState, now: Instant, known: Known?): RobotListView =
        RobotListView(
            registry = state,
            checkedAt = now,
            robots = known?.robots?.map { robot -> robotView(robot, known) },
            robotsAsOf = known?.at,
            settingsVersion = known?.settings?.version,
            connectionThresholdSeconds = known?.settings?.connectionThreshold?.seconds,
        )

    private fun robotView(robot: RegistryRobot, known: Known): RobotView {
        val threshold = known.settings.connectionThreshold
        val connection = Blockers.connection(robot, known.at, threshold)
        val base = Blockers.of(robot, known.at, threshold, known.settings.version)
        if (known.bindings == null) return RobotView(robot, connection, base)
        val binding = known.bindings.firstOrNull { it.robotId == robot.robotId && it.active }
        return RobotView(
            robot = robot,
            connection = connection,
            blockers = base + CommissioningJudge.blockers(robot, binding, known.at),
            binding = binding,
            commissioning = CommissioningJudge.of(robot, binding),
            software = known.software?.firstOrNull { it.robotId == robot.robotId },
        )
    }
}
