package dev.picasso.ops.host.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 일지 행 이후의 일(S4b 스펙 T2·T3). */
enum class JournalEventKind { RESTORED, DEFERRED, GAVE_UP, SETTLED }

/**
 * 실행 일지 한 행(S4b 스펙 T2). [jobOrder] 는 picasso `JobOrder` 의 칸 그대로의 JSON 글자다.
 *
 * @param missionVersion 받은 때의 임무 버전. `null` 이면 코드 정의다
 * @param instanceId·executionId 받은 인스턴스와 그때의 실행 id
 */
data class JournalRow(
    val journalId: Long,
    val jobOrderId: String,
    val robotId: String,
    val jobOrder: String,
    val workMasterId: String,
    val missionVersion: Int?,
    val instanceId: String,
    val executionId: String,
    val receivedAt: Instant,
)

/** 일지 이벤트 한 행. [executionId] 는 RESTORED·SETTLED 에만, [detail] 은 RESTORED 밖에만 있다. */
data class JournalEvent(
    val eventId: Long,
    val jobOrderId: String,
    val kind: JournalEventKind,
    val instanceId: String,
    val executionId: String?,
    val detail: String?,
    val recordedAt: Instant,
)

/**
 * 작업 응답의 내용 키(S4b 스펙 T6). 인스턴스마다 달라지는 것(응답 id, 실행 id, 미완 사유 문자열, `connection`)은 뺀다.
 * 단위 id 목록은 정렬해 둔다. 두 키가 같은지는 이 값의 `equals` 다.
 */
data class ResponseContent(
    val jobOrderId: String,
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
)

/** 송신 기록의 처분. [RESTART_DUPLICATE] 는 적었으나 송신하지 않은 것이다. */
enum class ResponseDisposition { SENT, RESTART_DUPLICATE }

data class ResponseLogRow(
    val logId: Long,
    val instanceId: String,
    val jobResponseId: String,
    val executionId: String,
    val content: ResponseContent,
    val disposition: ResponseDisposition,
    val recordedAt: Instant,
)

/** 사본의 판단 한 행. [at] 은 호스트 시계, [wallClockAt] 은 실제 시각이다. */
data class CopyResolutionRow(val decision: String, val at: Instant, val wallClockAt: Instant, val decidedById: String, val decidedByKind: String)

/** 인시던트 사본 한 행. [detail] 은 봉인 뒤 처음 적을 때의 호스트 상세 본문 JSON 글자다. */
data class IncidentCopyRow(
    val copyId: Long,
    val instanceId: String,
    val incidentId: String,
    val detail: String,
    val resolution: CopyResolutionRow?,
)

/** 운영자 실행 취소 한 행(P0-b2, V5). [physicalState] 는 취소를 건 때의 물리 상태다. */
data class CancelRow(
    val cancelId: Long,
    val instanceId: String,
    val executionId: String,
    val jobOrderId: String,
    val physicalState: String,
    val approverId: String,
    val approverKind: String,
    val reason: String,
    val recordedAt: Instant,
)

/** 잔여 파지 표시 한 행(P0-b2, V5). [objectRef] 는 관측이 이름을 싣지 않았으면 `null` 이다. */
data class ResidualHoldRow(
    val markerId: Long,
    val robotId: String,
    val jobOrderId: String,
    val executionId: String,
    val instanceId: String,
    val unitId: String,
    val objectRef: String?,
    val recordedAt: Instant,
)

/** 잔여 파지 표시의 해소 한 행(P0-b2, V5). */
data class ResidualHoldClearingRow(val markerId: Long, val approverId: String, val approverKind: String, val reason: String, val clearedAt: Instant)

/**
 * 판단 기록 한 행(V6, picasso 한계 §15.221 · 판단 기록 영속). [record] 는 picasso `JudgmentRecordWire` 의 글자 그대로다.
 *
 * @param wireVersion 적을 때의 `JudgmentRecordWire.SCHEMA_VERSION`
 * @param instanceId·executionId 적은 인스턴스와 그때의 실행 id
 */
data class JudgmentRecordRow(
    val recordId: Long,
    val jobOrderId: String,
    val instanceId: String,
    val executionId: String,
    val wireVersion: String,
    val record: String,
    val recordedAt: Instant,
)

/**
 * 실행 호스트의 기록(S4b 스펙 §6.1). 실행 일지와 그 이벤트, 송신 기록, 인시던트 사본과 판단의 다섯 표다. 덧붙이기와 읽기만 있다.
 * P0-b2 의 V5 가 운영자 실행 취소, 잔여 파지 표시와 그 해소의 세 표를 더한다. HA 단계 0 의 V6 이 판단 기록(덧붙이기만)을, V7 이
 * 인스턴스 심장박동(인스턴스마다 한 행, 그 인스턴스만 고침)을 더한다.
 *
 * pump 뒤 기록은 [inTransaction] 하나로 묶는다(§6.2). 그 밖의 호출은 문장 하나가 한 트랜잭션이다. 호스트 잠금 아래에서 부를 수
 * 있고, 연결을 쥔 채 호스트 잠금을 기다리는 길은 없다(S3b 와 같은 잠금 순서).
 */
class HostRecords(private val jdbc: JdbcClient, private val transactions: TransactionTemplate, private val json: ObjectMapper) {

    /** [action] 을 한 트랜잭션으로 돈다. 예외가 나가면 아무것도 남지 않는다. */
    fun <T> inTransaction(action: () -> T): T = transactions.execute { action() } as T

    /** Serialized transactional counter: allocation order is commit order, with no rollback gaps. */
    fun appendResult(instance: String, responseId: String, snapshot: com.fasterxml.jackson.databind.JsonNode) {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.sql("SELECT last_sequence FROM mission.result_producer WHERE singleton FOR UPDATE").query(Long::class.java).single()
        val exists = jdbc.sql("SELECT count(*) FROM mission.result_outbox WHERE instance_id=:instance AND response_id=:response")
            .param("instance",instance).param("response",responseId).query(Long::class.java).single() > 0
        if (exists) return
        val sequence = jdbc.sql("UPDATE mission.result_producer SET last_sequence=last_sequence+1 WHERE singleton RETURNING last_sequence").query(Long::class.java).single()
        jdbc.sql("INSERT INTO mission.result_outbox(sequence,instance_id,response_id,snapshot) VALUES(:sequence,:instance,:response,CAST(:snapshot AS JSONB))")
            .param("sequence",sequence).param("instance",instance).param("response",responseId).param("snapshot",snapshot.toString()).update()
    }

    fun resultEvents(after: Long, limit: Int): Map<String,Any> {
        require(after >= 0 && limit in 1..200)
        val producer = jdbc.sql("SELECT producer_id,last_sequence FROM mission.result_producer WHERE singleton")
            .query {rs,_ -> rs.getString(1) to rs.getLong(2)}.single()
        require(after <= producer.second) { "cursor exceeds producer watermark" }
        val events = jdbc.sql("SELECT sequence,snapshot,recorded_at FROM mission.result_outbox WHERE sequence>:after AND sequence<=:watermark ORDER BY sequence LIMIT :limit")
            .param("after",after).param("watermark",producer.second).param("limit",limit)
            .query {rs,_ -> mapOf("sequence" to rs.getLong(1),"snapshot" to json.readTree(rs.getString(2)),"recordedAt" to rs.getString(3))}.list()
        return mapOf("producerId" to producer.first,"highWatermark" to producer.second,"events" to events)
    }

    // ── 실행 일지

    fun journal(
        jobOrderId: String,
        robotId: String,
        jobOrder: String,
        workMasterId: String,
        missionVersion: Int?,
        instanceId: String,
        executionId: String,
    ): JournalRow =
        jdbc.sql(
            """
            INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
            VALUES (:jobOrderId, :robotId, CAST(:jobOrder AS jsonb), :workMaster, :missionVersion, :instanceId, :executionId)
            RETURNING $JOURNAL_COLUMNS
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .param("robotId", robotId)
            .param("jobOrder", jobOrder)
            .param("workMaster", workMasterId)
            .param("missionVersion", missionVersion)
            .param("instanceId", instanceId)
            .param("executionId", executionId)
            .query { rs, _ -> journalRow(rs) }
            .single()

    fun event(jobOrderId: String, kind: JournalEventKind, instanceId: String, executionId: String?, detail: String?): JournalEvent =
        jdbc.sql(
            """
            INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail)
            VALUES (:jobOrderId, :kind, :instanceId, :executionId, :detail)
            RETURNING $EVENT_COLUMNS
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .param("kind", kind.name)
            .param("instanceId", instanceId)
            .param("executionId", executionId)
            .param("detail", detail)
            .query { rs, _ -> event(rs) }
            .single()

    /** 정착도 포기도 없는 일지 행. 받은 순서다. 기동 복원의 대상이다(T3). */
    fun openJournal(): List<JournalRow> = journalWhere(
        """
        NOT EXISTS (SELECT 1 FROM mission.execution_journal_event e
                    WHERE e.job_order_id = j.job_order_id AND e.kind IN ('SETTLED', 'GAVE_UP'))
        """.trimIndent(),
    )

    /** 포기했고 정착하지 않은 일지 행. 받은 순서다. 판정이 그 기체를 뺄지 본다(T4). */
    fun gaveUpJournal(): List<JournalRow> = journalWhere(
        """
        EXISTS (SELECT 1 FROM mission.execution_journal_event e WHERE e.job_order_id = j.job_order_id AND e.kind = 'GAVE_UP')
        AND NOT EXISTS (SELECT 1 FROM mission.execution_journal_event e WHERE e.job_order_id = j.job_order_id AND e.kind = 'SETTLED')
        """.trimIndent(),
    )

    fun journalRow(jobOrderId: String): JournalRow? =
        jdbc.sql("SELECT $JOURNAL_COLUMNS FROM mission.execution_journal j WHERE job_order_id = :jobOrderId")
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> journalRow(rs) }
            .optional()
            .orElse(null)

    /** 그 작업 지시의 이벤트. 적은 순서다. */
    fun events(jobOrderId: String): List<JournalEvent> =
        jdbc.sql("SELECT $EVENT_COLUMNS FROM mission.execution_journal_event WHERE job_order_id = :jobOrderId ORDER BY event_id")
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> event(rs) }
            .list()

    private fun journalWhere(condition: String): List<JournalRow> =
        jdbc.sql("SELECT $JOURNAL_COLUMNS FROM mission.execution_journal j WHERE $condition ORDER BY journal_id")
            .query { rs, _ -> journalRow(rs) }
            .list()

    // ── 송신 기록

    /** 그 인스턴스가 그 작업 지시에 대해 적은 행이 있는가. 없으면 다음 응답이 그 인스턴스의 첫 응답이다. */
    fun loggedIn(instanceId: String, jobOrderId: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM mission.job_response_log WHERE instance_id = :instanceId AND job_order_id = :jobOrderId)")
            .param("instanceId", instanceId)
            .param("jobOrderId", jobOrderId)
            .query(Boolean::class.java)
            .single()

    /** 그 작업 지시의 가장 최근 송신 행(인스턴스 무관). 재기동 중복 행은 보지 않는다. */
    fun lastSent(jobOrderId: String): ResponseLogRow? =
        jdbc.sql(
            """
            SELECT $LOG_COLUMNS FROM mission.job_response_log
            WHERE job_order_id = :jobOrderId AND disposition = 'SENT' ORDER BY log_id DESC LIMIT 1
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> logRow(rs) }
            .optional()
            .orElse(null)

    fun logResponse(instanceId: String, jobResponseId: String, executionId: String, content: ResponseContent, disposition: ResponseDisposition): ResponseLogRow =
        jdbc.sql(
            """
            INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state,
                required_evidence, reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units,
                operator_required, residual_hold, blocked_by, disposition)
            VALUES (:instanceId, :jobResponseId, :jobOrderId, :executionId, :version, :physicalState, :required, :reached,
                CAST(:completed AS jsonb), CAST(:unverified AS jsonb), CAST(:incomplete AS jsonb), CAST(:inDoubt AS jsonb),
                :operatorRequired, :residualHold, CAST(:blockedBy AS jsonb), :disposition)
            RETURNING $LOG_COLUMNS
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("jobResponseId", jobResponseId)
            .param("jobOrderId", content.jobOrderId)
            .param("executionId", executionId)
            .param("version", content.version)
            .param("physicalState", content.physicalState)
            .param("required", content.requiredEvidence)
            .param("reached", content.reachedEvidence)
            .param("completed", json.writeValueAsString(content.completedUnits))
            .param("unverified", json.writeValueAsString(content.unverifiedUnits))
            .param("incomplete", json.writeValueAsString(content.incompleteUnits))
            .param("inDoubt", json.writeValueAsString(content.inDoubtUnits))
            .param("operatorRequired", content.operatorRequired)
            .param("residualHold", content.residualHold)
            .param("blockedBy", json.writeValueAsString(content.blockedBy))
            .param("disposition", disposition.name)
            .query { rs, _ -> logRow(rs) }
            .single()

    /** 송신 기록. 최근부터 많아야 [limit] 개다. [jobOrderId] 를 주면 그 작업 지시만이다. */
    fun responseLog(jobOrderId: String?, limit: Int): List<ResponseLogRow> =
        jdbc.sql(
            """
            SELECT $LOG_COLUMNS FROM mission.job_response_log
            WHERE CAST(:jobOrderId AS text) IS NULL OR job_order_id = :jobOrderId
            ORDER BY log_id DESC LIMIT :limit
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .param("limit", limit)
            .query { rs, _ -> logRow(rs) }
            .list()

    fun responseLogCount(jobOrderId: String?): Int =
        jdbc.sql("SELECT count(*) FROM mission.job_response_log WHERE CAST(:jobOrderId AS text) IS NULL OR job_order_id = :jobOrderId")
            .param("jobOrderId", jobOrderId)
            .query(Int::class.java)
            .single()

    // ── 인시던트 사본

    fun copyIncident(instanceId: String, incidentId: String, executionId: String, jobOrderId: String, unitId: String, detail: String) {
        jdbc.sql(
            """
            INSERT INTO mission.incident_copy (instance_id, incident_id, execution_id, job_order_id, unit_id, detail)
            VALUES (:instanceId, :incidentId, :executionId, :jobOrderId, :unitId, CAST(:detail AS json))
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("incidentId", incidentId)
            .param("executionId", executionId)
            .param("jobOrderId", jobOrderId)
            .param("unitId", unitId)
            .param("detail", detail)
            .update()
    }

    fun copyResolution(instanceId: String, incidentId: String, resolution: CopyResolutionRow) {
        jdbc.sql(
            """
            INSERT INTO mission.incident_copy_resolution (instance_id, incident_id, decision, decided_at, wall_clock_at, decided_by_id, decided_by_kind)
            VALUES (:instanceId, :incidentId, :decision, :at, :wallClockAt, :byId, :byKind)
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("incidentId", incidentId)
            .param("decision", resolution.decision)
            .param("at", OffsetDateTime.ofInstant(resolution.at, ZoneOffset.UTC))
            .param("wallClockAt", OffsetDateTime.ofInstant(resolution.wallClockAt, ZoneOffset.UTC))
            .param("byId", resolution.decidedById)
            .param("byKind", resolution.decidedByKind)
            .update()
    }

    /** [instanceId] 가 아닌 인스턴스의 사본. 최근에 적은 것부터 많아야 [limit] 개다. */
    fun earlierCopies(instanceId: String, limit: Int): List<IncidentCopyRow> =
        jdbc.sql("$COPY_SELECT WHERE c.instance_id <> :instanceId ORDER BY c.copy_id DESC LIMIT :limit")
            .param("instanceId", instanceId)
            .param("limit", limit)
            .query { rs, _ -> copyRow(rs) }
            .list()

    fun earlierCopyCount(instanceId: String): Int =
        jdbc.sql("SELECT count(*) FROM mission.incident_copy WHERE instance_id <> :instanceId")
            .param("instanceId", instanceId)
            .query(Int::class.java)
            .single()

    fun incidentCopy(instanceId: String, incidentId: String): IncidentCopyRow? =
        jdbc.sql("$COPY_SELECT WHERE c.instance_id = :instanceId AND c.incident_id = :incidentId")
            .param("instanceId", instanceId)
            .param("incidentId", incidentId)
            .query { rs, _ -> copyRow(rs) }
            .optional()
            .orElse(null)

    // ── 운영자 실행 취소와 잔여 파지 표시(P0-b2)

    fun cancel(
        instanceId: String,
        executionId: String,
        jobOrderId: String,
        physicalState: String,
        approverId: String,
        approverKind: String,
        reason: String,
    ): CancelRow =
        jdbc.sql(
            """
            INSERT INTO mission.execution_cancel (instance_id, execution_id, job_order_id, physical_state, approver_id, approver_kind, reason)
            VALUES (:instanceId, :executionId, :jobOrderId, :physicalState, :approverId, :approverKind, :reason)
            RETURNING $CANCEL_COLUMNS
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("executionId", executionId)
            .param("jobOrderId", jobOrderId)
            .param("physicalState", physicalState)
            .param("approverId", approverId)
            .param("approverKind", approverKind)
            .param("reason", reason)
            .query { rs, _ -> cancelRow(rs) }
            .single()

    /** 그 인스턴스의 운영자 취소. 적은 순서다. */
    fun cancels(instanceId: String): List<CancelRow> =
        jdbc.sql("SELECT $CANCEL_COLUMNS FROM mission.execution_cancel WHERE instance_id = :instanceId ORDER BY cancel_id")
            .param("instanceId", instanceId)
            .query { rs, _ -> cancelRow(rs) }
            .list()

    /**
     * 잔여 파지 표시를 적는다. 그 작업 지시에 이미 표시가 있으면 아무것도 적지 않고 `null` 이다(재기동·중복 정착에도 하나).
     * [objectRef] 가 빈 문자열이면 `null` 로 적는다.
     */
    fun markResidualHold(robotId: String, jobOrderId: String, executionId: String, instanceId: String, unitId: String, objectRef: String?): ResidualHoldRow? =
        jdbc.sql(
            """
            INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id, object_ref)
            VALUES (:robotId, :jobOrderId, :executionId, :instanceId, :unitId, :objectRef)
            ON CONFLICT (job_order_id) DO NOTHING
            RETURNING $HOLD_COLUMNS
            """.trimIndent(),
        )
            .param("robotId", robotId)
            .param("jobOrderId", jobOrderId)
            .param("executionId", executionId)
            .param("instanceId", instanceId)
            .param("unitId", unitId)
            .param("objectRef", objectRef?.takeIf { it.isNotEmpty() }, Types.VARCHAR)
            .query { rs, _ -> holdRow(rs) }
            .optional()
            .orElse(null)

    /** 해소 행이 없는 표시. 적은 순서다. */
    fun openResidualHolds(): List<ResidualHoldRow> =
        jdbc.sql(
            """
            SELECT $HOLD_COLUMNS FROM mission.robot_residual_hold h
            WHERE NOT EXISTS (SELECT 1 FROM mission.robot_residual_hold_clearing c WHERE c.marker_id = h.marker_id)
            ORDER BY marker_id
            """.trimIndent(),
        )
            .query { rs, _ -> holdRow(rs) }
            .list()

    /** 표시 하나와 그 해소. 표시가 없으면 `null` 이다. */
    fun residualHold(markerId: Long): Pair<ResidualHoldRow, ResidualHoldClearingRow?>? {
        val hold = jdbc.sql("SELECT $HOLD_COLUMNS FROM mission.robot_residual_hold WHERE marker_id = :markerId")
            .param("markerId", markerId)
            .query { rs, _ -> holdRow(rs) }
            .optional()
            .orElse(null) ?: return null
        val clearing = jdbc.sql("SELECT $CLEARING_COLUMNS FROM mission.robot_residual_hold_clearing WHERE marker_id = :markerId")
            .param("markerId", markerId)
            .query { rs, _ -> clearingRow(rs) }
            .optional()
            .orElse(null)
        return hold to clearing
    }

    fun clearResidualHold(markerId: Long, approverId: String, approverKind: String, reason: String): ResidualHoldClearingRow =
        jdbc.sql(
            """
            INSERT INTO mission.robot_residual_hold_clearing (marker_id, approver_id, approver_kind, reason)
            VALUES (:markerId, :approverId, :approverKind, :reason)
            RETURNING $CLEARING_COLUMNS
            """.trimIndent(),
        )
            .param("markerId", markerId)
            .param("approverId", approverId)
            .param("approverKind", approverKind)
            .param("reason", reason)
            .query { rs, _ -> clearingRow(rs) }
            .single()

    // ── 판단 기록(V6)

    fun judgmentRecord(jobOrderId: String, instanceId: String, executionId: String, wireVersion: String, record: String): JudgmentRecordRow =
        jdbc.sql(
            """
            INSERT INTO mission.execution_judgment_record (job_order_id, instance_id, execution_id, wire_version, record)
            VALUES (:jobOrderId, :instanceId, :executionId, :wireVersion, :record)
            RETURNING $RECORD_COLUMNS
            """.trimIndent(),
        )
            .param("jobOrderId", jobOrderId)
            .param("instanceId", instanceId)
            .param("executionId", executionId)
            .param("wireVersion", wireVersion)
            .param("record", record)
            .query { rs, _ -> recordRow(rs) }
            .single()

    /** 그 작업 지시의 가장 최근 판단 기록(인스턴스 무관). 없으면 `null` 이다. 기동 복원이 `resume` 에 넘긴다. */
    fun latestJudgmentRecord(jobOrderId: String): JudgmentRecordRow? =
        jdbc.sql("SELECT $RECORD_COLUMNS FROM mission.execution_judgment_record WHERE job_order_id = :jobOrderId ORDER BY record_id DESC LIMIT 1")
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> recordRow(rs) }
            .optional()
            .orElse(null)

    /** 그 작업 지시의 판단 기록 전부. 적은 순서다. */
    fun judgmentRecords(jobOrderId: String): List<JudgmentRecordRow> =
        jdbc.sql("SELECT $RECORD_COLUMNS FROM mission.execution_judgment_record WHERE job_order_id = :jobOrderId ORDER BY record_id")
            .param("jobOrderId", jobOrderId)
            .query { rs, _ -> recordRow(rs) }
            .list()

    // ── 인스턴스와 심장박동(V7). 시각은 모두 DB 시각이다.

    /** 이 인스턴스의 행을 넣거나(기동) 고친다(심장박동). 이미 멈춤을 적은 행은 되살리지 않는다. */
    fun beat(instanceId: String, expiry: java.time.Duration) {
        jdbc.sql(
            """
            INSERT INTO mission.host_instance (instance_id, started_at, last_seen_at, expires_at)
            SELECT :instanceId, t.now, t.now, t.now + :ms * interval '1 millisecond' FROM (SELECT clock_timestamp() AS now) t
            ON CONFLICT (instance_id) DO UPDATE
                SET last_seen_at = EXCLUDED.last_seen_at, expires_at = EXCLUDED.expires_at
                WHERE mission.host_instance.stopped_at IS NULL
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .param("ms", expiry.toMillis())
            .update()
    }

    /** 정상 종료. 멈춤을 적고 만료를 지금으로 당긴다. 다음 기동이 만료를 기다리지 않는다. */
    fun stopInstance(instanceId: String) {
        jdbc.sql(
            """
            UPDATE mission.host_instance SET stopped_at = clock_timestamp(), expires_at = least(expires_at, clock_timestamp())
            WHERE instance_id = :instanceId AND stopped_at IS NULL
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .update()
    }

    /** 그 인스턴스가 지금(DB 시각) 살아 있는가. 행이 없으면 거짓이다(이 표 이전의 인스턴스와 모르는 인스턴스). */
    fun instanceLive(instanceId: String): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM mission.host_instance
                           WHERE instance_id = :instanceId AND stopped_at IS NULL AND expires_at > clock_timestamp())
            """.trimIndent(),
        )
            .param("instanceId", instanceId)
            .query(Boolean::class.java)
            .single()

    private fun recordRow(rs: ResultSet) = JudgmentRecordRow(
        recordId = rs.getLong("record_id"),
        jobOrderId = rs.getString("job_order_id"),
        instanceId = rs.getString("instance_id"),
        executionId = rs.getString("execution_id"),
        wireVersion = rs.getString("wire_version"),
        record = rs.getString("record"),
        recordedAt = instant(rs, "recorded_at"),
    )

    private fun cancelRow(rs: ResultSet) = CancelRow(
        cancelId = rs.getLong("cancel_id"),
        instanceId = rs.getString("instance_id"),
        executionId = rs.getString("execution_id"),
        jobOrderId = rs.getString("job_order_id"),
        physicalState = rs.getString("physical_state"),
        approverId = rs.getString("approver_id"),
        approverKind = rs.getString("approver_kind"),
        reason = rs.getString("reason"),
        recordedAt = instant(rs, "recorded_at"),
    )

    private fun holdRow(rs: ResultSet) = ResidualHoldRow(
        markerId = rs.getLong("marker_id"),
        robotId = rs.getString("robot_id"),
        jobOrderId = rs.getString("job_order_id"),
        executionId = rs.getString("execution_id"),
        instanceId = rs.getString("instance_id"),
        unitId = rs.getString("unit_id"),
        objectRef = rs.getString("object_ref"),
        recordedAt = instant(rs, "recorded_at"),
    )

    private fun clearingRow(rs: ResultSet) = ResidualHoldClearingRow(
        markerId = rs.getLong("marker_id"),
        approverId = rs.getString("approver_id"),
        approverKind = rs.getString("approver_kind"),
        reason = rs.getString("reason"),
        clearedAt = instant(rs, "cleared_at"),
    )

    private fun journalRow(rs: ResultSet) = JournalRow(
        journalId = rs.getLong("journal_id"),
        jobOrderId = rs.getString("job_order_id"),
        robotId = rs.getString("robot_id"),
        jobOrder = rs.getString("job_order"),
        workMasterId = rs.getString("work_master_id"),
        missionVersion = rs.getObject("mission_version") as Int?,
        instanceId = rs.getString("instance_id"),
        executionId = rs.getString("execution_id"),
        receivedAt = instant(rs, "received_at"),
    )

    private fun event(rs: ResultSet) = JournalEvent(
        eventId = rs.getLong("event_id"),
        jobOrderId = rs.getString("job_order_id"),
        kind = JournalEventKind.valueOf(rs.getString("kind")),
        instanceId = rs.getString("instance_id"),
        executionId = rs.getString("execution_id"),
        detail = rs.getString("detail"),
        recordedAt = instant(rs, "recorded_at"),
    )

    private fun logRow(rs: ResultSet) = ResponseLogRow(
        logId = rs.getLong("log_id"),
        instanceId = rs.getString("instance_id"),
        jobResponseId = rs.getString("job_response_id"),
        executionId = rs.getString("execution_id"),
        content = ResponseContent(
            jobOrderId = rs.getString("job_order_id"),
            version = rs.getInt("version"),
            physicalState = rs.getString("physical_state"),
            requiredEvidence = rs.getString("required_evidence"),
            reachedEvidence = rs.getString("reached_evidence"),
            completedUnits = strings(rs, "completed_units"),
            unverifiedUnits = strings(rs, "unverified_units"),
            incompleteUnits = strings(rs, "incomplete_units"),
            inDoubtUnits = strings(rs, "in_doubt_units"),
            operatorRequired = rs.getBoolean("operator_required"),
            residualHold = rs.getString("residual_hold"),
            blockedBy = strings(rs, "blocked_by"),
        ),
        disposition = ResponseDisposition.valueOf(rs.getString("disposition")),
        recordedAt = instant(rs, "recorded_at"),
    )

    private fun copyRow(rs: ResultSet) = IncidentCopyRow(
        copyId = rs.getLong("copy_id"),
        instanceId = rs.getString("instance_id"),
        incidentId = rs.getString("incident_id"),
        detail = rs.getString("detail"),
        resolution = rs.getString("decision")?.let {
            CopyResolutionRow(it, instant(rs, "decided_at"), instant(rs, "wall_clock_at"), rs.getString("decided_by_id"), rs.getString("decided_by_kind"))
        },
    )

    private fun strings(rs: ResultSet, column: String): List<String> = json.readValue(rs.getString(column))

    private fun instant(rs: ResultSet, column: String): Instant = rs.getObject(column, OffsetDateTime::class.java).toInstant()

    private companion object {
        const val JOURNAL_COLUMNS =
            "journal_id, job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id, received_at"
        const val EVENT_COLUMNS = "event_id, job_order_id, kind, instance_id, execution_id, detail, recorded_at"
        const val LOG_COLUMNS =
            "log_id, instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
                "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, " +
                "residual_hold, blocked_by, disposition, recorded_at"
        const val CANCEL_COLUMNS =
            "cancel_id, instance_id, execution_id, job_order_id, physical_state, approver_id, approver_kind, reason, recorded_at"
        const val HOLD_COLUMNS = "marker_id, robot_id, job_order_id, execution_id, instance_id, unit_id, object_ref, recorded_at"
        const val CLEARING_COLUMNS = "marker_id, approver_id, approver_kind, reason, cleared_at"
        const val RECORD_COLUMNS = "record_id, job_order_id, instance_id, execution_id, wire_version, record, recorded_at"
        const val COPY_SELECT =
            "SELECT c.copy_id, c.instance_id, c.incident_id, c.detail, r.decision, r.decided_at, r.wall_clock_at, r.decided_by_id, r.decided_by_kind " +
                "FROM mission.incident_copy c LEFT JOIN mission.incident_copy_resolution r " +
                "ON r.instance_id = c.instance_id AND r.incident_id = c.incident_id"
    }
}
