package dev.picasso.ops.host

import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.Route
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.Unassigned
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellSnapshot
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
 * 미들웨어 실행 호스트(S3a 스펙 §7).
 *
 * ## 잠금 하나
 *
 * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회를 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
 * mimic 엔진 잠금으로 한 방향뿐이다(gRPC 호출이 잠금 아래에서 나간다).
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
 * @param robots 하류 포트. 판정의 케이퍼빌리티도 이것으로 묻는다(`PicassoClient` 가 세대별로 캐시한다).
 */
class MissionHost(
    private val robots: RobotPort,
    private val cellBand: CellBandClient,
    private val clock: HostClock,
) : AutoCloseable {

    private val lock = ReentrantLock(true)
    private val signals = CellBandSignals()

    /** 코드 정의 임무의 카탈로그. 미들웨어에 넘긴 것과 같은 참조로 스킬 적합을 판정한다. */
    private val catalog = InMemoryMissionCatalog(now = clock::now)

    private val middleware = Middleware(robots = robots, cell = signals, now = clock::now, missions = catalog)

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
        robotIds.distinct().map { judge(order, it) }
    }

    /**
     * 판정을 다시 해 통과한 기체만 `assign` 에 넘긴다. 통과한 기체가 없어도 `assign` 을 부른다. 그때 결과는 사유 없는
     * UNASSIGNED 이고 이유는 [SubmitOutcome.excluded] 에 있다.
     *
     * 도는 실행과 같은 작업 지시 id 로 다시 내면 판정이 그 기체를 도는 실행으로 빼므로 IDEMPOTENT 가 아니라 UNASSIGNED 다.
     * 운영 서비스는 작업 지시 id 를 다시 쓰지 않는다.
     */
    fun submit(order: JobOrder, candidates: List<String>): SubmitOutcome = lock.withLock {
        val judged = candidates.distinct().map { judge(order, it) }
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

    /** 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null` 이다. */
    fun cell(): CellSnapshot? = lock.withLock { latestCell }

    /**
     * 스킬 적합은 지금 활성 정의로 작업 지시를 계획해, 경로가 로봇인 단위의 스킬이 기체가 선언한 스킬에 다 있는가다.
     * 도는 실행은 그 기체의 실행 중 물리 상태가 정착하지 않은 것이다. 운영자 보류에 선 실행도 도는 실행이다(스펙 §12).
     */
    private fun judge(order: JobOrder, robotId: String): HostEligibility {
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
        }
        return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons)
    }

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

        /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
        val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
    }
}
