package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.PrepareSequencedRack
import java.util.UUID

/** 초안 저장 요청. [definition] 은 글자 그대로이고 읽을 수 없는 문서여도 된다. */
data class DraftRequest(val definition: String, val actor: String, val requestId: UUID)

/** 모의 실행 요청. [robotIds] 는 운영 서비스가 넘긴 시운전 완료 기체다(현장 스킬 계산용). */
data class MockRunRequest(val robotIds: List<String>, val requestId: UUID)

/** 활성화 요청. 사유는 비어 있지 않아야 한다(T5). */
data class ActivationRequest(val actor: String, val reason: String, val robotIds: List<String>, val requestId: UUID)

/**
 * 호스트 임무 REST 의 요청 본문(S3b JSON 계약 §4). [HostRequests] 와 같이 스프링에 맡기지 않고 직접 읽는다. 칸은 엄격하게
 * 본다. 형이 틀린 칸은 없는 것으로 보고 400 이다.
 */
object MissionRequests {

    /** 편집 대상 WorkMaster(T6). 작업 지시 폼과 호스트가 두 임무로 고정이고 요구 근거 E2 도 고정이다. */
    val EDITABLE: Set<String> = setOf(PrepareSequencedRack.WORK_MASTER)

    fun workMaster(value: String): String {
        if (value !in EDITABLE) {
            throw BadRequest(HostRequests.UNKNOWN_WORK_MASTER, "편집하지 않는 WorkMaster 다: $value (편집하는 것: ${EDITABLE.sorted()})")
        }
        return value
    }

    fun draftId(value: String): Long =
        value.toLongOrNull()?.takeIf { it > 0 } ?: throw BadRequest(HostRequests.BAD_REQUEST, "초안 id 가 양의 정수가 아니다: $value")

    fun requestId(value: String?): UUID {
        if (value == null) throw BadRequest(HostRequests.BAD_REQUEST, "requestId 가 비어 있지 않은 문자열이 아니다")
        return try {
            UUID.fromString(value).also { require(it.toString() == value.lowercase()) }
        } catch (_: IllegalArgumentException) {
            throw BadRequest(HostRequests.BAD_REQUEST, "requestId 가 UUID 가 아니다: $value")
        }
    }

    fun draft(body: JsonNode?): DraftRequest {
        val node = obj(body)
        val definition = node.get("definition")?.takeIf { it.isTextual }?.asText()
            ?: throw BadRequest(HostRequests.BAD_REQUEST, "definition 이 문자열이 아니다")
        return DraftRequest(definition, text(node, "actor"), requestId(node.get("requestId")?.takeIf { it.isTextual }?.asText()))
    }

    fun robotIds(body: JsonNode?): List<String> = strings(obj(body).get("robotIds"), "robotIds")

    fun mockRun(body: JsonNode?): MockRunRequest {
        val node = obj(body)
        return MockRunRequest(strings(node.get("robotIds"), "robotIds"), requestId(node.get("requestId")?.takeIf { it.isTextual }?.asText()))
    }

    fun activation(body: JsonNode?): ActivationRequest {
        val node = obj(body)
        return ActivationRequest(
            actor = text(node, "actor"),
            reason = text(node, "reason"),
            robotIds = strings(node.get("robotIds"), "robotIds"),
            requestId = requestId(node.get("requestId")?.takeIf { it.isTextual }?.asText()),
        )
    }

    private fun obj(body: JsonNode?): JsonNode {
        if (body == null || !body.isObject) throw BadRequest(HostRequests.BAD_REQUEST, "본문이 JSON 객체가 아니다")
        return body
    }

    /** 비어 있지 않은 문자열 칸. */
    private fun text(node: JsonNode, field: String): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: throw BadRequest(HostRequests.BAD_REQUEST, "$field 가 비어 있지 않은 문자열이 아니다")

    /** 비어 있지 않은 문자열의 배열. 필수다. 빈 배열은 된다. */
    private fun strings(node: JsonNode?, field: String): List<String> {
        if (node == null || !node.isArray || node.any { !it.isTextual || it.asText().isBlank() }) {
            throw BadRequest(HostRequests.BAD_REQUEST, "$field 가 비어 있지 않은 문자열의 배열이 아니다")
        }
        return node.map { it.asText() }
    }
}
