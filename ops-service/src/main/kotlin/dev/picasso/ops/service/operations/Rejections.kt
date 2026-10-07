package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 기체 조작 3가지(스펙 §7.2). */
enum class RobotOp { DECLARE, RETIRE, REINSTATE }

/**
 * registry 가 응답한 거절(400/404/409)을 종류로 옮기는 대응표(스펙 §7.4). 409 는 본문으로 가르며 하나로 다루지 않는다.
 *
 * 401 과 5xx 는 여기 오지 않는다. 401 은 화면 전체 상태이고, 5xx 는 응답 없음과 같이 다룬다(스펙 §7.4).
 * 표에 없는 응답은 [UNCLASSIFIED] 로 두어 엔지니어가 본다. 아는 종류로 접지 않는다.
 *
 * 거절은 대부분 화면 안에서 풀린다(고쳐서 다시, 복귀, 목록 새로 읽기). [UNCLASSIFIED] 만 registry 응답을
 * 조사해야 하므로 화면 밖이다. [WRONG_DOOR] 는 할 일이 없지만 그 사실을 화면이 알린다.
 */
object Rejections {

    const val BAD_REQUEST = "BAD_REQUEST"
    const val WRONG_DOOR = "WRONG_DOOR"
    const val RETIRED_ALREADY = "RETIRED_ALREADY"
    const val RETIRE_BAD_REQUEST = "RETIRE_BAD_REQUEST"
    const val UNKNOWN_ROBOT = "UNKNOWN_ROBOT"
    const val UNCLASSIFIED = "UNCLASSIFIED"

    fun of(op: RobotOp, status: Int, body: JsonNode?, checkedAt: Instant, robotId: String): Finding {
        val error = body?.get("error")?.asText()
        val origin = body?.get("origin")?.asText()
        val ledger = body?.get("status")?.asText()
        val expected = if (op == RobotOp.DECLARE) "201 또는 200" else "200"

        fun finding(kind: String, observed: String, owner: Owner, action: String, inScreen: Boolean = true) =
            Finding(kind, observed, expected, checkedAt, owner, inScreen, action, robotId)

        val detail = listOfNotNull("HTTP $status", origin?.let { "origin=$it" }, ledger?.let { "status=$it" }, error)
            .joinToString(", ")
        return when {
            op == RobotOp.DECLARE && status == 400 -> finding(BAD_REQUEST, detail, Owner.ENGINEER, "고쳐서 다시")
            op == RobotOp.DECLARE && status == 409 && origin != null ->
                finding(WRONG_DOOR, detail, Owner.NONE, "고칠 것 없음(이미 다른 문으로 들어온 기체)")
            op == RobotOp.DECLARE && status == 409 && ledger == "RETIRED" ->
                finding(RETIRED_ALREADY, detail, Owner.OPERATOR, "복귀")
            op == RobotOp.RETIRE && status == 400 -> finding(RETIRE_BAD_REQUEST, detail, Owner.OPERATOR, "사유를 넣어 다시")
            op != RobotOp.DECLARE && status == 404 -> finding(UNKNOWN_ROBOT, detail, Owner.ENGINEER, "목록 새로 읽고 조사")
            else -> finding(UNCLASSIFIED, detail, Owner.ENGINEER, "registry 응답 조사", inScreen = false)
        }
    }
}
