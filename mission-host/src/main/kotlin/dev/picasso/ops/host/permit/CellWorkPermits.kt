package dev.picasso.ops.host.permit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.PermitCause
import dev.picasso.middleware.PermitReading
import dev.picasso.middleware.PermitReason
import dev.picasso.middleware.PermitScope
import dev.picasso.middleware.RobotPermit
import dev.picasso.middleware.WorkPermit
import dev.picasso.middleware.WorkPermitPort
import dev.picasso.ops.host.cell.CellSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 안전 신호 하나가 맡은 기체 집합(ADR 52 결정 3). 현장이 선언한다. 이 층은 신호 이름과 기체 id 를 추론하지 않는다.
 *
 * @param signal 셀 대역의 안전 신호 이름. 셀 대역에서 `safety` 가 참인 신호여야 한다(아니면 그 줄은 모름이다)
 * @param permittedWhen 이 값일 때만 새 발행을 허가로 비춘다. 다른 값은 모두 막음이다
 * @param robots 이 신호가 맡은 기체 id. 비어 있지 않다
 */
data class PermitMapping(val signal: String, val permittedWhen: String, val robots: Set<String>)

/**
 * 현장이 선언한 작업 허가 대응(실행 호스트 설정 `host.work-permit.mapping` 의 JSON 파일). 이것이 있어야 실행 호스트가 미들웨어에 허가
 * 포트를 붙인다. 없으면 붙이지 않고 오늘의 발행 거동 그대로다(ADR 52 결정 10).
 *
 * 본문은 `{"validFor":"PT10S","signals":[{"signal":"guard_closed","permittedWhen":"true","robots":["humanoid-01"]}]}` 이다.
 * `validFor` 는 빠지면 [DEFAULT_VALID_FOR] 다. 모르는 칸은 받지 않는다.
 *
 * **대응에 없는 기체는 붙인 뒤로 어느 원천도 덮지 않는 기체**이고 발행 게이트가 막는다(`NOT_COVERED`). 그래서 현장은 명부의 기체를
 * 빠짐없이 어느 신호엔가 넣어야 한다.
 *
 * @param validFor 판독 하나가 지금의 값으로 읽히는 폭. 관측 시각은 이 층이 셀 대역을 읽은 호스트 시각이다
 */
data class WorkPermitMap(val signals: List<PermitMapping>, val validFor: Duration = DEFAULT_VALID_FOR) {
    init {
        require(signals.isNotEmpty()) { "작업 허가 대응에 신호가 없다" }
        require(signals.map { it.signal }.distinct().size == signals.size) { "작업 허가 대응의 신호 이름이 겹친다: ${signals.map { it.signal }}" }
        require(signals.all { it.signal.isNotBlank() && it.permittedWhen.isNotBlank() && it.robots.isNotEmpty() && it.robots.none(String::isBlank) }) {
            "작업 허가 대응의 줄마다 신호 이름, 허가 값, 기체가 있어야 한다"
        }
        require(!validFor.isNegative && !validFor.isZero) { "validFor 는 양의 기간이어야 한다: $validFor" }
    }

    /** 어느 신호엔가 든 기체 전부. */
    val governed: Set<String> get() = signals.flatMapTo(sortedSetOf()) { it.robots }

    companion object {
        /**
         * 판독의 기본 유효 폭. pump 주기(250ms)와 셀 대역 읽기 제한(1초)보다 넉넉하다. 셀 대역을 못 읽으면 이 폭을 기다리지 않고 세션이
         * 바뀌어 곧바로 모름이 된다([CellWorkPermits.observe]). 이 폭이 잡는 것은 pump 가 멈춘 채 다른 길(개정)이 허가를 묻는 경우다.
         */
        val DEFAULT_VALID_FOR: Duration = Duration.ofSeconds(10)

        private val FIELDS = setOf("signals", "validFor")
        private val LINE_FIELDS = setOf("signal", "permittedWhen", "robots")

        fun read(file: Path): WorkPermitMap = parse(ObjectMapper().readTree(Files.readString(file)))

        fun parse(node: JsonNode): WorkPermitMap {
            require(node.isObject && node.fieldNames().asSequence().toSet().let { it.containsAll(setOf("signals")) && FIELDS.containsAll(it) }) {
                "작업 허가 대응은 signals 와 선택 칸 validFor 만 든 객체여야 한다"
            }
            val lines = requireNotNull(node["signals"].takeIf { it.isArray }) { "signals 는 배열이어야 한다" }.mapIndexed { index, line ->
                require(line.isObject && line.fieldNames().asSequence().toSet() == LINE_FIELDS) { "signals ${index}번째 줄은 signal·permittedWhen·robots 를 든 객체여야 한다" }
                fun text(name: String) = requireNotNull(line[name].takeIf { it.isTextual }?.asText()) { "signals ${index}번째 줄의 $name 이 문자열이 아니다" }
                val robots = requireNotNull(line["robots"].takeIf { it.isArray && it.all(JsonNode::isTextual) }) { "signals ${index}번째 줄의 robots 는 문자열 배열이어야 한다" }
                PermitMapping(text("signal"), text("permittedWhen"), robots.map { it.asText() }.toCollection(linkedSetOf()))
            }
            val validFor = node["validFor"]?.let { value ->
                require(value.isTextual) { "validFor 는 ISO-8601 기간 문자열이어야 한다" }
                try {
                    Duration.parse(value.asText())
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException("validFor 를 읽지 못했다: ${value.asText()}", e)
                }
            } ?: DEFAULT_VALID_FOR
            return WorkPermitMap(lines, validFor)
        }
    }
}

/**
 * **셀 대역 안전 신호 위의 작업 허가 포트**(ADR 52) — 현장이 선언한 대응([WorkPermitMap])으로 안전 신호의 지금 값을 기체 집합마다의 허가로
 * 비춘다.
 *
 * ⚠ **안전 기능이 아니다**(ADR 32). 기체를 세우는 것은 안전 계통이다. 이 값으로 미들웨어는 새 로봇 명령만 내지 않고, 이미 나간 명령과
 * 취소·관측은 그대로다.
 *
 * ## 원천과 줄
 *
 * 대응의 신호 하나가 원천 하나다(`cell/<신호 이름>`). 원천마다 판독 하나, 줄 하나이고 줄의 기체 집합은 대응의 기체 집합 그대로다 — 이
 * 층은 범위를 모르는 줄(`robots = null`)을 내지 않는다. 신호의 값에 따라:
 *
 * - 허가 값과 같으면 `PERMITTED`.
 * - 다르면 `INHIBITED`. 막는 주체는 `신호=값`, 풀릴 근거는 안전 계통이 그 신호를 허가 값으로 되돌릴 때다.
 * - 셀 대역에 그 신호가 없거나, 신호 목록이 없거나, 안전 신호가 아니면 `UNKNOWN`(원천이 모른다고 말한 것, `REPORTED`).
 *
 * ## 세션(정직한 태그)
 *
 * 셀 대역은 세션이나 기동 번호를 내지 않는다. 그래서 이 층이 정한다: **셀 대역을 못 읽은 순간마다 새 세션**이다. 읽기가 끊긴 사이에
 * 현장이 다시 떴는지 이 층은 모르므로 끊김 하나를 원천의 재기동으로 본다(닫힌 쪽). 끊긴 동안 판독은 앞 세션의 줄을 그대로 들고 세션만
 * 새 것을 말하므로 발행 게이트가 그 줄을 `SESSION_CHANGED` 모름으로 읽는다 — 마지막으로 본 주체가 함께 보인다. 다시 읽히면 새 세션으로
 * 다시 관측한다. 호스트가 다시 뜨면 세션 번호는 1 부터 다시 센다(미들웨어도 새 인스턴스라 앞 세션의 줄을 들고 있지 않다).
 *
 * ## 막히지 않는 읽기
 *
 * [read] 는 [observe] 가 마지막으로 지어 둔 판독을 돌려줄 뿐이다. 셀 대역 HTTP 는 호스트가 잠금 밖에서 읽고 잠금 아래에서 [observe] 로
 * 넘긴다. 그래서 [read] 는 호스트 잠금 아래에서 불려도 기다리지 않는다(seams «작업 허가»).
 *
 * @param start 처음 판독의 관측 시각. 셀 대역을 한 번도 읽기 전에는 모든 원천이 `UNKNOWN` 이다
 */
class CellWorkPermits(val map: WorkPermitMap, start: Instant) : WorkPermitPort {

    private var session = 1
    private var lost = false
    private var lastScopes: Map<String, PermitScope> = map.signals.associate { line ->
        line.signal to scope(line, WorkPermit.UNKNOWN, NOT_READ_YET, "셀 대역을 읽을 때", start)
    }

    @Volatile
    private var readings: List<PermitReading> = build()

    /** 지금 세션의 이름. 화면과 시험이 읽는다. */
    val currentSession: String get() = sessionName(session)

    /**
     * 셀 대역 스냅숏 하나를 받는다. 호스트가 pump 마다 잠금 아래에서 부른다. [snapshot] 이 `null` 이면 셀 대역을 못 읽은 것이다.
     *
     * @param at 이 층이 셀 대역을 읽은 호스트 시각. 줄의 관측 시각이 된다
     */
    @Synchronized
    fun observe(snapshot: CellSnapshot?, at: Instant) {
        if (snapshot == null) {
            if (!lost) {
                lost = true
                session++
            }
        } else {
            lost = false
            lastScopes = map.signals.associate { line -> line.signal to project(line, snapshot, at) }
        }
        readings = build()
    }

    override fun read(): List<PermitReading> = readings

    /**
     * 기체마다 지금의 허가. **발행 게이트가 같은 판독을 판정하는 규칙**(picasso `IssuingGate`)을 그대로 따른다 — 게이트는 미들웨어 안에
     * 있어 밖에서 부를 수 없으므로 화면과 스테이션 조정이 읽을 값을 여기서 같은 규칙으로 낸다. 어긋나지 않는지는 호스트 시험이 막힌
     * 실행의 `permitBlock` 과 대조한다.
     *
     * @param robots 판정할 기체. 기본은 대응이 맡은 기체 전부다
     */
    fun evaluate(now: Instant, robots: Set<String> = map.governed): List<RobotPermit> {
        val current = readings
        return robots.sorted().map { robot -> permitOf(robot, current, now) }
    }

    private fun permitOf(robot: String, readings: List<PermitReading>, now: Instant): RobotPermit {
        val found = readings.flatMap { reading ->
            reading.scopes.filter { scope -> scope.robots?.contains(robot) ?: (robot in reading.governed) }
                .map { scope -> judge(reading, scope, now) }
        }
        if (found.isEmpty()) {
            return RobotPermit(robot, WorkPermit.UNKNOWN, listOf(PermitReason(null, PermitCause.NOT_COVERED, null, "이 기체를 덮는 허가 원천이 생길 때", null)))
        }
        val permit = listOf(WorkPermit.INHIBITED, WorkPermit.UNKNOWN, WorkPermit.PERMITTED).first { p -> found.any { it.first == p } }
        return RobotPermit(robot, permit, found.filter { it.first == permit }.map { it.second })
    }

    private fun judge(reading: PermitReading, scope: PermitScope, now: Instant): Pair<WorkPermit, PermitReason> {
        val source = reading.source
        fun unknown(cause: PermitCause, basis: String) =
            WorkPermit.UNKNOWN to PermitReason(source, cause, scope.subject, scope.resumeBasis ?: basis, scope.observedAt)
        return when {
            scope.session != reading.session ->
                unknown(PermitCause.SESSION_CHANGED, "원천 $source 가 지금 세션(${reading.session})에서 다시 관측할 때")
            now.isAfter(scope.observedAt.plus(scope.validFor)) ->
                unknown(PermitCause.EXPIRED, "원천 $source 가 유효한 관측을 다시 낼 때")
            scope.robots == null ->
                unknown(PermitCause.SCOPE_UNKNOWN, "원천 $source 가 영향 범위를 기체 집합으로 다시 말할 때")
            else -> scope.permit to PermitReason(
                source, PermitCause.REPORTED, scope.subject, scope.resumeBasis ?: "원천 $source 가 PERMITTED 를 낼 때", scope.observedAt,
            )
        }
    }

    private fun build(): List<PermitReading> = map.signals.map { line ->
        PermitReading(source(line.signal), sessionName(session), line.robots, listOf(lastScopes.getValue(line.signal)))
    }

    private fun project(line: PermitMapping, snapshot: CellSnapshot, at: Instant): PermitScope {
        val signals = snapshot.signals
            ?: return scope(line, WorkPermit.UNKNOWN, "셀 대역이 신호 목록을 주지 않았다", "셀 대역이 신호 목록을 줄 때", at)
        val signal = signals.firstOrNull { it.name == line.signal }
            ?: return scope(line, WorkPermit.UNKNOWN, "${line.signal} 신호가 셀 대역에 없다", "셀 대역이 ${line.signal} 을 낼 때", at)
        if (!signal.safety) {
            return scope(line, WorkPermit.UNKNOWN, "${line.signal} 은 안전 신호가 아니다", "작업 허가 대응을 안전 신호로 고칠 때", at)
        }
        val subject = "${line.signal}=${signal.value}"
        return if (signal.value == line.permittedWhen) {
            scope(line, WorkPermit.PERMITTED, subject, null, at)
        } else {
            scope(line, WorkPermit.INHIBITED, subject, "안전 계통이 ${line.signal} 을 ${line.permittedWhen} 으로 되돌릴 때", at)
        }
    }

    private fun scope(line: PermitMapping, permit: WorkPermit, subject: String?, resumeBasis: String?, at: Instant) =
        PermitScope(line.robots, permit, subject, resumeBasis, at, map.validFor, sessionName(session))

    companion object {
        /** 원천 이름의 앞머리. 뒤에 신호 이름이 붙는다. */
        const val SOURCE_PREFIX = "cell/"

        /** 셀 대역을 한 번도 읽기 전의 막는 주체. */
        const val NOT_READ_YET = "셀 대역을 아직 읽지 않았다"

        fun source(signal: String) = "$SOURCE_PREFIX$signal"

        private fun sessionName(n: Int) = "cell-band#$n"
    }
}
