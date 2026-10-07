package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.operations.AdapterOp
import dev.picasso.ops.service.operations.AdapterRejections
import dev.picasso.ops.service.operations.Rejections
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 어댑터 조작의 거절 대응표(스펙 §7.4 의 P1·인스턴스 행)의 행마다 응답 코드와 본문을 넣는다. */
class AdapterRejectionsTest {

    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val json = ObjectMapper()

    private data class Row(val op: AdapterOp, val status: Int, val body: String, val kind: String, val action: String)

    private val table = listOf(
        Row(AdapterOp.DECLARE_ADAPTER, 400, """{"error":"vendor 와 name 은 비울 수 없다"}""", AdapterRejections.ADAPTER_BAD_REQUEST, "고쳐서 다시"),
        Row(AdapterOp.DECLARE_BUILD, 400, """{"error":"계약 semver가 형식이 아니다"}""", AdapterRejections.ADAPTER_BAD_REQUEST, "고쳐서 다시"),
        Row(AdapterOp.DECLARE_BUILD, 404, """{"error":"모르는 어댑터다: 9"}""", AdapterRejections.UNKNOWN_ADAPTER, "제품 목록 새로 읽기"),
        Row(
            AdapterOp.DECLARE_BUILD, 409, """{"error":"같은 버전이 다른 계약 semver 로 이미 있다","existing_contract_semver":"1.0.0"}""",
            AdapterRejections.VERSION_CONFLICT, "다른 버전 번호로",
        ),
        Row(AdapterOp.REGISTER_INSTANCE, 400, """{"error":"모르는 어댑터 빌드다: 9"}""", AdapterRejections.INSTANCE_BAD_REQUEST, "고쳐서 다시"),
    )

    @Test
    fun `대응표의 행마다 종류와 후속 행동이 맞고 엔지니어가 화면 안에서 푼다`() {
        table.forEach { row ->
            val finding = AdapterRejections.of(row.op, row.status, json.readTree(row.body), at)
            assertEquals(row.kind to row.action, finding.kind to finding.action, "$row")
            assertEquals(Owner.ENGINEER to true, finding.owner to finding.inScreen, "$row")
            assertEquals(at, finding.checkedAt)
            assertNull(finding.target)
        }
    }

    @Test
    fun `409 는 기존 계약값을 관측값에 싣는다`() {
        val conflict = AdapterRejections.of(
            AdapterOp.DECLARE_BUILD, 409, json.readTree("""{"error":"같은 버전","existing_contract_semver":"1.0.0"}"""), at,
        )
        assertEquals("HTTP 409, existing_contract_semver=1.0.0, 같은 버전", conflict.observed)
    }

    @Test
    fun `표에 없는 응답은 아는 종류로 접지 않고 화면 밖 조사로 둔다`() {
        val cases = listOf(AdapterOp.DECLARE_ADAPTER to 409, AdapterOp.DECLARE_ADAPTER to 404, AdapterOp.REGISTER_INSTANCE to 404)
        cases.forEach { (op, status) ->
            val finding = AdapterRejections.of(op, status, null, at)
            assertEquals(Rejections.UNCLASSIFIED to false, finding.kind to finding.inScreen, "$op $status")
        }
    }
}
