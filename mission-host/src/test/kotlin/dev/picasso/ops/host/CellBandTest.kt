package dev.picasso.ops.host

import dev.picasso.middleware.SlotSignal
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.CellBandSignals
import dev.picasso.ops.host.cell.CellPlace
import dev.picasso.ops.host.cell.CellSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 셀 대역 클라이언트의 셀 신호(S3a 스펙 §7.4). */
class CellBandTest {

    private val filledAt = Instant.parse("2026-10-08T00:01:00Z")

    private val snapshot = CellSnapshot(
        presentations = listOf(CellPlace("SEQ-IN-02.BIN-A", true, "ENGINE-COVER-A", null)),
        slots = listOf(
            // 막 채운 슬롯도 같은 자재를 든다. 대안 자리로 내면 안 된다.
            CellPlace("RACK-204.S01", true, "ENGINE-COVER-A", filledAt),
            CellPlace("RACK-204.S02", false, null, null),
        ),
    )

    @Test
    fun `holding 은 그 자재를 든 제시 자리만 낸다`() {
        val signals = CellBandSignals().apply { snapshot = this@CellBandTest.snapshot }
        assertEquals(listOf("SEQ-IN-02.BIN-A"), signals.holding("ENGINE-COVER-A"))
        assertEquals(emptyList(), signals.holding("OTHER"))
    }

    @Test
    fun `observe 는 자리의 점유·자재·관측 시각을 내고 모르는 자리는 null 이다`() {
        val signals = CellBandSignals().apply { snapshot = this@CellBandTest.snapshot }
        assertEquals(SlotSignal(true, "ENGINE-COVER-A", null), signals.observe("SEQ-IN-02.BIN-A"))
        assertEquals(SlotSignal(true, "ENGINE-COVER-A", filledAt), signals.observe("RACK-204.S01"))
        assertEquals(SlotSignal(false, null, null), signals.observe("RACK-204.S02"))
        assertNull(signals.observe("dock-3"))
        assertNull(signals.signal("door-open"))
    }

    @Test
    fun `스냅숏이 없으면 observe 와 holding 모두 못 물어봄이다`() {
        val signals = CellBandSignals()
        assertNull(signals.observe("SEQ-IN-02.BIN-A"))
        assertNull(signals.holding("ENGINE-COVER-A"))
    }

    @Test
    fun `현장 본문을 읽고 모양이 어긋나면 예외다`() {
        val read = CellBandClient.parse(HostBench.JSON.readTree(HostBench.STANDARD_CELL))
        assertEquals(listOf(CellPlace("SEQ-IN-02.BIN-A", true, "ENGINE-COVER-A", null)), read.presentations)
        assertEquals(4, read.slots.size)
        val filled = CellBandClient.parse(
            HostBench.JSON.readTree(
                """{"presentations":[],"slots":[{"id":"S","occupied":true,"material":"M","observedAt":"$filledAt"}]}""",
            ),
        )
        assertEquals(CellPlace("S", true, "M", filledAt), filled.slots.single())
        assertFailsWith<IllegalArgumentException> { CellBandClient.parse(HostBench.JSON.readTree("""{"slots":[]}""")) }
    }

    @Test
    fun `닿지 않는 셀 대역은 null 이다`() {
        assertNull(CellBandClient("http://127.0.0.1:1").fetch())
    }
}
