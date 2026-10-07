package dev.picasso.ops.service.registry

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/** registry `GET /operations/skill-types` 의 스킬 종류 한 줄(P2·S1d 스펙 §8.1). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrySkillType(
    val name: String,
    val major: Int,
    @JsonAlias("introduced_in_semver") val introducedInSemver: String,
    @JsonAlias("site_reference_keys") val siteReferenceKeys: List<String> = emptyList(),
)

/** registry `GET /operations/skill-types` 의 답. 카탈로그는 registry 가 기동 때 계약에서 채운다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryCatalog(
    @JsonAlias("contract_semver") val contractSemver: String,
    @JsonAlias("skill_types") val skillTypes: List<RegistrySkillType> = emptyList(),
)

/** 스위트 하나의 최신 실행. [detail] 은 실행기가 낸 JSON 그대로다(`checks`·`failures`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrySuiteRun(
    val result: String,
    @JsonAlias("ran_at") val ranAt: String,
    @JsonAlias("ran_by") val ranBy: String,
    val detail: JsonNode? = null,
)

/** 개정판의 최신 시험 요청 1건. 끝났든 아니든 가장 늦게 들어온 요청이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryTestRequest(
    @JsonAlias("request_id") val requestId: Long,
    @JsonAlias("requested_by") val requestedBy: String,
    @JsonAlias("requested_at") val requestedAt: String,
    @JsonAlias("claimed_by") val claimedBy: String? = null,
    @JsonAlias("claimed_at") val claimedAt: String? = null,
    @JsonAlias("claim_expires_at") val claimExpiresAt: String? = null,
    @JsonAlias("completed_at") val completedAt: String? = null,
)

/** registry `GET /operations/profile-revisions` 의 한 줄. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryRevision(
    @JsonAlias("profile_revision_id") val profileRevisionId: Long,
    val vendor: String,
    val model: String,
    val revision: Int,
    val status: String,
    val reasons: List<String> = emptyList(),
    @JsonAlias("document_hash") val documentHash: String,
    @JsonAlias("created_by") val createdBy: String? = null,
    @JsonAlias("created_at") val createdAt: String? = null,
    @JsonAlias("activated_by") val activatedBy: String? = null,
    @JsonAlias("activated_at") val activatedAt: String? = null,
    val suites: Map<String, RegistrySuiteRun> = emptyMap(),
    @JsonAlias("latest_test_request") val latestTestRequest: RegistryTestRequest? = null,
)

/**
 * registry `GET /diag/bindings` 의 활성 바인딩 한 줄. 명칭 상태는 registry 의 5값(`NOT_REQUIRED`·`UNREGISTERED`·`CLAIMED`·
 * `CONFIRMED`·`CONTRADICTED`)이다. 사람의 기록(`siteNamesRegistered*`)과 기체의 답(`siteNamesReported*`)을 따로 싣는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryBinding(
    val robotId: String,
    val vendor: String,
    val model: String,
    val profileRevisionId: Long,
    val revision: Int,
    val adapterName: String,
    val adapterVersion: String,
    val conformanceStatus: String,
    val active: Boolean,
    val siteNames: String,
    val siteNameKeys: List<String> = emptyList(),
    val adapterVersionId: Long,
    val boundBy: String,
    val boundAt: String,
    val siteNamesRegisteredBy: String? = null,
    val siteNamesRegisteredAt: String? = null,
    val siteNamesReportedAt: String? = null,
    val siteNamesCount: Int? = null,
    val siteNamesUnsupported: Boolean? = null,
)

/** registry `GET /diag/bindings` 의 답. 운영 서비스는 줄만 쓴다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryBindings(val rows: List<RegistryBinding> = emptyList())

/** registry `GET /diag/software` 의 한 줄. `verdict` 는 `MATCH`·`MISMATCH`·`UNREPORTED` 다. 시운전을 막지 않고 보이기만 한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistrySoftware(
    val robotId: String,
    val declared: String? = null,
    val reported: String? = null,
    val verdict: String,
)

/** 카탈로그와 개정판 목록의 출처. 둘 다 운영자 토큰 관문 안의 읽기다. 시험이 registry 없이 대신 끼운다. */
interface ProfileSource {
    fun catalog(): RegistryCall<RegistryCatalog>

    fun revisions(): RegistryCall<List<RegistryRevision>>
}

/** 바인딩과 소프트웨어 대조의 출처. 둘 다 관문 밖의 진단이다. 시험이 registry 없이 대신 끼운다. */
interface CommissioningSource {
    /** 이 사이트의 활성 바인딩. 이력은 빼고 읽는다. */
    fun bindings(siteId: String): RegistryCall<List<RegistryBinding>>

    fun software(siteId: String): RegistryCall<List<RegistrySoftware>>
}

/** 개정판 조작 3가지(P2·S1d 스펙 §8.2). 시험이 registry 없이 대신 끼운다. */
interface ProfileWrites {
    /** [document] 는 화면이 보낸 본문 바이트 그대로다. 다시 직렬화하면 registry 의 문서 해시가 달라진다. */
    fun submit(document: ByteArray, actor: String): RegistryWrite

    fun requestTest(profileRevisionId: Long, actor: String): RegistryWrite

    fun activate(profileRevisionId: Long, actor: String): RegistryWrite
}

/** 바인딩과 명칭 기록(P2·S1d 스펙 §8.2). 시험이 registry 없이 대신 끼운다. */
interface BindingWrites {
    fun bind(robotId: String, adapterVersionId: Long, profileRevisionId: Long, actor: String): RegistryWrite

    fun recordSiteNames(robotId: String, actor: String): RegistryWrite
}
