package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.picasso.contracts.v1.TaskState
import dev.picasso.middleware.ActiveMission
import dev.picasso.middleware.Approver
import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.OperatorDecision
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.Route
import dev.picasso.middleware.ResolveOutcome
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.SiteTimingsSource
import dev.picasso.middleware.Unassigned
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.Verification
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellSnapshot
import dev.picasso.ops.host.mission.SiteInputs
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.ops.host.web.BadRequest
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.ops.host.store.CopyResolutionRow
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.IncidentCopyRow
import dev.picasso.ops.host.store.JournalRow
import dev.picasso.ops.host.store.JournalEventKind
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.store.ResponseContent
import dev.picasso.ops.host.store.ResponseDisposition
import dev.picasso.ops.host.store.ResponseLogRow
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
 * @param runningExecutionId 이 기체에서 물리 상태가 정착하지 않은 실행. 없으면 `null`.
 * @param passed 스킬 적합이고 도는 실행이 없다.
 * @param reasons 통과하지 못한 이유. 화면에 그대로 보인다.
 */
data class HostEligibility(
    val robotId: String,
    val skillFit: SkillFit,
    val missingSkills: List<String>,
    val runningExecutionId: String?,
    val passed: Boolean,
    val reasons: List<String>,
)

/** 미들웨어 `Submission` 넷의 이름(S3a 스펙 §7.6). */
enum class SubmitResult { ACCEPTED, IDEMPOTENT, REJECTED, UNASSIGNED }

/** `assign` 이 기체마다 낸 미배정 사유. */
data class Refusal(val robotId: String, val reason: String)

/**
 * 제출의 결과.
 *
 * @param executionId·robotId ACCEPTED·IDEMPOTENT 일 때만 있다.
 * @param rejectionReason REJECTED 일 때만 있다.
 * @param refusals UNASSIGNED 일 때 `assign` 이 기체마다 낸 사유. 호스트가 먼저 뺀 기체는 여기 없고 [excluded] 에 있다.
 * @param excluded 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체와 그 판정.
 */
data class SubmitOutcome(
    val result: SubmitResult,
    val executionId: String?,
    val robotId: String?,
    val rejectionReason: String?,
    val refusals: List<Refusal>,
    val excluded: List<HostEligibility>,
)

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
 * 넷을 보지 않으므로 제출 때 호스트가 다시 판정해 통과한 기체만 `assign` 에 넘긴다.
 *
 * ## 작업 응답과 재기동(S4b 스펙)
 *
 * 상위 시스템이 없어 송신 기록(`mission.job_response_log`)이 전선 자리를 대신한다. pump 뒤 같은 잠금 안에서 응답을 적고 `ack`
 * 한다. 받은 작업 지시는 실행 일지에 적고 기동 때 [start] 가 다시 짓는다. 인시던트와 판단은 사본으로 남는다. 미들웨어 아웃박스는
 * ack 한 응답도 들고 있어 계속 자라는 것은 그대로 한계다(S3a 스펙 §12).
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
) : AutoCloseable {

    private val lock = ReentrantLock(true)
    private val signals = CellBandSignals()

    private val middleware = Middleware(robots = robots, cell = signals, now = clock::now, missions = catalog, siteTimings = siteTimings)

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
     * 포기했고 정착하지 않은 일지 행(T4). 그 기체의 스냅숏에 그 작업 지시의 비종착 태스크가 없어질 때까지 그 기체를 판정에서 뺀다.
     * 이전 기동에서 포기한 행도 든다. 풀린 행은 이 인스턴스에서 다시 빼지 않는다.
     */
    private val gaveUp = mutableListOf<JournalRow>()

    /** 미룬 행. [order]·[mission] 은 복원 때 한 번 읽은 것이고 다시 시도할 때 그대로 쓴다. */
    private class Deferred(val row: JournalRow, val order: JobOrder, val mission: ActiveMission, val index: Int)

    private val pumper = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-pump").apply { isDaemon = true } }

    val instanceId: String = middleware.instanceId

    /**
     * 실행을 복원하고 pump 를 시작한다. [MissionHostApplication] 이 빈을 만들 때 부른다. 복원이 빈 생성 안에서 끝나므로 웹 서버가
     * 열리기 전에 판정이 도는 실행을 안다(S4b 스펙 T3). 복원 중 DB 가 실패하면 예외가 나가 기동이 멈춘다.
     */
    fun start(period: Duration = PUMP_PERIOD): MissionHost = apply {
        restore()
        pumper.scheduleWithFixedDelay(::pumpOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 기동 복원(S4b 스펙 T3, §6.3). 정착도 포기도 없는 일지 행을 받은 순서대로 다시 짓는다. 결과는 일지 이벤트로 남고 복원 보고에
     * 실린다. 그 뒤 포기한 행(이전 기동의 것 포함)을 판정 제외 목록으로 읽는다.
     */
    private fun restore() = lock.withLock {
        restoredAt = clock.now()
        records.openJournal().forEach { row -> restoreRow(row) }
        // 이번 기동에서 포기한 행은 attempt 가 이미 넣었고 그 GAVE_UP 이벤트도 일지에 있다. 작업 지시마다 한 번만 든다.
        gaveUp += records.gaveUpJournal().filter { row -> gaveUp.none { it.jobOrderId == row.jobOrderId } }
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
        attempt(Deferred(row, prepared.first, prepared.second, index), previous, first = true)
    }

    /** 미룬 행을 다시 시도한다(T3). pump 가 미들웨어 pump 전에 잠금 아래에서 부른다. 이벤트를 못 적으면 다음 pump 에 다시 한다. */
    private fun retryDeferred() {
        deferred.toList().forEach { waiting ->
            val previous = restoreRows[waiting.index].let { RestoredFromView(it.previousInstanceId, it.previousExecutionId) }
            try {
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
     * @return 결과, RESTORED 의 실행 id, DEFERRED·GAVE_UP 의 사유
     */
    private fun resumed(waiting: Deferred): Triple<RestoreResult, String?, String?> =
        when (val submission = middleware.resume(waiting.order, waiting.row.robotId, waiting.mission)) {
            is Middleware.Submission.Accepted -> Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            is Middleware.Submission.Idempotent -> Triple(RestoreResult.RESTORED, submission.execution.executionId, null)
            is Middleware.Submission.Rejected ->
                if (submission.reason.startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE)) {
                    Triple(RestoreResult.DEFERRED, null, submission.reason)
                } else {
                    Triple(RestoreResult.GAVE_UP, null, submission.reason)
                }
            is Unassigned -> Triple(RestoreResult.GAVE_UP, null, "배정되지 않았다: ${submission.refusals}")
        }

    private fun restoreView(): RestoreView? = restoredAt?.let { RestoreView(it, restoreRows.toList()) }

    /** pump 한 번. 예외를 삼키고 다음 주기에 다시 돈다. 스케줄러는 작업이 예외를 던지면 다음 실행을 멈춘다. */
    fun pumpOnce() {
        try {
            val cell = cellBand.fetch()
            lock.withLock {
                signals.snapshot = cell
                latestCell = cell
                val at = clock.now()
                retryDeferred()
                middleware.pump()
                pumpedAt = at
                recordAfterPump()
            }
        } catch (e: Exception) {
            log.warn("pump 실패: {}", e.toString())
        }
    }

    /** 기체마다 호스트 판정을 낸다. [order] 의 WorkMaster 는 부르는 쪽이 [WORK_MASTERS] 로 거른다. */
    fun eligibility(order: JobOrder, robotIds: List<String>): List<HostEligibility> = lock.withLock {
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
     * 실패하면 [JournalWriteFailed] 이고 실행은 미들웨어에 남는다(§9, 한계).
     */
    fun submit(order: JobOrder, candidates: List<String>): SubmitOutcome = lock.withLock {
        val applied = siteTimings.current() != null
        val judged = candidates.distinct().map { judge(order, it, applied) }
        val excluded = judged.filter { !it.passed }
        val existed = middleware.executions().any { it.order.jobOrderId == order.jobOrderId }
        when (val submission = middleware.assign(order, judged.filter { it.passed }.map { it.robotId })) {
            is Middleware.Submission.Accepted -> {
                if (!existed) journal(submission.execution)
                SubmitOutcome(
                    SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
                )
            }
            is Middleware.Submission.Idempotent -> SubmitOutcome(
                SubmitResult.IDEMPOTENT, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
            )
            is Middleware.Submission.Rejected -> SubmitOutcome(
                SubmitResult.REJECTED, null, null, submission.reason, emptyList(), excluded,
            )
            is Unassigned -> SubmitOutcome(
                SubmitResult.UNASSIGNED, null, null, null,
                submission.refusals.map { (robotId, reason) -> Refusal(robotId, reason) }, excluded,
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

    fun executions(): ExecutionsView = lock.withLock {
        val responses = middleware.responses()
        ExecutionsView(
            instanceId = middleware.instanceId,
            pumpedAt = pumpedAt,
            executions = middleware.executions().map { execution ->
                ExecutionView(
                    executionId = execution.executionId,
                    jobOrderId = execution.order.jobOrderId,
                    workMasterId = execution.order.workMasterId,
                    missionVersion = execution.missionVersion,
                    robotId = execution.robotId,
                    physicalState = execution.physicalState.name,
                    units = execution.units.map { UnitView(it.unitId, it.skillType, it.state.name, it.reached.name) },
                    jobResponse = responses.lastOrNull { it.executionId == execution.executionId }?.let(::view),
                    restoredFrom = restoredFrom[execution.executionId],
                )
            },
            restore = restoreView(),
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
        if (pending.isEmpty() && fresh.isEmpty() && resolved.isEmpty() && settled.isEmpty()) return

        val held = heldIncidents(incidents)
        val copies = fresh.map { it to json.writeValueAsString(IncidentViews.detail(instanceId, it, it.incidentId in held, unitStateOf(it))) }
        try {
            records.inTransaction {
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
                settled.forEach {
                    records.event(it.order.jobOrderId, JournalEventKind.SETTLED, instanceId, it.executionId, it.physicalState.name)
                }
            }
        } catch (e: Exception) {
            log.warn("pump 뒤 기록 실패, 다음 pump 에 다시 한다: {}", e.toString())
            return
        }
        copiedIncidents = incidents.size
        copiedResolutions += resolved.map { it.incidentId }
        settledRecorded += settled.map { it.order.jobOrderId }
        pending.forEach { middleware.ack(it.jobResponseId) }
    }

    private fun logResponse(response: JobResponse) {
        val content = contentOf(response)
        val duplicate = !records.loggedIn(instanceId, response.jobOrderId) && records.lastSent(response.jobOrderId)?.content == content
        records.logResponse(
            instanceId, response.jobResponseId, response.executionId, content,
            if (duplicate) ResponseDisposition.RESTART_DUPLICATE else ResponseDisposition.SENT,
        )
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
                ResolveOutcome.Resolved -> ResolveView(RESOLVED, null, target, requestId)
                ResolveOutcome.NotHeld -> ResolveView(NOT_HELD, null, null, requestId)
                is ResolveOutcome.Refused -> ResolveView(REFUSED, outcome.reason, null, requestId)
            }
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
     * 도는 실행은 그 기체의 실행 중 물리 상태가 정착하지 않은 것이다. 운영자 보류에 선 실행도 도는 실행이다(스펙 §12).
     */
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
        val running = middleware.executions().firstOrNull { it.robotId == robotId && !it.physicalState.isSettled }?.executionId

        val reasons = buildList {
            when (fit) {
                SkillFit.FIT -> Unit
                SkillFit.MISSING -> add("모자란 스킬: ${missing.joinToString(", ")}")
                SkillFit.UNKNOWN -> add("기체 케이퍼빌리티를 못 물어봤다")
            }
            if (running != null) add("도는 실행이 있다: $running")
            unrestoredOn(robotId).takeIf { it.isNotEmpty() }?.let { add("$UNRESTORED_REASON: ${it.joinToString(", ")}") }
            if (!applied) add(UNAPPLIED_REASON)
        }
        return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons)
    }

    /**
     * 이 기체에서 다시 짓지 못한 작업 지시(T4). 미룬 행은 늘 든다. 포기한 행은 그 기체의 스냅숏을 이번에 한 번 읽어, 그 작업 지시의
     * 비종착 태스크(id 가 `jobOrderId#` 로 시작, `@rN` 이 붙은 재작업 태스크 포함)가 없으면 풀고 더 빼지 않는다. 스냅숏을 못 읽으면
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

    override fun close() {
        pumper.shutdownNow()
        pumper.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MissionHost::class.java)

        /** pump 주기(T5). 셀 대역이 채운 슬롯을 E2 마감(15초)보다 훨씬 짧게 다시 읽는다. */
        val PUMP_PERIOD: Duration = Duration.ofMillis(250)

        /** 현장 시간값 미적용 동안 판정이 기체마다 더하는 이유(S3c 스펙 T8). 화면에 그대로 보인다. */
        const val UNAPPLIED_REASON = "현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다"

        /** 다시 짓지 못한 실행이 있는 기체에 판정이 더하는 이유의 앞부분(S4b 스펙 T4). 뒤에 작업 지시 id 가 붙는다. */
        const val UNRESTORED_REASON = "복원 못 한 실행이 있다"

        /** 종착한 태스크 상태. 미들웨어가 단위의 종착으로 보는 것과 같다(사람을 기다리는 RETRIABLE·NEEDS_INTERVENTION 포함). */
        private val TERMINAL_TASK_STATES = setOf(
            TaskState.TASK_STATE_SUCCEEDED, TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_CANCELLED,
            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED, TaskState.TASK_STATE_NEEDS_INTERVENTION, TaskState.TASK_STATE_RETRIABLE,
        )

        /** 운영자 판단의 결과 이름. picasso `ResolveOutcome` 의 이름 그대로다(S4a 스펙 T6). */
        const val RESOLVED = "Resolved"
        const val NOT_HELD = "NotHeld"
        const val REFUSED = "Refused"

        /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
        val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
    }
}
