package dev.picasso.ops.service.robots

import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryRobot
import java.time.Instant

/** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다 — 시운전은 갖춘 조건이고 연결은 지금의 보고다. */
enum class CommissioningState { COMPLETE, INCOMPLETE, RETIRED }

/**
 * 시운전 판정 한 건. 세 조건(결정 6)을 따로 들고 있어 화면의 «시운전» 카드가 무엇이 빠졌는지 체크 목록으로 보인다.
 *
 * @param ledgerConfirmed 원장 상태가 `CONFIRMED` 다(퇴역이면 거짓). 근거는 `/diag/robots`
 * @param bound 활성 바인딩이 있다. 근거는 `/diag/bindings`
 * @param siteNamesReady 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 다. 근거는 `/diag/bindings` 의 명칭 상태
 */
data class Commissioning(
    val state: CommissioningState,
    val ledgerConfirmed: Boolean,
    val bound: Boolean,
    val siteNamesReady: Boolean,
)

/**
 * 시운전 판정과 그 막힘 4종(P2·S1d 스펙 §8.5). 모두 registry 에서 읽은 값과 그 시각만으로 정한다.
 *
 * 소프트웨어 대조와 어댑터 적합성은 판정에 넣지 않는다(결정 6). 막지 않고 보이기만 한다.
 */
object CommissioningJudge {

    const val UNBOUND = "UNBOUND"
    const val SITE_NAMES_UNREGISTERED = "SITE_NAMES_UNREGISTERED"
    const val SITE_NAMES_UNANSWERED = "SITE_NAMES_UNANSWERED"
    const val SITE_NAMES_CONTRADICTED = "SITE_NAMES_CONTRADICTED"

    /** 명칭이 «다 됐다» 로 읽히는 상태. `NOT_REQUIRED` 는 이 기체의 스킬이 명칭을 쓰지 않는 것이다. */
    private val READY = setOf("CONFIRMED", "NOT_REQUIRED")

    fun of(robot: RegistryRobot, binding: RegistryBinding?): Commissioning {
        val ledger = robot.status == "CONFIRMED"
        val names = binding != null && binding.siteNames in READY
        val state = when {
            robot.status == "RETIRED" -> CommissioningState.RETIRED
            ledger && binding != null && names -> CommissioningState.COMPLETE
            else -> CommissioningState.INCOMPLETE
        }
        return Commissioning(state, ledger, binding != null, names)
    }

    /** 퇴역 기체에는 막힘이 없다. 퇴역은 시운전에서 빠지는 것이다. */
    fun blockers(robot: RegistryRobot, binding: RegistryBinding?, at: Instant): List<Finding> {
        if (robot.status == "RETIRED") return emptyList()
        fun finding(kind: String, observed: String, expected: String, owner: Owner, inScreen: Boolean, action: String) =
            Finding(kind, observed, expected, at, owner, inScreen, action, robot.robotId)

        if (binding == null) {
            return listOf(
                finding(UNBOUND, "활성 바인딩 없음", "빌드와 활성 개정판의 바인딩", Owner.ENGINEER, true, "빌드와 활성 개정판을 골라 바인딩"),
            )
        }
        val keys = binding.siteNameKeys.joinToString(", ")
        return when (binding.siteNames) {
            "UNREGISTERED" -> listOf(
                finding(
                    SITE_NAMES_UNREGISTERED, "명칭 기록 없음(요구 키 $keys)", "명칭 등록 기록",
                    Owner.ENGINEER, true, "현장 티칭을 확인한 뒤 명칭 기록",
                ),
            )
            "CLAIMED" -> listOf(
                finding(
                    SITE_NAMES_UNANSWERED, "기록 ${binding.siteNamesRegisteredAt}, 기체 답 없음", "기체가 아는 명칭 1개 이상",
                    Owner.SITE, false, "기체 보고 확인",
                ),
            )
            "CONTRADICTED" -> listOf(
                if (binding.siteNamesUnsupported == true) {
                    finding(
                        SITE_NAMES_CONTRADICTED, "기록 ${binding.siteNamesRegisteredAt}, 기체가 명칭을 지원하지 않음",
                        "기체가 아는 명칭 1개 이상", Owner.ENGINEER, true, "그 기종의 프로파일과 명칭 기록 확인",
                    )
                } else {
                    finding(
                        SITE_NAMES_CONTRADICTED, "기록 ${binding.siteNamesRegisteredAt}, 기체가 아는 명칭 ${binding.siteNamesCount ?: 0}개",
                        "기체가 아는 명칭 1개 이상", Owner.SITE, false, "현장에서 명칭 티칭을 다시. 다음 보고로 풀림",
                    )
                },
            )
            else -> emptyList()
        }
    }
}
