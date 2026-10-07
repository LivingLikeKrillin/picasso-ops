package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 어댑터 조작 3가지(스펙 §3 S1c). 제품 선언, 빌드 선언, 인스턴스 등록이다. */
enum class AdapterOp { DECLARE_ADAPTER, DECLARE_BUILD, REGISTER_INSTANCE }

/**
 * 어댑터 조작의 거절 대응표(스펙 §7.4 의 P1·인스턴스 행). 모두 엔지니어가 화면 안에서 푼다.
 *
 * 409 는 빌드 선언에만 있고 «같은 버전에 다른 계약값» 하나다. 404 도 빌드 선언에만 있고 «없는 제품» 이다.
 * 인스턴스 등록은 없는 빌드 id 도 400 으로 답한다(registry `AdapterInstanceService.register`).
 * 표에 없는 응답은 [Rejections.UNCLASSIFIED] 로 두어 엔지니어가 본다. 아는 종류로 접지 않는다.
 *
 * 바로 갈 링크([Finding.target])는 비운다. 링크는 기체 상세를 열고, 어댑터 목록은 같은 영역에 늘 보인다.
 */
object AdapterRejections {

    const val ADAPTER_BAD_REQUEST = "ADAPTER_BAD_REQUEST"
    const val UNKNOWN_ADAPTER = "UNKNOWN_ADAPTER"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val INSTANCE_BAD_REQUEST = "INSTANCE_BAD_REQUEST"

    fun of(op: AdapterOp, status: Int, body: JsonNode?, checkedAt: Instant): Finding {
        val error = body?.get("error")?.asText()
        val existing = body?.get("existing_contract_semver")?.asText()

        fun finding(kind: String, action: String, inScreen: Boolean = true) =
            Finding(
                kind = kind,
                observed = listOfNotNull("HTTP $status", existing?.let { "existing_contract_semver=$it" }, error).joinToString(", "),
                expected = "201 또는 200",
                checkedAt = checkedAt,
                owner = Owner.ENGINEER,
                inScreen = inScreen,
                action = action,
                target = null,
            )

        return when {
            op != AdapterOp.REGISTER_INSTANCE && status == 400 -> finding(ADAPTER_BAD_REQUEST, "고쳐서 다시")
            op == AdapterOp.DECLARE_BUILD && status == 404 -> finding(UNKNOWN_ADAPTER, "제품 목록 새로 읽기")
            op == AdapterOp.DECLARE_BUILD && status == 409 -> finding(VERSION_CONFLICT, "다른 버전 번호로")
            op == AdapterOp.REGISTER_INSTANCE && status == 400 -> finding(INSTANCE_BAD_REQUEST, "고쳐서 다시")
            else -> finding(Rejections.UNCLASSIFIED, "registry 응답 조사", inScreen = false)
        }
    }
}
