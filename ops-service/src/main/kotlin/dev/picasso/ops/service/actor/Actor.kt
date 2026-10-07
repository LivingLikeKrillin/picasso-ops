package dev.picasso.ops.service.actor

/** 화면의 모드(스펙 §8). 등록·어댑터 조작은 엔지니어, 퇴역·복귀는 운영자. */
enum class Mode(val wire: String) {
    ENGINEER("engineer"),
    OPERATOR("operator"),
    ;

    companion object {
        fun parse(raw: String?): Mode? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

/**
 * 조작한 사람. 인증 없이 화면이 요청 헤더로 싣는다(스펙 §7.1).
 *
 * 사용자 이름은 `[A-Za-z0-9._-]` 만 받는다. registry 로 가는 `X-Actor` 헤더가 ASCII 만 실을 수 있고,
 * `/` 는 모드와 사용자를 가르는 자리라서다.
 */
data class Actor(val mode: Mode, val user: String) {

    init {
        require(USER.matches(user)) { "사용자 이름은 영문·숫자·._- 로 1~64자다: '$user'" }
    }

    /** registry 로 보내는 `X-Actor` 값. registry `audit_log.actor` 와 조작 기록을 맞대 보려고 이 형식을 쓴다. */
    fun header(): String = "${mode.wire}/$user"

    companion object {
        const val MODE_HEADER = "X-Ops-Mode"
        const val USER_HEADER = "X-Ops-User"
        private val USER = Regex("[A-Za-z0-9._-]{1,64}")

        /** 헤더가 없거나 틀리면 널이다. 조작 API 가 400 으로 돌려보낸다(S1b). */
        fun fromHeaders(mode: String?, user: String?): Actor? {
            val parsedMode = Mode.parse(mode) ?: return null
            val trimmed = user?.trim() ?: return null
            return if (USER.matches(trimmed)) Actor(parsedMode, trimmed) else null
        }
    }
}
