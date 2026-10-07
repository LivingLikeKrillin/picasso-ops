package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.registry.AdapterSource
import dev.picasso.ops.service.registry.AdapterWrites
import dev.picasso.ops.service.registry.RegistryAdapter
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.map
import java.time.Clock
import java.time.Duration

/**
 * 어댑터 제품 선언, 빌드 선언, 인스턴스 등록(스펙 §3 S1c). 기록과 응답 없음 뒤 재조회는 [OperationRunner] 가 한다.
 *
 * «반영됨» 의 판정(스펙 §9)은 registry 가 같은 요청을 어떻게 다루는지에 맞춘다.
 * - 제품 선언은 이미 있으면 그대로 둔다. 그 제품이 목록에 있으면 반영된 것이다.
 * - 빌드 선언은 같은 버전에 다른 계약값이면 거절한다. 그 버전이 같은 계약값으로 있어야 반영된 것이다.
 * - 인스턴스 등록은 같은 id 면 빌드·사이트·플릿 주소를 덮어쓴다. 그 인스턴스가 이 사이트에, 요청한 빌드와
 *   플릿 주소로 있어야 반영된 것이다. 인스턴스 목록에는 빌드 id 가 없어 제품 목록에서 빌드의 제품 이름과 버전을 찾아 맞댄다.
 */
class AdapterOperations(
    private val writes: AdapterWrites,
    private val reads: AdapterSource,
    log: OperationLog,
    private val siteId: String,
    clock: Clock,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {
    private val runner = OperationRunner(log, clock, requeryDelay, json)

    fun declareAdapter(actor: Actor, vendor: String, name: String): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", AdapterOp.DECLARE_ADAPTER.name)
            .put("vendor", vendor)
            .put("name", name)
        return runner.run(
            actor, "adapter $vendor/$name", request, reason = null,
            rejection = { status, body, at -> AdapterRejections.of(AdapterOp.DECLARE_ADAPTER, status, body, at) },
            recheck = {
                reads.adapters().map { adapters ->
                    val seen = adapters.firstOrNull { it.vendor == vendor && it.name == name }
                    Recheck(seen != null, seen?.let { json.createObjectNode().put("adapter_id", it.adapterId) })
                }
            },
        ) { writes.declareAdapter(vendor, name, actor.header()) }
    }

    fun declareBuild(actor: Actor, adapterId: Long, version: String, contractSemver: String): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", AdapterOp.DECLARE_BUILD.name)
            .put("adapter_id", adapterId)
            .put("version", version)
            .put("contract_semver", contractSemver)
        return runner.run(
            actor, "build $adapterId@$version", request, reason = null,
            rejection = { status, body, at -> AdapterRejections.of(AdapterOp.DECLARE_BUILD, status, body, at) },
            recheck = {
                reads.adapters().map { adapters ->
                    val seen = adapters.firstOrNull { it.adapterId == adapterId }?.versions?.firstOrNull { it.version == version }
                    Recheck(
                        seen?.contractSemver == contractSemver,
                        seen?.let { json.createObjectNode().put("contract_semver", it.contractSemver) },
                    )
                }
            },
        ) { writes.declareBuild(adapterId, version, contractSemver, actor.header()) }
    }

    fun registerInstance(actor: Actor, instanceId: String, adapterVersionId: Long, fleetEndpoint: String?): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", AdapterOp.REGISTER_INSTANCE.name)
            .put("instance_id", instanceId)
            .put("adapter_version_id", adapterVersionId)
            .put("site", siteId)
            .put("fleet_endpoint", fleetEndpoint)
        return runner.run(
            actor, "instance $instanceId", request, reason = null,
            rejection = { status, body, at -> AdapterRejections.of(AdapterOp.REGISTER_INSTANCE, status, body, at) },
            recheck = {
                when (val adapters = reads.adapters()) {
                    is RegistryCall.Ok -> reads.instances(siteId).map { instances ->
                        val build = buildOf(adapters.value, adapterVersionId)
                        val seen = instances.firstOrNull { it.instanceId == instanceId }
                        val applied = seen != null && build != null && seen.adapter == build.first &&
                            seen.version == build.second && seen.fleetEndpoint == fleetEndpoint
                        val observed = seen?.let {
                            json.createObjectNode()
                                .put("adapter", it.adapter)
                                .put("version", it.version)
                                .put("fleet_endpoint", it.fleetEndpoint)
                        }
                        Recheck(applied, observed)
                    }
                    is RegistryCall.Silent -> adapters
                    RegistryCall.Unauthorized -> RegistryCall.Unauthorized
                }
            },
        ) { writes.registerInstance(siteId, instanceId, adapterVersionId, fleetEndpoint, actor.header()) }
    }

    /**
     * 빌드 id 의 제품 이름(`vendor/name`)과 버전. 인스턴스 목록이 쓰는 꼴이다.
     *
     * 알려진 한계: 인스턴스 목록은 제품을 `vendor/name` 으로만 이름 짓고 registry 는 두 칸 모두에 `/` 를 허용하므로,
     * 버전이 같은 두 제품(`a/b` + `c` 와 `a` + `b/c`)은 여기서 가를 수 없다.
     * registry 가 인스턴스에 빌드 id 를 내기 전에는 운영 서비스가 고칠 수 없다.
     */
    private fun buildOf(adapters: List<RegistryAdapter>, adapterVersionId: Long): Pair<String, String>? =
        adapters.firstNotNullOfOrNull { adapter ->
            adapter.versions.firstOrNull { it.adapterVersionId == adapterVersionId }?.let { "${adapter.vendor}/${adapter.name}" to it.version }
        }
}
