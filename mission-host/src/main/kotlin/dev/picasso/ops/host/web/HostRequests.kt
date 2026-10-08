package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.MaterialRequirement
import dev.picasso.ops.host.MissionHost

/** 거부한 요청의 답. 운영 서비스의 사전 거부(`PreRejection`)와 같은 모양이다. */
data class HostRejection(val error: String, val detail: String)

/** 본문을 못 받는 까닭. [error] 가 응답의 `error` 칸이다. */
class BadRequest(val error: String, detail: String) : RuntimeException(detail)

/**
 * 호스트 REST 의 요청 본문(S3a 스펙 §7.5·§7.6). 작업 지시 본문의 칸은 picasso `JobOrder` 와 같다.
 *
 * 스프링에 맡기지 않고 직접 읽는다. 맡기면 못 읽는 본문의 400 이 [HostRejection] 모양이 아니다. 칸은 엄격하게 본다.
 * 문자열 칸에 숫자가 오거나 정수 칸에 소수가 오면 없는 것으로 보고 400 이다.
 */
object HostRequests {

    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNKNOWN_WORK_MASTER = "UNKNOWN_WORK_MASTER"

    /** `{jobOrder, <listField>}` 를 읽는다. [listField] 는 판정이면 `robotIds`, 제출이면 `candidates` 다. */
    fun read(body: JsonNode?, listField: String): Pair<JobOrder, List<String>> {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val order = jobOrder(body.get("jobOrder"))
        val robots = strings(body.get(listField), listField)
        return order to robots
    }

    fun jobOrder(node: JsonNode?): JobOrder {
        if (node == null || !node.isObject) throw BadRequest(BAD_REQUEST, "jobOrder 가 객체가 아니다")
        val workMasterId = text(node, "workMasterId")
        if (workMasterId !in MissionHost.WORK_MASTERS) {
            throw BadRequest(UNKNOWN_WORK_MASTER, "받지 않는 WorkMaster 다: $workMasterId (받는 것: ${MissionHost.WORK_MASTERS.sorted()})")
        }
        val version = node.get("version")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
            ?: throw BadRequest(BAD_REQUEST, "version 이 정수가 아니다")
        if (version < 1) throw BadRequest(BAD_REQUEST, "version 은 1 이상이다: $version")
        val evidence = text(node, "requiredEvidence").let { value ->
            Evidence.entries.firstOrNull { it.name == value }
                ?: throw BadRequest(BAD_REQUEST, "requiredEvidence 가 E0~E3 이 아니다: $value")
        }
        return JobOrder(
            jobOrderId = text(node, "jobOrderId"),
            workMasterId = workMasterId,
            version = version,
            requiredEvidence = evidence,
            parameters = stringMap(node.get("parameters"), "parameters"),
            materialRequirements = array(node.get("materialRequirements"), "materialRequirements").map {
                val quantity = it.get("quantity")?.takeIf { q -> q.isIntegralNumber && q.canConvertToInt() }?.asInt()
                    ?: throw BadRequest(BAD_REQUEST, "materialRequirements 의 quantity 가 정수가 아니다")
                if (quantity < 0) throw BadRequest(BAD_REQUEST, "materialRequirements 의 quantity 가 음수다: $quantity")
                MaterialRequirement(text(it, "materialDefinitionId"), quantity)
            },
            equipmentRequirements = array(node.get("equipmentRequirements"), "equipmentRequirements").map {
                EquipmentRequirement(text(it, "id"), text(it, "equipmentUse"), stringMap(it.get("properties"), "properties"))
            },
        )
    }

    /** 비어 있지 않은 문자열 칸. */
    private fun text(node: JsonNode, field: String): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: throw BadRequest(BAD_REQUEST, "$field 가 비어 있지 않은 문자열이 아니다")

    /** 없으면 빈 목록이다. 있으면 객체의 배열이어야 한다. */
    private fun array(node: JsonNode?, field: String): List<JsonNode> {
        if (node == null || node.isNull) return emptyList()
        if (!node.isArray || node.any { !it.isObject }) throw BadRequest(BAD_REQUEST, "$field 가 객체의 배열이 아니다")
        return node.toList()
    }

    /** 없으면 빈 맵이다. 있으면 값이 전부 문자열인 객체여야 한다. */
    private fun stringMap(node: JsonNode?, field: String): Map<String, String> {
        if (node == null || node.isNull) return emptyMap()
        if (!node.isObject) throw BadRequest(BAD_REQUEST, "$field 가 객체가 아니다")
        return node.properties().associate { (key, value) ->
            if (!value.isTextual) throw BadRequest(BAD_REQUEST, "$field.$key 가 문자열이 아니다")
            key to value.asText()
        }
    }

    /** 비어 있지 않은 문자열의 배열. 필수다. */
    private fun strings(node: JsonNode?, field: String): List<String> {
        if (node == null || !node.isArray || node.any { !it.isTextual || it.asText().isBlank() }) {
            throw BadRequest(BAD_REQUEST, "$field 가 비어 있지 않은 문자열의 배열이 아니다")
        }
        return node.map { it.asText() }
    }
}
