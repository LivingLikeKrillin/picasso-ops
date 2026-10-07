package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.operations.Rejections
import dev.picasso.ops.service.operations.RobotOp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 조작 거절 대응표(스펙 §7.4)의 행마다 응답 코드와 본문을 넣는다. */
class RejectionsTest {

    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val json = ObjectMapper()

    private data class Row(val op: RobotOp, val status: Int, val body: String, val kind: String, val owner: Owner)

    private val table = listOf(
        Row(RobotOp.DECLARE, 400, """{"error":"serial_number 가 비었다"}""", Rejections.BAD_REQUEST, Owner.ENGINEER),
        Row(RobotOp.DECLARE, 409, """{"error":"다른 문","origin":"DISCOVERED"}""", Rejections.WRONG_DOOR, Owner.NONE),
        Row(RobotOp.DECLARE, 409, """{"error":"퇴역","status":"RETIRED"}""", Rejections.RETIRED_ALREADY, Owner.OPERATOR),
        Row(RobotOp.RETIRE, 400, """{"error":"사유가 없다"}""", Rejections.RETIRE_BAD_REQUEST, Owner.OPERATOR),
        Row(RobotOp.RETIRE, 404, """{"error":"모르는 기체다"}""", Rejections.UNKNOWN_ROBOT, Owner.ENGINEER),
        Row(RobotOp.REINSTATE, 404, """{"error":"모르는 기체다"}""", Rejections.UNKNOWN_ROBOT, Owner.ENGINEER),
    )

    @Test
    fun `대응표의 행마다 종류와 해결 담당이 맞다`() {
        table.forEach { row ->
            val finding = Rejections.of(row.op, row.status, json.readTree(row.body), at, "r1")
            assertEquals(row.kind to row.owner, finding.kind to finding.owner, "$row")
            assertEquals(at, finding.checkedAt)
            assertEquals("r1", finding.target)
        }
    }

    @Test
    fun `409 는 본문으로 가르고 관측값에 가른 본문을 싣는다`() {
        val wrongDoor = Rejections.of(RobotOp.DECLARE, 409, json.readTree("""{"origin":"DISCOVERED"}"""), at, "r1")
        val retired = Rejections.of(RobotOp.DECLARE, 409, json.readTree("""{"status":"RETIRED"}"""), at, "r1")
        assertEquals("HTTP 409, origin=DISCOVERED", wrongDoor.observed)
        assertEquals("HTTP 409, status=RETIRED", retired.observed)
    }

    @Test
    fun `표에 없는 응답은 아는 종류로 접지 않고 화면 밖 조사로 둔다`() {
        val unclassified = Rejections.of(RobotOp.DECLARE, 409, json.readTree("{}"), at, "r1")
        assertEquals(Rejections.UNCLASSIFIED to false, unclassified.kind to unclassified.inScreen)
        assertEquals(Rejections.UNCLASSIFIED, Rejections.of(RobotOp.DECLARE, 404, null, at, "r1").kind)
        assertEquals(Rejections.UNCLASSIFIED, Rejections.of(RobotOp.REINSTATE, 400, null, at, "r1").kind)
    }
}
