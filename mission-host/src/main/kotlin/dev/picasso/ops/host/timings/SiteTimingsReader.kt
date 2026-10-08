package dev.picasso.ops.host.timings

import dev.picasso.middleware.SiteTimings
import dev.picasso.middleware.SiteTimingsSource
import dev.picasso.ops.host.HostClock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 실행 호스트가 읽는 운영 서비스의 현재 버전 뷰(S3c 스펙 §6.3, T7). 뷰의 이름과 칸(이름과 형)이 두 프로세스의 계약이다.
 *
 * 호스트 시험 세트에는 ops 스키마가 없어 이 상수로 대역 뷰를 만들고, 통합 시험은 이 상수를 실제 뷰의 `information_schema`
 * 칸과 대조한다. 그래서 상수를 main 에 둔다. 형 이름은 `information_schema.columns.data_type` 의 값이다.
 */
object SiteTimingsView {
    const val NAME = "ops.site_timings_current"

    data class Column(val name: String, val type: String)

    val COLUMNS: List<Column> = listOf(
        Column("version", "bigint"),
        Column("evidence_before_seconds", "integer"),
        Column("evidence_after_seconds", "integer"),
        Column("in_doubt_grace_seconds", "integer"),
        Column("stall_window_seconds", "integer"),
    )

    /** 읽기 질의. 칸을 이름으로 적는다. 뷰에 칸이 더해져도 읽는 칸은 같다. */
    val SELECT: String = "SELECT ${COLUMNS.joinToString(", ") { it.name }} FROM $NAME"
}

/** 범위 밖이라 적용하지 않은 버전과 그 이유. 이유는 picasso `SiteTimings.problems()` 의 문장 그대로다. */
data class RejectedTimings(val version: Long, val reasons: List<String>)

/**
 * 읽기 주기의 상태(S3c 스펙 §7.1). 통째로 바꾸는 불변 값이다.
 *
 * @param applied 적용한 시간값. 읽기 주기가 받아들여 다음 pump 부터 쓰일 값이다. `null` 이면 미적용이다
 * @param appliedAt [applied] 를 받아들인 호스트 시각
 * @param lastReadAt 마지막으로 읽기를 마친 호스트 시각. 성공과 실패를 가리지 않는다
 * @param readError 마지막 읽기가 실패했으면 그 까닭. 성공하면 지운다
 * @param rejected 마지막으로 읽은 행이 범위 밖이면 그 버전과 이유. 적용할 수 있는 행을 읽으면 지운다
 */
data class SiteTimingsState(
    val applied: SiteTimings?,
    val appliedAt: Instant?,
    val lastReadAt: Instant?,
    val readError: String?,
    val rejected: RejectedTimings?,
) {
    companion object {
        val UNAPPLIED = SiteTimingsState(null, null, null, null, null)
    }
}

/**
 * 현장 시간값 읽기 주기(S3c 스펙 §7.1, T8). 운영 서비스의 뷰를 [period] 마다 **호스트 잠금 밖에서** 읽고, picasso 범위로
 * 검사해 통과한 값만 적용 스냅숏으로 바꾼다. 미들웨어는 [current] 로 그 스냅숏을 받아 다음 pump 부터 쓴다. [current] 는 DB 를
 * 읽지 않고 스냅숏만 돌려주므로 호스트 잠금 아래에서 불려도 막히지 않는다.
 *
 * - 기동 안에서 한 번 동기로 읽는다([start]). 실패하면 미적용으로 뜬다. 기본값으로 대신하지 않는다(S2 스펙 §6.4 의 원칙).
 *   기동 순서상 호스트가 운영 서비스보다 먼저 뜰 수 있어 기동 실패로 두지 않는다
 * - 그 뒤 읽기가 실패하면 마지막으로 적용한 값을 계속 쓰고 [SiteTimingsState.readError] 에 남긴다
 * - 범위 밖 행은 적용하지 않고 마지막 값을 유지하며 [SiteTimingsState.rejected] 에 버전과 이유를 남긴다
 * - 같은 버전이면 바꾸지 않는다. 버전이 다르면 작아도 적용한다(뷰는 가장 큰 버전 하나만 보이므로 작아지는 것은 ops 스키마를
 *   다시 세운 경우뿐이고, 그때도 뷰가 지금 값이다)
 *
 * 쓰는 쪽은 읽기 주기 하나뿐이다([readOnce] 는 동기화된다).
 */
class SiteTimingsReader(
    private val jdbc: JdbcClient,
    private val clock: HostClock,
) : SiteTimingsSource, AutoCloseable {

    private val state = AtomicReference(SiteTimingsState.UNAPPLIED)

    private val reader = Executors.newSingleThreadScheduledExecutor { Thread(it, "site-timings-reader").apply { isDaemon = true } }

    override fun current(): SiteTimings? = state.get().applied

    fun state(): SiteTimingsState = state.get()

    /**
     * 한 번 동기로 읽고 그 뒤 [period] 마다 읽는다. [MissionHostApplication][dev.picasso.ops.host.MissionHostApplication] 이 빈을 만들 때
     * 부른다. 스케줄러가 밀리초로 받으므로 [period] 는 1ms 이상이어야 한다(1ms 미만은 0 으로 잘려 `scheduleWithFixedDelay` 가 거부한다).
     */
    fun start(period: Duration = READ_PERIOD): SiteTimingsReader = apply {
        require(period.toMillis() > 0) { "현장 시간값 읽기 주기(host.site-timings.read-interval)는 1ms 이상이어야 한다: $period" }
        readOnce()
        reader.scheduleWithFixedDelay(::readOnce, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 뷰를 한 번 읽어 상태를 바꾼다. 스케줄러는 작업이 무엇이든 던지면 다음 실행을 조용히 멈추므로, `Exception` 이 아닌 `Error`
     * (드라이버 클래스를 못 찾는 `LinkageError`, 단언 실패 등)도 잡아 [SiteTimingsState.readError] 에 남기고 다음 주기에 다시 읽는다.
     *
     * `VirtualMachineError`(메모리 부족, 스택 넘침 등)만 다시 던진다. JVM 이 더 믿을 수 없는 상태라 같은 작업을 1초마다 되풀이해
     * 가릴 일이 아니고, 잡아 두면 그 오류를 다룰 바깥(스레드의 처리기, 프로세스 감시)이 보지 못한다. 그때 주기 읽기는 멈추고
     * 마지막 적용 값이 남는다.
     */
    @Synchronized
    fun readOnce() {
        val before = state.get()
        val after = try {
            next(before, read(), clock.now())
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            before.copy(lastReadAt = runCatching { clock.now() }.getOrNull() ?: before.lastReadAt, readError = describe(e))
        }
        state.set(after)
        if (after.readError != null && after.readError != before.readError) log.warn("현장 시간값 읽기 실패: {}", after.readError)
        if (after.rejected != null && after.rejected != before.rejected) log.warn("현장 시간값 버전 {} 을 적용하지 않음: {}", after.rejected.version, after.rejected.reasons)
        if (after.applied != before.applied) log.info("현장 시간값 버전 {} 적용", after.applied?.siteSettingsVersion)
    }

    private fun read(): List<SiteTimings> =
        jdbc.sql(SiteTimingsView.SELECT)
            .query { rs, _ ->
                SiteTimings.ofSeconds(
                    rs.getLong("version"),
                    rs.getLong("evidence_before_seconds"),
                    rs.getLong("evidence_after_seconds"),
                    rs.getLong("in_doubt_grace_seconds"),
                    rs.getLong("stall_window_seconds"),
                )
            }
            .list()

    private fun next(before: SiteTimingsState, rows: List<SiteTimings>, at: Instant): SiteTimingsState {
        if (rows.size != 1) return before.copy(lastReadAt = at, readError = "${SiteTimingsView.NAME} 가 행 ${rows.size} 개를 냈다(한 행이어야 한다)")
        val row = rows.single()
        if (row.siteSettingsVersion == before.applied?.siteSettingsVersion) {
            return before.copy(lastReadAt = at, readError = null, rejected = null)
        }
        val problems = row.problems()
        if (problems.isNotEmpty()) {
            return before.copy(lastReadAt = at, readError = null, rejected = RejectedTimings(row.siteSettingsVersion, problems))
        }
        return SiteTimingsState(applied = row, appliedAt = at, lastReadAt = at, readError = null, rejected = null)
    }

    private fun describe(e: Throwable): String =
        generateSequence<Throwable>(e) { it.cause }.last().let { root -> "${root.javaClass.simpleName}: ${root.message}" }

    override fun close() {
        reader.shutdownNow()
        reader.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SiteTimingsReader::class.java)

        /** 읽기 주기 기본값(S3c 스펙 사용자 결정 3). 기동에서는 설정 키 `host.site-timings.read-interval` 이 준다. */
        val READ_PERIOD: Duration = Duration.ofSeconds(1)
    }
}
