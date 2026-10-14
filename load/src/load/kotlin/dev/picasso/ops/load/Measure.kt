package dev.picasso.ops.load

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** 실제 시간 표본(ms). */
class Timings {
    private val samples = mutableListOf<Double>()
    fun <T> time(block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            samples += (System.nanoTime() - started) / 1e6
        }
    }
    fun add(ms: Double) { samples += ms }
    val count: Int get() = samples.size
    fun pct(p: Double): Double {
        if (samples.isEmpty()) return Double.NaN
        val sorted = samples.sorted()
        return sorted[((p / 100.0) * (sorted.size - 1)).let { Math.round(it).toInt() }]
    }
    val p50: Double get() = pct(50.0)
    val p95: Double get() = pct(95.0)
    val max: Double get() = samples.maxOrNull() ?: Double.NaN
    val mean: Double get() = if (samples.isEmpty()) Double.NaN else samples.average()
    fun cells(): List<String> = listOf(f(p50), f(p95), f(max))
}

fun f(v: Double): String = if (v.isNaN()) "-" else if (v >= 100) "%.0f".format(v) else if (v >= 10) "%.1f".format(v) else "%.2f".format(v)

/** pg_stat_statements 한 줄. */
data class Stmt(val calls: Long, val totalMs: Double, val rows: Long, val hit: Long, val read: Long, val query: String)

/** 이 세트 DB 의 pg_stat_statements. 측정 구간 앞에서 [reset], 뒤에서 [read]. */
class StatementStats(private val stack: LoadStack) {
    fun reset() = stack.sql("SELECT pg_stat_statements_reset()")

    fun read(filter: (String) -> Boolean = { true }): List<Stmt> = stack.query(
        """SELECT calls, total_exec_time, rows, shared_blks_hit, shared_blks_read, query FROM pg_stat_statements
           WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) AND query NOT ILIKE '%pg_stat_statements%'
           ORDER BY total_exec_time DESC""",
    ) { Stmt(it.getLong(1), it.getDouble(2), it.getLong(3), it.getLong(4), it.getLong(5), it.getString(6).replace(Regex("\\s+"), " ").trim()) }
        .filter { filter(it.query) }
}

fun List<Stmt>.calls(): Long = sumOf { it.calls }
fun List<Stmt>.rows(): Long = sumOf { it.rows }
fun List<Stmt>.dbMs(): Double = sumOf { it.totalMs }
fun List<Stmt>.blocks(): Long = sumOf { it.hit + it.read }

/** 결과 파일. 표와 명령만 적는다. */
class Report(name: String) {
    private val file: Path = Path.of(System.getProperty("load.out") ?: "build/load").resolve("$name.md")
    init {
        Files.createDirectories(file.parent)
        Files.writeString(file, "")
    }
    fun line(text: String = "") {
        println(text)
        Files.writeString(file, text + "\n", StandardOpenOption.APPEND)
    }
    fun table(header: List<String>, rows: List<List<String>>) {
        line("| " + header.joinToString(" | ") + " |")
        line("|" + header.joinToString("|") { "---" } + "|")
        rows.forEach { line("| " + it.joinToString(" | ") { cell -> cell.replace("|", "\\|") } + " |") }
        line()
    }
    /** 세트의 바닥 값. 표 위에 한 줄. */
    fun floor(stack: LoadStack, label: String) {
        val rtt = stack.roundTrip()
        line("$label — Postgres ${stack.serverVersion()}, JDBC 왕복 SELECT 1 p50 ${f(rtt.p50)} ms / p95 ${f(rtt.p95)} ms / max ${f(rtt.max)} ms")
        line()
    }
    fun statements(title: String, stmts: List<Stmt>, rounds: Int, limit: Int = 12) {
        line(title)
        line()
        table(
            listOf("calls/회차", "rows/회차", "DB ms/회차", "blocks/회차", "문장"),
            stmts.sortedByDescending { it.calls }.take(limit).map {
                listOf(f(it.calls.toDouble() / rounds), f(it.rows.toDouble() / rounds), f(it.totalMs / rounds), f((it.hit + it.read).toDouble() / rounds), "`" + it.query.take(110) + "`")
            },
        )
    }
}
