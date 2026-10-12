package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.web.JobContractController
import dev.picasso.ops.service.web.PreRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JobContractControllerTest {
    private val controller = JobContractController()
    private val json = ObjectMapper()

    @Test fun `계약 표본은 실제 제출 변환과 일치하고 모든 지원 업무를 포함한다`() {
        val contracts = controller.contracts().contracts
        assertEquals(JobOrderForm.WORK_MASTERS, contracts.map { it.workMasterId }.toSet())
        contracts.forEach { contract ->
            val reply = controller.preview(json.writeValueAsBytes(contract.sample))
            assertEquals(200, reply.statusCode.value())
            val order = assertIs<JsonNode>(reply.body)
            assertEquals(JobOrderForm.jobOrder(JobOrderForm.read(contract.sample), "JO-PREVIEW", json), order)
            assertEquals(contract.requiredEvidence, order["requiredEvidence"].asText())
        }
    }
    @Test fun `중복 단위는 제출과 같은 규칙으로 거절한다`() {
        val reply = controller.preview("""{"workMasterId":"PrepareSequencedRack","slots":["S1","S1"],"material":"M","presentation":"P"}""".toByteArray())
        assertEquals(400, reply.statusCode.value())
        assertEquals(JobOrderForm.UNIT_ID_CONFLICT, assertIs<PreRejection>(reply.body).error)
    }
    @Test fun `잘못된 입력과 미지원 업무를 거절한다`() {
        listOf(null, "bad".toByteArray(), "{}".toByteArray(), """{"workMasterId":"Unknown"}""".toByteArray()).forEach {
            assertEquals(400, controller.preview(it).statusCode.value())
        }
    }
    @Test fun `대상 id 길이 제한을 실제 제출과 동일하게 적용한다`() {
        val input = json.readTree("""{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay"}]}""")
        (input["targets"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("id", "x".repeat(65))
        assertEquals(400, controller.preview(json.writeValueAsBytes(input)).statusCode.value())
    }
}
