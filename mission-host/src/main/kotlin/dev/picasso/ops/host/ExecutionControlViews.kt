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

/**
 * 운영자 결함 감수의 결과 이름(결함 감수·조치 승인 노출 설계 제안 §2.1).
 *
 * - [RELEASED]: 감수 행을 적고 picasso `release` 가 막는 결함 전부를 감수했다. 실행은 `RUNNING` 이고 다음 pump 가 다음 단위를 낸다.
 * - [NOT_BLOCKED]: 그 실행에 막는 결함이 없다. 작업 허가만 막고 있거나(감수로 풀리지 않는다), 정착했거나(이 인스턴스가 놓은 실행 포함),
 *   결함 없이 돈다.
 * - [BLOCK_CHANGED]: 운영자가 본 결함과 지금 막는 결함이 다르다. 다시 읽고 판단한다.
 * - [NOT_FOUND]: 지금 인스턴스에 그 실행이 없다(메모리에도 정착 본문에도).
 * - [REFUSED]: 승인자가 사람이 아니다. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다.
 *
 * RELEASED 밖의 넷은 아무것도 바꾸지 않는다.
 */
enum class ReleaseResult { RELEASED, NOT_BLOCKED, BLOCK_CHANGED, NOT_FOUND, REFUSED }

/**
 * `POST /host/executions/{executionId}/release` 의 200 본문.
 *
 * @param physicalState 응답할 때의 물리 상태. RELEASED 면 감수 뒤(`RUNNING`), 이 인스턴스가 놓은 실행이면 마지막 정착 본문의 것, NOT_FOUND·
 *   REFUSED 면 `null` 이다
 * @param detail RELEASED 가 아닐 때의 사유. RELEASED 면 작업 허가가 아직 막고 있을 때만 그 사실이고 아니면 `null` 이다
 * @param requestId 요청의 requestId 를 그대로 돌려준다. RELEASED 면 감수 행에 적혀 있다
 */
data class ReleaseView(
    val result: ReleaseResult,
    val executionId: String,
    val physicalState: String?,
    val detail: String?,
    val requestId: String?,
)

/**
 * 운영자가 본 막는 결함 하나(감수 요청의 `sawFaults` 원소). 지금 막는 결함과 오류 종류와 참조로 대조한다. 참조의 순서는 보지 않는다.
 * 열쇠 문자열은 picasso 의 사설 형식이라 다시 만들지 않는다.
 */
data class FaultSighting(val errorType: String, val references: List<FaultReferenceView>)

/**
 * 실행에 건 운영자 결함 감수 하나(실행 목록의 `releases` 칸). 작업 지시 id 로 읽으므로 재기동 전 인스턴스가 적은 감수도 든다.
 *
 * @param faults 감수한 막는 결함 전부(감수할 때의 [FaultDetailView])
 * @param recordedAt 적은 DB 시각
 */
data class FaultReleaseView(
    val requestId: String?,
    val approverId: String,
    val reason: String,
    val faults: List<FaultDetailView>,
    val recordedAt: Instant,
)
