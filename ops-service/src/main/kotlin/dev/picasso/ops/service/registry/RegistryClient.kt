package dev.picasso.ops.service.registry

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
 * 값을 모른다는 점이 같다. 조작의 거절 대응(400/404/409)은 S1b 에서 더한다(스펙 §7.4).
 *
 * [Unauthorized] 는 운영자 토큰 관문(`/operations` 이하) 안의 호출에서만 나온다. S1a 의 유일한 읽기인
 * `/diag/robots` 는 관문 밖이라 토큰이 틀려도 401 이 오지 않는다. 토큰 불일치는 S1b 의 첫 조작에서 드러난다.
 */
sealed interface RegistryCall<out T> {
    data class Ok<T>(val value: T) : RegistryCall<T>

    data class Silent(val cause: String) : RegistryCall<Nothing>

    /** 운영 서비스의 운영자 토큰이 registry 와 맞지 않는다(스펙 §7.4). */
    data object Unauthorized : RegistryCall<Nothing>
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

/** registry REST 클라이언트. 운영 서비스만 운영자 토큰을 쥔다(스펙 §4). DB 에 직결하지 않는다. */
class RegistryClient(
    baseUrl: String,
    private val operatorToken: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) : RobotSource, AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    /** JDK 21 의 HttpClient 는 닫아야 셀렉터 스레드가 끝난다. 스프링이 빈을 내릴 때 부른다. */
    override fun close() = http.close()

    /** 퇴역 기체를 늘 포함한다. 기본값(`retired=false`)은 퇴역 뒤 보고와 복귀를 감춘다(스펙 §7.2). */
    override fun robots(siteId: String): RegistryCall<List<RegistryRobot>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/robots?retired=true&site=$site") { json.readValue<List<RegistryRobot>?>(it) }
    }

    /** [read] 가 널을 내면(본문 `null`) 값을 모르는 것이다. `OK` 인데 목록이 널인 보기를 만들지 않는다. */
    private fun <T : Any> get(path: String, read: (String) -> T?): RegistryCall<T> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $operatorToken")
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

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
