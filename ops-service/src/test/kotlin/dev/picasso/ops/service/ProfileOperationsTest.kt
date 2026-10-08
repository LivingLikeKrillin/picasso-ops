package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.ProfileOperations
import dev.picasso.ops.service.operations.ProfileRejections
import dev.picasso.ops.service.registry.BindingWrites
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.ProfileSource
import dev.picasso.ops.service.registry.ProfileWrites
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryCatalog
import dev.picasso.ops.service.registry.RegistryRevision
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.registry.RegistryTestRequest
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 개정판·바인딩 조작이 조작 기록에 남는 모양과 응답 없음 뒤 재조회의 «반영됨» 판정(P2·S1d 스펙 §8.2·§8.4). registry 는 대역이다. */
class ProfileOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val at = Instant.parse("2026-10-08T00:00:00Z")
    private val kim = Actor(Mode.ENGINEER, "kim")

    private var answer: RegistryWrite = RegistryWrite.NoResponse("응답 없음")
    private var revisions: RegistryCall<List<RegistryRevision>> = RegistryCall.Ok(emptyList())
    private var bindings: RegistryCall<List<RegistryBinding>> = RegistryCall.Ok(emptyList())
    private val sent = mutableListOf<String>()
    private var sentDocument: ByteArray? = null

    private val profileWrites = object : ProfileWrites {
        override fun submit(document: ByteArray, actor: String) = answer.also { sentDocument = document; sent += "submit $actor" }
        override fun requestTest(profileRevisionId: Long, actor: String) = answer.also { sent += "requestTest $profileRevisionId $actor" }
        override fun activate(profileRevisionId: Long, actor: String) = answer.also { sent += "activate $profileRevisionId $actor" }
    }
    private val bindingWrites = object : BindingWrites {
        override fun bind(robotId: String, adapterVersionId: Long, profileRevisionId: Long, actor: String) =
            answer.also { sent += "bind $robotId $adapterVersionId $profileRevisionId $actor" }
        override fun recordSiteNames(robotId: String, actor: String) = answer.also { sent += "names $robotId $actor" }
    }
    private val profiles = object : ProfileSource {
        override fun catalog() = RegistryCall.Ok(RegistryCatalog("0.9.0"))
        override fun revisions() = revisions
    }
    private val commissioning = object : CommissioningSource {
        override fun bindings(siteId: String) = bindings
        override fun software(siteId: String): RegistryCall<List<RegistrySoftware>> = RegistryCall.Ok(emptyList())
    }

    private val operations = ProfileOperations(
        profileWrites, bindingWrites, profiles, commissioning, log, "site-01", Clock.fixed(at, ZoneOffset.UTC), requeryDelay = Duration.ZERO,
    )

    private val document = """{"vendor":"picasso-ref","model":"humanoid-a","revision":2,"skills":[]}"""
    private val hash = MessageDigest.getInstance("SHA-256").digest(document.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun revision(id: Long, status: String = "VALIDATED", hash: String = this.hash, request: RegistryTestRequest? = null) =
        RegistryRevision(id, "picasso-ref", "humanoid-a", 2, status, documentHash = hash, latestTestRequest = request)

    private fun binding(build: Long, revision: Long, siteNames: String = "UNREGISTERED") = RegistryBinding(
        robotId = "r1", vendor = "picasso-ref", model = "humanoid-a", profileRevisionId = revision, revision = 2, adapterName = "acme/fleet",
        adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames, adapterVersionId = build,
        boundBy = "engineer/kim", boundAt = "t",
    )

    /** 가장 늦게 남은 행의 결과. 확인 행이 요청 행 뒤에 붙는다. */
    private fun latest() = log.list().first().result

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `제출은 본문 바이트를 그대로 넘기고 기종·번호를 대상으로 남긴다`() {
        answer = RegistryWrite.Answered(201, """{"profile_revision_id":5}""")
        val bytes = document.toByteArray()
        assertEquals(OperationResult.SUCCEEDED, operations.submit(kim, bytes).result)
        assertContentEquals(bytes, sentDocument)
        assertEquals(listOf("submit engineer/kim"), sent)
        val row = log.list().single()
        assertEquals("profile picasso-ref/humanoid-a#2", row.target)
        assertTrue(row.request.contains(hash), row.request)
    }

    @Test
    fun `대상 칸을 읽지 못하는 문서는 profile 물음표로 남기고 그대로 보낸다`() {
        answer = RegistryWrite.Answered(400, """{"error":"프로파일을 읽을 수 없다"}""")
        val outcome = operations.submit(kim, "{not json".toByteArray())
        assertEquals(ProfileRejections.PROFILE_UNREADABLE, outcome.rejection!!.kind)
        assertEquals("profile ?", log.list().single().target)
    }

    @Test
    fun `응답 없는 제출은 같은 기종·번호가 같은 문서 해시로 있어야 반영됨이다`() {
        revisions = RegistryCall.Ok(listOf(revision(5)))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.submit(kim, document.toByteArray()).confirmation)
        // 확인 행에는 재조회에서 본 개정판 id 와 상태가 남는다(스펙 §8.4 마지막 문단).
        val confirmed = log.list().first()
        assertTrue(confirmed.targetResponse!!.contains("\"profile_revision_id\": 5"), confirmed.targetResponse)
        assertTrue(confirmed.targetResponse!!.contains("\"status\": \"VALIDATED\""), confirmed.targetResponse)

        revisions = RegistryCall.Ok(listOf(revision(5, hash = "other")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.submit(kim, document.toByteArray()).confirmation)
    }

    @Test
    fun `응답 없는 시험 요청은 열린 요청이 있거나 보낸 뒤에 만든 끝난 요청이 있어야 반영됨이다`() {
        fun request(requestedAt: String, completed: String?) = RegistryTestRequest(9, "engineer/kim", requestedAt, completedAt = completed)

        revisions = RegistryCall.Ok(listOf(revision(5, request = request("2026-10-07T23:00:00Z", null))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.requestTest(kim, 5).confirmation)

        revisions = RegistryCall.Ok(listOf(revision(5, request = request("2026-10-08T00:00:00.5Z", "2026-10-08T00:00:01Z"))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.requestTest(kim, 5).confirmation)

        revisions = RegistryCall.Ok(listOf(revision(5, request = request("2026-10-07T23:00:00Z", "2026-10-07T23:01:00Z"))))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.requestTest(kim, 5).confirmation)
        assertEquals("revision 5", log.list().first().target)
    }

    @Test
    fun `응답 없는 활성화는 그 개정판이 ACTIVE 여야 반영됨이다`() {
        revisions = RegistryCall.Ok(listOf(revision(5, status = "ACTIVE")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.activate(kim, 5).confirmation)
        revisions = RegistryCall.Ok(listOf(revision(5, status = "TESTED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.activate(kim, 5).confirmation)
        assertEquals(listOf("activate 5 engineer/kim", "activate 5 engineer/kim"), sent)
    }

    @Test
    fun `응답 없는 바인딩은 그 기체의 활성 바인딩이 요청한 빌드와 개정판이어야 반영됨이다`() {
        bindings = RegistryCall.Ok(listOf(binding(7, 5)))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.bind(kim, "r1", 7, 5).confirmation)
        bindings = RegistryCall.Ok(listOf(binding(7, 4)))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.bind(kim, "r1", 7, 5).confirmation)
        bindings = RegistryCall.Ok(listOf(binding(8, 5)))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.bind(kim, "r1", 7, 5).confirmation)
        assertEquals("robot r1", log.list().first().target)
        assertEquals("bind r1 7 5 engineer/kim", sent.first())
    }

    @Test
    fun `응답 없는 명칭 기록은 그 기체의 명칭 상태가 UNREGISTERED 가 아니어야 반영됨이다`() {
        bindings = RegistryCall.Ok(listOf(binding(7, 5, "CLAIMED")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.recordSiteNames(kim, "r1").confirmation)
        bindings = RegistryCall.Ok(listOf(binding(7, 5, "UNREGISTERED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.recordSiteNames(kim, "r1").confirmation)
        bindings = RegistryCall.Ok(emptyList())
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.recordSiteNames(kim, "r1").confirmation)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, latest())
    }

    @Test
    fun `바인딩 거절은 그 기체로 바로 가는 대응표 판정이다`() {
        answer = RegistryWrite.Answered(409, """{"reason":"ROBOT_RETIRED","error":"퇴역한 기체다"}""")
        val outcome = operations.bind(kim, "r1", 7, 5)
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(ProfileRejections.ROBOT_RETIRED to "r1", outcome.rejection!!.kind to outcome.rejection!!.target)
    }
}
