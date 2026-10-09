package dev.picasso.ops.service.settings

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** 판정에 쓰는 현장 설정 한 세트(S2 스펙 §5). [version] 이 근거 버전이 된다. */
data class SiteSettingsValues(val version: Long, val connectionThreshold: Duration)

/** 지금의 현장 설정. 못 읽으면 예외를 던진다. 기본값으로 대신하지 않는다(S2 스펙 §6.4). */
fun interface SettingsSource {
    fun current(): SiteSettingsValues
}

/**
 * 현장 설정 한 세트의 값 다섯(S3c 스펙 §6). 모두 초 단위 정수다. 연결 기준 시간은 운영 서비스의 판정 값이고, 나머지 넷은
 * 실행 호스트가 뷰로 읽어 picasso 미들웨어에 주는 시간값이다(T7).
 */
data class SiteSettingsFields(
    val connectionThresholdSeconds: Int,
    val evidenceBeforeSeconds: Int,
    val evidenceAfterSeconds: Int,
    val inDoubtGraceSeconds: Int,
    val stallWindowSeconds: Int,
)

/**
 * 변경 요청의 값 칸(S3c 스펙 §6.2). 칸마다 선택이며 널이면 빠진 것이다. 빠진 칸은 기준 버전의 값으로 채운다([resolve]).
 * 다섯이 다 빠진 요청은 관문이 400 으로 막는다.
 */
data class SiteSettingsChange(
    val connectionThresholdSeconds: Int? = null,
    val evidenceBeforeSeconds: Int? = null,
    val evidenceAfterSeconds: Int? = null,
    val inDoubtGraceSeconds: Int? = null,
    val stallWindowSeconds: Int? = null,
) {
    /** 칸 이름(JSON 본문 이름)과 값. 빠진 칸은 널이다. 순서는 [SiteSettingsRange.FIELDS] 와 같다. */
    fun entries(): List<Pair<String, Int?>> = listOf(
        SiteSettingsRange.CONNECTION_THRESHOLD to connectionThresholdSeconds,
        SiteSettingsRange.EVIDENCE_BEFORE to evidenceBeforeSeconds,
        SiteSettingsRange.EVIDENCE_AFTER to evidenceAfterSeconds,
        SiteSettingsRange.IN_DOUBT_GRACE to inDoubtGraceSeconds,
        SiteSettingsRange.STALL_WINDOW to stallWindowSeconds,
    )

    val isEmpty: Boolean get() = entries().all { it.second == null }

    /** 빠진 칸을 [base] 의 값으로 채운다. */
    fun resolve(base: SiteSettingsFields): SiteSettingsFields = SiteSettingsFields(
        connectionThresholdSeconds = connectionThresholdSeconds ?: base.connectionThresholdSeconds,
        evidenceBeforeSeconds = evidenceBeforeSeconds ?: base.evidenceBeforeSeconds,
        evidenceAfterSeconds = evidenceAfterSeconds ?: base.evidenceAfterSeconds,
        inDoubtGraceSeconds = inDoubtGraceSeconds ?: base.inDoubtGraceSeconds,
        stallWindowSeconds = stallWindowSeconds ?: base.stallWindowSeconds,
    )
}

/** 현장 설정 버전 한 행(S2 스펙 §5, S3c 스펙 §6.1). 값 칸 다섯은 응답 JSON 에 평평하게 실린다. */
data class SiteSettingsRecord(
    val version: Long,
    val connectionThresholdSeconds: Int,
    val evidenceBeforeSeconds: Int,
    val evidenceAfterSeconds: Int,
    val inDoubtGraceSeconds: Int,
    val stallWindowSeconds: Int,
    val mode: Mode,
    val user: String,
    val reason: String,
    val recordedAt: Instant,
) {
    fun values(): SiteSettingsValues = SiteSettingsValues(version, Duration.ofSeconds(connectionThresholdSeconds.toLong()))

    fun fields(): SiteSettingsFields = SiteSettingsFields(
        connectionThresholdSeconds, evidenceBeforeSeconds, evidenceAfterSeconds, inDoubtGraceSeconds, stallWindowSeconds,
    )
}

/**
 * 현장 설정 값의 허용 범위(초).
 *
 * 연결 기준 시간(S2 스펙 §5)은 주인이 picasso 가 아니라 운영 서비스라서 범위도 여기 둔다. 하한은 프로파일 보고 간격 상한
 * 30초의 2배다. 보고를 한 번 놓쳐도 오래됨이 되지 않는다. 보고 간격 가까이 두면 정상 기체가 신선과 오래됨을 오간다.
 * 상한 1시간은 그보다 길면 연결 칸이 뜻을 잃는다는 판단이다.
 *
 * 시간값 넷(S3c 스펙 §5.1, T3)의 범위는 picasso `SiteTimings` 범위 상수의 사본이다. 주인은 picasso 이지만 운영 서비스
 * main 은 picasso 를 쓰지 못한다(`checkNoPicassoOnMain`). 사본이 picasso 와 같은지는 통합 시험이 대조한다(T6).
 */
object SiteSettingsRange {
    const val MIN_CONNECTION_THRESHOLD_SECONDS = 60
    const val MAX_CONNECTION_THRESHOLD_SECONDS = 3600

    /** picasso `SiteTimings.EVIDENCE_WINDOW_BEFORE_SECONDS` 의 사본. */
    const val MIN_EVIDENCE_BEFORE_SECONDS = 5
    const val MAX_EVIDENCE_BEFORE_SECONDS = 120

    /** picasso `SiteTimings.EVIDENCE_WINDOW_AFTER_SECONDS` 의 사본. */
    const val MIN_EVIDENCE_AFTER_SECONDS = 5
    const val MAX_EVIDENCE_AFTER_SECONDS = 120

    /** picasso `SiteTimings.IN_DOUBT_GRACE_SECONDS` 의 사본. */
    const val MIN_IN_DOUBT_GRACE_SECONDS = 10
    const val MAX_IN_DOUBT_GRACE_SECONDS = 600

    /** picasso `SiteTimings.STALL_WINDOW_SECONDS` 의 사본. */
    const val MIN_STALL_WINDOW_SECONDS = 30
    const val MAX_STALL_WINDOW_SECONDS = 3600

    /** 본문 칸 이름. 범위 밖 메시지가 이 이름으로 칸을 가리킨다. */
    const val CONNECTION_THRESHOLD = "connectionThresholdSeconds"
    const val EVIDENCE_BEFORE = "evidenceBeforeSeconds"
    const val EVIDENCE_AFTER = "evidenceAfterSeconds"
    const val IN_DOUBT_GRACE = "inDoubtGraceSeconds"
    const val STALL_WINDOW = "stallWindowSeconds"

    /** 칸 이름과 범위. 순서가 본문 칸의 순서이고 범위 밖 메시지의 순서다. */
    val FIELDS: List<Pair<String, IntRange>> = listOf(
        CONNECTION_THRESHOLD to MIN_CONNECTION_THRESHOLD_SECONDS..MAX_CONNECTION_THRESHOLD_SECONDS,
        EVIDENCE_BEFORE to MIN_EVIDENCE_BEFORE_SECONDS..MAX_EVIDENCE_BEFORE_SECONDS,
        EVIDENCE_AFTER to MIN_EVIDENCE_AFTER_SECONDS..MAX_EVIDENCE_AFTER_SECONDS,
        IN_DOUBT_GRACE to MIN_IN_DOUBT_GRACE_SECONDS..MAX_IN_DOUBT_GRACE_SECONDS,
        STALL_WINDOW to MIN_STALL_WINDOW_SECONDS..MAX_STALL_WINDOW_SECONDS,
    )

    /** 연결 기준 시간의 범위 검사. */
    fun allows(seconds: Long): Boolean = seconds in MIN_CONNECTION_THRESHOLD_SECONDS..MAX_CONNECTION_THRESHOLD_SECONDS

    /** [field] 칸의 값 [value] 가 범위 밖이면 그 문장, 안이면 `null`. 문장은 칸 이름으로 시작한다. */
    fun problem(field: String, value: Long): String? {
        val range = FIELDS.first { it.first == field }.second
        return if (value in range.first.toLong()..range.last.toLong()) {
            null
        } else {
            "$field ${value}초는 범위 밖이다. ${range.first}~${range.last}초여야 한다"
        }
    }

    /** [change] 에 실린 칸 중 범위 밖인 것. 칸마다 한 문장이다. 빠진 칸은 기준 버전의 값이라 보지 않는다. */
    fun problems(change: SiteSettingsChange): List<String> =
        change.entries().mapNotNull { (field, value) -> value?.let { problem(field, it.toLong()) } }
}

/** ops 스키마의 현장 설정 버전 표. 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다. */
class SiteSettingsStore(private val jdbc: JdbcClient) : SettingsSource {

    override fun current(): SiteSettingsValues = latest().values()

    /** 가장 큰 버전. 마이그레이션이 버전 1 을 넣으므로 늘 있다. */
    fun latest(): SiteSettingsRecord = history(limit = 1).single()

    /** 최신 버전부터. */
    fun history(limit: Int = 200): List<SiteSettingsRecord> =
        jdbc.sql(
            """
            SELECT version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds,
                   in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason, recorded_at
            FROM ops.site_settings
            ORDER BY version DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .param("limit", limit)
            .query { rs, _ -> record(rs) }
            .list()

    /** 버전 [version] 한 행. 없으면 `null`. */
    fun find(version: Long): SiteSettingsRecord? =
        jdbc.sql(
            """
            SELECT version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds,
                   in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason, recorded_at
            FROM ops.site_settings
            WHERE version = :version
            """.trimIndent(),
        )
            .param("version", version)
            .query { rs, _ -> record(rs) }
            .optional()
            .orElse(null)

    /** 버전 [version] 행을 넣는다. 그 번호가 이미 있으면 기본 키가 막는다(`DuplicateKeyException`). */
    fun insert(version: Long, fields: SiteSettingsFields, actor: Actor, reason: String) {
        jdbc.sql(
            """
            INSERT INTO ops.site_settings (version, connection_threshold_seconds, evidence_before_seconds, evidence_after_seconds,
                                           in_doubt_grace_seconds, stall_window_seconds, mode, actor_user, reason)
            VALUES (:version, :threshold, :before, :after, :grace, :stall, :mode, :user, :reason)
            """.trimIndent(),
        )
            .param("version", version)
            .param("threshold", fields.connectionThresholdSeconds)
            .param("before", fields.evidenceBeforeSeconds)
            .param("after", fields.evidenceAfterSeconds)
            .param("grace", fields.inDoubtGraceSeconds)
            .param("stall", fields.stallWindowSeconds)
            .param("mode", actor.mode.name)
            .param("user", actor.user)
            .param("reason", reason)
            .update()
    }

    private fun record(rs: ResultSet) = SiteSettingsRecord(
        version = rs.getLong("version"),
        connectionThresholdSeconds = rs.getInt("connection_threshold_seconds"),
        evidenceBeforeSeconds = rs.getInt("evidence_before_seconds"),
        evidenceAfterSeconds = rs.getInt("evidence_after_seconds"),
        inDoubtGraceSeconds = rs.getInt("in_doubt_grace_seconds"),
        stallWindowSeconds = rs.getInt("stall_window_seconds"),
        mode = Mode.valueOf(rs.getString("mode")),
        user = rs.getString("actor_user"),
        reason = rs.getString("reason"),
        recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
    )
}
