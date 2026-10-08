package dev.picasso.ops.service.host

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 실행 호스트 읽기 한 번의 결과(S3a 스펙 §8). «없음» 과 «모름» 을 접지 않는다.
 *
 * 연결 실패·시간 초과·200 아님·해석 불가 어느 것이든 값을 모른다는 점이 같아 [Silent] 하나로 둔다. 호스트에는 토큰이 없어
 * registry 의 `Unauthorized` 같은 하위 범주가 없다. 제출은 [HostWrite] 로 따로 돌려준다.
 */
sealed interface HostCall<out T> {
    data class Ok<T>(val value: T) : HostCall<T>

    data class Silent(val cause: String) : HostCall<Nothing>
}

/**
 * 제출 한 번의 결과. 응답이 오면 코드와 본문을 그대로 넘기고, 분류는 부르는 쪽이 한다(스펙 §8).
 * 응답이 오지 않으면 호스트가 받았는지 모르는 것이며, 거부와 섞지 않는다.
 */
sealed interface HostWrite {
    data class Answered(val status: Int, val body: String) : HostWrite

    data class NoResponse(val cause: String) : HostWrite
}

/** 호스트의 스킬 적합 판정. [UNKNOWN] 은 호스트가 기체 케이퍼빌리티를 못 물어봤다는 뜻이고 적합이 아니다. */
enum class HostSkillFit { FIT, MISSING, UNKNOWN }

/**
 * 호스트 판정 한 행(S3a JSON 계약 §2.2). 호스트는 스킬 적합과 도는 실행만 보고, 시운전·연결은 운영 서비스가 합친다(T3).
 *
 * @param runningExecutionId 이 기체에서 물리 상태가 정착하지 않은 실행. 없으면 널. 운영자 보류도 도는 실행이다
 * @param passed 스킬 적합이고 도는 실행이 없다
 * @param reasons 통과하지 못한 이유(화면 표시용). 통과면 비어 있다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostEligibility(
    val robotId: String,
    val skillFit: HostSkillFit,
    val missingSkills: List<String> = emptyList(),
    val runningExecutionId: String? = null,
    val passed: Boolean,
    val reasons: List<String> = emptyList(),
)

/** `POST /host/eligibility` 의 답. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostEligibilityList(val robots: List<HostEligibility>)

/** 미들웨어 제출 결과 넷의 이름(S3a JSON 계약 §4). */
enum class HostSubmitResult { ACCEPTED, IDEMPOTENT, REJECTED, UNASSIGNED }

/** `assign` 이 기체마다 낸 미배정 사유. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostRefusal(val robotId: String, val reason: String)

/**
 * `POST /host/job-orders` 의 200 본문(S3a JSON 계약 §4). 결과가 무엇이든 200 이며 결과는 [result] 에 있다.
 *
 * @param executionId·robotId ACCEPTED·IDEMPOTENT 일 때만 있다
 * @param rejectionReason REJECTED 일 때만 있다
 * @param refusals UNASSIGNED 일 때 `assign` 이 기체마다 낸 사유
 * @param excluded 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체의 판정 행
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostSubmitOutcome(
    val result: HostSubmitResult,
    val executionId: String? = null,
    val robotId: String? = null,
    val rejectionReason: String? = null,
    val refusals: List<HostRefusal> = emptyList(),
    val excluded: List<HostEligibility> = emptyList(),
)

/** `GET /host/executions` 의 실행 한 줄 중 재조회가 쓰는 칸. 나머지는 버린다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostExecutionRef(
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val physicalState: String,
)

/** `GET /host/executions` 를 재조회로 읽은 모양. 화면에 넘길 때는 해석하지 않고 그대로 넘긴다([HostReads.executions]). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostExecutions(val instanceId: String, val executions: List<HostExecutionRef>)

/** 호스트 읽기 셋. 시험이 호스트 없이 대신 끼운다. */
interface HostReads {
    /** 작업 지시 본문 [jobOrder] 를 [robotIds] 의 기체마다 판정한다. */
    fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>>

    /** `GET /host/executions` 본문 그대로. 객체가 아니면 모름이다. */
    fun executions(): HostCall<JsonNode>

    /** `GET /host/cell` 본문 그대로. 셀 대역을 못 읽은 호스트는 `{"cell": null}` 을 200 으로 준다. */
    fun cell(): HostCall<JsonNode>
}

/** 호스트 제출. 시험이 호스트 없이 대신 끼운다. */
fun interface HostWrites {
    fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite
}

/**
 * 실행 호스트 REST 클라이언트(S3a 스펙 §8). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
 *
 * 연결 제한은 registry 와 같고 요청 제한은 더 길다. 호스트는 판정과 제출을 자기 잠금 아래에서 하며, 그 안에서 mimic 에
 * gRPC 를 부르고, mimic 은 엔진 잠금 아래에서 registry 로 태스크 관측을 동기 HTTP 로 적재한다(요청 제한 3초, 스펙 §5.3).
 * 호스트가 registry 장애 한 번을 기다리는 동안 운영 서비스가 먼저 끊으면, 받은 제출을 응답 없음으로 남기게 된다.
 */
class HostClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) : HostReads, HostWrites, AutoCloseable {

    private val base = checkBaseUrl(baseUrl)

    /** JDK 21 의 HttpClient 는 닫아야 셀렉터 스레드가 끝난다. 스프링이 빈을 내릴 때 부른다. */
    override fun close() = http.close()

    override fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>> {
        val body = json.createObjectNode()
        body.set<JsonNode>("jobOrder", jobOrder)
        body.putArray("robotIds").apply { robotIds.forEach(::add) }
        val response = when (val write = post("/host/eligibility", body)) {
            is HostWrite.NoResponse -> return HostCall.Silent(write.cause)
            is HostWrite.Answered -> write
        }
        if (response.status != 200) return HostCall.Silent("HTTP ${response.status}")
        return read(response.body) { json.readValue<HostEligibilityList?>(it)?.robots }
    }

    override fun executions(): HostCall<JsonNode> = get("/host/executions")

    override fun cell(): HostCall<JsonNode> = get("/host/cell")

    override fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite {
        val body = json.createObjectNode()
        body.set<JsonNode>("jobOrder", jobOrder)
        body.putArray("candidates").apply { candidates.forEach(::add) }
        return post("/host/job-orders", body)
    }

    /** 객체 본문만 받는다. 호스트의 두 GET 은 늘 객체를 준다(S3a JSON 계약 §5·§6). */
    private fun get(path: String): HostCall<JsonNode> {
        val request = HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT).GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return HostCall.Silent("응답 없음: ${e.javaClass.simpleName}")
        }
        if (response.statusCode() != 200) return HostCall.Silent("HTTP ${response.statusCode()}")
        return read(response.body()) { body -> json.readTree(body).takeIf { it.isObject } }
    }

    private fun post(path: String, body: ObjectNode): HostWrite {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            HostWrite.Answered(response.statusCode(), response.body())
        } catch (e: IOException) {
            HostWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
        }
    }

    /** [parse] 가 널을 내면(본문 `null`, 모양 어긋남) 값을 모르는 것이다. */
    private fun <T : Any> read(body: String, parse: (String) -> T?): HostCall<T> = try {
        parse(body)?.let { HostCall.Ok(it) } ?: HostCall.Silent("본문 모양이 다르다")
    } catch (e: JacksonException) {
        HostCall.Silent("본문 해석 실패: ${e.originalMessage}")
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 호스트 불통으로 보인다. */
        fun checkBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "실행 호스트 주소가 http(s)://호스트[:포트] 꼴이 아니다: '$baseUrl'"
            }
            return trimmed
        }
    }
}
