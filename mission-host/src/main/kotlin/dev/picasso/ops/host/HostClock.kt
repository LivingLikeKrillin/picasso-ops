package dev.picasso.ops.host

import java.time.Instant

/**
 * 실행 호스트의 시계(S3a 스펙 §7.2). 미들웨어의 `now` 와 마지막 pump 의 시각이 이것으로 정해진다.
 *
 * 기본은 실제 시각이다. 런처의 현장은 가상 시계를 실제 시각까지 따라잡게 밀므로 둘이 한 주기 안으로 맞는다. 시험이
 * 현장 시계를 실제 시각보다 앞으로 밀면 이 빈을 현장 시계(`Site.now()`)로 바꿔 끼운다
 * ([MissionHostApplication.builder]). 미들웨어는 E2 시간 윈도우의 기준 시각을 mimic 응답 헤더에서 가져오고 마감은 이
 * 시계로 보므로, 둘이 어긋나면 셀 대역 신호가 윈도우 밖으로 읽힌다.
 */
fun interface HostClock {
    fun now(): Instant

    companion object {
        val SYSTEM = HostClock { Instant.now() }
    }
}
