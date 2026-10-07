package dev.picasso.ops.service.robots

import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryRobot
import java.time.Duration
import java.time.Instant

/** 연결 칸(스펙 §7.3). 원장 상태와 합치지 않는다. */
enum class Connection { FRESH, STALE, NO_REPORT }

/**
 * 기체별 상태 막힘 4종과 연결 칸(스펙 §7.3·§7.4). 모두 registry 에서 읽은 값과 그 시각만으로 정한다.
 *
 * [at] 은 목록을 읽은 시각이다. 지금 시각이 아니라 읽은 시각으로 판정해야, registry 가 침묵해 직전 목록을
 * 보일 때 판정도 그 목록과 같은 시점의 것이 된다.
 */
object Blockers {

    const val AWAITING_FIRST_REPORT = "AWAITING_FIRST_REPORT"
    const val REPORT_STALE = "REPORT_STALE"
    const val REPORTING_AFTER_RETIREMENT = "REPORTING_AFTER_RETIREMENT"
    const val UNREGISTERED_ROW = "UNREGISTERED_ROW"

    /** 마지막 보고가 [threshold] 보다 오래면 오래됨이다. 정확히 [threshold] 이면 아직 신선하다. */
    fun connection(robot: RegistryRobot, at: Instant, threshold: Duration): Connection {
        val last = robot.lastReportedAt?.let(Instant::parse) ?: return Connection.NO_REPORT
        return if (Duration.between(last, at) > threshold) Connection.STALE else Connection.FRESH
    }

    fun of(robot: RegistryRobot, at: Instant, threshold: Duration): List<Finding> = buildList {
        fun add(kind: String, observed: String, expected: String, owner: Owner, inScreen: Boolean, action: String) =
            add(Finding(kind, observed, expected, at, owner, inScreen, action, robot.robotId))

        if (robot.status == "CLAIMED" && robot.lastReportedAt == null) {
            add(AWAITING_FIRST_REPORT, "보고 0회", "생존 보고 1회 이상", Owner.SITE, false, "기체·어댑터 기동과 사이트 id 확인")
        }
        if (robot.status == "CONFIRMED" && connection(robot, at, threshold) == Connection.STALE) {
            add(
                REPORT_STALE, "마지막 보고 ${robot.lastReportedAt}", "${threshold.seconds}초 안의 보고",
                Owner.SITE, false, "연결 확인",
            )
        }
        if (robot.reportingAfterRetirement) {
            add(
                REPORTING_AFTER_RETIREMENT, "퇴역 ${robot.retiredAt}, 마지막 보고 ${robot.lastReportedAt}", "퇴역 뒤 보고 없음",
                Owner.OPERATOR, true, "현장에서 기체를 내리거나 복귀",
            )
        }
        if (robot.status == "UNREGISTERED") {
            add(UNREGISTERED_ROW, "출처 없음", "선언 또는 발견으로 들어온 행", Owner.ENGINEER, false, "조사")
        }
    }
}
