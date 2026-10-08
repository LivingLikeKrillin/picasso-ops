package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.mission.DraftNotFound
import dev.picasso.ops.host.mission.MissionTemplates
import dev.picasso.ops.host.mission.MissionVersions
import dev.picasso.ops.host.mission.RequestIdReused
import dev.picasso.ops.host.mission.RequestInProgress
import dev.picasso.ops.host.mission.TemplatesView
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 실행 호스트의 임무 버전 REST 와 신호 조작 전달(S3b 스펙 §6.6). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다. 모드
 * 검사(엔지니어 모드만)와 조작 기록은 운영 서비스가 한다.
 *
 * POST 는 `application/json` 만 받는다(S3a 와 같은 까닭). 결과(통과·거부·모름·활성화)는 늘 200 의 본문에 있다. 상태 코드는
 * 요청이 틀렸거나(400) 대상이 없거나(404) 요청 id 가 이미 쓰였거나 그 요청을 아직 처리 중이거나(409) 현장이 안 닿을 때(503)만
 * 가른다.
 */
@RestController
class MissionController(
    private val versions: MissionVersions,
    private val cellBand: CellBandClient,
    private val json: ObjectMapper,
) {

    @GetMapping("/host/missions/{workMasterId}")
    fun overview(@PathVariable workMasterId: String): ResponseEntity<Any> = answering {
        versions.overview(MissionRequests.workMaster(workMasterId))
    }

    @GetMapping("/host/missions/templates/{workMasterId}")
    fun templates(@PathVariable workMasterId: String): ResponseEntity<Any> = answering {
        val workMaster = MissionRequests.workMaster(workMasterId)
        TemplatesView(workMaster, MissionTemplates.of(workMaster))
    }

    @PostMapping("/host/missions/{workMasterId}/drafts", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun saveDraft(@PathVariable workMasterId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val workMaster = MissionRequests.workMaster(workMasterId)
        val request = MissionRequests.draft(read(body))
        versions.saveDraft(workMaster, request.definition, request.actor, request.requestId)
    }

    @PostMapping("/host/missions/drafts/{draftId}/validate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun validate(@PathVariable draftId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val id = MissionRequests.draftId(draftId)
        versions.validate(id, MissionRequests.robotIds(read(body)))
    }

    @PostMapping("/host/missions/drafts/{draftId}/mock-run", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mockRun(@PathVariable draftId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val id = MissionRequests.draftId(draftId)
        val request = MissionRequests.mockRun(read(body))
        versions.mockRun(id, request.robotIds, request.requestId)
    }

    @PostMapping("/host/missions/drafts/{draftId}/activate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun activate(@PathVariable draftId: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = answering {
        val id = MissionRequests.draftId(draftId)
        val request = MissionRequests.activation(read(body))
        versions.activate(id, request.actor, request.reason, request.robotIds, request.requestId)
    }

    /**
     * 운영 서비스의 재조회(T9). 그 요청 id 로 남은 행이 없으면 404 다. 그 요청을 아직 처리 중이면(잠금을 기다리는 활성화, 도는
     * 모의 실행) 409 `REQUEST_IN_PROGRESS` 다. 행이 없다는 응답이 아니어서 운영 서비스는 확인하지 못한 것으로 둔다.
     */
    @GetMapping("/host/missions/requests/{requestId}")
    fun byRequest(@PathVariable requestId: String): ResponseEntity<Any> {
        val id = try {
            MissionRequests.requestId(requestId)
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        val found = try {
            versions.byRequest(id)
        } catch (e: RequestInProgress) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(REQUEST_IN_PROGRESS, e.message ?: ""))
        } ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(HostRejection(REQUEST_NOT_FOUND, "그 요청 id 로 남은 행이 없다: $id"))
        return ResponseEntity.ok(found)
    }

    /**
     * 신호 조작을 현장 셀 대역에 넘긴다(결정 3). 현장의 응답(200·400·403·404·415)은 상태 코드와 본문 그대로 돌려준다. 안전
     * 신호 쓰기 거부도 현장이 하고 호스트는 넘기기만 한다(ADR 32). 현장이 안 닿으면 503 `CELL_SILENT` 다. 본문은 현장이
     * 본다. 바뀐 값은 다음 pump 부터 `GET /host/cell` 과 설비 대기에 보인다.
     */
    @PostMapping("/host/cell/signals/{name}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun writeSignal(@PathVariable name: String, @RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val relay = cellBand.writeSignal(name, body ?: ByteArray(0))
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(HostRejection(CELL_SILENT, "현장 셀 대역이 답하지 않는다"))
        val builder = ResponseEntity.status(relay.status)
        relay.contentType?.let { builder.contentType(MediaType.parseMediaType(it)) }
        return builder.body(relay.body)
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }

    private inline fun answering(action: () -> Any): ResponseEntity<Any> = try {
        ResponseEntity.ok(action())
    } catch (e: BadRequest) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
    } catch (e: DraftNotFound) {
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(HostRejection(DRAFT_NOT_FOUND, e.message ?: ""))
    } catch (e: RequestIdReused) {
        ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(REQUEST_ID_REUSED, e.message ?: ""))
    }

    companion object {
        /** 오류 이름(S3b JSON 계약 §3). */
        const val DRAFT_NOT_FOUND = "DRAFT_NOT_FOUND"
        const val REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND"
        const val REQUEST_ID_REUSED = "REQUEST_ID_REUSED"
        const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
        const val CELL_SILENT = "CELL_SILENT"
    }
}
