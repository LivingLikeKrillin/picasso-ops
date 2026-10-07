package dev.picasso.ops.service.registry

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * registry 호출 한 번의 결과. «없음» 과 «모름» 을 접지 않는다(스펙 §9).
 *
 * 읽기에서는 401 이 아닌 실패를 모두 [Silent] 로 둔다. 연결 실패·시간 초과·5xx·해석 불가 어느 것이든
 * 값을 모른다는 점이 같다. 조작은 [RegistryWrite] 로 따로 돌려준다.
 *
 * [Unauthorized] 는 운영자 토큰 관문(`/operations` 이하) 안의 호출에서만 나온다. 목록 읽기 `/diag/robots` 는
 * 관문 밖이라 토큰을 [TokenProbe] 가 관문 안의 읽기로 따로 확인한다.
 */
sealed interface RegistryCall<out T> {
    data class Ok<T>(val value: T) : RegistryCall<T>

    data class Silent(val cause: String) : RegistryCall<Nothing>

    /** 운영 서비스의 운영자 토큰이 registry 와 맞지 않는다(스펙 §7.4). */
    data object Unauthorized : RegistryCall<Nothing>
}

/** 읽은 값만 바꾼다. 읽지 못한 결과는 그대로 둔다. */
fun <T, R> RegistryCall<T>.map(transform: (T) -> R): RegistryCall<R> = when (this) {
    is RegistryCall.Ok -> RegistryCall.Ok(transform(value))
    is RegistryCall.Silent -> this
    RegistryCall.Unauthorized -> RegistryCall.Unauthorized
}

/** registry `GET /diag/robots` 의 한 줄. 운영 서비스가 쓰는 칸만 읽고 나머지는 버린다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryRobot(
    val robotId: String,
    val siteId: String,
    val serialNumber: String? = null,
    val displayName: String? = null,
    val origin: String? = null,
    val registeredAt: String? = null,
    val registeredBy: String? = null,
    val lastReportedAt: String? = null,
    val status: String,
    val retiredAt: String? = null,
    val retiredBy: String? = null,
    val retiredReason: String? = null,
    val reportingAfterRetirement: Boolean = false,
)

/** 기체 목록의 출처. 시험이 registry 없이 대신 끼운다. */
fun interface RobotSource {
    fun robots(siteId: String): RegistryCall<List<RegistryRobot>>
}

/**
 * registry `GET /operations/adapters` 의 빌드 한 줄. registry 는 snake_case 로 주고, 운영 서비스는 camelCase 로
 * 화면에 넘긴다. [JsonAlias] 는 읽을 때만 쓰인다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryBuild(
    @JsonAlias("adapter_version_id") val adapterVersionId: Long,
    val version: String,
    @JsonAlias("contract_semver") val contractSemver: String,
    val conformance: String,
    @JsonAlias("registered_at") val registeredAt: String? = null,
    @JsonAlias("registered_by") val registeredBy: String? = null,
)

/** registry `GET /operations/adapters` 의 제품 한 줄. 빌드 목록이 안에 든다(스펙 §5). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryAdapter(
    @JsonAlias("adapter_id") val adapterId: Long,
    val vendor: String,
    val name: String,
    val versions: List<RegistryBuild> = emptyList(),
)

/** registry `GET /diag/adapter-instances` 의 한 줄. 빌드 id 는 없고 제품 이름(`vendor/name`)과 버전이 있다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryInstance(
    val instanceId: String,
    val siteId: String,
    val fleetEndpoint: String? = null,
    val registeredAt: String? = null,
    val registeredBy: String? = null,
    val adapter: String,
    val version: String,
    val contractSemver: String,
    val conformance: String,
    val discoveredRobots: Int = 0,
)

/** 어댑터 제품·빌드와 인스턴스 목록의 출처. 시험이 registry 없이 대신 끼운다. */
interface AdapterSource {
    /** 운영자 토큰 관문 안의 읽기다. 토큰이 틀리면 [RegistryCall.Unauthorized] 다. */
    fun adapters(): RegistryCall<List<RegistryAdapter>>

    fun instances(siteId: String): RegistryCall<List<RegistryInstance>>
}

/** 운영자 토큰 확인. 목록 읽기는 관문 밖이라 401 이 오지 않으므로 관문 안의 읽기를 따로 부른다. */
fun interface TokenProbe {
    fun operatorToken(): RegistryCall<Unit>
}

/**
 * 조작 한 번의 결과. 응답이 오면 코드와 본문을 그대로 넘기고, 분류는 부르는 쪽이 대응표로 한다(스펙 §7.4).
 * 응답이 오지 않으면 반영 여부를 모르는 것이며, 거절과 섞지 않는다(스펙 §9).
 */
sealed interface RegistryWrite {
    data class Answered(val status: Int, val body: String) : RegistryWrite

    data class NoResponse(val cause: String) : RegistryWrite
}

/** 기체 조작 3가지(스펙 §7.2). 시험이 registry 없이 대신 끼운다. */
interface RobotWrites {
    fun declare(siteId: String, robotId: String, serialNumber: String, displayName: String?, actor: String): RegistryWrite

    fun retire(robotId: String, reason: String, actor: String): RegistryWrite

    fun reinstate(robotId: String, actor: String): RegistryWrite
}

/** 어댑터 조작 3가지(스펙 §3 S1c). 시험이 registry 없이 대신 끼운다. */
interface AdapterWrites {
    fun declareAdapter(vendor: String, name: String, actor: String): RegistryWrite

    fun declareBuild(adapterId: Long, version: String, contractSemver: String, actor: String): RegistryWrite

    fun registerInstance(
        siteId: String,
        instanceId: String,
        adapterVersionId: Long,
        fleetEndpoint: String?,
        actor: String,
    ): RegistryWrite
}

/** registry REST 클라이언트. 운영 서비스만 운영자 토큰을 쥔다(스펙 §4). DB 에 직결하지 않는다. */
class RegistryClient(
    baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) : RobotSource, TokenProbe, RobotWrites, AdapterSource, AdapterWrites,
    ProfileSource, CommissioningSource, ProfileWrites, BindingWrites, AutoCloseable {

    private val base = checkBaseUrl(baseUrl)

    /** JDK 21 의 HttpClient 는 닫아야 셀렉터 스레드가 끝난다. 스프링이 빈을 내릴 때 부른다. */
    override fun close() = http.close()

    /** 퇴역 기체를 늘 포함한다. 기본값(`retired=false`)은 퇴역 뒤 보고와 복귀를 감춘다(스펙 §7.2). */
    override fun robots(siteId: String): RegistryCall<List<RegistryRobot>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/robots?retired=true&site=$site") { json.readValue<List<RegistryRobot>?>(it) }
    }

    /** 관문 안의 읽기 하나로 토큰을 본다. 읽은 값은 쓰지 않는다. */
    override fun operatorToken(): RegistryCall<Unit> = get("/operations/adapters") { }

    override fun declare(
        siteId: String,
        robotId: String,
        serialNumber: String,
        displayName: String?,
        actor: String,
    ): RegistryWrite {
        val body = json.createObjectNode()
            .put("robot_id", robotId)
            .put("site", siteId)
            .put("serial_number", serialNumber)
        if (displayName != null) body.put("display_name", displayName)
        return send("POST", "/operations/robots", actor, json.writeValueAsString(body))
    }

    override fun retire(robotId: String, reason: String, actor: String): RegistryWrite =
        send(
            "POST", "/operations/robots/${segment(robotId)}/retirement", actor,
            json.writeValueAsString(json.createObjectNode().put("reason", reason)),
        )

    override fun reinstate(robotId: String, actor: String): RegistryWrite =
        send("DELETE", "/operations/robots/${segment(robotId)}/retirement", actor, null)

    override fun adapters(): RegistryCall<List<RegistryAdapter>> =
        get("/operations/adapters") { json.readValue<List<RegistryAdapter>?>(it) }

    override fun instances(siteId: String): RegistryCall<List<RegistryInstance>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/adapter-instances?site=$site") { json.readValue<List<RegistryInstance>?>(it) }
    }

    override fun declareAdapter(vendor: String, name: String, actor: String): RegistryWrite =
        send(
            "POST", "/operations/adapters", actor,
            json.writeValueAsString(json.createObjectNode().put("vendor", vendor).put("name", name)),
        )

    override fun declareBuild(adapterId: Long, version: String, contractSemver: String, actor: String): RegistryWrite =
        send(
            "POST", "/operations/adapters/$adapterId/versions", actor,
            json.writeValueAsString(json.createObjectNode().put("version", version).put("contract_semver", contractSemver)),
        )

    override fun registerInstance(
        siteId: String,
        instanceId: String,
        adapterVersionId: Long,
        fleetEndpoint: String?,
        actor: String,
    ): RegistryWrite {
        val body = json.createObjectNode()
            .put("instance_id", instanceId)
            .put("adapter_version_id", adapterVersionId)
            .put("site", siteId)
        if (fleetEndpoint != null) body.put("fleet_endpoint", fleetEndpoint)
        return send("POST", "/operations/adapter-instances", actor, json.writeValueAsString(body))
    }

    override fun catalog(): RegistryCall<RegistryCatalog> =
        get("/operations/skill-types") { json.readValue<RegistryCatalog?>(it) }

    override fun revisions(): RegistryCall<List<RegistryRevision>> =
        get("/operations/profile-revisions") { json.readValue<List<RegistryRevision>?>(it) }

    /** 이력은 빼고(`history` 기본값) 이 사이트의 활성 바인딩만 읽는다. */
    override fun bindings(siteId: String): RegistryCall<List<RegistryBinding>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/bindings?site=$site") { json.readValue<RegistryBindings?>(it)?.rows }
    }

    override fun software(siteId: String): RegistryCall<List<RegistrySoftware>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/software?site=$site") { json.readValue<List<RegistrySoftware>?>(it) }
    }

    override fun submit(document: ByteArray, actor: String): RegistryWrite =
        sendBody("POST", "/operations/profile-revisions", actor, HttpRequest.BodyPublishers.ofByteArray(document))

    override fun requestTest(profileRevisionId: Long, actor: String): RegistryWrite =
        send("POST", "/operations/profile-revisions/$profileRevisionId/test-requests", actor, null)

    override fun activate(profileRevisionId: Long, actor: String): RegistryWrite =
        send("POST", "/operations/profile-revisions/$profileRevisionId/activation", actor, null)

    override fun bind(robotId: String, adapterVersionId: Long, profileRevisionId: Long, actor: String): RegistryWrite =
        send(
            "POST", "/operations/robots/${segment(robotId)}/binding", actor,
            json.writeValueAsString(
                json.createObjectNode().put("adapter_version_id", adapterVersionId).put("profile_revision_id", profileRevisionId),
            ),
        )

    override fun recordSiteNames(robotId: String, actor: String): RegistryWrite =
        send("POST", "/operations/site-names?robot=${URLEncoder.encode(robotId, StandardCharsets.UTF_8)}", actor, null)

    /** [read] 가 널을 내면(본문 `null`) 값을 모르는 것이다. `OK` 인데 목록이 널인 보기를 만들지 않는다. */
    private fun <T : Any> get(path: String, read: (String) -> T?): RegistryCall<T> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return RegistryCall.Silent("응답 없음: ${e.javaClass.simpleName}")
        }
        return when (val status = response.statusCode()) {
            in 200..299 -> try {
                read(response.body())?.let { RegistryCall.Ok(it) } ?: RegistryCall.Silent("본문이 null 이다")
            } catch (e: JacksonException) {
                RegistryCall.Silent("본문 해석 실패: ${e.originalMessage}")
            }
            401 -> RegistryCall.Unauthorized
            else -> RegistryCall.Silent("HTTP $status")
        }
    }

    private fun send(method: String, path: String, actor: String, body: String?): RegistryWrite =
        sendBody(method, path, actor, body?.let(HttpRequest.BodyPublishers::ofString))

    private fun sendBody(method: String, path: String, actor: String, body: HttpRequest.BodyPublisher?): RegistryWrite {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token")
            .header("X-Actor", actor)
            .header("Content-Type", "application/json")
            .method(method, body ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            RegistryWrite.Answered(response.statusCode(), response.body())
        } catch (e: IOException) {
            RegistryWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
        }
    }

    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)

        /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 500 이 되어 화면에는 운영 서비스 불통으로 보인다. */
        fun checkBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "registry 주소가 http(s)://호스트[:포트] 꼴이 아니다: '$baseUrl'"
            }
            return trimmed
        }
    }
}
