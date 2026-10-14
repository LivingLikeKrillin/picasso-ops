package dev.picasso.ops.load

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.interfaces.MissionInterfaces
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.requests.WorkRequests
import dev.picasso.ops.service.stations.OpsStationPort
import dev.picasso.ops.service.stations.StationCoordinator
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/** 운영 서비스의 호스트 읽기를 세는 겉감. 호출마다 끝점 이름으로 수와 시간을 쌓는다. */
class CountingHost(private val delegate: HostReads) : HostReads by delegate {
    val timings = ConcurrentHashMap<String, Timings>()
    fun reset() = timings.clear()
    private fun <T> timed(name: String, call: () -> T): T = timings.computeIfAbsent(name) { Timings() }.let { t -> synchronized(t) { t.time(call) } }
    override fun executions(): HostCall<JsonNode> = timed("executions") { delegate.executions() }
    override fun cell(): HostCall<JsonNode> = timed("cell") { delegate.cell() }
    override fun workPermits(): HostCall<JsonNode> = timed("work-permits") { delegate.workPermits() }
}

/**
 * 핫스팟 1 — 스테이션 tick. 운영 서비스 빈(JdbcClient·DataSource·MissionInterfaces·JobOrderOperations·HostClient)으로 운영 코드
 * `StationCoordinator`·`OpsStationPort`·`WorkRequests` 를 그대로 세우고, 호스트 읽기만 [CountingHost] 로 감싼다. 주기 tick 은 꺼져 있고
 * 하네스가 `tick()` 을 직접 부른다.
 *
 * 스테이션마다 한 세트(새 컨테이너)다. 처음에는 모든 스테이션이 비어 있고(idle), 그다음 모든 스테이션에 RUNNING 작업을 하나씩
 * 시드한다(active). RUNNING 작업은 준비 참·완료 거짓 신호를 보고 있어 tick 이 상태를 바꾸지 않는다(정상 상태). 그것을 회차 뒤에 확인한다.
 */
object StationTickLoad {

    fun run(report: Report, scales: List<Int>, rounds: Int) {
        report.line("## 핫스팟 1 — 스테이션 tick (`StationCoordinator.tick`)")
        report.line()
        report.line("회차 수 $rounds (예열 3회 뒤). 실행 호스트의 실행 수 0. DB 문장은 pg_stat_statements(ops 스키마 문장, 현장 시간값 읽기 제외).")
        report.line()
        val rows = mutableListOf<List<String>>()
        val details = mutableListOf<Pair<String, List<Stmt>>>()
        for (s in scales) LoadStack(robots = 1).use { stack ->
            report.floor(stack, "세트(스테이션 $s)")
            val (r, d) = measure(stack, s, listOf("idle", "active"), rounds)
            rows += r
            if (s == scales.max()) details += d
        }
        table(report, rows)
        details.forEach { (title, stmts) -> report.statements("문장별(tick 당) — $title", stmts, rounds) }
    }

    fun table(report: Report, rows: List<List<String>>) = report.table(
            listOf(
                "스테이션", "상태", "tick p50 ms", "p95 ms", "max ms", "DB 문장/tick", "DB rows/tick", "rf_station 전체 읽기/tick",
                "그 rows/tick", "DB 실행 ms/tick", "호스트 GET/tick (cell / work-permits / executions)", "executions GET p50 ms",
                "측정 중 station_event 증가", "측정 뒤 RUNNING 수",
            ),
            rows,
        )

    /**
     * 스테이션 [s] 개를 시드하고 [modes] 차례로 tick 을 잰다. [pump] 는 셀 신호를 호스트가 읽게 하는 것이다(주기 pump 를 멈춘 세트는
     * `pumpOnce` 를 넘긴다).
     */
    fun measure(stack: LoadStack, s: Int, modes: List<String>, rounds: Int, label: String = "", pump: () -> Unit = {}): Pair<List<List<String>>, List<Pair<String, List<Stmt>>>> {
        val rows = mutableListOf<List<String>>()
        val details = mutableListOf<Pair<String, List<Stmt>>>()
        stack.cellBody = LoadStack.cell(s)
        waitFor("셀 신호 $s 쌍") { pump(); stack.hostGet("/host/cell").first.path("cell").path("signals").size() == 2 + 2 * s }
        seedStations(stack, s)
        val host = CountingHost(stack.opsBean(HostClient::class.java))
        val jdbc = stack.opsBean(JdbcClient::class.java)
        val tx = TransactionTemplate(DataSourceTransactionManager(stack.opsBean(DataSource::class.java)))
        val interfaces = stack.opsBean(MissionInterfaces::class.java)
        val requests = WorkRequests(jdbc, interfaces, stack.opsBean(JobOrderOperations::class.java), host, tx, durableResults = true)
        val coordinator = StationCoordinator(jdbc, tx, OpsStationPort(requests, interfaces, host), Clock.systemUTC())
        val stats = StatementStats(stack)
        for (mode in modes) {
            if (mode == "active") seedRunning(stack, s)
            repeat(3) { coordinator.tick() }
            host.reset()
            val eventsBefore = stack.query("SELECT count(*) FROM ops.station_event") { it.getLong(1) }.single()
            stats.reset()
            val t = Timings()
            repeat(rounds) { t.time { coordinator.tick() } }
            val stmts = stats.read { it.contains("ops.") && !it.contains("site_timings") }
            val eventsAfter = stack.query("SELECT count(*) FROM ops.station_event") { it.getLong(1) }.single()
            val running = stack.query("SELECT count(*) FROM ops.station_run WHERE state='RUNNING'") { it.getLong(1) }.single()
            val calls = host.timings.mapValues { it.value.count.toDouble() / rounds }
            val stationScans = stmts.filter { it.query.startsWith("SELECT * FROM ops.rf_station ORDER BY station_id") }
            rows += listOf("$s", mode + label) + t.cells() + listOf(
                f(stmts.calls().toDouble() / rounds),
                f(stmts.rows().toDouble() / rounds),
                f(stationScans.calls().toDouble() / rounds),
                f(stationScans.rows().toDouble() / rounds),
                f(stmts.dbMs() / rounds),
                listOf("cell", "work-permits", "executions").joinToString(" / ") { f(calls[it] ?: 0.0) },
                f(host.timings["executions"]?.p50 ?: Double.NaN),
                "${eventsAfter - eventsBefore}", "$running",
            )
            details += "스테이션 $s · $mode$label" to stmts
        }
        return rows to details
    }

    private fun seedStations(stack: LoadStack, count: Int) = stack.connection().use { c ->
        c.prepareStatement(
            "INSERT INTO ops.rf_station(station_id,name,ready_signal,complete_signal,timeout_seconds,created_by) VALUES(?,?,?,?,3600,'load')",
        ).use { p ->
            (1..count).forEach { i ->
                val id = LoadStack.stationId(i)
                p.setString(1, id); p.setString(2, "부하 $id"); p.setString(3, "$id-ready"); p.setString(4, "$id-done")
                p.addBatch()
            }
            p.executeBatch()
        }
    }

    /** 스테이션마다 작업 요청 하나와 RUNNING 작업 하나를 시드하고 점유를 건다. 기한 3600초라 측정 중에 만료되지 않는다. */
    private fun seedRunning(stack: LoadStack, count: Int) = stack.connection().use { c ->
        c.autoCommit = false
        (1..count).forEach { i ->
            val id = LoadStack.stationId(i)
            val receipt = UUID.randomUUID()
            val run = UUID.randomUUID()
            c.prepareStatement(
                """INSERT INTO ops.work_request(receipt_id,source_system_id,external_order_id,payload_hash,payload,job_order_id,state,required_station_id)
                   VALUES(?,'ops:station',?,'h','{}'::jsonb,?,'ACCEPTED',?)""",
            ).use { p -> p.setObject(1, receipt); p.setString(2, "EXT-$id"); p.setString(3, "JO-$id"); p.setString(4, id); p.executeUpdate() }
            c.prepareStatement(
                """INSERT INTO ops.station_run(run_id,station_id,payload,actor,state,reason,receipt_id,created_at,phase_at,dispatched_at,result_baseline)
                   VALUES(?,?,'{}'::jsonb,'OPERATOR/load','RUNNING','부하 시드',?,now(),now(),now(),0)""",
            ).use { p -> p.setObject(1, run); p.setString(2, id); p.setObject(3, receipt); p.executeUpdate() }
            c.prepareStatement("UPDATE ops.rf_station SET owner=? WHERE station_id=?").use { p -> p.setObject(1, run); p.setString(2, id); p.executeUpdate() }
        }
        c.commit()
    }
}

fun waitFor(what: String, seconds: Long = 10, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "${seconds}초 안에 이르지 않았다: $what" }
        Thread.sleep(50)
    }
}
