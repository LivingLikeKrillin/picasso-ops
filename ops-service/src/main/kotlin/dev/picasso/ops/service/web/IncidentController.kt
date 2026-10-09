package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.host.HostIncidents
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 장애 주입, 인시던트 읽기, 운영자 판단 API(S4a 스펙 §7, T7).
 *
 * 두 읽기는 모드와 관계없고 호스트 본문을 그대로 넘긴다. 호스트가 안 닿으면 503 `HOST_SILENT` 다. 단건의 404
 * `INCIDENT_NOT_FOUND` 는 호스트 본문 그대로 404 로 넘긴다.
 *
 * 장애 주입은 엔지니어 모드, 판단은 운영자 모드만 한다. 판정 순서는 관문(헤더 400 `ACTOR_REQUIRED`, 모드 403) → 본문(400
 * `FAULT_BAD_REQUEST`·`RESOLVE_BAD_REQUEST`) → 사유(400 `REASON_REQUIRED`)다. 모두 호스트에 닿지 않은 사전 거부라 조작
 * 기록에 남지 않는다. 본문은 바이트로 받아 관문을 지난 뒤 직접 읽는다([SiteSettingsController] 와 같은 이유). 쓰기 본문은
 * `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]).
 *
 * 두 조작은 호스트가 무엇을 답했든 200 과 조작 결과다. 현장·호스트의 거부와 판단 결과 이름은 본문에 있다.
 */
@RestController
class IncidentController(
    private val faults: FaultOperations,
    private val resolutions: HoldResolutions,
    private val host: HostIncidents,
) {
    private val json = ObjectMapper()

    @GetMapping("/api/incidents")
    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val parsed = limit?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                ?: return reject(HttpStatus.BAD_REQUEST, INCIDENT_BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $raw")
        }
        return when (val call = host.incidents(parsed)) {
            is HostCall.Ok -> ResponseEntity.ok(call.value)
            is HostCall.Silent -> hostSilent(call.cause)
        }
    }

    @GetMapping("/api/incidents/{incidentId}")
    fun incident(@PathVariable incidentId: String): ResponseEntity<Any> = when (val found = host.incident(incidentId)) {
        is HostIncident.Found -> ResponseEntity.ok(found.body)
        is HostIncident.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(found.body)
        is HostIncident.Silent -> hostSilent(found.cause)
    }

    @PostMapping("/api/faults", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun inject(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val node = read(body)
        val robotId = text(node, "robotId")
        val kind = text(node, "kind")
        val state = node?.get("state")
        if (robotId == null || kind == null || (state != null && !state.isNull && !state.isTextual)) {
            return@guarded reject(
                HttpStatus.BAD_REQUEST, FAULT_BAD_REQUEST,
                "robotId·kind 는 비어 있지 않은 문자열이고 state 는 없거나 null 이거나 문자열이다",
            )
        }
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "장애 주입 사유가 없다")
        ResponseEntity.ok(faults.inject(actor, robotId, kind, state?.takeIf { it.isTextual }?.asText(), reason))
    }

    @PostMapping("/api/executions/{executionId}/units/{unitId}/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolve(
        @PathVariable executionId: String,
        @PathVariable unitId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val node = read(body)
        val decision = text(node, "decision")?.takeIf { it in HoldResolutions.DECISIONS }
            ?: return@guarded reject(
                HttpStatus.BAD_REQUEST, RESOLVE_BAD_REQUEST,
                "decision 은 ${HoldResolutions.DECISIONS.joinToString(" 또는 ")} 이다",
            )
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "판단 사유가 없다")
        ResponseEntity.ok(resolutions.resolve(actor, executionId, unitId, decision, reason))
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    private fun text(node: JsonNode?, field: String): String? =
        node?.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun reason(node: JsonNode?): String? = node?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun hostSilent(cause: String): ResponseEntity<Any> =
        reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: $cause")

    companion object {
        const val FAULT_BAD_REQUEST = "FAULT_BAD_REQUEST"
        const val RESOLVE_BAD_REQUEST = "RESOLVE_BAD_REQUEST"
        const val INCIDENT_BAD_REQUEST = "INCIDENT_BAD_REQUEST"

        /** 현장 설정 변경·임무 활성화와 같은 이름이다. */
        const val REASON_REQUIRED = MissionVersionController.REASON_REQUIRED

        /** 호스트 인시던트 목록이 받는 최대(S4a JSON 계약 §3). */
        const val MAX_LIMIT = 500
    }
}
