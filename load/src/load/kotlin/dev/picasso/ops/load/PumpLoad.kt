package dev.picasso.ops.load

import dev.picasso.middleware.Middleware
import dev.picasso.ops.host.MissionHost
import java.time.Duration

/**
 * 핫스팟 3·4 — 실행 호스트 pump(`MissionHost.pumpOnce`)와 미들웨어 아웃박스 누적.
 *
 * 세트를 띄운 뒤 호스트의 주기 pump 를 멈추고(`MissionHost.close` 는 pump 스레드만 닫는다) 하네스가 `pumpOnce()` 를 직접 불러 잰다.
 * 한 주기(cycle)는 기체마다 InspectAsset(E0, 대상 하나) 작업 지시를 하나씩 내고, 가상 시계를 5초씩 밀며 pump 해 전부 정착시킨다.
 * 정착한 실행은 미들웨어에 남으므로 주기를 돌수록 실행 수 E 와 아웃박스 크기가 는다. 검사점마다 일하지 않는 pump(idle) 를 잰다.
 * 미들웨어 크기(실행·아웃박스·pending·인시던트)는 반사로 읽는다(운영 코드는 그대로다).
 */
object PumpLoad {

    data class Plan(val robots: Int, val checkpoints: List<Int>)

    fun run(report: Report, plans: List<Plan>, idleRounds: Int, combinedStations: Int = 0, combinedRounds: Int = 10) {
        report.line("## 핫스팟 3·4 — 실행 호스트 pump (`MissionHost.pumpOnce`) 와 미들웨어 아웃박스")
        report.line()
        report.line("작업 지시: InspectAsset E0, 대상 하나(bay-7), 기체마다 한 주기에 하나. 가상 시계 5초 밀기마다 pump 2회(사이 20 ms). idle 은 실행이 모두 정착한 뒤 pump $idleRounds 회.")
        report.line()
        val active = mutableListOf<List<String>>()
        val idle = mutableListOf<List<String>>()
        val stmtDetails = mutableListOf<Pair<String, List<Stmt>>>()
        val combined = mutableListOf<List<String>>()
        for (plan in plans) LoadStack(robots = plan.robots, withOps = combinedStations > 0 && plan == plans.last()).use { stack ->
            report.floor(stack, "세트(기체 ${plan.robots})")
            val host = stack.missionHost
            host.close()
            val middleware = MissionHost::class.java.getDeclaredField("middleware").apply { isAccessible = true }.get(host) as Middleware
            val stats = StatementStats(stack)
            var cycle = 0
            var order = 0
            var activePumps = Timings()
            var activeStmts = 0L
            var activeCount = 0
            var lastCheckpoint = 0
            for (target in plan.checkpoints) {
                while (middleware.executions().size < target) {
                    cycle++
                    stack.robotIds.forEach { robot ->
                        order++
                        val reply = stack.hostPost(
                            "/host/job-orders",
                            """{"jobOrder":{"jobOrderId":"JO-$order","workMasterId":"InspectAsset","version":1,"requiredEvidence":"E0","parameters":{},
                               "materialRequirements":[],"equipmentRequirements":[{"id":"T1","equipmentUse":"inspection_target","properties":{"location":"bay-7"}}]},
                               "candidates":["$robot"]}""",
                        )
                        check(reply["result"].asText() == "ACCEPTED") { "제출 거부: $reply" }
                    }
                    stats.reset()
                    var pumps = 0
                    var rounds = 0
                    while (middleware.executions().any { !it.physicalState.isSettled }) {
                        check(++rounds <= 80) { "주기 $cycle 가 80번 밀어도 정착하지 않았다" }
                        stack.mimic.server.advance(Duration.ofSeconds(5))
                        repeat(2) {
                            Thread.sleep(20)
                            activePumps.time { host.pumpOnce() }
                            pumps++
                        }
                    }
                    // 정착 뒤 기록(응답 기록·정착 이벤트)이 마저 적히게 한 번 더
                    activePumps.time { host.pumpOnce() }
                    pumps++
                    activeStmts += stats.read().calls()
                    activeCount += pumps
                }
                active += listOf(
                    "${plan.robots}", "$lastCheckpoint→${middleware.executions().size}", "${activePumps.count}",
                ) + activePumps.cells() + listOf(f(activeStmts.toDouble() / activeCount))
                activePumps = Timings(); activeStmts = 0; activeCount = 0
                lastCheckpoint = middleware.executions().size

                // idle
                repeat(3) { host.pumpOnce() }
                stats.reset()
                val t = Timings()
                val pending = Timings()
                val incidents = Timings()
                repeat(idleRounds) {
                    t.time { host.pumpOnce() }
                    pending.time { middleware.pending() }
                    incidents.time { middleware.incidents() }
                }
                val stmts = stats.read()
                val view = Timings()
                var bytes = 0
                repeat(10) { val (_, ms, size) = stack.hostGet("/host/executions"); view.add(ms); bytes = size }
                idle += listOf(
                    "${plan.robots}", "${middleware.executions().size}", "${middleware.responses().size}", "${middleware.pending().size}",
                    "${middleware.incidents().size}",
                ) + t.cells() + listOf(f(stmts.calls().toDouble() / idleRounds), f(pending.p50 * 1000), f(incidents.p50 * 1000)) +
                    view.cells() + listOf("$bytes")
                if (plan == plans.last() && target == plan.checkpoints.last()) stmtDetails += "기체 ${plan.robots} · 실행 ${middleware.executions().size} · idle" to stmts
            }
            if (stack.ops != null) {
                // 핫스팟 1×3: 실행이 쌓인 호스트에 대고 스테이션 tick. 활성 스테이션마다 GET /host/executions 전체를 읽는다.
                val (r, _) = StationTickLoad.measure(stack, combinedStations, listOf("active"), combinedRounds, " (호스트 실행 ${middleware.executions().size})") { host.pumpOnce() }
                combined += r
            }
        }
        report.line("### 일하는 pump (주기 안의 모든 pumpOnce)")
        report.line()
        report.table(listOf("기체 M", "실행 수 구간", "pump 수", "p50 ms", "p95 ms", "max ms", "DB 문장/pump"), active)
        report.line("### idle pump 와 크기")
        report.line()
        report.table(
            listOf(
                "기체 M", "실행 E", "아웃박스(responses)", "pending", "인시던트", "idle p50 ms", "p95 ms", "max ms", "DB 문장/pump",
                "pending() p50 µs", "incidents() p50 µs", "GET /host/executions p50 ms", "p95 ms", "max ms", "그 응답 bytes",
            ),
            idle,
        )
        stmtDetails.forEach { (title, stmts) -> report.statements("문장별(pump 당) — $title", stmts, idleRounds) }
        if (combined.isNotEmpty()) {
            report.line("### 핫스팟 1×3 — 실행이 쌓인 호스트에 대고 스테이션 tick (회차 $combinedRounds)")
            report.line()
            StationTickLoad.table(report, combined)
        }
    }
}
