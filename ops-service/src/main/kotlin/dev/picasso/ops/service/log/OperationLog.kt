package dev.picasso.ops.service.log

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 조작 결과(스펙 §7.1). 앞의 3개는 조작 행, 뒤의 2개는 «응답 없음» 뒤 재조회 행이다.
 * 재조회 행은 같은 요청 id 로 새로 붙고, 처음 행은 그대로 남는다.
 */
enum class OperationResult { SUCCEEDED, REJECTED, NO_RESPONSE, CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED }

/**
 * 조작 기록 한 행. 칸 9개(스펙 §7.1). [request]·[targetResponse] 는 JSON 문자열이다.
 *
 * [targetResponse] 는 상태를 바꾸는 쪽(registry 또는 실행 호스트)의 응답이다. S2 까지는 registry 만 있어
 * `registry_response` 였고, S3a 의 V3 마이그레이션이 이름을 바꿨다(S3a 스펙 §8).
 */
data class OperationRecord(
    val requestId: UUID,
    val mode: Mode,
    val user: String,
    val target: String,
    val request: String,
    val reason: String?,
    val result: OperationResult,
    val targetResponse: String?,
    val recordedAt: Instant,
)

/** ops 스키마의 조작 기록. 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다. */
class OperationLog(private val jdbc: JdbcClient) {

    fun append(
        requestId: UUID,
        actor: Actor,
        target: String,
        request: String,
        reason: String?,
        result: OperationResult,
        targetResponse: String?,
    ) {
        jdbc.sql(
            """
            INSERT INTO ops.operation_log
                (request_id, mode, actor_user, target, request, reason, result, target_response)
            VALUES
                (:requestId, :mode, :user, :target, CAST(:request AS JSONB), :reason, :result,
                 CAST(:targetResponse AS JSONB))
            """.trimIndent(),
        )
            .param("requestId", requestId)
            .param("mode", actor.mode.name)
            .param("user", actor.user)
            .param("target", target)
            .param("request", request, Types.VARCHAR)
            .param("reason", reason, Types.VARCHAR)
            .param("result", result.name)
            .param("targetResponse", targetResponse, Types.VARCHAR)
            .update()
    }

    /**
     * 최근 것부터. 순서 칸을 따로 두지 않는다(칸 9개, 스펙 §7.1). `recorded_at` 은 문장마다 `clock_timestamp()` 라
     * 실제로는 겹치지 않지만, 같은 시각이면 둘의 순서는 정하지 않는다.
     */
    fun list(limit: Int = 200): List<OperationRecord> =
        jdbc.sql(
            """
            SELECT request_id, mode, actor_user, target, request::text AS request, reason, result,
                   target_response::text AS target_response, recorded_at
            FROM ops.operation_log
            ORDER BY recorded_at DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .param("limit", limit)
            .query { rs, _ ->
                OperationRecord(
                    requestId = rs.getObject("request_id", UUID::class.java),
                    mode = Mode.valueOf(rs.getString("mode")),
                    user = rs.getString("actor_user"),
                    target = rs.getString("target"),
                    request = rs.getString("request"),
                    reason = rs.getString("reason"),
                    result = OperationResult.valueOf(rs.getString("result")),
                    targetResponse = rs.getString("target_response"),
                    recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }
            .list()
}
