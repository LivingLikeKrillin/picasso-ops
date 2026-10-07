package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.AdapterOperations
import dev.picasso.ops.service.operations.AdapterRejections
import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.AdapterWrites
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryBuild
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryInstance
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 어댑터 조작이 조작 기록에 남는 모양과 응답 없음 뒤 재조회의 «반영됨» 판정(스펙 §7.1·§9). registry 는 대역이다. */
class AdapterOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val lee = Actor(Mode.ENGINEER, "lee")

    private var answer: RegistryWrite = RegistryWrite.Answered(201, """{"adapter_id":1}""")
    private var adapters: RegistryCall<List<RegistryAdapter>> = RegistryCall.Ok(emptyList())
    private var instances: RegistryCall<List<RegistryInstance>> = RegistryCall.Ok(emptyList())
    private val sent = mutableListOf<String>()

    private val writes = object : AdapterWrites {
        override fun declareAdapter(vendor: String, name: String, actor: String) =
            answer.also { sent += "declareAdapter $vendor $name $actor" }

        override fun declareBuild(adapterId: Long, version: String, contractSemver: String, actor: String) =
            answer.also { sent += "declareBuild $adapterId $version $contractSemver $actor" }

        override fun registerInstance(siteId: String, instanceId: String, adapterVersionId: Long, fleetEndpoint: String?, actor: String) =
            answer.also { sent += "registerInstance $siteId $instanceId $adapterVersionId $fleetEndpoint $actor" }
    }

    private val reads = object : AdapterSource {
        override fun adapters() = adapters
        override fun instances(siteId: String) = instances
    }

    private val operations =
        AdapterOperations(writes, reads, log, "site-01", Clock.fixed(at, ZoneOffset.UTC), requeryDelay = Duration.ZERO)

    private fun fleet(vararg builds: RegistryBuild) = RegistryAdapter(1, "acme", "fleet", builds.toList())

    private fun instance(version: String, fleetEndpoint: String? = null, adapter: String = "acme/fleet") = RegistryInstance(
        instanceId = "i1", siteId = "site-01", fleetEndpoint = fleetEndpoint, adapter = adapter, version = version,
        contractSemver = "0.9.0", conformance = "UNTESTED",
    )

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `제품 선언은 행위자와 함께 보내고 제품 이름을 대상으로 한 행 남긴다`() {
        val outcome = operations.declareAdapter(lee, "acme", "fleet")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(listOf("declareAdapter acme fleet engineer/lee"), sent)
        val row = log.list().single()
        assertEquals("adapter acme/fleet", row.target)
        assertTrue(row.request.contains("\"op\": \"DECLARE_ADAPTER\""), row.request)
    }

    @Test
    fun `빌드 선언의 409 는 대응표로 옮기고 거절 행으로 남긴다`() {
        answer = RegistryWrite.Answered(409, """{"error":"같은 버전","existing_contract_semver":"1.0.0"}""")
        val outcome = operations.declareBuild(lee, 1, "1.0.0", "0.9.0")
        assertEquals(listOf("declareBuild 1 1.0.0 0.9.0 engineer/lee"), sent)
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(AdapterRejections.VERSION_CONFLICT, outcome.rejection!!.kind)
        val row = log.list().single()
        assertEquals("build 1@1.0.0" to OperationResult.REJECTED, row.target to row.result)
    }

    @Test
    fun `인스턴스 등록은 운영 서비스의 사이트로 보낸다`() {
        operations.registerInstance(lee, "i1", 10, null)
        assertEquals(listOf("registerInstance site-01 i1 10 null engineer/lee"), sent)
        assertEquals("instance i1", log.list().single().target)
    }

    @Test
    fun `응답 없는 제품 선언은 그 제품이 목록에 있으면 반영됨이다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(listOf(fleet()))
        val outcome = operations.declareAdapter(lee, "acme", "fleet")
        assertEquals(OperationResult.NO_RESPONSE to OperationResult.CONFIRMED_APPLIED, outcome.result to outcome.confirmation)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertTrue(rows.first().registryResponse!!.contains("\"adapter_id\": 1"), rows.first().registryResponse)
    }

    @Test
    fun `응답 없는 빌드 선언은 같은 버전이 같은 계약값으로 있어야 반영됨이다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(listOf(fleet(RegistryBuild(10, "1.0.0", "1.0.0", "UNTESTED"))))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declareBuild(lee, 1, "1.0.0", "0.9.0").confirmation)
        adapters = RegistryCall.Ok(listOf(fleet(RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED"))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.declareBuild(lee, 1, "1.0.0", "0.9.0").confirmation)
    }

    @Test
    fun `응답 없는 빌드 선언은 다른 제품에 같은 버전이 있어도 그 제품의 빌드만 본다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(
            listOf(
                fleet(RegistryBuild(10, "2.0.0", "0.9.0", "UNTESTED")),
                RegistryAdapter(2, "other", "fleet", listOf(RegistryBuild(20, "1.0.0", "0.9.0", "UNTESTED"))),
            ),
        )
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declareBuild(lee, 1, "1.0.0", "0.9.0").confirmation)
    }

    @Test
    fun `응답 없는 인스턴스 등록은 그 인스턴스가 요청한 빌드와 플릿 주소로 있어야 반영됨이다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(
            listOf(fleet(RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED"), RegistryBuild(11, "1.1.0", "0.9.0", "UNTESTED"))),
        )
        // 같은 id 로 다시 등록하면 registry 가 덮어쓴다. 옛 빌드나 옛 주소가 그대로면 이 등록은 반영되지 않았다.
        instances = RegistryCall.Ok(listOf(instance("1.0.0")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.registerInstance(lee, "i1", 11, null).confirmation)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.registerInstance(lee, "i1", 10, "tcp://fleet:1").confirmation)
        instances = RegistryCall.Ok(listOf(instance("1.1.0", "tcp://fleet:1")))
        val outcome = operations.registerInstance(lee, "i1", 11, "tcp://fleet:1")
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        val confirmation = log.list().first()
        assertTrue(confirmation.registryResponse!!.contains("\"version\": \"1.1.0\""), confirmation.registryResponse)
    }

    @Test
    fun `응답 없는 인스턴스 등록은 다른 제품의 같은 버전에 붙어 있으면 반영되지 않았다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        adapters = RegistryCall.Ok(
            listOf(
                fleet(RegistryBuild(10, "1.0.0", "0.9.0", "UNTESTED")),
                RegistryAdapter(2, "other", "fleet", listOf(RegistryBuild(20, "1.0.0", "0.9.0", "UNTESTED"))),
            ),
        )
        // 버전 문자열이 같아도 제품 이름이 다르면 요청한 빌드(10)가 아니다.
        instances = RegistryCall.Ok(listOf(instance("1.0.0", adapter = "other/fleet")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.registerInstance(lee, "i1", 10, null).confirmation)
    }

    @Test
    fun `인스턴스 재조회에서 제품 목록을 못 읽으면 확인 행을 붙이지 않는다`() {
        answer = RegistryWrite.Answered(503, "")
        adapters = RegistryCall.Silent("응답 없음")
        instances = RegistryCall.Ok(listOf(instance("1.0.0")))
        val outcome = operations.registerInstance(lee, "i1", 10, null)
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }
}
