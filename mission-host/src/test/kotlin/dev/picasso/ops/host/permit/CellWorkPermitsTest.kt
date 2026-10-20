package dev.picasso.ops.host.permit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.PermitCause
import dev.picasso.middleware.RobotPermit
import dev.picasso.middleware.WorkPermit
import dev.picasso.middleware.mission.SignalKind
import dev.picasso.ops.host.cell.CellSignal
import dev.picasso.ops.host.cell.CellSnapshot
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 셀 대역 안전 신호 위의 작업 허가 포트(ADR 52). 대응에서 판독으로, 판독에서 기체마다의 허가로. HTTP·DB 없이 스냅숏을 직접 넘긴다.
 *
 * 기체마다의 판정([CellWorkPermits.evaluate])이 발행 게이트와 같은 규칙인지는 호스트 시험(`WorkPermitHostTest`)이 막힌 실행의 `permitBlock` 과
 * 대조한다.
 */
class CellWorkPermitsTest {

    private val start = Instant.parse("2026-10-14T00:00:00Z")
    private val map = WorkPermitMap(
        listOf(
            PermitMapping("guard_closed", "true", setOf(HUMANOID)),
            PermitMapping("door_b_closed", "true", setOf(QUADRUPED)),
        ),
    )

    private fun snapshot(vararg signals: CellSignal) = CellSnapshot(emptyList(), emptyList(), signals.toList())

    private fun safety(name: String, value: String, safety: Boolean = true) = CellSignal(name, null, SignalKind.BOOLEAN, safety, value, null)

    private fun CellWorkPermits.one(robot: String, now: Instant): RobotPermit = evaluate(now, setOf(robot)).single()

    @Test
    fun `안전 신호가 허가 값이면 PERMITTED 이고 다른 값이면 막는 주체와 풀릴 근거를 든 INHIBITED 다`() {
        val permits = CellWorkPermits(map, start)
        val at = start.plusSeconds(1)
        permits.observe(snapshot(safety("guard_closed", "false"), safety("door_b_closed", "true")), at)

        val humanoid = permits.one(HUMANOID, at)
        assertEquals(WorkPermit.INHIBITED, humanoid.permit)
        val reason = humanoid.reasons.single()
        assertEquals("cell/guard_closed", reason.source)
        assertEquals(PermitCause.REPORTED, reason.cause)
        assertEquals("guard_closed=false", reason.subject)
        assertEquals("안전 계통이 guard_closed 을 true 으로 되돌릴 때", reason.resumeBasis)
        assertEquals(at, reason.observedAt)

        assertEquals(WorkPermit.PERMITTED, permits.one(QUADRUPED, at).permit)
        // 판독은 원천(신호)마다 하나, 줄 하나이고 범위는 대응의 기체 집합 그대로다.
        val readings = permits.read()
        assertEquals(listOf("cell/guard_closed", "cell/door_b_closed"), readings.map { it.source })
        assertEquals(listOf(setOf(HUMANOID), setOf(QUADRUPED)), readings.map { it.scopes.single().robots })
        assertEquals(listOf(setOf(HUMANOID), setOf(QUADRUPED)), readings.map { it.governed })
    }

    @Test
    fun `신호가 없거나 안전 신호가 아니거나 신호 목록이 없으면 원천이 말한 UNKNOWN 이다`() {
        val permits = CellWorkPermits(map, start)
        permits.observe(snapshot(safety("guard_closed", "true", safety = false)), start)
        assertEquals(WorkPermit.UNKNOWN to "guard_closed 은 안전 신호가 아니다", permits.one(HUMANOID, start).let { it.permit to it.reasons.single().subject })
        assertEquals(WorkPermit.UNKNOWN to "door_b_closed 신호가 셀 대역에 없다", permits.one(QUADRUPED, start).let { it.permit to it.reasons.single().subject })
        assertEquals(PermitCause.REPORTED, permits.one(QUADRUPED, start).reasons.single().cause)

        permits.observe(CellSnapshot(emptyList(), emptyList(), null), start)
        assertEquals("셀 대역이 신호 목록을 주지 않았다", permits.one(HUMANOID, start).reasons.single().subject)
    }

    @Test
    fun `셀 대역을 한 번도 읽기 전에는 모든 기체가 UNKNOWN 이다`() {
        val permits = CellWorkPermits(map, start)
        val humanoid = permits.one(HUMANOID, start)
        assertEquals(WorkPermit.UNKNOWN, humanoid.permit)
        assertEquals(CellWorkPermits.NOT_READ_YET, humanoid.reasons.single().subject)
    }

    @Test
    fun `셀 대역을 못 읽으면 세션이 바뀌어 앞 세션의 PERMITTED 가 SESSION_CHANGED UNKNOWN 이 되고 다시 읽으면 새 세션으로 허가한다`() {
        val permits = CellWorkPermits(map, start)
        permits.observe(snapshot(safety("guard_closed", "true"), safety("door_b_closed", "true")), start)
        assertEquals("cell-band#1", permits.currentSession)
        assertEquals(WorkPermit.PERMITTED, permits.one(HUMANOID, start).permit)

        permits.observe(null, start.plusSeconds(1))
        assertEquals("cell-band#2", permits.currentSession)
        val lost = permits.one(HUMANOID, start.plusSeconds(1))
        assertEquals(WorkPermit.UNKNOWN, lost.permit)
        assertEquals(PermitCause.SESSION_CHANGED, lost.reasons.single().cause)
        // 마지막으로 본 주체가 함께 보인다.
        assertEquals("guard_closed=true", lost.reasons.single().subject)
        assertEquals("원천 cell/guard_closed 가 지금 세션(cell-band#2)에서 다시 관측할 때", lost.reasons.single().resumeBasis)

        // 끊긴 채 다시 못 읽어도 세션은 한 번만 바뀐다.
        permits.observe(null, start.plusSeconds(2))
        assertEquals("cell-band#2", permits.currentSession)

        permits.observe(snapshot(safety("guard_closed", "true"), safety("door_b_closed", "true")), start.plusSeconds(3))
        assertEquals("cell-band#2", permits.currentSession)
        assertEquals(WorkPermit.PERMITTED, permits.one(HUMANOID, start.plusSeconds(3)).permit)
        assertEquals(setOf("cell-band#2"), permits.read().flatMap { r -> r.scopes.map { it.session } + r.session }.toSet())
    }

    @Test
    fun `관측이 유효 기간을 넘기면 EXPIRED UNKNOWN 이다`() {
        val permits = CellWorkPermits(map.copy(validFor = Duration.ofSeconds(2)), start)
        permits.observe(snapshot(safety("guard_closed", "true"), safety("door_b_closed", "true")), start)
        assertEquals(WorkPermit.PERMITTED, permits.one(HUMANOID, start.plusSeconds(2)).permit)
        val stale = permits.one(HUMANOID, start.plusSeconds(2).plusMillis(1))
        assertEquals(WorkPermit.UNKNOWN, stale.permit)
        assertEquals(PermitCause.EXPIRED, stale.reasons.single().cause)
    }

    @Test
    fun `대응에 없는 기체는 NOT_COVERED UNKNOWN 이다`() {
        val permits = CellWorkPermits(map, start)
        permits.observe(snapshot(safety("guard_closed", "true"), safety("door_b_closed", "true")), start)
        val ghost = permits.one(GHOST, start)
        assertEquals(WorkPermit.UNKNOWN, ghost.permit)
        assertEquals(PermitCause.NOT_COVERED, ghost.reasons.single().cause)
        assertNull(ghost.reasons.single().source)
        // 기본 판정 대상은 대응이 맡은 기체 전부다.
        assertEquals(listOf(HUMANOID, QUADRUPED), permits.evaluate(start).map { it.robotId })
    }

    @Test
    fun `한 기체를 둘이 맡으면 가장 닫힌 값을 고른다`() {
        val shared = WorkPermitMap(listOf(PermitMapping("guard_closed", "true", setOf(HUMANOID)), PermitMapping("door_b_closed", "true", setOf(HUMANOID))))
        val permits = CellWorkPermits(shared, start)
        permits.observe(snapshot(safety("guard_closed", "true"), safety("door_b_closed", "false")), start)
        val humanoid = permits.one(HUMANOID, start)
        assertEquals(WorkPermit.INHIBITED, humanoid.permit)
        assertEquals(listOf("cell/door_b_closed"), humanoid.reasons.map { it.source })
    }

    @Test
    fun `대응 파일은 칸을 엄격히 읽고 validFor 가 빠지면 기본값이다`() {
        val json = ObjectMapper()
        val read = WorkPermitMap.parse(json.readTree("""{"signals":[{"signal":"guard_closed","permittedWhen":"true","robots":["humanoid-01","quadruped-01"]}]}"""))
        assertEquals(listOf(PermitMapping("guard_closed", "true", setOf(HUMANOID, QUADRUPED))), read.signals)
        assertEquals(WorkPermitMap.DEFAULT_VALID_FOR, read.validFor)
        assertEquals(Duration.ofSeconds(3), WorkPermitMap.parse(json.readTree("""{"validFor":"PT3S","signals":[{"signal":"g","permittedWhen":"true","robots":["r"]}]}""")).validFor)
        for (bad in listOf(
            """{"signals":[]}""",
            """{"signals":[{"signal":"g","permittedWhen":"true","robots":[]}]}""",
            """{"signals":[{"signal":"g","permittedWhen":"true","robots":["r"],"zone":"A"}]}""",
            """{"signals":[{"signal":"g","robots":["r"]}]}""",
            """{"signals":[{"signal":"g","permittedWhen":"true","robots":["r"]},{"signal":"g","permittedWhen":"false","robots":["s"]}]}""",
            """{"signals":[{"signal":"g","permittedWhen":"true","robots":["r"]}],"validFor":"PT0S"}""",
            """{"signals":[{"signal":"g","permittedWhen":"true","robots":["r"]}],"extra":1}""",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { WorkPermitMap.parse(json.readTree(bad)) }
        }
        assertTrue(read.governed.containsAll(setOf(HUMANOID, QUADRUPED)))
    }

    companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val GHOST = "ghost-01"
    }
}
