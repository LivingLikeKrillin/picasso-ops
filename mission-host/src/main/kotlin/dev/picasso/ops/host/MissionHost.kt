package dev.picasso.ops.host

import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.Route
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.SiteTimingsSource
import dev.picasso.middleware.Unassigned
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellSnapshot
import dev.picasso.ops.host.mission.SiteInputs
import dev.picasso.ops.host.mission.StoredMissionCatalog
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
)

/**
 * `GET /host/executions` 의 본문. [instanceId] 는 미들웨어가 뜬 한 번을 가리킨다. 재기동하면 바뀌고 `exec-N` 은 1부터
 * 다시 센다. [pumpedAt] 은 마지막 pump 가 셀 대역을 읽고 잠금을 잡은 뒤의 호스트 시계 값이며 아직 한 번도 안 돌았으면 `null` 이다.
 */
data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val executions: List<ExecutionView>)

/**
 * 인시던트 하나(S3c 스펙 §7.2). 미들웨어 `IncidentBundle` 에서 통합 시험과 화면이 쓰는 칸만 옮긴다. 시간값은 picasso 의 ISO-8601
 * 문자열(`Duration.toString()`, 60초면 `PT1M`)을 초 단위 정수로 되돌린 것이다.
 *
 * @param at 봉인 라운드의 미들웨어 시각(호스트 시계)
 * @param missionVersion 임무 버전. `null` 이면 코드 정의다
 * @param siteSettingsVersion 봉인 라운드의 현장 설정 버전. 현장 시간값 없이 봉인했으면 `null` 이다
 * @param evidenceBeforeSeconds·evidenceAfterSeconds 봉인 라운드의 근거 윈도우 앞·뒤 폭. 늘 있다
 * @param inDoubtGraceSeconds·stallWindowSeconds 봉인 라운드의 값. 현장 시간값 없이 봉인했으면 `null` 이다
 */
data class IncidentView(
    val incidentId: String,
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val unitId: String,
    val at: Instant,
    val failureClass: String?,
    val route: String,
    val missionVersion: Int?,
    val siteSettingsVersion: Long?,
    val evidenceBeforeSeconds: Long,
    val evidenceAfterSeconds: Long,
    val inDoubtGraceSeconds: Long?,
    val stallWindowSeconds: Long?,
)

/** `GET /host/incidents` 의 본문. [incidents] 는 최신부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
data class IncidentsView(val instanceId: String, val total: Int, val incidents: List<IncidentView>)

/**
 * 미들웨어 실행 호스트(S3a 스펙 §7).
 *
 * ## 잠금 하나
 *
 * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회를 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
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
 * ## 작업 응답
 *
 * 상위 시스템이 없어 `ack` 하지 않는다(스펙 §7.7). 아웃박스가 계속 자라는 것은 한계다(스펙 §12).
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
) : AutoCloseable {

    private val lock = ReentrantLock(true)
    private val signals = CellBandSignals()

    private val middleware = Middleware(robots = robots, cell = signals, now = clock::now, missions = catalog, siteTimings = siteTimings)

    private var pumpedAt: Instant? = null
    private var latestCell: CellSnapshot? = null

    private val pumper = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-pump").apply { isDaemon = true } }

    val instanceId: String get() = middleware.instanceId

    /** pump 를 시작한다. [MissionHostApplication] 이 빈을 만들 때 부른다. */
    fun start(period: Duration = PUMP_PERIOD): MissionHost = apply {
        pumper.scheduleWithFixedDelay(::pumpOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** pump 한 번. 예외를 삼키고 다음 주기에 다시 돈다. 스케줄러는 작업이 예외를 던지면 다음 실행을 멈춘다. */
    fun pumpOnce() {
        try {
            val cell = cellBand.fetch()
            lock.withLock {
                signals.snapshot = cell
                latestCell = cell
                val at = clock.now()
                middleware.pump()
                pumpedAt = at
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
     */
    fun submit(order: JobOrder, candidates: List<String>): SubmitOutcome = lock.withLock {
        val applied = siteTimings.current() != null
        val judged = candidates.distinct().map { judge(order, it, applied) }
        val excluded = judged.filter { !it.passed }
        when (val submission = middleware.assign(order, judged.filter { it.passed }.map { it.robotId })) {
            is Middleware.Submission.Accepted -> SubmitOutcome(
                SubmitResult.ACCEPTED, submission.execution.executionId, submission.execution.robotId, null, emptyList(), excluded,
            )
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
                )
            },
        )
    }

    /**
     * 봉인된 인시던트(S3c 스펙 §7.2). 최신부터 많아야 [limit] 개다. 번들은 미들웨어 안의 값이라 호스트 잠금 아래에서 옮긴다.
     */
    fun incidents(limit: Int): IncidentsView = lock.withLock {
        val all = middleware.incidents()
        IncidentsView(
            instanceId = middleware.instanceId,
            total = all.size,
            incidents = all.asReversed().take(limit).map { bundle ->
                val intent = bundle.intent
                IncidentView(
                    incidentId = bundle.incidentId,
                    executionId = bundle.executionId,
                    jobOrderId = bundle.jobOrderId,
                    robotId = bundle.robotId,
                    unitId = bundle.unitId,
                    at = bundle.at,
                    failureClass = bundle.failureClass,
                    route = bundle.route,
                    missionVersion = intent.missionVersion,
                    siteSettingsVersion = intent.siteSettingsVersion,
                    evidenceBeforeSeconds = seconds(intent.evidenceWindowBefore),
                    evidenceAfterSeconds = seconds(intent.evidenceWindowAfter),
                    inDoubtGraceSeconds = intent.inDoubtGrace?.let(::seconds),
                    stallWindowSeconds = intent.stallWindow?.let(::seconds),
                )
            },
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
            if (!applied) add(UNAPPLIED_REASON)
        }
        return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons)
    }

    /** picasso 의 ISO-8601 기간 문자열을 초로 되돌린다. 60초는 `PT1M` 으로 접혀 온다. */
    private fun seconds(iso: String): Long = Duration.parse(iso).seconds

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

        /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
        val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
    }
}
