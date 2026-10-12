package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.interfaces.InterfaceMapping
import dev.picasso.ops.service.interfaces.InterfaceRejected
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.JobOrderForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InterfaceMappingTest {
    private val json = ObjectMapper()
    @Test fun `중첩 JSON 경로를 업무 입력으로 바꾸고 외부 workMaster 주입은 무시한다`() {
        val mapping = json.readTree("""{"slots":"/order/slots","material":"/material","presentation":"/source"}""")
        val input = json.readTree("""{"order":{"slots":["S1","S2"]},"material":"M","source":"P","workMasterId":"Unknown"}""")
        val order = JobOrderForm.jobOrder(InterfaceMapping.transform("PrepareSequencedRack", mapping, input), "preview", json)
        assertEquals("PrepareSequencedRack", order["workMasterId"].asText())
        assertEquals(2, order["materialRequirements"][0]["quantity"].asInt())
    }
    @Test fun `대상 목록을 통째로 연결하고 포인터 이스케이프를 지원한다`() {
        val mapping = json.readTree("""{"targets":"/a~1b"}""")
        val input = json.readTree("""{"a/b":[{"id":"T1","location":"bay-7"}]}""")
        val order = JobOrderForm.jobOrder(InterfaceMapping.transform("InspectAsset", mapping, input), "preview", json)
        assertEquals("T1", order["equipmentRequirements"][0]["id"].asText())
    }
    @Test fun `누락 경로와 잘못된 자료형과 중복 슬롯을 거절한다`() {
        val mapping = json.readTree("""{"slots":"/slots","material":"/material","presentation":"/source"}""")
        assertFailsWith<InterfaceRejected> { InterfaceMapping.transform("PrepareSequencedRack", mapping, json.readTree("{}")) }
        listOf("[]", "42", """["S1","S1"]""").forEach { slots ->
            assertFailsWith<FormRejection> { InterfaceMapping.transform("PrepareSequencedRack", mapping, json.readTree("""{"slots":$slots,"material":"M","source":"P"}""")) }
        }
    }
    @Test fun `임의 내부 필드와 스크립트와 잘못된 경로는 매핑할 수 없다`() {
        listOf("""{"targets":"/assets","requiredEvidence":"/evidence"}""", """{"targets":"javascript:evil"}""", """{"targets":"/bad~escape"}""").forEach {
            assertFailsWith<InterfaceRejected> { InterfaceMapping.transform("InspectAsset", json.readTree(it), json.readTree("{}")) }
        }
    }
    @Test fun `식별자와 버전의 계약을 검증한다`() {
        val valid = """{"interfaceId":"inspect-asset","version":1,"name":"점검","workMasterId":"InspectAsset","mapping":{"targets":"/assets"},"sample":{"assets":[{"id":"T1","location":"bay"}]}}"""
        assertEquals(1, InterfaceMapping.spec(json.readTree(valid)).version)
        val badId = json.readTree(valid).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("interfaceId", "../asset")
        val fractional = json.readTree(valid).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("version", 1.2)
        val zero = json.readTree(valid).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("version", 0)
        listOf(badId, fractional, zero).forEach {
            assertFailsWith<InterfaceRejected> { InterfaceMapping.spec(it) }
        }
    }
}
