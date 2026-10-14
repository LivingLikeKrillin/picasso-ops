package dev.picasso.ops.load

import dev.picasso.ops.service.requests.WorkRequests

/**
 * 핫스팟 5 — `work_request` 목록·검색. 행을 시드하고 운영 코드 `WorkRequests.list`·`summary` 의 질의를 EXPLAIN (ANALYZE, BUFFERS) 한다.
 * 질의 문자열은 `WorkRequests.kt` 의 것을 옮기고 매개변수를 값으로 펼쳤다(JDBC 는 이름 없는 문장이라 매번 값을 아는 계획이다).
 * 운영 코드 `list` 를 그대로 부른 실제 시간도 같이 잰다.
 *
 * 시드 분포: 상태 ACCEPTED 85%·REJECTED 5%·UNASSIGNED 5%·RECEIVED 3%·IN_DOUBT 2%, source mock-mes 70%·ops:manual 30%,
 * payload·normalized_order 각 약 0.8 KB, 전체의 60%(모두 mock-mes)에 결과 1판(그 가운데 80% ACK).
 */
object WorkRequestExplain {

    private val PAD = "x".repeat(700)

    data class Q(val name: String, val sql: String)

    /** `WorkRequests.list` 의 WHERE 를 값으로 펼친 것. 요청한 조건만 붙고 조건이 없으면 빈 문자열이다. */
    private fun where(state: String?, search: String, unacknowledged: Boolean = false, needsConfirmation: Boolean = false): String {
        val filters = buildList {
            if (needsConfirmation) add("state IN ('RECEIVED','DISPATCHING','IN_DOUBT')") else if (state != null) add("state='$state'")
            if (search.isNotEmpty()) add("(strpos(external_order_id,'$search')>0 OR strpos(job_order_id,'$search')>0 OR strpos(source_system_id,'$search')>0)")
            if (unacknowledged) add("source_system_id NOT LIKE 'ops:%' AND EXISTS($UNACKNOWLEDGED_LATEST AND u.receipt_id=ops.work_request.receipt_id)")
        }
        return if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ")
    }

    /** `WorkRequests.UNACKNOWLEDGED_LATEST` 와 같은 문자열. */
    private const val UNACKNOWLEDGED_LATEST = "SELECT 1 FROM ops.work_result u WHERE u.acknowledged_at IS NULL" +
        " AND NOT EXISTS(SELECT 1 FROM ops.work_result n WHERE n.receipt_id=u.receipt_id AND n.version>u.version)"

    private fun queries(n: Int): List<Q> = buildList {
        val w = where(null, "")
        add(Q("list 기본 count", "SELECT count(*) FROM ops.work_request$w"))
        add(Q("list 기본 1쪽(50)", "SELECT * FROM ops.work_request$w ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET 0"))
        add(Q("list 깊은 쪽(OFFSET ${n / 2})", "SELECT * FROM ops.work_request$w ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET ${n / 2}"))
        val ws = where(null, "EXT-${n / 3}")
        add(Q("list 검색 count", "SELECT count(*) FROM ops.work_request$ws"))
        add(Q("list 검색 1쪽", "SELECT * FROM ops.work_request$ws ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET 0"))
        val wr = where("IN_DOUBT", "")
        add(Q("list 상태 IN_DOUBT count", "SELECT count(*) FROM ops.work_request$wr"))
        add(Q("list 상태 IN_DOUBT 1쪽", "SELECT * FROM ops.work_request$wr ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET 0"))
        val wn = where(null, "", needsConfirmation = true)
        add(Q("list NEEDS_CONFIRMATION 1쪽", "SELECT * FROM ops.work_request$wn ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET 0"))
        val wu = where(null, "", unacknowledged = true)
        add(Q("list 미ACK count", "SELECT count(*) FROM ops.work_request$wu"))
        add(Q("list 미ACK 1쪽", "SELECT * FROM ops.work_request$wu ORDER BY received_at DESC,receipt_id LIMIT 50 OFFSET 0"))
        add(Q("summary 상태별", "SELECT state,count(*) AS n FROM ops.work_request GROUP BY state"))
        add(
            Q(
                "summary 미ACK 결과",
                """SELECT count(*) FROM ops.work_request r WHERE r.source_system_id NOT LIKE 'ops:%'
                   AND EXISTS($UNACKNOWLEDGED_LATEST AND u.receipt_id=r.receipt_id)""",
            ),
        )
        add(Q("summary 최근 5", "SELECT * FROM ops.work_request ORDER BY received_at DESC,receipt_id LIMIT 5"))
        add(Q("sweepStale(RECEIVED)", "SELECT receipt_id FROM ops.work_request WHERE state='RECEIVED' AND updated_at<clock_timestamp()-interval '1 millisecond'*CAST(300000 AS double precision)"))
        add(Q("결과 수집 job_order_id 조회", "SELECT receipt_id FROM ops.work_request WHERE job_order_id='JO-${n / 4}'"))
    }

    fun run(report: Report, scales: List<Int>) {
        report.line("## 핫스팟 5 — `work_request` 목록·검색 (`WorkRequests.list`·`summary`)")
        report.line()
        report.line("인덱스: V6 PK(receipt_id), UNIQUE(job_order_id), UNIQUE(source_system_id,external_order_id), work_request_received(received_at DESC,receipt_id) · V20 work_request_state_received(state,received_at DESC,receipt_id), work_result_unacknowledged(receipt_id,version) WHERE acknowledged_at IS NULL. VACUUM ANALYZE 뒤 같은 질의를 3번 돌려 마지막 것을 적는다.")
        report.line()
        val summary = mutableListOf<List<String>>()
        val plans = mutableListOf<Triple<Int, String, List<String>>>()
        LoadStack(robots = 1).use { stack ->
            report.floor(stack, "세트")
            var seeded = 0
            for (n in scales) {
                seed(stack, seeded + 1, n)
                seeded = n
                stack.sql("VACUUM ANALYZE ops.work_request")
                stack.sql("VACUUM ANALYZE ops.work_result")
                for (q in queries(n)) {
                    lateinit var plan: List<String>
                    repeat(3) { plan = explain(stack, q.sql) }
                    val exec = plan.lastOrNull { it.startsWith("Execution Time") }?.substringAfter(": ")?.substringBefore(" ms") ?: "-"
                    val top = plan.firstOrNull { "Scan" in it }?.trim()?.substringBefore("  (")?.removePrefix("->")?.trim() ?: plan.first().trim().substringBefore("  (")
                    val buffers = plan.firstOrNull { it.trim().startsWith("Buffers:") }?.trim()?.removePrefix("Buffers: ") ?: "-"
                    summary += listOf("$n", q.name, exec, top, buffers)
                    plans += Triple(n, q.name, plan)
                }
                // 운영 코드 그대로
                val requests = stack.opsBean(WorkRequests::class.java)
                for ((label, call) in listOf<Pair<String, () -> Any>>(
                    "WorkRequests.list(\"\", null, 0, 50)" to { requests.list("", null, 0, 50) },
                    "WorkRequests.list(\"EXT-…\", null, 0, 50)" to { requests.list("EXT-${n / 3}", null, 0, 50) },
                    "WorkRequests.list(\"\", null, 0, 50, unacknowledged)" to { requests.list("", null, 0, 50, "unacknowledged") },
                    "WorkRequests.summary()" to { requests.summary() },
                )) {
                    repeat(3) { call() }
                    val t = Timings()
                    repeat(10) { t.time(call) }
                    summary += listOf("$n", "실측 $label (10회)", "p50 ${f(t.p50)} / p95 ${f(t.p95)} / max ${f(t.max)}", "-", "-")
                }
            }
        }
        report.table(listOf("행 수", "질의", "Execution Time ms", "첫 스캔 노드", "최상위 Buffers"), summary)
        report.line("### 계획 원문")
        report.line()
        plans.forEach { (n, name, plan) ->
            report.line("$n 행 — $name")
            report.line()
            report.line("```")
            plan.forEach(report::line)
            report.line("```")
            report.line()
        }
    }

    private fun seed(stack: LoadStack, from: Int, to: Int) {
        stack.sql(
            """INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,normalized_order,received_at,updated_at)
               SELECT md5('w'||g)::uuid,
                 CASE WHEN g%10<7 THEN 'mock-mes' ELSE 'ops:manual' END,
                 'EXT-'||g,'h','{"pad":"$PAD"}'::jsonb,'JO-'||g,
                 CASE WHEN g%100<85 THEN 'ACCEPTED' WHEN g%100<90 THEN 'REJECTED' WHEN g%100<95 THEN 'UNASSIGNED' WHEN g%100<98 THEN 'RECEIVED' ELSE 'IN_DOUBT' END,
                 '{"workMasterId":"InspectAsset","pad":"$PAD"}'::jsonb,
                 now()-g*interval '1 second', now()-g*interval '1 second'
               FROM generate_series($from,$to) g""",
        )
        stack.sql(
            """INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash,acknowledged_at)
               SELECT md5('w'||g)::uuid,1,'inst','exec-'||g,'resp-'||g,1,'{"jobOrderId":"x"}'::jsonb,'hash',CASE WHEN g%5<4 THEN now() END
               FROM generate_series($from,$to) g WHERE g%10<6""",
        )
    }

    private fun explain(stack: LoadStack, sql: String): List<String> =
        stack.query("EXPLAIN (ANALYZE, BUFFERS) $sql") { it.getString(1) }
}
