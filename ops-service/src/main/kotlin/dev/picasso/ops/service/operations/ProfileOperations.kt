package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.registry.BindingWrites
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.ProfileWrites
import dev.picasso.ops.service.registry.map
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 개정판 제출·시험 요청·활성화와 바인딩·명칭 기록(P2·S1d 스펙 §8.2). 기록과 응답 없음 뒤 재조회는 [OperationRunner] 가 한다.
 *
 * «반영됨» 의 판정(스펙 §8.4)은 registry 가 같은 요청을 어떻게 다루는지에 맞춘다. 다섯 다 멱등이다(스펙 §10).
 * - 제출: 목록에 그 기종·번호가 같은 문서 해시로 있다. 해시는 registry 와 같은 값(본문 바이트의 SHA-256)이다.
 * - 시험 요청: 그 개정판에 끝나지 않은 요청이 있거나, 최신 요청이 이 조작을 보낸 시각 뒤에 만들어졌다. 실행기가 가상
 *   시계로 돌아 1초 뒤 재조회 때 이미 끝났을 수 있고, 멱등 200 은 조작 전에 만든 열린 요청을 돌려주기 때문이다.
 * - 활성화: 그 개정판이 `ACTIVE` 다.
 * - 바인딩: 그 기체의 활성 바인딩이 요청한 빌드 id 와 개정판 id 다.
 * - 명칭 기록: 그 기체의 명칭 상태가 `UNREGISTERED` 가 아니다.
 */
class ProfileOperations(
    private val profileWrites: ProfileWrites,
    private val bindingWrites: BindingWrites,
    private val profiles: ProfileSource,
    private val commissioning: CommissioningSource,
    log: OperationLog,
    private val siteId: String,
    private val clock: Clock,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {
    private val runner = OperationRunner(log, clock, requeryDelay, json)

    /**
     * [document] 를 바이트 그대로 registry 에 넘긴다. 조작 기록의 대상 칸을 채우려고 `vendor`·`model`·`revision` 만 읽고,
     * 읽지 못하면 `profile ?` 로 남긴다. 문서의 옳고 그름은 registry 가 판정한다.
     */
    fun submit(actor: Actor, document: ByteArray): OperationOutcome {
        val hash = sha256(document)
        val coordinate = runCatching { json.readTree(document) }.getOrNull()?.let { root ->
            val vendor = root.get("vendor")?.takeIf { it.isTextual }?.asText()
            val model = root.get("model")?.takeIf { it.isTextual }?.asText()
            val revision = root.get("revision")?.takeIf { it.isInt }?.asInt()
            if (vendor != null && model != null && revision != null) Triple(vendor, model, revision) else null
        }
        val request = json.createObjectNode()
            .put("op", ProfileOp.SUBMIT.name)
            .put("document_sha256", hash)
            .put("document_bytes", document.size)
        val target = coordinate?.let { (v, m, r) -> "profile $v/$m#$r" } ?: "profile ?"
        return runner.run(
            actor, target, request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.SUBMIT, status, body, at, null) },
            recheck = {
                profiles.revisions().map { revisions ->
                    val seen = coordinate?.let { (v, m, r) -> revisions.firstOrNull { it.vendor == v && it.model == m && it.revision == r } }
                    Recheck(
                        seen != null && seen.documentHash == hash,
                        seen?.let { json.createObjectNode().put("profile_revision_id", it.profileRevisionId).put("status", it.status) },
                    )
                }
            },
        ) { profileWrites.submit(document, actor.header()) }
    }

    fun requestTest(actor: Actor, profileRevisionId: Long): OperationOutcome {
        val sentAt = clock.instant()
        val request = json.createObjectNode()
            .put("op", ProfileOp.REQUEST_TEST.name)
            .put("profile_revision_id", profileRevisionId)
        return runner.run(
            actor, "revision $profileRevisionId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.REQUEST_TEST, status, body, at, null) },
            recheck = {
                profiles.revisions().map { revisions ->
                    val latest = revisions.firstOrNull { it.profileRevisionId == profileRevisionId }?.latestTestRequest
                    val applied = latest != null &&
                        (latest.completedAt == null || Instant.parse(latest.requestedAt).isAfter(sentAt))
                    Recheck(applied, latest?.let { json.createObjectNode().put("requested_at", it.requestedAt) })
                }
            },
        ) { profileWrites.requestTest(profileRevisionId, actor.header()) }
    }

    fun activate(actor: Actor, profileRevisionId: Long): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", ProfileOp.ACTIVATE.name)
            .put("profile_revision_id", profileRevisionId)
        return runner.run(
            actor, "revision $profileRevisionId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.ACTIVATE, status, body, at, null) },
            recheck = {
                profiles.revisions().map { revisions ->
                    val seen = revisions.firstOrNull { it.profileRevisionId == profileRevisionId }
                    Recheck(seen?.status == "ACTIVE", seen?.let { json.createObjectNode().put("status", it.status) })
                }
            },
        ) { profileWrites.activate(profileRevisionId, actor.header()) }
    }

    fun bind(actor: Actor, robotId: String, adapterVersionId: Long, profileRevisionId: Long): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", ProfileOp.BIND.name)
            .put("robot_id", robotId)
            .put("adapter_version_id", adapterVersionId)
            .put("profile_revision_id", profileRevisionId)
        return runner.run(
            actor, "robot $robotId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.BIND, status, body, at, robotId) },
            recheck = {
                commissioning.bindings(siteId).map { bindings ->
                    val seen = bindings.firstOrNull { it.robotId == robotId && it.active }
                    Recheck(
                        seen != null && seen.adapterVersionId == adapterVersionId && seen.profileRevisionId == profileRevisionId,
                        seen?.let {
                            json.createObjectNode()
                                .put("adapter_version_id", it.adapterVersionId)
                                .put("profile_revision_id", it.profileRevisionId)
                        },
                    )
                }
            },
        ) { bindingWrites.bind(robotId, adapterVersionId, profileRevisionId, actor.header()) }
    }

    fun recordSiteNames(actor: Actor, robotId: String): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", ProfileOp.RECORD_SITE_NAMES.name)
            .put("robot_id", robotId)
        return runner.run(
            actor, "robot $robotId", request, reason = null,
            rejection = { status, body, at -> ProfileRejections.of(ProfileOp.RECORD_SITE_NAMES, status, body, at, robotId) },
            recheck = {
                commissioning.bindings(siteId).map { bindings ->
                    val seen = bindings.firstOrNull { it.robotId == robotId && it.active }
                    Recheck(
                        seen != null && seen.siteNames != "UNREGISTERED",
                        seen?.let { json.createObjectNode().put("site_names", it.siteNames) },
                    )
                }
            },
        ) { bindingWrites.recordSiteNames(robotId, actor.header()) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
