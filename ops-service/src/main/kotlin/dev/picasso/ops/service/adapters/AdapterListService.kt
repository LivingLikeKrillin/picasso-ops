package dev.picasso.ops.service.adapters

import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryInstance
import dev.picasso.ops.service.robots.RegistryState
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * `GET /api/adapters` 의 답. 제품·빌드와 인스턴스를 한 번에 읽어 같은 시각으로 보인다.
 *
 * @param adapters 널이면 «모름»(한 번도 읽지 못했다). 빈 목록은 «없음». 둘을 접지 않는다(스펙 §9).
 * @param asOf [adapters]·[instances] 를 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 값이다.
 */
data class AdapterListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val adapters: List<RegistryAdapter>?,
    val instances: List<RegistryInstance>?,
    val asOf: Instant?,
)

/**
 * 어댑터 제품·빌드(`GET /operations/adapters`)와 이 사이트의 인스턴스(`GET /diag/adapter-instances`) 목록.
 * 둘 다 읽혀야 새 값으로 바꾼다. 하나라도 읽지 못하면 둘 다 직전 값을 보인다. 인스턴스는 빌드를 제품 이름과
 * 버전으로만 가리키므로, 다른 시각에 읽은 둘을 섞어 보이면 화면이 서로 맞지 않는 목록을 보일 수 있다.
 *
 * 인스턴스를 먼저, 제품·빌드를 나중에 읽는다. 제품과 빌드는 지워지지 않으므로, 이 순서면 보이는 인스턴스의 빌드가
 * 제품·빌드 목록에 늘 있다. 거꾸로 읽으면 그 사이에 등록된 인스턴스가 빌드 없이 보일 수 있다.
 *
 * 제품·빌드 읽기는 운영자 토큰 관문 안이라 토큰이 틀리면 [RegistryState.REGISTRY_UNAUTHORIZED] 다.
 */
class AdapterListService(
    private val source: AdapterSource,
    private val siteId: String,
    private val clock: Clock,
) {
    private data class Known(val adapters: List<RegistryAdapter>, val instances: List<RegistryInstance>, val at: Instant)

    private val last = AtomicReference<Known?>(null)

    fun read(): AdapterListView {
        val now = clock.instant()
        val instances = source.instances(siteId)
        val adapters = if (instances is RegistryCall.Ok) source.adapters() else null
        if (adapters is RegistryCall.Ok && instances is RegistryCall.Ok) {
            // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
            val known = last.updateAndGet { prev ->
                if (prev != null && prev.at.isAfter(now)) prev else Known(adapters.value, instances.value, now)
            }!!
            return view(RegistryState.OK, maxOf(now, known.at), known)
        }
        val state = if (adapters == RegistryCall.Unauthorized || instances == RegistryCall.Unauthorized) {
            RegistryState.REGISTRY_UNAUTHORIZED
        } else {
            RegistryState.REGISTRY_SILENT
        }
        return view(state, now, last.get())
    }

    private fun view(state: RegistryState, now: Instant, known: Known?): AdapterListView =
        AdapterListView(state, now, known?.adapters, known?.instances, known?.at)
}
