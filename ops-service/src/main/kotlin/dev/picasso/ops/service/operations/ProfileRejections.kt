package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 개정판·바인딩 조작 5가지(P2·S1d 스펙 §8.2). */
enum class ProfileOp { SUBMIT, REQUEST_TEST, ACTIVATE, BIND, RECORD_SITE_NAMES }

/**
 * 개정판·바인딩 조작의 거절 대응표(P2·S1d 스펙 §8.3). 409 와 바인딩의 404 는 본문으로 가르며 하나로 다루지 않는다.
 * 표에 없는 응답은 [Rejections.UNCLASSIFIED] 로 두어 엔지니어가 본다. 아는 종류로 접지 않는다.
 *
 * 개정판 거절의 바로 가기는 없다. 프로파일 구역이 같은 영역에 늘 보인다. 바인딩·명칭 거절의 바로 가기는 그 기체다.
 */
object ProfileRejections {

    const val PROFILE_UNREADABLE = "PROFILE_UNREADABLE"
    const val REVISION_NOT_MONOTONIC = "REVISION_NOT_MONOTONIC"
    const val UNKNOWN_REVISION = "UNKNOWN_REVISION"
    const val REVISION_NOT_TESTABLE = "REVISION_NOT_TESTABLE"
    const val ACTIVATION_REFUSED = "ACTIVATION_REFUSED"
    const val UNKNOWN_BUILD = "UNKNOWN_BUILD"
    const val REVISION_NOT_ACTIVE = "REVISION_NOT_ACTIVE"
    const val ROBOT_RETIRED = "ROBOT_RETIRED"
    const val CONTRACT_TOO_OLD = "CONTRACT_TOO_OLD"
    const val NO_ACTIVE_BINDING = "NO_ACTIVE_BINDING"
    const val NOTHING_TO_REGISTER = "NOTHING_TO_REGISTER"

    /** @param robotId 바인딩·명칭 기록의 대상 기체. 개정판 조작이면 널 */
    fun of(op: ProfileOp, status: Int, body: JsonNode?, checkedAt: Instant, robotId: String?): Finding {
        val reason = body?.get("reason")?.asText()
        val error = body?.get("error")?.asText()
        val observedExtra = when {
            op == ProfileOp.SUBMIT && status == 409 -> body?.get("highest")?.let { "highest=${it.asText()}" }
            op == ProfileOp.ACTIVATE && status == 409 -> listOfNotNull(
                body?.get("status")?.let { "status=${it.asText()}" },
                body?.get("suites")?.let { "suites=$it" },
            ).joinToString(", ").ifEmpty { null }
            op == ProfileOp.REQUEST_TEST && status == 409 -> body?.get("status")?.let { "status=${it.asText()}" }
            else -> null
        }
        val target = if (op == ProfileOp.BIND || op == ProfileOp.RECORD_SITE_NAMES) robotId else null

        fun finding(kind: String, action: String, owner: Owner = Owner.ENGINEER, inScreen: Boolean = true) =
            Finding(
                kind = kind,
                observed = listOfNotNull("HTTP $status", reason?.let { "reason=$it" }, observedExtra, error).joinToString(", "),
                expected = "201 또는 200",
                checkedAt = checkedAt,
                owner = owner,
                inScreen = inScreen,
                action = action,
                target = target,
            )

        return when {
            op == ProfileOp.SUBMIT && status == 400 -> finding(PROFILE_UNREADABLE, "문서를 고쳐서 다시")
            op == ProfileOp.SUBMIT && status == 409 -> finding(REVISION_NOT_MONOTONIC, "번호를 올려 다시")
            (op == ProfileOp.REQUEST_TEST || op == ProfileOp.ACTIVATE) && status == 404 ->
                finding(UNKNOWN_REVISION, "목록 새로 읽기")
            op == ProfileOp.REQUEST_TEST && status == 409 -> finding(REVISION_NOT_TESTABLE, "문서를 고쳐 새 번호로 제출")
            op == ProfileOp.ACTIVATE && status == 409 -> finding(ACTIVATION_REFUSED, "시험 요청 또는 시험 결과 확인")
            op == ProfileOp.BIND && status == 404 && reason == "UNKNOWN_ROBOT" -> finding(Rejections.UNKNOWN_ROBOT, "목록 새로 읽기")
            op == ProfileOp.BIND && status == 404 && reason == "UNKNOWN_REVISION" -> finding(UNKNOWN_REVISION, "프로파일 목록 새로 읽기")
            op == ProfileOp.BIND && status == 404 && reason == "UNKNOWN_BUILD" -> finding(UNKNOWN_BUILD, "빌드 목록 새로 읽기")
            op == ProfileOp.BIND && status == 409 && reason == "REVISION_NOT_ACTIVE" -> finding(REVISION_NOT_ACTIVE, "활성 개정판 고르기")
            op == ProfileOp.BIND && status == 409 && reason == "ROBOT_RETIRED" -> finding(ROBOT_RETIRED, "복귀 뒤 다시", Owner.OPERATOR)
            op == ProfileOp.BIND && status == 409 && reason == "CONTRACT_TOO_OLD" ->
                finding(CONTRACT_TOO_OLD, "계약 semver 가 높은 빌드 고르기")
            op == ProfileOp.RECORD_SITE_NAMES && status == 404 -> finding(NO_ACTIVE_BINDING, "바인딩 먼저")
            op == ProfileOp.RECORD_SITE_NAMES && status == 409 ->
                finding(NOTHING_TO_REGISTER, "고칠 것 없음(이 기체의 스킬은 명칭을 쓰지 않음)", Owner.NONE)
            else -> finding(Rejections.UNCLASSIFIED, "registry 응답 조사", inScreen = false)
        }
    }
}
