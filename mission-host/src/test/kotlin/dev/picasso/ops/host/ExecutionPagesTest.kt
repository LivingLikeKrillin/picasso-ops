package dev.picasso.ops.host

import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.UnitState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 화면 실행 목록의 활성·남은 실행 판정(HA 설계 기록 §10). 판정이 상태 이름으로 적은 표와 같은지 본다.
 */
class ExecutionPagesTest {

    /** 정착 전이거나 부분 완료(PARTIAL)인 상태. `PhysicalState.pumps` 를 쓰지 않고 이름으로 적는다. */
    private val running = setOf("REQUESTED", "ACCEPTED", "RUNNING", "PARTIAL", "IN_DOUBT", "OPERATOR_HOLD", "CANCELING")
    private val held = setOf("OPERATOR_HOLD", "IN_DOUBT")

    @Test
    fun `활성은 더 돌 수 있는 실행과 운영자 보류나 불확실 단위가 남은 실행과 일지에 막힌 실행이다`() {
        val units = listOf(emptyList<UnitState>()) + UnitState.entries.map { listOf(UnitState.DONE, it) }
        var seen = 0
        for (state in PhysicalState.entries) for (unit in units) for (blocked in listOf(false, true)) {
            val expected = state.name in running || unit.any { it.name in held } || blocked
            assertEquals(expected, ExecutionPages.active(state, unit, blocked), "$state $unit 일지 막힘 $blocked")
            seen++
        }
        assertEquals(PhysicalState.entries.size * (UnitState.entries.size + 1) * 2, seen)
        // 정착이고 보류 단위가 없고 일지에 든 실행만 정착 실행이다.
        val settled = PhysicalState.entries.filter { !ExecutionPages.active(it, listOf(UnitState.DONE), false) }.map { it.name }
        assertEquals(listOf("PHYSICALLY_DONE", "UNVERIFIED", "FAILED", "ABORTED"), settled)
    }

    @Test
    fun `남은 실행은 활성이거나 정착 기록이 아직 커밋되지 않은 실행이다`() {
        assertEquals(
            listOf(true, true, true, false),
            listOf(true to true, true to false, false to false, false to true).map { (active, recorded) -> ExecutionPages.live(active, recorded) },
        )
    }
}
