package dev.picasso.ops.host

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.TaskState
import dev.picasso.middleware.ActiveMission
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.JudgmentRecordWire
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.OperatorDecision
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.RemedyOutcome
import dev.picasso.middleware.Route
import dev.picasso.middleware.ResolveOutcome
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.SiteTimingsSource
import dev.picasso.middleware.SourceUnobserved
import dev.picasso.middleware.Unassigned
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.Verification
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellSnapshot
import dev.picasso.ops.host.cell.SourceConfirmation
import dev.picasso.ops.host.mission.SiteInputs
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.ops.host.permit.CellWorkPermits
import dev.picasso.ops.host.permit.PermitBlockView
import dev.picasso.ops.host.permit.PermitMappingView
import dev.picasso.ops.host.permit.RobotPermitView
import dev.picasso.ops.host.permit.WorkPermitsView
import dev.picasso.ops.host.web.BadRequest
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.ops.host.store.CancelRow
import dev.picasso.ops.host.store.CopyResolutionRow
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.IncidentCopyRow
import dev.picasso.ops.host.store.JournalRow
import dev.picasso.ops.host.store.JournalEventKind
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.store.ResponseContent
import dev.picasso.ops.host.store.ResponseDisposition
import dev.picasso.ops.host.store.ResponseLogRow
import dev.picasso.ops.host.store.ResidualHoldRow
import dev.picasso.ops.host.store.SettledRow
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 스킬 적합 판정(S3a 스펙 §7.6). [UNKNOWN] 은 기체 케이퍼빌리티를 못 물어봤다는 뜻이고 적합이 아니다. */
enum class SkillFit { FIT, MISSING, UNKNOWN }

/**
 * 기체 하나의 호스트 판정(T3). 운영 서비스가 시운전·연결 판정과 합쳐 배정 가능을 낸다.
 *
 * @param missingSkills [skillFit] 이 [SkillFit.MISSING] 일 때 모자란 스킬. 그 밖에는 비어 있다.
 * @param runningExecutionId 이 기체를 쥔 실행(picasso `PhysicalState.occupiesRobot`). 정착하지 않은 실행과, 리비전으로 다시 돌 수 있는
 *   부분 완료(`PARTIAL`) 실행이다. 없으면 `null`.
 * @param passed 스킬 적합이고 도는 실행이 없으며 판정 이유가 하나도 없다(미해소 잔여 파지 표시 포함, P0-b2).
 * @param reasons 통과하지 못한 이유. 화면에 그대로 보인다.
 */
data class HostEligibility(
    val robotId: String,
    val skillFit: SkillFit,
    val missingSkills: List<String>,
    val runningExecutionId: String?,
    val passed: Boolean,
    val reasons: List<String>,
    val requiredSkills: List<String>? = null,
    val availableSkills: List<String>? = null,
)

/** 미들웨어 `Submission` 넷의 이름(S3a 스펙 §7.6). */
enum class SubmitResult { ACCEPTED, IDEMPOTENT, REJECTED, UNASSIGNED }

/**
 * `assign` 이 기체마다 낸 미배정 사유.
 *
 * 가운데 두 칸은 출발 결품으로 떨어진 기체에만 값이 있다(picasso `Unassigned.sourceMissing`). 다른 사유로 떨어진 기체는 둘 다 `null` 이다.
 * 모양은 REJECTED 의 같은 이름 칸([SubmitOutcome.alternativeLocations]·[SubmitOutcome.sourceMissing])과 같다.
 *
 * 마지막 칸은 출발 미관측으로 떨어진 기체에만 값이 있다(picasso `Unassigned.sourceUnobserved`). 출발 결품과 접지 않는다. 한 기체는 둘 중
 * 많아야 하나를 든다(관문이 첫 거절에서 멈춘다).
 *
 * @param alternativeLocations 그 자재를 든 다른 자리. 빈 목록은 «든 자리가 없다» 는 응답이고 `null` 은 셀이 응답하지 않았거나 출발 결품이
 *   아니다. 둘을 접지 않는다. 어느 `null` 인지는 [sourceMissing] 으로 가른다. 출발 미관측이면 대체 자리를 계산하지 않으므로 `null` 이다.
 * @param sourceMissing 이 기체가 출발 결품으로 떨어졌으면 그 자리와 관측. 아니면 `null` 이다.
 * @param sourceUnobserved 이 기체가 출발 미관측으로 떨어졌으면 그 자리와 설비가 말한 것. 아니면 `null` 이다.
 */
data class Refusal(
    val robotId: String,
    val reason: String,
    val alternativeLocations: List<String>? = null,
    val sourceMissing: SourceMissingView? = null,
    val sourceUnobserved: SourceUnobservedView? = null,
) {
    companion object {
        /**
         * [missing] 은 이 기체의 출발 결품 답이고 [unobserved] 는 출발 미관측 답이다. 대체 자리 목록은 결품 답의 값을 그대로 쓴다.
         */
        fun of(robotId: String, reason: String, missing: RemedyOutcome.SourceMissing?, unobserved: SourceUnobserved? = null) = Refusal(
            robotId, reason,
            alternativeLocations = missing?.alternatives,
            sourceMissing = missing?.let { SourceMissingView(it.material, it.source, it.observed) },
            sourceUnobserved = unobserved?.let(SourceUnobservedView::of),
        )
    }
}

/**
 * 제출의 결과.
 *
 * 뒤 다섯 칸([remedy]·[remedyWithheld]·[alternativeLocations]·[sourceMissing]·[sourceUnobserved])은 미들웨어 `Submission.Rejected` 의 나머지 칸이다.
 * 미들웨어가 거부한 REJECTED 일 때만 값이 있고 그 밖에는 `null`·`false` 다. 앞 여섯 칸의 모양은 그대로다.
 *
 * @param executionId·robotId ACCEPTED·IDEMPOTENT 일 때만 있다.
 * @param rejectionReason REJECTED 일 때만 있다.
 * @param refusals UNASSIGNED 일 때 `assign` 이 기체마다 낸 사유. 호스트가 먼저 뺀 기체는 여기 없고 [excluded] 에 있다. 출발 결품·출발
 *   미관측으로 떨어진 기체의 대체 자리와 출발 자리는 [Refusal] 의 칸에 있고, UNASSIGNED 의 [alternativeLocations]·[sourceMissing]·
 *   [sourceUnobserved] 는 `null` 이다.
 * @param excluded 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체와 그 판정.
 * @param remedy 깨진 전제를 채우는 조치 탐색의 응답. `FOUND` 는 단계가 있고 `NONE` 은 못 찾은 종류와 못 채운 조건이 있다.
 *   탐색하지 않았으면 `null` 이다. 보류했으면 [remedyWithheld] 가 참이고 이것은 `null` 이다(보류한 단계는 싣지 않는다).
 * @param remedyWithheld 조치 시퀀스를 계산했으나 사람이 먼저 진단하도록 보류했다. «대안 없음» 과 다르다.
 * @param alternativeLocations 출발 자리가 비었을 때 그 자재를 든 다른 자리. 빈 목록은 «든 자리가 없다» 는 응답이고 `null` 은 셀이
 *   응답하지 않았거나 출발 결품 거부가 아니다. 미들웨어가 둘을 가르지 않으므로 [sourceMissing] 으로 가른다.
 * @param sourceMissing 이 거부가 출발 결품이면 그 자리와 관측. 이번 제출로 미들웨어 조치 탐색 기록에 적힌 `SOURCE_MISSING` 에서
 *   옮긴다. 아니면 `null` 이다.
 * @param sourceUnobserved 이 거부가 출발 미관측이면 그 자리와 설비가 말한 것(picasso `Submission.Rejected.sourceUnobserved`). 아니면
 *   `null` 이다. 조치 탐색 기록에는 적히지 않으므로 거부 값에서 바로 옮긴다. 출발 결품이 아니므로 그때 [alternativeLocations] 는 `null` 이다.
 */
data class SubmitOutcome(
    val result: SubmitResult,
    val executionId: String?,
    val robotId: String?,
    val rejectionReason: String?,
    val refusals: List<Refusal>,
    val excluded: List<HostEligibility>,
    val remedy: RemedyView? = null,
    val remedyWithheld: Boolean = false,
    val alternativeLocations: List<String>? = null,
    val sourceMissing: SourceMissingView? = null,
    val sourceUnobserved: SourceUnobservedView? = null,
) {
    companion object {
        /**
         * 미들웨어 거부를 옮긴다. [noted] 는 이 제출로 조치 탐색 기록에 새로 적힌 응답이다. 거기서는 출발 결품 하위 범주만 읽는다.
         * 대체 자리 목록은 [rejected] 의 값을 그대로 쓴다. `null` 과 빈 목록을 접지 않는다.
         */
        fun rejected(rejected: Middleware.Submission.Rejected, noted: RemedyOutcome?, excluded: List<HostEligibility>) = SubmitOutcome(
            SubmitResult.REJECTED, null, null, rejected.reason, emptyList(), excluded,
            remedy = rejected.remedy?.let(RemedyView::of),
            remedyWithheld = rejected.remedyWithheld,
            alternativeLocations = rejected.alternativeLocations,
            sourceMissing = (noted as? RemedyOutcome.SourceMissing)?.let { SourceMissingView(it.material, it.source, it.observed) },
            sourceUnobserved = rejected.sourceUnobserved?.let(SourceUnobservedView::of),
        )
    }
}

/**
 * 조치 탐색의 응답(picasso `Remedy`). `kind` 가 하위 범주이고 나머지는 그 하위 범주의 칸이다. 단계와 조건의 칸 이름은 picasso 조치
 * 탐색 기록 내보내기(`FOUND`·`NONE` 줄)와 같다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(JsonSubTypes.Type(RemedyView.Found::class, name = "FOUND"), JsonSubTypes.Type(RemedyView.None::class, name = "NONE"))
sealed interface RemedyView {
    /** 이 단계들을 하면 그 스킬을 보낼 수 있다. 승인은 미들웨어의 제안 승인으로만 한다. */
    data class Found(val steps: List<RemedyStepView>) : RemedyView

    /** 못 찾았다. [cause] 는 `NO_CAPABILITY`(이 기체로는 없다)·`DEPTH_LIMIT`(상한 안에서 못 찾았다)다. */
    data class None(val cause: String, val unmet: List<UnmetView>) : RemedyView

    companion object {
        fun of(remedy: Remedy): RemedyView = when (remedy) {
            is Remedy.Found -> Found(remedy.steps.map(RemedyStepView::of))
            is Remedy.None -> None(remedy.cause.name, remedy.unmet.map(UnmetView::of))
        }
    }
}

/** 조치 시퀀스의 단계 하나. 파지 값은 계약 `HoldKind` 의 이름이다. */
data class RemedyStepView(val skillType: String, val requires: List<RequiredHoldView>, val expectedHold: String, val onFailureHold: String) {
    companion object {
        fun of(step: RemedyStep) = RemedyStepView(
            step.skillType, step.requires.map { RequiredHoldView(it.subject.name, it.requires.name) }, step.expectedHold.name, step.onFailureHold.name,
        )
    }
}

/** 단계의 전제 하나(계약 `Precondition`). */
data class RequiredHoldView(val subject: String, val requires: String)

/** 끝내 못 채운 조건 하나(picasso `PreconditionViolation`). */
data class UnmetView(val subject: String, val required: String, val observed: String, val detail: String) {
    companion object {
        fun of(v: PreconditionViolation) = UnmetView(v.subject.name, v.required.name, v.observed.name, v.detail)
    }
}

/**
 * 출발 결품 거부의 자리(picasso `RemedyOutcome.SourceMissing` 의 앞 세 칸). [observed] 가 `null` 이면 빈 자리이고 아니면 거기 있던
 * 다른 식별 정보다. [material] 은 단위가 자재를 지정하지 않았으면 `null` 이다. 대체 자리는 REJECTED 이면 [SubmitOutcome.alternativeLocations],
 * UNASSIGNED 이면 기체마다 [Refusal.alternativeLocations] 에 있다.
 */
data class SourceMissingView(val material: String?, val source: String, val observed: String?)

/**
 * 출발 미관측 거부의 자리(picasso `SourceUnobserved`). 관측을 요구한 단위의 출발 자리를 설비가 확인해 주지 않았다. **결품이 아니다** —
 * 다음 행동은 주문을 고치는 것이 아니라 사람의 확인이고, 그 확인은 다음 제출의 [SourceConfirmation] 으로 셀 관측이 되어 들어온다.
 *
 * @param material 그 자리에 있어야 할 것. 단위가 자재를 지정하지 않았으면 `null` 이다
 * @param occupied 설비가 말한 것. `null` 이면 말이 없었고, 참이면 «있다» 고만 하고 무엇인지 말하지 않았다. 둘을 접지 않는다
 */
data class SourceUnobservedView(val material: String?, val source: String, val occupied: Boolean?) {
    companion object {
        fun of(unobserved: SourceUnobserved) = SourceUnobservedView(unobserved.material, unobserved.source, unobserved.occupied)
    }
}

/** 실행의 단위 하나. [reached] 는 단위가 이른 근거 등급이다. */
data class UnitView(val unitId: String, val skillType: String, val state: String, val reached: String)

/** 작업 응답 하나. 미들웨어 `JobResponse` 의 칸을 옮긴다. [residualHold] 는 남은 파지 상태의 종류 이름이다. */
data class JobResponseView(
    val jobResponseId: String,
    val version: Int,
    val physicalState: String,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    val incompleteUnits: Map<String, String>,
    val inDoubtUnits: List<String>,
    val operatorRequired: Boolean,
    val residualHold: String,
    val blockedBy: List<String>,
    val connection: String,
)

/**
 * 실행 하나. 실행은 가변 객체라 호스트 잠금 안에서 이 모양으로 복사한다.
 *
 * @param missionVersion 임무 버전. `null` 이면 코드 정의다.
 * @param jobResponse 그 실행의 마지막 작업 응답. 아직 없으면 `null`.
 * @param cancel 이 인스턴스에서 그 실행에 건 운영자 취소(P0-b2). 없으면 `null`.
 * @param permitBlock 이 실행의 새 로봇 명령을 지금 막고 있는 작업 허가(ADR 52). 막힌 것이 없거나 현장이 허가 대응을 선언하지 않았으면
 *   `null` 이다. 운영자 보류·결함 막음과 다른 칸이다([PermitBlockView]).
 */
data class ExecutionView(
    val executionId: String,
    val jobOrderId: String,
    val workMasterId: String,
    val missionVersion: Int?,
    val robotId: String,
    val physicalState: String,
    val units: List<UnitView>,
    val jobResponse: JobResponseView?,
    val restoredFrom: RestoredFromView?,
    val cancel: CancelRecordView? = null,
    val permitBlock: PermitBlockView? = null,
)

/** 다시 지은 실행의 이전 실행(S4b 스펙 §6.3). 바로 앞 인스턴스에서 그 작업 지시를 들었던 실행이다. */
data class RestoredFromView(val instanceId: String, val executionId: String)

/**
 * `GET /host/executions` 의 본문. [instanceId] 는 미들웨어가 뜬 한 번을 가리킨다. 재기동하면 바뀌고 `exec-N` 은 1부터
 * 다시 센다. [pumpedAt] 은 마지막 pump 가 셀 대역을 읽고 잠금을 잡은 뒤의 호스트 시계 값이며 아직 한 번도 안 돌았으면 `null` 이다.
 */
data class ExecutionsView(
    val instanceId: String,
    val pumpedAt: Instant?,
    val executions: List<ExecutionView>,
    val restore: RestoreView?,
    val journalBlockedExecutionIds: List<String> = emptyList(),
)

/** 복원의 결과(S4b 스펙 T3). DEFERRED 는 pump 마다 다시 시도해 RESTORED 나 GAVE_UP 이 된다. */
enum class RestoreResult { RESTORED, DEFERRED, GAVE_UP }

/**
 * 복원 보고의 한 행(S4b 스펙 §6.3).
 *
 * @param previousInstanceId·previousExecutionId 바로 앞 인스턴스에서 그 작업 지시를 들었던 실행. 앞서 다시 지었으면 그 실행이다
 * @param executionId RESTORED 일 때 새 실행 id. 그 밖에는 `null`
 * @param reason DEFERRED·GAVE_UP 의 사유. RESTORED 면 `null`
 */
data class RestoreRowView(
    val jobOrderId: String,
    val robotId: String,
    val previousInstanceId: String,
    val previousExecutionId: String,
    val result: String,
    val executionId: String?,
    val reason: String?,
)

/** 이번 기동의 복원 보고. [at] 은 복원한 호스트 시각이고 [rows] 는 일지의 받은 순서다. 다시 지을 것이 없었으면 [rows] 가 비어 있다. */
data class RestoreView(val at: Instant, val rows: List<RestoreRowView>)

/**
 * `GET /host/job-responses` 의 행 하나(S4b 스펙 T6). 내용 키의 칸과 처분이다. 단위 id 목록은 정렬돼 있고 [incompleteUnits] 는
 * 사유 없이 단위 id 만 든다.
 *
 * @param disposition `SENT` 또는 `RESTART_DUPLICATE`(적었으나 송신하지 않음)
 * @param recordedAt 적은 DB 시각(실제 시각)
 */
data class JobResponseLogView(
    val instanceId: String,
    val jobResponseId: String,
    val jobOrderId: String,
    val executionId: String,
    val version: Int,
    val physicalState: String,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    val incompleteUnits: List<String>,
    val inDoubtUnits: List<String>,
    val operatorRequired: Boolean,
    val residualHold: String,
    val blockedBy: List<String>,
    val disposition: String,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: ResponseLogRow) = row.content.let {
            JobResponseLogView(
                row.instanceId, row.jobResponseId, it.jobOrderId, row.executionId, it.version, it.physicalState, it.requiredEvidence,
                it.reachedEvidence, it.completedUnits, it.unverifiedUnits, it.incompleteUnits, it.inDoubtUnits, it.operatorRequired,
                it.residualHold, it.blockedBy, row.disposition.name, row.recordedAt,
            )
        }
    }
}

/** `GET /host/job-responses` 의 본문. [responses] 는 최근부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
data class JobResponsesView(val instanceId: String, val total: Int, val responses: List<JobResponseLogView>)

/** 일지 쓰기가 실패했다(S4b 스펙 §9). 실행은 미들웨어에 남는다. 제출은 500 이다. */
class JournalWriteFailed(val executionId: String, cause: Throwable) : RuntimeException("실행 일지를 적지 못했다: $executionId", cause)

/**
 * 미들웨어 실행 호스트(S3a 스펙 §7).
 *
 * ## 잠금 하나
 *
 * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회, 운영자 판단을 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
 * mimic 엔진 잠금으로 한 방향뿐이다(gRPC 호출이 잠금 아래에서 나간다).
 *
 * 임무 버전 활성화도 이 잠금 아래에서 한다([exclusive], S3b 스펙 T2). 판정과 배정 사이에 활성화가 끼면 한 제출 안에서 판정
 * 계획과 실행 계획의 버전이 갈린다. DB 는 잠금 안에서 부를 수 있으나 그 반대(DB 연결을 쥔 채 이 잠금을 기다림)는 없다.
 *
 * 공정 잠금이다. 제출이 mimic 을 기다리느라 운영 서비스의 요청 제한을 넘기면 운영 서비스가 실행 목록으로 재조회하는데,
 * 먼저 기다린 제출이 먼저 잡아야 재조회가 제출보다 앞서 «반영 안 됨» 을 남기지 않는다. `synchronized` 는 순서를 보장하지
 * 않고, 가상 스레드가 그것을 기다리면 캐리어 스레드를 붙잡는다.
 *
 * ## pump
 *
 * 단일 스레드가 [PUMP_PERIOD] 마다 셀 대역 스냅숏을 **잠금 밖에서** 한 번 읽고, 잠금 아래에서 그 스냅숏을 셀 신호로 끼운 뒤
 * `pump()` 를 부른다. 셀 HTTP 가 늦어도 REST 가 그 뒤에 줄 서지 않는다.
 *
 * ## 판정(T3)
 *
 * 호스트는 스킬 적합과 도는 실행 없음만 본다. 시운전 완료와 연결 신선은 운영 서비스가 본다. 미들웨어의 배정 관문은 이
 * 넷을 보지 않으므로 제출 때 호스트가 다시 판정해 통과한 기체만 `assign` 에 넘긴다. 미해소 잔여 파지 표시가 있는 기체도 뺀다
 * (P0-b2, [cancel]·[clearResidualHold]).
 *
 * ## 작업 응답과 재기동(S4b 스펙)
 *
 * 상위 시스템이 없어 송신 기록(`mission.job_response_log`)이 전선 자리를 대신한다. pump 뒤 같은 잠금 안에서 응답을 적고 `ack`
 * 한다. 받은 작업 지시는 실행 일지에 적고 기동 때 [start] 가 다시 짓는다. 인시던트와 판단은 사본으로 남는다. 미들웨어 아웃박스는
 * ack 한 응답도 들고 있어 계속 자라는 것은 그대로 한계다(S3a 스펙 §12).
 *
 * ## 판단 기록(picasso 한계 §15.221 · 판단 기록 영속)
 *
 * 미들웨어의 판단 기록(`Middleware.judgmentRecord`)을 `mission.execution_judgment_record` 에 덧붙인다. 받는 때는 수락 직후, pump 뒤
 * 기록(판정·검증 결과·정착, 송신 기록과 한 트랜잭션), 운영자 판단 직후다. 직전에 적은 글자와 같으면 적지 않는다. 기동 복원은 그
 * 작업 지시의 가장 최근 기록을 `resume` 에 넘긴다. 못 읽는 기록과 picasso 가 거부한 기록(`RESUME_RECORD_MISMATCH`)은 GAVE_UP 으로
 * 보고하고 기록 없이 다시 짓지 않는다. 이 호스트는 결함 감수(`release`)와 조치 승인(`approveRemedy`)을 부르지 않으므로 그 두 칸은
 * 이 호스트가 지은 실행에서 늘 비어 있다. 기록이 들고 오면 그대로 넘긴다.
 *
 * ## 인스턴스와 심장박동(HA 설계 기록 §8 단계 0)
 *
 * [heartbeat] 주기마다 `mission.host_instance` 의 자기 행을 DB 시각으로 고치고, 닫을 때 멈춤을 적는다. 기동 복원은 그 행을 든
 * 인스턴스(바로 앞 RESTORED 이벤트의 인스턴스, 없으면 받은 인스턴스)가 이 인스턴스이거나 심장박동이 끊긴 행만 다시 짓는다. 살아 있는
 * 다른 인스턴스가 든 행은 DEFERRED([HELD_BY_LIVE_INSTANCE])로 미루고 그 기체를 판정에서 빼며, pump 마다 다시 보아 만료되면 다시 짓는다.
 *
 * @param robots 하위 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다). 그 캐시는 잠금 밖에서
 *   안전하지 않으므로 케이퍼빌리티는 늘 [lock] 아래에서 묻는다.
 * @param catalog 임무 카탈로그(S3b 스펙 T1). 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. 기동 때 DB 의 활성 버전으로
 *   세운 것을 받는다.
 * @param siteTimings 현장 시간값(S3c 스펙 §7.1). 미들웨어에 그대로 넘긴다. `null` 을 주는 동안은 미적용이라 작업 지시를 받지 않는다.
 *   모의 실행의 별도 미들웨어에는 주지 않는다(T10).
 *
 * ## 미적용(S3c 스펙 T8)
 *
 * 첫 읽기가 성공하기 전에는 판정이 기체마다 [UNAPPLIED_REASON] 을 더해 통과시키지 않는다. 그래서 제출은 `assign` 에 기체를 하나도
 * 넘기지 않고, 미들웨어는 기체 없는 채택을 사유 없는 UNASSIGNED 로 돌려준다(이유는 [SubmitOutcome.excluded], S3a JSON 계약 그대로).
 * 기본값으로 대신하지 않는다. 나머지 경로는 시간값을 쓰지 않으므로 그대로 동작한다.
 *
 * ## 작업 허가(ADR 52)
 *
 * [permits] 는 현장이 작업 허가 대응을 선언했을 때만 있다. 있으면 미들웨어의 허가 포트로 붙이고, pump 마다 잠금 밖에서 읽은 셀 대역
 * 스냅숏을 잠금 아래에서 넘긴다([CellWorkPermits.observe]). 포트의 읽기는 그 스냅숏으로 지어 둔 판독이라 잠금 아래에서 기다리지 않는다.
 * 없으면 포트를 붙이지 않아 발행 거동이 오늘 그대로다. 허가는 안전 계통 상태를 비춘 정보이고 이 호스트는 그것으로 새 로봇 명령만 내지
 * 않는다.
 */
class MissionHost(
    private val robots: RobotPort,
    private val cellBand: CellBandClient,
    private val clock: HostClock,
    private val catalog: StoredMissionCatalog = StoredMissionCatalog(),
    private val siteTimings: SiteTimingsSource,
    private val records: HostRecords,
    private val store: MissionStore,
    private val json: ObjectMapper,
    private val permits: CellWorkPermits? = null,
    private val heartbeat: HostHeartbeat = HostHeartbeat.DEFAULT,
) : AutoCloseable {

    private val lock = ReentrantLock(true)
    private val signals = CellBandSignals()

    private val middleware = Middleware(
        robots = robots, cell = signals, now = clock::now, missions = catalog, siteTimings = siteTimings, permits = permits,
    )

    private var pumpedAt: Instant? = null
    private var latestCell: CellSnapshot? = null

    // ── S4b 기록. 모두 호스트 잠금 아래에서만 읽고 쓴다.

    /** 이 인스턴스에서 일지 행이 있는 실행의 작업 지시 id(제출로 적었거나 다시 지은 것). 정착 이벤트는 이것만 적는다. */
    private val journaled = mutableSetOf<String>()

    /** 이 인스턴스가 정착 이벤트를 적은 작업 지시 id. */
    private val settledRecorded = mutableSetOf<String>()

    /** 사본을 적은 인시던트 수. 미들웨어의 인시던트 목록은 덧붙이기만 하므로 이 색인 뒤가 새로 봉인된 것이다. */
    private var copiedIncidents = 0

    /** 판단 행을 적은 인시던트 id. */
    private val copiedResolutions = mutableSetOf<String>()

    /** 이번 기동의 복원 시각. [start] 전에는 `null` 이다. */
    private var restoredAt: Instant? = null

    /** 이번 기동의 복원 보고 행. 일지의 받은 순서이고, DEFERRED 행은 결과가 바뀌면 그 자리에서 갈린다. */
    private val restoreRows = mutableListOf<RestoreRowView>()

    /** 다시 지은 실행 id 에서 바로 앞 인스턴스의 실행으로. */
    private val restoredFrom = mutableMapOf<String, RestoredFromView>()

    /** 기체 스냅숏을 못 읽어 미룬 일지 행(T3). pump 마다 다시 시도하고 그동안 그 기체를 판정에서 뺀다(T4). */
    private val deferred = mutableListOf<Deferred>()

    /**
     * 포기했고 정착하지 않은 일지 행(T4). 그 기체의 스냅숏에 그 작업 지시의 종료하지 않은 태스크가 없어질 때까지 그 기체를 판정에서 뺀다.
     * 이전 기동에서 포기한 행도 든다. 풀린 행은 이 인스턴스에서 다시 빼지 않는다.
     */
    private val gaveUp = mutableListOf<JournalRow>()

    /**
     * 미해소 잔여 파지 표시(P0-b2). 기동 때 DB 에서 다시 읽고, 정착 기록 트랜잭션이 커밋된 뒤와 해소를 적은 뒤에 고친다. 판정이 이것을
     * 읽어 그 기체를 뺀다. 원본은 DB 이고 이것은 그 사본이다.
     */
    private val openHolds = mutableListOf<ResidualHoldRow>()

    /** 이 인스턴스에서 건 운영자 취소. 실행 id 에서. 이전 인스턴스의 취소는 그 인스턴스의 실행 id 라 싣지 않는다. */
    private val cancels = mutableMapOf<String, CancelRow>()

    /**
     * 이 인스턴스가 작업 지시마다 마지막으로 적은(또는 복원에 넘긴) 판단 기록 글자. 같은 글자는 다시 적지 않는다. 적기가 실패하면
     * 갱신하지 않으므로 다음 pump 가 다시 적는다.
     */
    private val recorded = mutableMapOf<String, String>()

    /**
     * 미룬 행. [order]·[mission] 은 복원 때 한 번 읽은 것이고 다시 시도할 때 그대로 쓴다. [heldBy] 는 이 행을 든 다른 인스턴스가
     * 살아 있어 미룬 동안 그 인스턴스다. 그 심장박동이 끊긴 것을 보면 `null` 로 두고 보통의 다시 시도로 넘어간다.
     */
    private class Deferred(val row: JournalRow, val order: JobOrder, val mission: ActiveMission, val index: Int, var heldBy: String? = null)

    private val pumper = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-pump").apply { isDaemon = true } }

    /** 심장박동은 pump 와 다른 스레드다. 호스트 잠금을 잡지 않으므로 pump 나 제출이 오래 걸려도 늦지 않는다. */
    private val beater = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-heartbeat").apply { isDaemon = true } }

    val instanceId: String = middleware.instanceId

    /**
     * 인스턴스 행을 넣고, 실행을 복원하고, pump 와 심장박동을 시작한다. [MissionHostApplication] 이 빈을 만들 때 부른다. 복원이 빈 생성
     * 안에서 끝나므로 웹 서버가 열리기 전에 판정이 도는 실행을 안다(S4b 스펙 T3). 인스턴스 행이나 복원에서 DB 가 실패하면 예외가 나가
     * 기동이 멈춘다.
     */
    fun start(period: Duration = PUMP_PERIOD): MissionHost = apply {
        records.beat(instanceId, heartbeat.expiry)
        restore()
        beater.scheduleWithFixedDelay(::beatOnce, heartbeat.period.toMillis(), heartbeat.period.toMillis(), TimeUnit.MILLISECONDS)
        pumper.scheduleWithFixedDelay(::pumpOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** 심장박동 한 번. 실패는 삼키고 다음 주기에 다시 한다. 만료 폭 안에 다시 적지 못하면 다른 인스턴스가 이 인스턴스를 끊긴 것으로 읽는다. */
    private fun beatOnce() {
        try {
            records.beat(instanceId, heartbeat.expiry)
        } catch (e: Exception) {
            log.warn("심장박동 실패: {}", e.toString())
        }
    }

    /**
     * 기동 복원(S4b 스펙 T3, §6.3). 정착도 포기도 없는 일지 행을 받은 순서대로 다시 짓는다. 결과는 일지 이벤트로 남고 복원 보고에
     * 실린다. 그 뒤 포기한 행(이전 기동의 것 포함)을 판정 제외 목록으로, 미해소 잔여 파지 표시를 판정이 읽을 사본으로 읽는다.
     *
     * 단계 0 범위(HA 설계 기록 §7): 행을 든 인스턴스가 이 인스턴스이거나 심장박동이 끊긴 행만 지금 다시 짓는다. 살아 있는 다른
     * 인스턴스가 든 행은 DEFERRED 로 미룬다([restoreRow]).
     */
    private fun restore() = lock.withLock {
        restoredAt = clock.now()
        records.openJournal().forEach { row -> restoreRow(row) }
        // 이번 기동에서 포기한 행은 attempt 가 이미 넣었고 그 GAVE_UP 이벤트도 일지에 있다. 작업 지시마다 한 번만 든다.
        gaveUp += records.gaveUpJournal().filter { row -> gaveUp.none { it.jobOrderId == row.jobOrderId } }
        openHolds += records.openResidualHolds()
    }

    private fun restoreRow(row: JournalRow) {
        val previous = records.events(row.jobOrderId).lastOrNull { it.kind == JournalEventKind.RESTORED }
            ?.let { RestoredFromView(it.instanceId, it.executionId!!) }
            ?: RestoredFromView(row.instanceId, row.executionId)
        val index = restoreRows.size
        restoreRows += RestoreRowView(row.jobOrderId, row.robotId, previous.instanceId, previous.executionId, RestoreResult.GAVE_UP.name, null, null)
        val prepared = try {
            val order = HostRequests.jobOrder(json.readTree(row.jobOrder))
            val version = row.missionVersion?.let {
                store.version(row.workMasterId, it) ?: throw IllegalStateException("임무 버전 행이 없다: ${row.workMasterId} 버전 $it")
            }
            order to catalog.mission(row.workMasterId, version)
        } catch (e: BadRequest) {
            settleRestore(row, index, previous, RestoreResult.GAVE_UP, null, "일지의 작업 지시를 읽지 못했다: ${e.message}")
            return
        } catch (e: IllegalStateException) {
            settleRestore(row, index, previous, RestoreResult.GAVE_UP, null, e.message ?: e.toString())
            return
        }
        val waiting = Deferred(row, prepared.first, prepared.second, index)
        // 행을 든 인스턴스는 바로 앞 RESTORED 의 인스턴스, 없으면 받은 인스턴스다([previous]). 살아 있으면 가져가지 않는다.
        val holder = previous.instanceId
        if (holder != instanceId && records.instanceLive(holder)) {
            waiting.heldBy = holder
            deferred += waiting
            settleRestore(row, index, previous, RestoreResult.DEFERRED, null, "$HELD_BY_LIVE_INSTANCE: instance=$holder")
            return
        }
        attempt(waiting, previous, first = true)
    }

    /**
     * 미룬 행을 다시 시도한다(T3). pump 가 미들웨어 pump 전에 잠금 아래에서 부른다. 이벤트를 못 적으면 다음 pump 에 다시 한다.
     * 다른 인스턴스가 들어 미룬 행은 그 인스턴스의 심장박동이 끊길 때까지 시도하지 않는다.
     */
    private fun retryDeferred() {
        deferred.toList().forEach { waiting ->
            val previous = restoreRows[waiting.index].let { RestoredFromView(it.previousInstanceId, it.previousExecutionId) }
            try {
                val holder = waiting.heldBy
                if (holder != null) {
                    if (records.instanceLive(holder)) return@forEach
                    waiting.heldBy = null
                }
                attempt(waiting, previous, first = false)
            } catch (e: Exception) {
                log.warn("미룬 복원 다시 시도 실패: {} {}", waiting.row.jobOrderId, e.toString())
            }
        }
    }

    private fun attempt(waiting: Deferred, previous: RestoredFromView, first: Boolean) {
        val (result, executionId, reason) = resumed(waiting)
        if (result == RestoreResult.DEFERRED) {
            if (first) {
                deferred += waiting
                settleRestore(waiting.row, waiting.index, previous, result, null, reason)
            } else if (restoreRows[waiting.index].reason != reason) {
                // 미룬 까닭이 바뀌었다(다른 인스턴스의 만료 → 스냅숏). 이벤트는 미룬 기동마다 한 번이라 보고 행만 고친다.
                restoreRows[waiting.index] = restoreRows[waiting.index].copy(reason = reason)
            }
            return
        }
        settleRestore(waiting.row, waiting.index, previous, result, executionId, reason)
        deferred.remove(waiting)
        if (result == RestoreResult.GAVE_UP) gaveUp += waiting.row
    }

    /** 결과를 일지 이벤트로 적고 보고 행을 갈아 끼운다. 이벤트를 먼저 적는다. 못 적으면 예외가 나가고 보고는 그대로다. */
    private fun settleRestore(row: JournalRow, index: Int, previous: RestoredFromView, result: RestoreResult, executionId: String?, reason: String?) {
        val kind = when (result) {
            RestoreResult.RESTORED -> JournalEventKind.RESTORED
            RestoreResult.DEFERRED -> JournalEventKind.DEFERRED
            RestoreResult.GAVE_UP -> JournalEventKind.GAVE_UP
        }
        records.event(row.jobOrderId, kind, instanceId, executionId, reason)
        restoreRows[index] = RestoreRowView(row.jobOrderId, row.robotId, previous.instanceId, previous.executionId, result.name, executionId, reason)
        if (executionId != null) {
            restoredFrom[executionId] = previous
            journaled += row.jobOrderId
        }
    }

    /**
     * 미들웨어에 다시 넣는다(picasso `Middleware.resume`, S4b 스펙 T1). 기체 스냅숏을 못 읽은 거부는 DEFERRED, 그 밖의 거부는
     * GAVE_UP 이다. Idempotent 는 이번 기동에서 이미 다시 지은 행을 또 부른 경우(앞선 이벤트 쓰기가 실패한 다시 시도)뿐이고
     * 그 실행으로 RESTORED 다.
     *
     * 그 작업 지시의 가장 최근 판단 기록을 시도마다 읽어 넘긴다(미루는 동안 앞 인스턴스가 더 적었을 수 있다). 기록을 못 읽으면
     * `resume` 을 부르지 않고 GAVE_UP([RECORD_UNREADABLE]) 이다. 기록 없이 다시 지으면 읽지 못한 판단이 조용히 «없었다» 가 된다.
     * picasso 가 기록을 거부하면(`RESUME_RECORD_MISMATCH`) 그 문장 뒤에 기록 행 번호를 붙여 GAVE_UP 이다.
     *
     * @return 결과, RESTORED 의 실행 id, DEFERRED·GAVE_UP 의 사유
     */
    private fun resumed(waiting: Deferred): Triple<RestoreResult, String?, String?> {
        val stored = records.latestJudgmentRecord(waiting.row.jobOrderId)
        val record = stored?.let {
            try {
                JudgmentRecordWire.decode(it.record)
            } catch (e: Exception) {
                return Triple(RestoreResult.GAVE_UP, null, "$RECORD_UNREADABLE: record=${it.recordId}, ${e.message}")
            }
        }
        return when (val submission = middleware.resume(waiting.order, waiting.row.robotId, waiting.mission, record)) {
            is Middleware.Submission.Accepted -> {
                stored?.let { recorded[waiting.row.jobOrderId] = it.record }
                Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            }
            is Middleware.Submission.Idempotent -> Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            is Middleware.Submission.Rejected ->
                if (submission.reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE)) {
                    Triple(RestoreResult.DEFERRED, null, submission.reason)
                } else if (stored != null && submission.reason.startsWith(Middleware.RESUME_RECORD_MISMATCH)) {
                    Triple(RestoreResult.GAVE_UP, null, "${submission.reason} (record=${stored.recordId})")
                } else {
                    Triple(RestoreResult.GAVE_UP, null, submission.reason)
                }
            is Unassigned -> Triple(RestoreResult.GAVE_UP, null, "배정되지 않았다: ${submission.refusals}")
        }
    }

    private fun restoreView(): RestoreView? = restoredAt?.let { RestoreView(it, restoreRows.toList()) }

    /** pump 한 번. 예외를 삼키고 다음 주기에 다시 돈다. 스케줄러는 작업이 예외를 던지면 다음 실행을 멈춘다. */
    fun pumpOnce() {
        try {
            val cell = cellBand.fetch()
            // 작업 허가 줄의 관측 시각은 이 층이 셀 대역을 읽은 시각이다. 잠금을 기다린 시간은 관측의 나이에 든다.
            val fetchedAt = clock.now()
            lock.withLock {
                signals.snapshot = cell
                latestCell = cell
                permits?.observe(cell, fetchedAt)
                val at = clock.now()
                retryDeferred()
                if (journalBlocked().isNotEmpty()) return@withLock
                middleware.pump()
                pumpedAt = at
                recordAfterPump()
            }
        } catch (e: Exception) {
            log.warn("pump 실패: {}", e.toString())
        }
    }

    /** 기체마다 호스트 판정을 낸다. [order] 의 WorkMaster 는 부르는 쪽이 [WORK_MASTERS] 로 거른다. */
    private fun selectedMission(workMaster:String,version:String?):dev.picasso.middleware.ActiveMission? {
        if(version==null){if(catalog.active(workMaster)==null)throw BadRequest(HostRequests.UNKNOWN_WORK_MASTER,"활성화하지 않은 임무입니다: $workMaster");return null}
        if(version=="CODE"){if(workMaster !in WORK_MASTERS)throw BadRequest(HostRequests.BAD_REQUEST,"새 임무는 숫자 버전이 필요합니다");return catalog.mission(workMaster,null)}
        val number=version.toIntOrNull()?.takeIf{it>0 && it.toString()==version}
            ?: throw BadRequest(HostRequests.BAD_REQUEST,"고정 임무 버전이 잘못되었습니다")
        val row=store.version(workMaster,number)
            ?: throw BadRequest(HostRequests.BAD_REQUEST,"고정 임무 버전을 찾을 수 없습니다: $workMaster/v$number")
        return catalog.mission(workMaster,row)
    }
    fun eligibility(order:JobOrder,robotIds:List<String>,missionVersion:String?=null):List<HostEligibility> = lock.withLock {
        catalog.withMission(order.workMasterId,selectedMission(order.workMasterId,missionVersion)) { eligibilityCurrent(order,robotIds) }
    }
    private fun eligibilityCurrent(order: JobOrder, robotIds: List<String>): List<HostEligibility> = lock.withLock {
        val applied = siteTimings.current() != null
        robotIds.distinct().map { judge(order, it, applied) }
    }

    /**
     * 판정을 다시 해 통과한 기체만 `assign` 에 넘긴다. 통과한 기체가 없어도 `assign` 을 부른다. 그때 결과는 사유 없는
     * UNASSIGNED 이고 이유는 [SubmitOutcome.excluded] 에 있다.
     *
     * 도는 실행과 같은 작업 지시 id 로 다시 내면 판정이 그 기체를 도는 실행으로 빼므로 IDEMPOTENT 가 아니라 UNASSIGNED 다.
     * 운영 서비스는 작업 지시 id 를 다시 쓰지 않는다.
     *
     * 미적용 판단은 한 번만 읽어 모든 기체의 판정에 같은 값을 쓴다. 미적용이면 통과한 기체가 없어 UNASSIGNED 다. 미들웨어의 채택은
     * 기체마다 관문을 걸므로 기체가 없으면 요구 근거 등급 검사도 하지 않는다.
     *
     * 새 실행으로 ACCEPTED 이면 같은 잠금 안에서 응답하기 전에 실행 일지를 적는다(S4b 스펙 T2). 이미 있던 실행의 `revise` 가 낸
     * Accepted 와 IDEMPOTENT 는 적지 않는다. pump 도 이 잠금을 잡으므로 일지에 없는 실행은 로봇 명령을 낸 적이 없다. 일지 쓰기가
     * 실패하면 [JournalWriteFailed] 이고 실행은 메모리에 남지만 다음 pump와 신규 제출을 차단한다.
     *
     * [confirmations] 는 사람이 기록한 출발 자리 확인이다. 이 `assign` 한 번 동안만 셀 포트의 관측이 되고([CellBandSignals.observe])
     * 끝나면 비운다. 호스트는 저장하지 않는다 — 다음 제출이나 재배정은 확인을 다시 싣지 않으면 설비의 답만 본다.
     */
    fun submit(order:JobOrder,candidates:List<String>,missionVersion:String?=null,confirmations:List<SourceConfirmation> = emptyList()):SubmitOutcome = lock.withLock {
        catalog.withMission(order.workMasterId,selectedMission(order.workMasterId,missionVersion)) { submitCurrent(order,candidates,confirmations) }
    }
    private fun submitCurrent(order: JobOrder, candidates: List<String>, confirmations: List<SourceConfirmation>): SubmitOutcome = lock.withLock {
        if (journalBlocked().isNotEmpty()) return@withLock SubmitOutcome(SubmitResult.REJECTED, null, null, "실행 일지 저장 실패: 호스트 복구 전 제출 차단", emptyList(), emptyList())
        val applied = siteTimings.current() != null
        val judged = candidates.distinct().map { judge(order, it, applied) }
        val excluded = judged.filter { !it.passed }
        val existed = middleware.executions().any { it.order.jobOrderId == order.jobOrderId }
        val searched = middleware.remedySearches().size
        signals.confirmations = confirmations.associateBy { it.source }
        val assigned = try {
            middleware.assign(order, judged.filter { it.passed }.map { it.robotId })
        } finally {
            signals.confirmations = emptyMap()
        }
        when (val submission = assigned) {
            is Middleware.Submission.Accepted -> {
                if (!existed) {
                    journal(submission.execution)
                    persistRecord(submission.execution.executionId)
                }
                SubmitOutcome(
                    SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
                )
            }
            is Middleware.Submission.Idempotent -> SubmitOutcome(
                SubmitResult.IDEMPOTENT, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
            )
            // 같은 잠금 안이라 이번 assign 뒤에 조치 탐색 기록에 붙은 줄은 이 제출의 것이다. 한 번의 작업 수락은 많아야 한 줄을 남긴다.
            is Middleware.Submission.Rejected -> SubmitOutcome.rejected(
                submission,
                middleware.remedySearches().drop(searched).lastOrNull { it.jobOrderId == order.jobOrderId }?.outcome,
                excluded,
            )
            is Unassigned -> SubmitOutcome(
                SubmitResult.UNASSIGNED, null, null, null,
                submission.refusals.map { (robotId, reason) ->
                    Refusal.of(robotId, reason, submission.sourceMissing[robotId], submission.sourceUnobserved[robotId])
                },
                excluded,
            )
        }
    }

    /** 새 실행의 일지 행을 적는다. 작업 지시는 picasso `JobOrder` 의 칸 그대로의 JSON 이다. */
    private fun journal(execution: Middleware.Execution) {
        val order = execution.order
        try {
            records.journal(
                order.jobOrderId, execution.robotId, orderJson(order), order.workMasterId, execution.missionVersion,
                middleware.instanceId, execution.executionId,
            )
        } catch (e: Exception) {
            throw JournalWriteFailed(execution.executionId, e)
        }
        journaled += order.jobOrderId
    }

    private fun orderJson(order: JobOrder): String = json.writeValueAsString(
        linkedMapOf(
            "jobOrderId" to order.jobOrderId,
            "workMasterId" to order.workMasterId,
            "version" to order.version,
            "requiredEvidence" to order.requiredEvidence.name,
            "parameters" to order.parameters,
            "materialRequirements" to order.materialRequirements.map {
                linkedMapOf("materialDefinitionId" to it.materialDefinitionId, "quantity" to it.quantity)
            },
            "equipmentRequirements" to order.equipmentRequirements.map {
                linkedMapOf("id" to it.id, "equipmentUse" to it.equipmentUse, "properties" to it.properties)
            },
        ),
    )

    fun executions(): ExecutionsView = lock.withLock { view(middleware.executions()) }

    /**
     * [executions] 를 작업 지시 id 가 [jobOrderIds] 에 들거나 실행 id 가 [executionIds] 에 드는 실행으로 좁힌 것(HA 설계 기록 §10).
     * 모양과 칸은 같고 실행과 일지 막힘 목록만 고른 실행으로 줄어든다. 같은 잠금 아래의 전체 목록에서 그 실행만 남긴 것과 같다.
     * 정착한 실행이 쌓여도 응답은 고른 실행 수만큼이다. 미들웨어의 실행과 아웃박스는 여전히 한 번씩 훑는다(메모리 안).
     */
    fun executions(jobOrderIds: Set<String>, executionIds: Set<String>): ExecutionsView = lock.withLock {
        view(middleware.executions().filter { it.order.jobOrderId in jobOrderIds || it.executionId in executionIds })
    }

    /**
     * 화면의 실행 목록(HA 설계 기록 §10). 지금 인스턴스의 남은 실행([ExecutionPages.live])을 메모리에서, 최근 정착 이력 [recent] 개를
     * 정착 기록(DB)에서 읽는다. 정착한 실행이 미들웨어 메모리에서 사라져도 이력은 그대로다.
     *
     * 메모리를 먼저(호스트 잠금 아래) 읽고 DB 를 잠금 밖에서 뒤에 읽는다. 남은 실행이 아닌 실행은 정착 기록이 커밋된 뒤라 DB 에 있으므로 어느
     * 쪽에서도 빠지지 않는다. 그 사이 정착을 적은 실행은 두 쪽에 다 나올 수 있다(화면이 실행 id 와 인스턴스로 하나만 보인다).
     */
    fun executionsRecent(recent: Int): ExecutionPageView {
        require(recent in 1..ExecutionPages.MAX) { "recent 는 1~${ExecutionPages.MAX} 다: $recent" }
        val live = lock.withLock { view(middleware.executions().filter(::live)) }
        val page = settledPage(null, recent)
        return ExecutionPageView(
            instanceId = live.instanceId,
            pumpedAt = live.pumpedAt,
            executions = live.executions,
            restore = live.restore,
            journalBlockedExecutionIds = live.journalBlockedExecutionIds,
            settled = page.settled,
            nextBefore = page.nextBefore,
            olderTotal = page.olderTotal,
        )
    }

    /** 정착 차례가 [before] 보다 앞인 정착 이력 가운데 최근부터 [limit] 개(HA 설계 기록 §10). DB 만 읽고 호스트 잠금을 잡지 않는다. */
    fun executionsBefore(before: Long, limit: Int): SettledPageView {
        require(limit in 1..ExecutionPages.MAX) { "limit 은 1~${ExecutionPages.MAX} 다: $limit" }
        return settledPage(before, limit)
    }

    /** 남은 실행의 수만(HA 설계 기록 §10). 실행 본문과 작업 응답을 옮기지 않는다. */
    fun executionSummary(): ExecutionSummaryView = lock.withLock {
        val live = middleware.executions().filter(::live)
        val states = PhysicalState.entries.associateTo(linkedMapOf()) { it.name to 0 }
        live.forEach { states[it.physicalState.name] = states.getValue(it.physicalState.name) + 1 }
        ExecutionSummaryView(
            instanceId = middleware.instanceId,
            pumpedAt = pumpedAt,
            live = live.size,
            states = states,
            journalBlockedExecutionIds = journalBlocked(),
        )
    }

    /** [ExecutionPages.live]. 호스트 잠금 아래에서 부른다. */
    private fun live(execution: Middleware.Execution): Boolean {
        val journalBlocked = execution.order.jobOrderId !in journaled
        return ExecutionPages.live(
            ExecutionPages.active(execution.physicalState, execution.units.map { it.state }, journalBlocked),
            execution.order.jobOrderId in settledRecorded,
        )
    }

    private fun settledPage(before: Long?, limit: Int): SettledPageView =
        records.settledHistory(before, limit).let { SettledPageView(it.rows.map(::settledView), it.nextBefore, it.olderTotal) }

    /** 정착 이력 한 줄. 본문을 적지 않은 행(V8 전)은 일지 행으로 짓는다. */
    private fun settledView(row: SettledRow): SettledExecutionView = SettledExecutionView(
        settleSeq = row.eventId,
        instanceId = row.instanceId,
        settledAt = row.settledAt,
        snapshot = row.snapshot != null,
        execution = row.snapshot?.let(json::readTree) ?: json.valueToTree(
            ExecutionView(
                row.executionId, row.jobOrderId, row.workMasterId, row.missionVersion, row.robotId, row.physicalState,
                units = emptyList(), jobResponse = null, restoredFrom = null,
            ),
        ),
    )

    /** [chosen] 실행의 목록 본문. 호스트 잠금 아래에서 부른다. */
    private fun view(chosen: List<Middleware.Execution>): ExecutionsView {
        val responses = latestResponses(middleware.responses())
        return ExecutionsView(
            instanceId = middleware.instanceId,
            pumpedAt = pumpedAt,
            executions = chosen.map { execution ->
                ExecutionView(
                    executionId = execution.executionId,
                    jobOrderId = execution.order.jobOrderId,
                    workMasterId = execution.order.workMasterId,
                    missionVersion = execution.missionVersion,
                    robotId = execution.robotId,
                    physicalState = execution.physicalState.name,
                    units = execution.units.map { UnitView(it.unitId, it.skillType, it.state.name, it.reached.name) },
                    jobResponse = responses[execution.executionId]?.let(::view),
                    restoredFrom = restoredFrom[execution.executionId],
                    cancel = cancels[execution.executionId]?.let(CancelRecordView::of),
                    permitBlock = execution.permitBlock?.let(PermitBlockView::of),
                )
            },
            restore = restoreView(),
            journalBlockedExecutionIds = journalBlocked(chosen),
        )
    }

    /**
     * 봉인된 인시던트(S3c 스펙 §7.2, S4a 스펙 §6). 최신부터 많아야 [limit] 개다. 번들은 미들웨어 안의 값이라 호스트 잠금 아래에서
     * 옮긴다. 보류 중 여부는 자르기 전의 전부로 정한다.
     *
     * 이전 인스턴스의 사본은 [IncidentsView.earlier] 에 따로 싣는다(S4b 스펙 T7). 적은 순서의 역순으로 많아야 [limit] 개다. 사본은
     * 잠금 밖에서 DB 로 읽는다.
     */
    fun incidents(limit: Int): IncidentsView {
        val (total, live) = lock.withLock {
            val all = middleware.incidents()
            val held = heldIncidents(all)
            all.size to all.asReversed().take(limit).map { IncidentViews.item(it, it.incidentId in held) }
        }
        return IncidentsView(
            instanceId = instanceId,
            total = total,
            incidents = live,
            earlierTotal = records.earlierCopyCount(instanceId),
            earlier = records.earlierCopies(instanceId, limit).map { row ->
                EarlierIncidentView(row.instanceId, IncidentViews.item(copyDetail(row)))
            },
        )
    }

    /**
     * 인시던트 하나의 상세(S4a 스펙 §6). 없으면 `null` 이다. [ofInstance] 가 없거나 지금 인스턴스면 호스트 잠금 아래에서 미들웨어의
     * 번들을 읽는다. 이전 인스턴스면 그 인스턴스의 사본을 돌려준다(S4b 스펙 T7).
     */
    fun incident(incidentId: String, ofInstance: String? = null): IncidentDetailView? {
        if (ofInstance != null && ofInstance != instanceId) return records.incidentCopy(ofInstance, incidentId)?.let(::copyDetail)
        return lock.withLock { liveDetail(incidentId) }
    }

    private fun liveDetail(incidentId: String): IncidentDetailView? {
        val bundle = middleware.incident(incidentId) ?: return null
        return IncidentViews.detail(middleware.instanceId, bundle, incidentId in heldIncidents(middleware.incidents()), unitStateOf(bundle))
    }

    private fun unitStateOf(bundle: IncidentBundle): String? =
        middleware.executions().firstOrNull { it.executionId == bundle.executionId }
            ?.units?.firstOrNull { it.unitId == bundle.unitId }?.state?.name

    /**
     * 사본의 상세. 판단은 판단 행으로 다시 세우고 근거 없는 완료 확인도 그것으로 다시 계산한다. 이전 인스턴스의 인시던트는 보류 중이
     * 아니고, 그 실행은 이 인스턴스에 없으므로 단위의 지금 상태는 `null` 이다.
     */
    private fun copyDetail(row: IncidentCopyRow): IncidentDetailView {
        val stored = json.readValue<IncidentDetailView>(row.detail)
        val resolution = row.resolution?.let { ResolutionView(it.decision, it.at, it.wallClockAt, ApproverView(it.decidedById, it.decidedByKind)) }
        return stored.copy(
            resolution = resolution,
            held = false,
            confirmedWithoutEvidence = resolution?.decision == OperatorDecision.CONFIRM_DONE.name &&
                stored.verification != Verification.MATCHED.name,
            unitState = null,
        )
    }

    /** 송신 기록(S4b 스펙 T6). 최근부터 많아야 [limit] 개다. DB 만 읽고 호스트 잠금을 잡지 않는다. */
    fun jobResponses(jobOrderId: String?, limit: Int): JobResponsesView = JobResponsesView(
        instanceId = instanceId,
        total = records.responseLogCount(jobOrderId),
        responses = records.responseLog(jobOrderId, limit).map(JobResponseLogView::of),
    )

    /**
     * pump 뒤 기록(S4b 스펙 §6.2, T6·T7). 같은 잠금 안에서 한 트랜잭션으로 송신 기록, 새로 봉인된 인시던트의 사본, 아직 판단 행이
     * 없는 판단, 아직 정착 이벤트가 없는 정착한 실행을 적는다. 트랜잭션이 실패하면 아무것도 `ack` 하지 않고 다음 pump 에 다시 한다.
     *
     * 송신 기록: 새 인스턴스가 그 작업 지시에 대해 처음 내는 응답이 그 작업 지시의 가장 최근 송신 행(인스턴스 무관)과 내용 키가
     * 같으면 재기동 중복으로 적고 송신하지 않는다. 그 밖에는 송신으로 적는다. 어느 쪽이든 적은 뒤 `ack` 한다.
     *
     * 정착은 pump 가 더 돌리지 않는 상태다(`PARTIAL` 은 미들웨어가 계속 돌리므로 정착으로 적지 않는다).
     *
     * 잔여 파지 표시(P0-b2): 정착 이벤트를 적는 실행 가운데 [residualHoldUnit] 이 단위를 내는 것은 같은 트랜잭션에서 표시를 적는다.
     * 작업 지시마다 하나라 이미 있으면 적지 않는다. 판정이 읽는 사본은 커밋된 뒤에만 늘린다.
     */
    private fun recordAfterPump() {
        val pending = middleware.pending()
        val incidents = middleware.incidents()
        val fresh = incidents.drop(copiedIncidents)
        val resolved = incidents.filter { it.resolution != null && it.incidentId !in copiedResolutions }
        val settled = middleware.executions().filter {
            it.physicalState.isSettled && it.physicalState != PhysicalState.PARTIAL &&
                it.order.jobOrderId in journaled && it.order.jobOrderId !in settledRecorded
        }
        val judgments = changedRecords()
        if (pending.isEmpty() && fresh.isEmpty() && resolved.isEmpty() && settled.isEmpty() && judgments.isEmpty()) return

        val held = heldIncidents(incidents)
        // 정착 이력(HA 설계 기록 §10): 정착 이벤트에 그때의 실행 본문을 같이 적는다. 화면은 정착 실행을 메모리가 아니라 이것에서 읽는다.
        val snapshots = if (settled.isEmpty()) emptyList() else view(settled).executions.map(json::writeValueAsString)
        val copies = fresh.map { it to json.writeValueAsString(IncidentViews.detail(instanceId, it, it.incidentId in held, unitStateOf(it))) }
        val marked = mutableListOf<ResidualHoldRow>()
        try {
            records.inTransaction {
                marked.clear()
                // 판단 기록을 송신 기록과 같이 커밋한다. 상위가 받은 응답의 판정은 기록에도 있다.
                judgments.forEach { (execution, text) -> writeRecord(execution, text) }
                pending.forEach(::logResponse)
                copies.forEach { (bundle, detail) ->
                    records.copyIncident(instanceId, bundle.incidentId, bundle.executionId, bundle.jobOrderId, bundle.unitId, detail)
                }
                resolved.forEach { bundle ->
                    val resolution = bundle.resolution!!
                    records.copyResolution(
                        instanceId, bundle.incidentId,
                        CopyResolutionRow(
                            resolution.decision.name, resolution.at, resolution.wallClockAt, resolution.decidedBy.id, resolution.decidedBy.kind.name,
                        ),
                    )
                }
                settled.zip(snapshots).forEach { (it, snapshot) ->
                    records.settled(it.order.jobOrderId, instanceId, it.executionId, it.physicalState.name, snapshot)
                    residualHoldUnit(it.units)?.let { unit ->
                        records.markResidualHold(it.robotId, it.order.jobOrderId, it.executionId, instanceId, unit.unitId, unit.hold.objectRef)
                            ?.let(marked::add)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("pump 뒤 기록 실패, 다음 pump 에 다시 한다: {}", e.toString())
            return
        }
        copiedIncidents = incidents.size
        judgments.forEach { (execution, text) -> recorded[execution.order.jobOrderId] = text }
        copiedResolutions += resolved.map { it.incidentId }
        settledRecorded += settled.map { it.order.jobOrderId }
        openHolds += marked
        pending.forEach { middleware.ack(it.jobResponseId) }
    }

    /**
     * 적을 판단 기록. 일지에 있고 정착 이벤트를 아직 적지 않은 실행 가운데 글자가 이 인스턴스가 마지막으로 적은 것과 다른 것이다.
     * 정착하는 pump 의 실행도 든다(마지막 판정이 그 pump 에서 섰다).
     */
    private fun changedRecords(): List<Pair<Middleware.Execution, String>> =
        middleware.executions()
            .filter { it.order.jobOrderId in journaled && it.order.jobOrderId !in settledRecorded }
            .mapNotNull { execution ->
                middleware.judgmentRecord(execution.executionId)?.let(JudgmentRecordWire::encode)
                    ?.takeIf { it != recorded[execution.order.jobOrderId] }
                    ?.let { execution to it }
            }

    private fun writeRecord(execution: Middleware.Execution, text: String) {
        records.judgmentRecord(execution.order.jobOrderId, instanceId, execution.executionId, JudgmentRecordWire.SCHEMA_VERSION, text)
    }

    /**
     * 판단 기록 하나를 지금 적는다(수락 직후, 운영자 판단 직후). 못 적으면 경고만 남긴다 — 그 판단은 이미 미들웨어에 섰고, 다음 pump 뒤
     * 기록이 같은 글자를 다시 적는다(그 pump 가 성공하기 전에 프로세스가 죽으면 그 판단은 넘어오지 않는다).
     */
    private fun persistRecord(executionId: String) {
        val changed = changedRecords().firstOrNull { it.first.executionId == executionId } ?: return
        try {
            writeRecord(changed.first, changed.second)
            recorded[changed.first.order.jobOrderId] = changed.second
        } catch (e: Exception) {
            log.warn("판단 기록을 적지 못했다, 다음 pump 에 다시 한다: {} {}", executionId, e.toString())
        }
    }

    private fun logResponse(response: JobResponse) {
        val content = contentOf(response)
        val duplicate = !records.loggedIn(instanceId, response.jobOrderId) && records.lastSent(response.jobOrderId)?.content == content
        records.logResponse(
            instanceId, response.jobResponseId, response.executionId, content,
            if (duplicate) ResponseDisposition.RESTART_DUPLICATE else ResponseDisposition.SENT,
        )
        if (!duplicate) {
            val journal = checkNotNull(records.journalRow(response.jobOrderId))
            val snapshot = json.createObjectNode().apply {
                put("instanceId",instanceId); put("executionId",response.executionId)
                put("jobOrderId",response.jobOrderId); put("robotId",journal.robotId)
                put("workMasterId",journal.workMasterId)
                if(journal.missionVersion == null) putNull("missionVersion") else put("missionVersion",journal.missionVersion)
                put("physicalState",response.physicalState.name)
                set<com.fasterxml.jackson.databind.JsonNode>("jobResponse",json.valueToTree(view(response)))
            }
            records.appendResult(instanceId,response.jobResponseId,snapshot)
        }
    }

    /**
     * 운영자 판단(S4a 스펙 T4, T6). 호스트 잠금 아래에서 `Middleware.resolve` 를 부르고 결과 이름을 picasso 그대로 돌려준다.
     * 판단은 그 단위의 판단 없는 가장 최근 인시던트에 붙는다(picasso `IncidentLog.noteResolution`). Resolved 이면 그 인시던트 id 를
     * 같이 낸다. REST 는 늘 `PERSON` 승인자를 만들므로 Refused 는 이 메서드를 직접 부를 때만 난다.
     */
    fun resolve(executionId: String, unitId: String, decision: OperatorDecision, approver: Approver, requestId: String?): ResolveView =
        lock.withLock {
            val target = middleware.incidents().lastOrNull {
                it.executionId == executionId && it.unitId == unitId && it.resolution == null
            }?.incidentId
            when (val outcome = middleware.resolve(executionId, unitId, decision, approver)) {
                ResolveOutcome.Resolved -> {
                    persistRecord(executionId)
                    ResolveView(RESOLVED, null, target, requestId)
                }
                ResolveOutcome.NotHeld -> ResolveView(NOT_HELD, null, null, requestId)
                is ResolveOutcome.Refused -> ResolveView(REFUSED, outcome.reason, null, requestId)
            }
        }

    /**
     * 운영자 실행 취소(P0-b2). 호스트 잠금 아래에서 판정하고, 취소를 DB 에 적은 뒤 미들웨어 `cancel` 을 부른다. 적지 못하면 예외가
     * 나가고 취소하지 않는다. 실제 중단은 다음 pump 가 하류를 읽고 확정하며, 그 뒤는 보통의 정착 기록(정착 이벤트, `ABORTED` 작업
     * 응답, 잔여 파지 표시)이다.
     *
     * 받는 실행은 물리 상태가 `pumps` 인 것(정착하지 않았거나 부분 완료 `PARTIAL`)뿐이다. 이미 취소를 건 실행(`CANCELING`, 또는 이
     * 인스턴스의 취소 행이 있음)과 실행 일지에 없는 실행은 NOT_CANCELABLE 이다. 승인자는 사람이어야 한다(ADR 47 과 같은 규칙).
     * picasso 의 `cancel` 은 승인자를 받지 않으므로 이 판정은 호스트가 한다.
     */
    fun cancel(executionId: String, approver: Approver, reason: String, requestId: String?): CancelView = lock.withLock {
        if (approver.kind != ApproverKind.PERSON) {
            return@withLock CancelView(CancelResult.REFUSED, executionId, null, "$AGENT_CANCEL_REFUSED: ${approver.id}", requestId)
        }
        val execution = middleware.executions().firstOrNull { it.executionId == executionId }
            ?: return@withLock CancelView(CancelResult.NOT_FOUND, executionId, null, "그 실행이 지금 인스턴스에 없다", requestId)
        val state = execution.physicalState
        val refusal = when {
            !state.pumps -> "정착한 실행은 취소하지 않는다: ${state.name}"
            state == PhysicalState.CANCELING || executionId in cancels -> "이미 취소를 건 실행이다"
            execution.order.jobOrderId !in journaled -> "실행 일지에 없는 실행이다"
            else -> null
        }
        if (refusal != null) return@withLock CancelView(CancelResult.NOT_CANCELABLE, executionId, state.name, refusal, requestId)
        val row = records.cancel(instanceId, executionId, execution.order.jobOrderId, state.name, approver.id, approver.kind.name, reason)
        cancels[executionId] = row
        check(middleware.cancel(executionId)) { "미들웨어가 취소를 받지 않았다: $executionId ${state.name}" }
        CancelView(CancelResult.CANCELED, executionId, state.name, null, requestId)
    }

    /** 미해소 잔여 파지 표시(P0-b2). 적은 순서다. */
    fun residualHolds(): ResidualHoldsView = lock.withLock { ResidualHoldsView(openHolds.map(ResidualHoldView::of)) }

    /**
     * 잔여 파지 표시 해소(P0-b2). 사람이 그 기체가 빈손임을 현장에서 확인했다는 기록을 적고 판정이 읽는 사본에서 뺀다. 원본은 DB 라
     * 사본에 없더라도 DB 로 판정한다. 그 기체의 표시가 아니면 NotFound, 이미 해소했으면 AlreadyCleared 다. 승인자는 사람이어야 한다.
     */
    fun clearResidualHold(robotId: String, markerId: Long, approver: Approver, reason: String, requestId: String?): ClearOutcome = lock.withLock {
        if (approver.kind != ApproverKind.PERSON) return@withLock ClearOutcome.Refused("$AGENT_CLEAR_REFUSED: ${approver.id}")
        val (hold, clearing) = records.residualHold(markerId)?.takeIf { it.first.robotId == robotId } ?: return@withLock ClearOutcome.NotFound
        if (clearing != null) return@withLock ClearOutcome.AlreadyCleared(ResidualHoldClearingView.of(clearing))
        val cleared = records.clearResidualHold(markerId, approver.id, approver.kind.name, reason)
        openHolds.removeAll { it.markerId == markerId }
        ClearOutcome.Cleared(ResidualHoldClearedView(ResidualHoldView.of(hold), ResidualHoldClearingView.of(cleared), requestId))
    }

    /**
     * 보류 중인 인시던트. 그 단위가 지금 운영자 보류이고, 그 단위의 인시던트 가운데 `unresolved` 이고 판단이 없는 가장 최근의
     * 것이다. 재작업 뒤 두 번째 보류가 서면 앞 인시던트는 판단이 붙어 빠지므로 한 단위에 많아야 하나다.
     */
    private fun heldIncidents(all: List<IncidentBundle>): Set<String> {
        val holding = middleware.executions().flatMap { execution ->
            execution.units.filter { it.state == UnitState.OPERATOR_HOLD }.map { execution.executionId to it.unitId }
        }.toSet()
        return all.filter { (it.executionId to it.unitId) in holding && it.unresolved && it.resolution == null }
            .groupBy { it.executionId to it.unitId }
            .values.map { it.last().incidentId }
            .toSet()
    }

    /**
     * 작업 허가의 지금(ADR 52). 현장이 대응을 선언하지 않았으면 `declared` 가 거짓이고 나머지가 비어 있다. 판정은 발행 관문과 같은 규칙이다
     * ([CellWorkPermits.evaluate]). 지어 둔 판독만 읽으므로 호스트 잠금을 잡지 않는다.
     */
    fun workPermits(): WorkPermitsView {
        val at = clock.now()
        val source = permits ?: return WorkPermitsView(false, at, null, null, emptyList(), emptyList())
        return WorkPermitsView(
            declared = true,
            evaluatedAt = at,
            session = source.currentSession,
            validFor = source.map.validFor.toString(),
            mappings = source.map.signals.map { PermitMappingView(it.signal, it.permittedWhen, it.robots.toList()) },
            robots = source.evaluate(at).map(RobotPermitView::of),
        )
    }

    /** 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null` 이다. */
    fun cell(): CellSnapshot? = lock.withLock { latestCell }

    /** [action] 을 호스트 잠금 아래에서 돈다. 임무 버전 활성화가 쓴다(S3b 스펙 T2). 잠금은 재진입된다. */
    fun <T> exclusive(action: () -> T): T = lock.withLock(action)

    /** 검증 입력을 잠금 아래에서 한 번에 읽는다(S3b 스펙 T7). 검증과 모의 실행이 쓴다. 그동안 DB 연결을 쥐지 않는다. */
    fun siteInputs(robotIds: List<String>): SiteInputs = lock.withLock { siteInputsLocked(robotIds) }

    /**
     * 검증 입력. 신호 사양은 마지막 pump 가 읽은 셀 대역 스냅숏에서, 현장 스킬은 [robotIds] 마다 기체가 선언한 스킬에서
     * 온다. 케이퍼빌리티를 못 물어본 기체는 `null` 이다. 이미 잠금을 쥔 쪽(활성화)만 부른다.
     */
    fun siteInputsLocked(robotIds: List<String>): SiteInputs {
        check(lock.isHeldByCurrentThread) { "검증 입력은 호스트 잠금 아래에서 읽는다" }
        return SiteInputs(
            cell = latestCell,
            skillsByRobot = robotIds.distinct().associateWith { id -> robots.capabilities(id)?.skillsList?.map { it.skillType }?.toSet() },
        )
    }

    /**
     * 스킬 적합은 지금 활성 정의로 작업 지시를 계획해, 경로가 로봇인 단위의 스킬이 기체가 선언한 스킬에 다 있는가다.
     * 도는 실행은 그 기체를 쥔 실행이다(picasso `PhysicalState.occupiesRobot`). 정착하지 않은 실행과 부분 완료(`PARTIAL`) 실행이고,
     * 운영자 보류에 선 실행도 도는 실행이다(스펙 §12). 부분 완료는 리비전으로 남은 단위가 다시 돌 수 있어 그 기체가 아직 비지 않았다.
     * 부분 완료 실행은 운영자 취소([cancel])로 닫는다. 같은 작업 지시 id 의 리비전은 이 판정에 걸려 UNASSIGNED 다.
     */
    private fun journalBlocked(among: List<Middleware.Execution> = middleware.executions()): List<String> =
        among.filter { it.order.jobOrderId !in journaled }.map { it.executionId }

    private fun judge(order: JobOrder, robotId: String, applied: Boolean): HostEligibility {
        val active = requireNotNull(catalog.active(order.workMasterId)) { "카탈로그에 없는 WorkMaster 다: ${order.workMasterId}" }
        val needed = active.capability.plan(order).filter { it.route == Route.ROBOT }.map { it.skillType }.toSortedSet()
        val declared = robots.capabilities(robotId)?.skillsList?.map { it.skillType }?.toSet()
        val missing = declared?.let { (needed - it).toList() } ?: emptyList()
        val fit = when {
            declared == null -> SkillFit.UNKNOWN
            missing.isEmpty() -> SkillFit.FIT
            else -> SkillFit.MISSING
        }
        val running = middleware.executions().firstOrNull { it.robotId == robotId && it.physicalState.occupiesRobot }?.executionId

        val reasons = buildList {
            when (fit) {
                SkillFit.FIT -> Unit
                SkillFit.MISSING -> add("모자란 스킬: ${missing.joinToString(", ")}")
                SkillFit.UNKNOWN -> add("기체 케이퍼빌리티를 못 물어봤다")
            }
            if (running != null) add("도는 실행이 있다: $running")
            unrestoredOn(robotId).takeIf { it.isNotEmpty() }?.let { add("$UNRESTORED_REASON: ${it.joinToString(", ")}") }
            openHolds.filter { it.robotId == robotId }.takeIf { it.isNotEmpty() }
                ?.let { holds -> add("$RESIDUAL_HOLD_REASON: ${holds.joinToString(", ") { "${it.jobOrderId} ${it.unitId}" }}") }
            if (!applied) add(UNAPPLIED_REASON)
            if (journalBlocked().isNotEmpty()) add("실행 일지 저장 실패: 호스트 복구 전 배정 차단")
        }
        return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons, requiredSkills = needed.toList(), availableSkills = declared?.sorted())
    }

    /**
     * 이 기체에서 다시 짓지 못한 작업 지시(T4). 미룬 행은 늘 든다. 포기한 행은 그 기체의 스냅숏을 이번에 한 번 읽어, 그 작업 지시의
     * 종료하지 않은 태스크(id 가 `jobOrderId#` 로 시작, `@rN` 이 붙은 재작업 태스크 포함)가 없으면 풀고 더 빼지 않는다. 스냅숏을 못 읽으면
     * 계속 뺀다.
     */
    private fun unrestoredOn(robotId: String): List<String> {
        val waiting = deferred.filter { it.row.robotId == robotId }.map { it.row.jobOrderId }
        val abandoned = gaveUp.filter { it.robotId == robotId }
        if (abandoned.isEmpty()) return waiting
        val snapshot = robots.snapshot(robotId) ?: return waiting + abandoned.map { it.jobOrderId }
        val still = abandoned.filter { row ->
            snapshot.tasks.any { (taskId, state) -> taskId.startsWith("${row.jobOrderId}#") && state !in TERMINAL_TASK_STATES }
        }
        gaveUp.removeAll((abandoned - still.toSet()).toSet())
        return waiting + still.map { it.jobOrderId }
    }

    private fun contentOf(response: JobResponse) = ResponseContent(
        jobOrderId = response.jobOrderId,
        version = response.version,
        physicalState = response.physicalState.name,
        requiredEvidence = response.requiredEvidence.name,
        reachedEvidence = response.reachedEvidence.name,
        completedUnits = response.completedUnits.sorted(),
        unverifiedUnits = response.unverifiedUnits.sorted(),
        incompleteUnits = response.incompleteUnits.keys.sorted(),
        inDoubtUnits = response.inDoubtUnits.sorted(),
        operatorRequired = response.operatorRequired,
        residualHold = response.residualHold.kind.name,
        blockedBy = response.blockedBy.sorted(),
    )

    private fun view(response: JobResponse) = JobResponseView(
        jobResponseId = response.jobResponseId,
        version = response.version,
        physicalState = response.physicalState.name,
        requiredEvidence = response.requiredEvidence.name,
        reachedEvidence = response.reachedEvidence.name,
        completedUnits = response.completedUnits,
        unverifiedUnits = response.unverifiedUnits,
        incompleteUnits = response.incompleteUnits,
        inDoubtUnits = response.inDoubtUnits,
        operatorRequired = response.operatorRequired,
        residualHold = response.residualHold.kind.name,
        blockedBy = response.blockedBy,
        connection = response.connection,
    )

    /**
     * pump 와 심장박동을 멈추고 멈춤을 적는다. 다음 기동은 이 인스턴스의 행을 만료를 기다리지 않고 가져간다. 멈춤을 못 적으면(DB
     * 실패) 다음 기동은 만료 폭만큼 그 행을 미룬다.
     */
    override fun close() {
        pumper.shutdownNow()
        beater.shutdownNow()
        pumper.awaitTermination(2, TimeUnit.SECONDS)
        beater.awaitTermination(2, TimeUnit.SECONDS)
        try {
            records.stopInstance(instanceId)
        } catch (e: Exception) {
            log.warn("인스턴스 멈춤을 적지 못했다: {}", e.toString())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MissionHost::class.java)

        /** pump 주기(T5). 셀 대역이 채운 슬롯을 E2 마감(15초)보다 훨씬 짧게 다시 읽는다. */
        val PUMP_PERIOD: Duration = Duration.ofMillis(250)

        /** 현장 시간값 미적용 동안 판정이 기체마다 더하는 이유(S3c 스펙 T8). 화면에 그대로 보인다. */
        const val UNAPPLIED_REASON = "현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다"

        /**
         * 살아 있는 다른 인스턴스가 든 일지 행을 미룬 DEFERRED 사유의 앞부분(HA 설계 기록 §7 단계 0). 뒤에 `: instance=<id>` 가 붙는다.
         * 그 인스턴스의 심장박동이 끊기면 pump 가 다시 짓는다.
         */
        const val HELD_BY_LIVE_INSTANCE = "살아 있는 다른 인스턴스가 든 실행이라 다시 짓지 않는다"

        /** 판단 기록을 못 읽은 GAVE_UP 사유의 앞부분. 뒤에 `: record=<기록 행 번호>, <picasso 의 읽기 거부 문장>` 이 붙는다. */
        const val RECORD_UNREADABLE = "판단 기록을 읽지 못해 다시 짓지 않는다"

        /** 다시 짓지 못한 실행이 있는 기체에 판정이 더하는 이유의 앞부분(S4b 스펙 T4). 뒤에 작업 지시 id 가 붙는다. */
        const val UNRESTORED_REASON = "복원 못 한 실행이 있다"

        /**
         * 미해소 잔여 파지 표시가 있는 기체에 판정이 더하는 이유의 앞부분(P0-b2). 뒤에 `작업 지시 id 단위 id` 가 표시마다 `, ` 로 붙는다.
         * 복원 못 한 실행 다음, 미적용 앞자리다.
         */
        const val RESIDUAL_HOLD_REASON = "잔여 파지 확인 필요"

        /** 에이전트 승인자의 취소·해소 거부 사유의 앞부분. 뒤에 승인자 id 가 붙는다. */
        const val AGENT_CANCEL_REFUSED = "에이전트는 실행을 취소하지 못한다. 실행 중단은 사람의 것이다"
        const val AGENT_CLEAR_REFUSED = "에이전트는 잔여 파지 표시를 해소하지 못한다. 기체가 빈손인지는 사람이 현장에서 확인한다"

        /** 종료 상태(용어집 태스크 수명주기). 사람을 기다리는 RETRIABLE·NEEDS_INTERVENTION 은 종료하지 않은 상태라 기체를 계속 뺀다. */
        private val TERMINAL_TASK_STATES = setOf(
            TaskState.TASK_STATE_SUCCEEDED, TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELLED,
            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
        )

        /**
         * 정착한 실행의 잔여 파지 단위(P0-b2). 경로가 로봇인 단위 가운데 파지 관측이 있는 마지막 단위(계획 순서)의 관측이 HOLDING 이면
         * 그 단위다. 플릿 단위의 HOLDING 은 플릿 AMR 이 용기를 든 것이라 보지 않는다. picasso 는 정착한 실행의 파지를 정보로만 두고 관문에
         * 쓰지 않는다(§15.146). 이 표시는 실행 호스트의 운영 정책이다(ADR 41).
         */
        internal fun residualHoldUnit(units: List<ExecutionUnit>): ExecutionUnit? =
            units.filter { it.route == Route.ROBOT }
                .lastOrNull { it.hold.kind != HoldKind.HOLD_KIND_UNSPECIFIED }
                ?.takeIf { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }

        /**
         * 실행 id → 아웃박스에서 그 실행의 마지막 응답. `GET /host/executions` 가 실행마다 아웃박스를 다시 훑지 않게 한 번에 모은다
         * (HA 설계 기록 §10). 같은 실행의 응답이 여럿이면 뒤에 적힌 것이 남는다.
         */
        internal fun latestResponses(responses: List<JobResponse>): Map<String, JobResponse> = responses.associateBy { it.executionId }

        /** 운영자 판단의 결과 이름. picasso `ResolveOutcome` 의 이름 그대로다(S4a 스펙 T6). */
        const val RESOLVED = "Resolved"
        const val NOT_HELD = "NotHeld"
        const val REFUSED = "Refused"

        /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
        val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
    }
}
