package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.operations.ProfileOp
import dev.picasso.ops.service.operations.ProfileRejections
import dev.picasso.ops.service.operations.Rejections
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 개정판·바인딩 조작의 거절 대응표(P2·S1d 스펙 §8.3)의 행마다 응답 코드와 본문을 넣는다. */
class ProfileRejectionsTest {

    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val json = ObjectMapper()

    private data class Row(val op: ProfileOp, val status: Int, val body: String, val kind: String, val owner: Owner, val inScreen: Boolean)

    private val table = listOf(
        Row(ProfileOp.SUBMIT, 400, """{"error":"프로파일을 읽을 수 없다"}""", ProfileRejections.PROFILE_UNREADABLE, Owner.ENGINEER, true),
        Row(ProfileOp.SUBMIT, 409, """{"received":2,"highest":2}""", ProfileRejections.REVISION_NOT_MONOTONIC, Owner.ENGINEER, true),
        Row(ProfileOp.REQUEST_TEST, 404, """{"error":"없는 개정판이다"}""", ProfileRejections.UNKNOWN_REVISION, Owner.ENGINEER, true),
        Row(ProfileOp.ACTIVATE, 404, """{"error":"없는 개정판이다"}""", ProfileRejections.UNKNOWN_REVISION, Owner.ENGINEER, true),
        Row(ProfileOp.REQUEST_TEST, 409, """{"status":"DRAFT"}""", ProfileRejections.REVISION_NOT_TESTABLE, Owner.ENGINEER, true),
        Row(ProfileOp.ACTIVATE, 409, """{"status":"VALIDATED","suites":{}}""", ProfileRejections.ACTIVATION_REFUSED, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 404, """{"reason":"UNKNOWN_ROBOT"}""", Rejections.UNKNOWN_ROBOT, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 404, """{"reason":"UNKNOWN_REVISION"}""", ProfileRejections.UNKNOWN_REVISION, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 404, """{"reason":"UNKNOWN_BUILD"}""", ProfileRejections.UNKNOWN_BUILD, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 409, """{"reason":"REVISION_NOT_ACTIVE","status":"VALIDATED"}""", ProfileRejections.REVISION_NOT_ACTIVE, Owner.ENGINEER, true),
        Row(ProfileOp.BIND, 409, """{"reason":"ROBOT_RETIRED"}""", ProfileRejections.ROBOT_RETIRED, Owner.OPERATOR, true),
        Row(ProfileOp.BIND, 409, """{"reason":"CONTRACT_TOO_OLD"}""", ProfileRejections.CONTRACT_TOO_OLD, Owner.ENGINEER, true),
        Row(ProfileOp.RECORD_SITE_NAMES, 404, """{"error":"활성 바인딩이 없다"}""", ProfileRejections.NO_ACTIVE_BINDING, Owner.ENGINEER, true),
        Row(ProfileOp.RECORD_SITE_NAMES, 409, """{"error":"등록할 것이 없다"}""", ProfileRejections.NOTHING_TO_REGISTER, Owner.NONE, true),
    )

    @Test
    fun `대응표의 행마다 종류와 해결 담당이 맞다`() {
        table.forEach { row ->
            val finding = ProfileRejections.of(row.op, row.status, json.readTree(row.body), at, "r1")
            assertEquals(Triple(row.kind, row.owner, row.inScreen), Triple(finding.kind, finding.owner, finding.inScreen), "$row")
            assertEquals(at, finding.checkedAt)
        }
    }

    @Test
    fun `바인딩·명칭 거절의 바로 가기는 그 기체이고 개정판 거절은 바로 가기가 없다`() {
        table.forEach { row ->
            val finding = ProfileRejections.of(row.op, row.status, json.readTree(row.body), at, "r1")
            if (row.op == ProfileOp.BIND || row.op == ProfileOp.RECORD_SITE_NAMES) assertEquals("r1", finding.target, "$row") else assertNull(finding.target, "$row")
        }
    }

    @Test
    fun `409 는 본문으로 가르고 관측값에 지금 최대 번호와 상태·스위트 결과를 싣는다`() {
        val notMonotonic = ProfileRejections.of(ProfileOp.SUBMIT, 409, json.readTree("""{"received":2,"highest":3}"""), at, null)
        assertEquals("HTTP 409, highest=3", notMonotonic.observed)
        val refused = ProfileRejections.of(
            ProfileOp.ACTIVATE, 409, json.readTree("""{"status":"VALIDATED","suites":{"CONTRACT":"FAIL"}}"""), at, null,
        )
        assertEquals("HTTP 409, status=VALIDATED, suites={\"CONTRACT\":\"FAIL\"}", refused.observed)
        val retired = ProfileRejections.of(ProfileOp.BIND, 409, json.readTree("""{"reason":"ROBOT_RETIRED"}"""), at, "r1")
        assertEquals("HTTP 409, reason=ROBOT_RETIRED", retired.observed)
    }

    @Test
    fun `표에 없는 응답과 모르는 reason 은 아는 종류로 접지 않고 화면 밖 조사로 둔다`() {
        listOf(
            Triple(ProfileOp.SUBMIT, 404, "{}"),
            Triple(ProfileOp.BIND, 409, """{"reason":"SOMETHING_NEW"}"""),
            Triple(ProfileOp.BIND, 404, "{}"),
            Triple(ProfileOp.ACTIVATE, 400, "{}"),
        ).forEach { (op, status, body) ->
            val finding = ProfileRejections.of(op, status, json.readTree(body), at, "r1")
            assertEquals(Rejections.UNCLASSIFIED to false, finding.kind to finding.inScreen, "$op $status $body")
        }
    }
}
