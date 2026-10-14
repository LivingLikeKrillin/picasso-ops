package dev.picasso.ops.load

import java.lang.management.ManagementFactory

/**
 * `./gradlew :load:loadBench --args="<시나리오…> [quick]"`. 시나리오는 stations·delivery·pump·explain·all 이다.
 * `quick` 을 붙이면 규모를 줄여 배선만 확인한다. 결과는 `load/build/load/<시나리오>.md` 와 표준 출력에 같이 남는다.
 */
fun main(args: Array<String>) {
    val quick = "quick" in args
    val chosen = args.filter { it != "quick" }.ifEmpty { listOf("all") }
    val all = "all" in chosen
    val env = Report("environment")
    val os = ManagementFactory.getOperatingSystemMXBean()
    env.table(
        listOf("항목", "값"),
        listOf(
            listOf("JVM", "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"),
            listOf("OS", "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}"),
            listOf("논리 CPU(JVM)", "${os.availableProcessors}"),
            listOf("최대 힙", "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB"),
            listOf("모드", if (quick) "quick" else "full"),
        ),
    )
    if (all || "explain" in chosen) WorkRequestExplain.run(Report("explain"), if (quick) listOf(1_000) else listOf(10_000, 100_000))
    if (all || "delivery" in chosen) DeliveryLoad.run(Report("delivery"), if (quick) listOf(100) else listOf(100, 1_000, 10_000, 100_000), if (quick) 5 else 20)
    if (all || "stations" in chosen) StationTickLoad.run(Report("stations"), if (quick) listOf(1, 3) else listOf(1, 10, 50, 100), if (quick) 5 else 20)
    if (all || "pump" in chosen) PumpLoad.run(
        Report("pump"),
        if (quick) {
            listOf(PumpLoad.Plan(2, listOf(4)))
        } else {
            listOf(
                PumpLoad.Plan(1, listOf(10, 50)),
                PumpLoad.Plan(10, listOf(10, 100, 300)),
                PumpLoad.Plan(50, listOf(50, 500, 1_000, 2_000)),
            )
        },
        if (quick) 5 else 30,
        combinedStations = if (quick) 2 else 50,
    )
    System.exit(0)
}
