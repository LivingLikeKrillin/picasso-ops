package dev.picasso.ops.load

import dev.picasso.middleware.Middleware
import dev.picasso.ops.host.MissionHost
import java.net.URLEncoder
import java.time.Duration

/**
 * 실행 목록 크기(HA 설계 기록 §10) — 정착한 실행이 쌓인 호스트의 `GET /host/executions` 와 스테이션 tick.
 *
 * 기체 50 세트에 [PumpLoad] 와 같은 주기(기체마다 InspectAsset 하나, 가상 시계 5초 밀기마다 pump 2회)로 실행을 검사점까지 쌓는다.
 * 검사점마다 전체 목록, 작업 지시 id 하나·50개로 고른 목록을 각 10회 읽고, 스테이션 50 활성 tick 을 잰다. 고르는 인자를 모르는
 * 호스트는 인자를 버리고 전체 목록을 준다(그대로 적는다).
 */
object ListLoad {

    fun run(report: Report, checkpoints: List<Int>, stations: Int, rounds: Int) {
        report.line("## 실행 목록 크기 — `GET /host/executions` 와 스테이션 tick")
        report.line()
        report.line("기체 50, 실행을 검사점까지 쌓은 뒤 각 GET 10회. 스테이션 $stations 활성 tick $rounds 회(예열 3회 뒤).")
        report.line()
        val gets = mutableListOf<List<String>>()
        val ticks = mutableListOf<List<String>>()
        LoadStack(robots = 50).use { stack ->
            report.floor(stack, "세트(기체 50)")
            val host = stack.missionHost
            host.close()
            val middleware = MissionHost::class.java.getDeclaredField("middleware").apply { isAccessible = true }.get(host) as Middleware
            var order = 0
            for (target in checkpoints) {
                while (middleware.executions().size < target) {
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
                    var rounds = 0
                    while (middleware.executions().any { !it.physicalState.isSettled }) {
                        check(++rounds <= 80) { "80번 밀어도 정착하지 않았다" }
                        stack.mimic.server.advance(Duration.ofSeconds(5))
                        repeat(2) { Thread.sleep(20); host.pumpOnce() }
                    }
                    host.pumpOnce()
                }
                repeat(3) { host.pumpOnce() }
                val size = middleware.executions().size
                val one = listOf("JO-${size / 2}")
                val fifty = (1..50).map { "JO-${it * (size / 50)}" }
                gets += listOf("$size", "전체") + read(stack, "/host/executions")
                gets += listOf("$size", "jobOrderId 1") + read(stack, "/host/executions?" + query(one))
                gets += listOf("$size", "jobOrderId 50") + read(stack, "/host/executions?" + query(fifty))
                clearStations(stack)
                ticks += StationTickLoad.measure(stack, stations, listOf("active"), rounds, " (호스트 실행 $size)") { host.pumpOnce() }.first
            }
        }
        report.table(listOf("실행 E", "읽기", "p50 ms", "p95 ms", "max ms", "응답 bytes", "응답 실행 수"), gets)
        StationTickLoad.table(report, ticks)
    }

    private fun query(jobs: List<String>) = jobs.joinToString("&") { "jobOrderId=" + URLEncoder.encode(it, Charsets.UTF_8) }

    private fun read(stack: LoadStack, path: String): List<String> {
        val t = Timings()
        var bytes = 0
        var count = 0
        repeat(10) { val (body, ms, size) = stack.hostGet(path); t.add(ms); bytes = size; count = body["executions"].size() }
        return t.cells() + listOf("$bytes", "$count")
    }

    /** 앞 검사점의 스테이션 시드를 지운다. 다음 측정이 같은 스테이션 id 로 다시 시드한다. */
    private fun clearStations(stack: LoadStack) {
        stack.sql("DELETE FROM ops.station_event")
        stack.sql("UPDATE ops.rf_station SET owner=NULL")
        stack.sql("DELETE FROM ops.station_run")
        stack.sql("DELETE FROM ops.work_request WHERE source_system_id='ops:station'")
        stack.sql("DELETE FROM ops.rf_station")
    }
}
