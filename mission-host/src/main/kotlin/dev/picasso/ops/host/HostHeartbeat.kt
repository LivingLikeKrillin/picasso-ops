package dev.picasso.ops.host

import java.time.Duration

/**
 * 실행 호스트 인스턴스의 심장박동(HA 설계 기록 §7·§8 단계 0). [period] 마다 `mission.host_instance` 의 자기 행에 DB 시각으로
 * 마지막 관측과 만료(그때 + [expiry])를 적는다. 다른 인스턴스는 그 만료가 DB 시각으로 지났는지로 살아 있음을 판정한다.
 *
 * **리스가 아니다.** 세대도 펜싱도 없고, 만료 뒤 깨어난 프로세스를 막지 않는다. 단계 0 의 쓰임은 기동 복원이 «살아 있는 다른
 * 인스턴스가 든 일지 행» 을 가져가지 않게 하는 것뿐이다.
 *
 * 만료 폭은 설계 기록 §4.5 의 하한(심장박동 주기의 세 배 + 최대 GC 정지 + DB 왕복)을 넘게 잡는다. 기본값은 주기 5초, 만료 30초다.
 * 실측이 없으므로 주기의 세 배에 15초의 여유(GC 정지·DB 왕복·스케줄러 지연)를 더했다. 만료가 길수록 다른 인스턴스의 행을 잘못
 * 가져갈 위험이 줄고, 대신 비정상 종료 뒤 재기동이 그 행을 다시 짓기까지 더 기다린다(정상 종료는 멈춤을 적어 기다리지 않는다).
 *
 * @param period 심장박동 주기(`host.instance.heartbeat-period`)
 * @param expiry 마지막 심장박동에서 만료까지(`host.instance.heartbeat-expiry`). 주기의 세 배 이상이어야 한다
 */
data class HostHeartbeat(val period: Duration, val expiry: Duration) {
    init {
        require(!period.isNegative && !period.isZero) { "심장박동 주기는 양수여야 한다: $period" }
        require(expiry >= period.multipliedBy(3)) { "심장박동 만료($expiry)는 주기($period)의 세 배 이상이어야 한다" }
    }

    companion object {
        val DEFAULT = HostHeartbeat(Duration.ofSeconds(5), Duration.ofSeconds(30))
    }
}
