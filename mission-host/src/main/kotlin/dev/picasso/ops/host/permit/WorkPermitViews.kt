package dev.picasso.ops.host.permit

import dev.picasso.middleware.PermitBlock
import dev.picasso.middleware.PermitReason
import dev.picasso.middleware.RobotPermit
import java.time.Instant

/**
 * 기체 하나에 대한 판단 하나(picasso `PermitReason`). [source]·[observedAt] 은 이 층이 정한 까닭(`NOT_COVERED`·`PORT_FAILED`)이면 `null` 이다.
 *
 * @param cause picasso `PermitCause` 의 이름
 * @param subject 막는 주체. 원천이 말하지 않았으면 `null` 이다
 */
data class PermitReasonView(val source: String?, val cause: String, val subject: String?, val resumeBasis: String, val observedAt: Instant?) {
    companion object {
        fun of(reason: PermitReason) = PermitReasonView(reason.source, reason.cause.name, reason.subject, reason.resumeBasis, reason.observedAt)
    }
}

/** 기체 하나의 허가(picasso `RobotPermit`). [permit] 은 `PERMITTED`·`INHIBITED`·`UNKNOWN` 이다. */
data class RobotPermitView(val robotId: String, val permit: String, val reasons: List<PermitReasonView>) {
    companion object {
        fun of(permit: RobotPermit) = RobotPermitView(permit.robotId, permit.permit.name, permit.reasons.map(PermitReasonView::of))
    }
}

/**
 * 실행의 새 로봇 명령을 지금 막고 있는 작업 허가(picasso `Execution.permitBlock`, ADR 52 결정 8). 실행 목록의 칸이다. 작업 응답과
 * 내보내기에는 싣지 않는다(picasso 한계 §15.219).
 *
 * 운영자 보류(물리 상태 `OPERATOR_HOLD`)와 결함 막음(작업 응답 `blockedBy`)과 칸이 다르다. 운영자 판단이나 해제로 풀리지 않고 원천이
 * 허가를 다시 낼 때만 걷힌다.
 *
 * @param withheld 막힌 명령의 종류(picasso `WithheldIssue` 이름): `START`·`REVISION_UPDATE`·`IN_DOUBT_LOOKUP`
 * @param robots 막은 기체와 그 까닭. 통과한 기체는 없다
 * @param since 같은 단위의 같은 종류가 처음 막힌 시각(호스트 시계)
 */
data class PermitBlockView(val unitId: String, val withheld: String, val robots: List<RobotPermitView>, val since: Instant) {
    companion object {
        fun of(block: PermitBlock) = PermitBlockView(block.unitId, block.withheld.name, block.robots.map(RobotPermitView::of), block.since)
    }
}

/** 현장이 선언한 대응의 한 줄. */
data class PermitMappingView(val signal: String, val permittedWhen: String, val robots: List<String>)

/**
 * `GET /host/work-permits` 의 본문. 작업 허가는 안전 계통 상태를 비춘 정보다(ADR 52). 이 본문이 기체를 세우거나 풀지 않는다.
 *
 * @param declared 현장이 작업 허가 대응을 선언했는가. 거짓이면 미들웨어에 허가 포트가 없고 나머지 칸이 비어 있다
 * @param evaluatedAt 판정한 호스트 시각
 * @param session 허가 원천(셀 대역)의 지금 세션. 선언하지 않았으면 `null`
 * @param validFor 판독 하나의 유효 폭(ISO-8601 기간 문자열). 선언하지 않았으면 `null`
 * @param robots 대응이 맡은 기체마다 지금의 허가. 발행 게이트와 같은 규칙으로 판정한다
 */
data class WorkPermitsView(
    val declared: Boolean,
    val evaluatedAt: Instant,
    val session: String?,
    val validFor: String?,
    val mappings: List<PermitMappingView>,
    val robots: List<RobotPermitView>,
)
