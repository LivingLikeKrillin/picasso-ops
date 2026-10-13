package dev.picasso.ops.host

import dev.picasso.ops.host.store.CancelRow
import dev.picasso.ops.host.store.ResidualHoldClearingRow
import dev.picasso.ops.host.store.ResidualHoldRow
import java.time.Instant

/**
 * 운영자 실행 취소의 결과 이름(P0-b2).
 *
 * - [CANCELED]: 취소를 적고 미들웨어에 걸었다. 실제 중단은 다음 pump 가 하류를 읽고 확정한다(물리 상태 `ABORTED`).
 * - [NOT_CANCELABLE]: 그 실행이 더 돌지 않거나(`PhysicalState.pumps` 가 거짓), 이미 취소를 걸었거나, 실행 일지에 없다.
 * - [NOT_FOUND]: 지금 인스턴스에 그 실행이 없다.
 * - [REFUSED]: 승인자가 사람이 아니다. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다.
 */
enum class CancelResult { CANCELED, NOT_CANCELABLE, NOT_FOUND, REFUSED }

/**
 * `POST /host/executions/{executionId}/cancel` 의 200 본문.
 *
 * @param physicalState 판정한 때의 물리 상태. NOT_FOUND 면 `null` 이다
 * @param detail 결과가 CANCELED 가 아닐 때의 사유. CANCELED 면 `null` 이다
 * @param requestId 요청의 requestId 를 그대로 돌려준다(저장하지 않는다)
 */
data class CancelView(
    val result: CancelResult,
    val executionId: String,
    val physicalState: String?,
    val detail: String?,
    val requestId: String?,
)

/** 실행에 걸린 운영자 취소(실행 목록의 `cancel` 칸). [physicalState] 는 취소를 건 때의 물리 상태, [recordedAt] 은 적은 DB 시각이다. */
data class CancelRecordView(
    val approverId: String,
    val approverKind: String,
    val reason: String,
    val physicalState: String,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: CancelRow) = CancelRecordView(row.approverId, row.approverKind, row.reason, row.physicalState, row.recordedAt)
    }
}

/**
 * 미해소 잔여 파지 표시 하나(P0-b2). 실행이 정착할 때 경로가 로봇인 단위의 마지막 파지 관측이 HOLDING 이었다. 해소할 때까지 실행
 * 호스트 판정이 그 기체를 배정에서 뺀다. [objectRef] 는 관측이 이름을 싣지 않았으면 `null` 이다.
 */
data class ResidualHoldView(
    val markerId: Long,
    val robotId: String,
    val jobOrderId: String,
    val executionId: String,
    val instanceId: String,
    val unitId: String,
    val objectRef: String?,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: ResidualHoldRow) = ResidualHoldView(
            row.markerId, row.robotId, row.jobOrderId, row.executionId, row.instanceId, row.unitId, row.objectRef, row.recordedAt,
        )
    }
}

/** `GET /host/robots/residual-holds` 의 본문. 표시를 적은 순서다. */
data class ResidualHoldsView(val holds: List<ResidualHoldView>)

/** 해소 한 행. [clearedAt] 은 적은 DB 시각이다. */
data class ResidualHoldClearingView(val approverId: String, val approverKind: String, val reason: String, val clearedAt: Instant) {
    companion object {
        fun of(row: ResidualHoldClearingRow) = ResidualHoldClearingView(row.approverId, row.approverKind, row.reason, row.clearedAt)
    }
}

/** `POST /host/robots/{robotId}/residual-holds/{markerId}/clear` 의 200 본문. 해소한 표시와 해소 행이다. */
data class ResidualHoldClearedView(val hold: ResidualHoldView, val clearing: ResidualHoldClearingView, val requestId: String?)

/** 잔여 파지 표시 해소의 결과. REST 는 [Cleared] 만 200 이고 나머지는 4xx 다. */
sealed interface ClearOutcome {
    data class Cleared(val view: ResidualHoldClearedView) : ClearOutcome

    /** 그 기체에 그 표시가 없다. */
    data object NotFound : ClearOutcome

    /** 이미 해소했다. [clearing] 이 앞선 해소다. */
    data class AlreadyCleared(val clearing: ResidualHoldClearingView) : ClearOutcome

    /** 승인자가 사람이 아니다. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다. */
    data class Refused(val reason: String) : ClearOutcome
}
