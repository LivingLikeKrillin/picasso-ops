package dev.picasso.ops.load

import dev.picasso.ops.service.requests.WorkRequests
import dev.picasso.ops.service.requests.WorkResultDelivery
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 핫스팟 2 — 결과 전달 한 회차(`WorkResultDelivery.deliver`). 운영 코드 그대로를 이 세트의 callback 대역(200)에 겨눠 세운다.
 *
 * 결과 수를 누적으로 늘리며(한 세트) 단계마다 셋을 잰다.
 * - 첫 회차: 새 결과가 막 들어온 회차. `INSERT…SELECT` 가 새 행을 넣고 10건을 보낸다.
 * - 정상 상태: 모든 전달 행을 DELIVERED 로 둔 뒤의 회차. 보낼 것이 없어도 `INSERT…SELECT` 가 결과 전체를 훑는다.
 * - 그 `INSERT…SELECT` 의 EXPLAIN (ANALYZE, BUFFERS). 트랜잭션 안에서 돌리고 되돌린다.
 */
object DeliveryLoad {

    /** 결과 하나의 스냅숏 크기를 실제 작업 응답 스냅숏에 가깝게 둔다(약 1 KB). */
    private val PAD = "x".repeat(900)

    fun run(report: Report, scales: List<Int>, rounds: Int) {
        report.line("## 핫스팟 2 — 결과 전달 (`WorkResultDelivery.deliver`)")
        report.line()
        report.line("결과 행마다 work_request 1행·work_result 1행(source mock-mes, 스냅숏 약 1 KB). 정상 상태 회차 수 $rounds. callback 대역은 같은 JVM 의 루프백 HTTP 200.")
        report.line()
        val rows = mutableListOf<List<String>>()
        val plans = mutableListOf<Pair<Int, List<String>>>()
        LoadStack(robots = 1).use { stack ->
            report.floor(stack, "세트")
            val delivery = WorkResultDelivery(stack.opsBean(JdbcClient::class.java), stack.opsBean(WorkRequests::class.java), stack.callbackUrl, "", "mock-mes")
            val stats = StatementStats(stack)
            var seeded = 0
            for (r in scales) {
                seed(stack, seeded + 1, r)
                seeded = r
                stack.sql("VACUUM ANALYZE ops.work_request")
                stack.sql("VACUUM ANALYZE ops.work_result")
                stack.sql("VACUUM ANALYZE ops.work_result_delivery")
                // 첫 회차
                stats.reset()
                val hitsBefore = stack.callbackHits.get()
                val first = Timings().also { t -> t.time { delivery.deliver() } }
                val firstStmts = stats.read { it.contains("ops.") }
                val sent = stack.callbackHits.get() - hitsBefore
                val queued = stack.query("SELECT count(*) FROM ops.work_result_delivery") { it.getLong(1) }.single()
                // 정상 상태: 전부 보낸 것으로 둔다
                stack.sql("UPDATE ops.work_result_delivery SET state='DELIVERED',delivered_at=clock_timestamp(),updated_at=clock_timestamp() WHERE state<>'DELIVERED'")
                stack.sql("VACUUM ANALYZE ops.work_result_delivery")
                repeat(3) { delivery.deliver() }
                stats.reset()
                val steady = Timings()
                repeat(rounds) { steady.time { delivery.deliver() } }
                val steadyStmts = stats.read { it.contains("ops.") }
                val insert = steadyStmts.filter { it.query.startsWith("INSERT INTO ops.work_result_delivery") }
                rows += listOf(
                    "$r", f(first.max), "$sent", "$queued",
                ) + steady.cells() + listOf(
                    f(steadyStmts.calls().toDouble() / rounds), f(insert.dbMs() / rounds), f(insert.blocks().toDouble() / rounds),
                    f(firstStmts.calls().toDouble()),
                )
                plans += r to explain(stack)
            }
        }
        report.table(
            listOf(
                "결과 수", "첫 회차 ms", "첫 회차 전송 수", "전달 행 수", "정상 p50 ms", "정상 p95 ms", "정상 max ms",
                "정상 DB 문장/회차", "INSERT…SELECT DB ms/회차", "INSERT…SELECT blocks/회차", "첫 회차 DB 문장",
            ),
            rows,
        )
        plans.forEach { (r, plan) ->
            report.line("EXPLAIN (ANALYZE, BUFFERS) — 정상 상태 `INSERT…SELECT`, 결과 $r")
            report.line()
            report.line("```")
            plan.forEach(report::line)
            report.line("```")
            report.line()
        }
    }

    private fun seed(stack: LoadStack, from: Int, to: Int) {
        if (from > to) return
        stack.sql(
            """INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,received_at,updated_at)
               SELECT md5('r'||g)::uuid,'mock-mes','EXT-'||g,'h','{"pad":"$PAD"}'::jsonb,'JO-'||g,'ACCEPTED',now()-g*interval '1 second',now()
               FROM generate_series($from,$to) g""",
        )
        stack.sql(
            """INSERT INTO ops.work_result(receipt_id,version,instance_id,execution_id,response_id,response_version,snapshot,result_hash)
               SELECT md5('r'||g)::uuid,1,'inst','exec-'||g,'resp-'||g,1,jsonb_build_object('jobOrderId','JO-'||g,'pad','$PAD'),'hash-'||g
               FROM generate_series($from,$to) g""",
        )
    }

    private fun explain(stack: LoadStack): List<String> = stack.connection().use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement(
                """EXPLAIN (ANALYZE, BUFFERS) INSERT INTO ops.work_result_delivery(receipt_id,version,target_url)
                   SELECT r.receipt_id,v.version,'${stack.callbackUrl}' FROM ops.work_result v JOIN ops.work_request r USING(receipt_id)
                   WHERE r.source_system_id='mock-mes' ON CONFLICT(receipt_id,version) DO NOTHING""",
            ).use { p -> p.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        } finally {
            c.rollback()
        }
    }
}
