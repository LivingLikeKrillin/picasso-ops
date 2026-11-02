package dev.picasso.ops.host.retire

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.RetirementPort
import dev.picasso.ops.host.HostClock
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 읽기 주기의 상태. 통째로 바꾸는 불변 값이다.
 *
 * @param retired 마지막으로 읽기에 성공한 퇴역 기체 id 에서 퇴역 사유로. 아직 한 번도 못 읽었으면 비어 있다
 * @param readAt 마지막으로 읽기에 성공한 호스트 시각
 * @param lastReadAt 마지막으로 읽기를 마친 호스트 시각. 성공과 실패를 가리지 않는다
 * @param readError 마지막 읽기가 실패했으면 그 까닭. 성공하면 지운다
 */
data class RetirementState(
    val retired: Map<String, String>,
    val readAt: Instant?,
    val lastReadAt: Instant?,
    val readError: String?,
) {
    companion object {
        val UNREAD = RetirementState(emptyMap(), null, null, null)
    }
}

/**
 * registry 원장의 기체 퇴역을 미들웨어에 비춘다(picasso §15.249, [RetirementPort]). registry 의 토큰 없는 진단 읽기
 * `GET /diag/robots?retired=true` 를 [period] 마다 **호스트 잠금 밖에서** 읽고, `retiredAt` 이 있는 기체만 퇴역으로 둔다.
 * [retired] 는 읽어 둔 스냅숏만 돌려주므로 호스트 잠금 아래에서 불려도 막히지 않는다.
 *
 * - [baseUrl] 이 비어 있으면 붙이지 않는다([declared] 거짓). 읽지 않고 미들웨어에도 넘기지 않아 발행 동작이 오늘 그대로다
 * - 기동 안에서 한 번 동기로 읽는다([start]). 실패하면 퇴역 없음으로 뜬다. registry 가 호스트보다 늦게 뜰 수 있어 기동 실패로 두지 않는다
 * - 그 뒤 읽기가 실패하면 마지막으로 읽은 값을 계속 쓰고 [RetirementState.readError] 에 남긴다. 퇴역은 원장의 운영자 판단이라
 *   못 읽었다고 풀지 않는다. 한 번도 못 읽었으면 아무것도 막지 않는다(picasso 의 «원장을 못 읽으면 막지 않는다» 와 같은 쪽)
 * - 사이트로 좁히지 않는다. 기체 id 는 registry 에서 사이트를 건너 하나이고, 미들웨어는 자기 기체의 id 로만 묻는다
 *
 * 쓰는 쪽은 읽기 주기 하나뿐이다([readOnce] 는 동기화된다).
 *
 * @param baseUrl registry 의 주소(`host.registry.url`). 경로 `/diag/robots` 를 붙여 부른다
 * @param timeout 연결 제한과 요청 제한
 */
class RegistryRetirements(
    baseUrl: String,
    private val clock: HostClock,
    private val timeout: Duration = Duration.ofSeconds(2),
) : RetirementPort, AutoCloseable {

    private val base = baseUrl.trim().trimEnd('/')

    /** 주소가 있어 미들웨어에 붙이는 포트다. */
    val declared: Boolean = base.isNotEmpty()

    private val uri: URI? = if (declared) URI.create("$base$PATH") else null
    private val http: HttpClient? = if (declared) HttpClient.newBuilder().connectTimeout(timeout).build() else null
    private val json = ObjectMapper()
    private val state = AtomicReference(RetirementState.UNREAD)
    private val reader = Executors.newSingleThreadScheduledExecutor { Thread(it, "retirement-reader").apply { isDaemon = true } }

    override fun retired(): Map<String, String> = state.get().retired

    fun state(): RetirementState = state.get()

    /**
     * 한 번 동기로 읽고 그 뒤 [period] 마다 읽는다. [declared] 가 거짓이면 아무것도 하지 않는다. 스케줄러가 밀리초로 받으므로
     * [period] 는 1ms 이상이어야 한다.
     */
    fun start(period: Duration = READ_PERIOD): RegistryRetirements = apply {
        require(period.toMillis() > 0) { "퇴역 읽기 주기(host.retirement.read-interval)는 1ms 이상이어야 한다: $period" }
        if (!declared) return@apply
        readOnce()
        reader.scheduleWithFixedDelay(::readOnce, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
    }

    /**
     * 원장을 한 번 읽어 상태를 바꾼다. 스케줄러는 작업이 던지면 다음 실행을 조용히 멈추므로 `Error` 도 잡아 남기고 다음 주기에 다시
     * 읽는다. `VirtualMachineError` 만 다시 던진다(현장 시간값 읽기와 같은 규율).
     */
    @Synchronized
    fun readOnce() {
        if (!declared) return
        val before = state.get()
        val after = try {
            val retired = read()
            val at = clock.now()
            RetirementState(retired, readAt = at, lastReadAt = at, readError = null)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            if (e is InterruptedException) Thread.currentThread().interrupt()
            before.copy(lastReadAt = runCatching { clock.now() }.getOrNull() ?: before.lastReadAt, readError = describe(e))
        }
        state.set(after)
        if (after.readError != null && after.readError != before.readError) log.warn("기체 퇴역 읽기 실패: {}", after.readError)
        if (after.readError == null && after.retired != before.retired) log.info("퇴역 기체 {}", after.retired.keys.sorted())
    }

    private fun read(): Map<String, String> {
        val response = http!!.send(
            HttpRequest.newBuilder(uri!!).timeout(timeout).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(response.statusCode() == 200) { "registry $PATH 가 ${response.statusCode()} 를 냈다" }
        return parse(json.readTree(response.body()))
    }

    private fun describe(e: Throwable): String =
        generateSequence<Throwable>(e) { it.cause }.last().let { root -> "${root.javaClass.simpleName}: ${root.message}" }

    override fun close() {
        reader.shutdownNow()
        reader.awaitTermination(2, TimeUnit.SECONDS)
        http?.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RegistryRetirements::class.java)

        /** 퇴역 이력을 함께 내는 진단 읽기. 기본값(`retired=false`)은 퇴역 기체를 감춘다. */
        const val PATH = "/diag/robots?retired=true"

        /** 읽기 주기 기본값. 기동에서는 설정 키 `host.retirement.read-interval` 이 준다. */
        val READ_PERIOD: Duration = Duration.ofSeconds(2)

        /** registry 가 사유를 비워 둔 퇴역의 사유 칸. 미들웨어가 차단의 주체 칸에 싣는다. */
        const val NO_REASON = "사유 없이 퇴역"

        /**
         * 진단 본문에서 퇴역 기체만 골라 id 에서 사유로. 본문 모양이 어긋나면 예외다. [readOnce] 가 그것을 읽기 실패로 접어 마지막
         * 값을 남긴다 — 모양이 틀린 본문을 «퇴역 없음» 으로 읽으면 퇴역한 기체가 풀린다.
         */
        fun parse(node: JsonNode): Map<String, String> {
            require(node.isArray) { "registry $PATH 의 본문이 배열이 아니다" }
            return node.mapNotNull { robot ->
                val id = requireNotNull(robot.get("robotId")?.takeIf { it.isTextual && it.asText().isNotEmpty() }) { "robotId 가 없는 기체가 있다" }.asText()
                val retiredAt = robot.get("retiredAt")
                if (retiredAt == null || retiredAt.isNull) return@mapNotNull null
                val reason = robot.get("retiredReason")?.takeIf { it.isTextual }?.asText()?.trim().orEmpty()
                id to reason.ifEmpty { NO_REASON }
            }.toMap()
        }
    }
}
