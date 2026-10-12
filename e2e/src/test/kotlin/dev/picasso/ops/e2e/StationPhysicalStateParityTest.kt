package dev.picasso.ops.e2e

import dev.picasso.middleware.PhysicalState
import dev.picasso.ops.service.stations.StationCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 스테이션 조정기의 확인 필요 물리 상태 사본을 picasso `PhysicalState` 와 맞댄다. 운영 서비스 main 과 시험은 picasso 미들웨어를
 * 클래스패스에 두지 않아(`checkNoPicassoOnMain`, registry testFixtures 에는 미들웨어가 없다) 이 모듈에서 본다. 스택은 띄우지 않는다.
 */
class StationPhysicalStateParityTest {

    @Test
    fun `확인 필요 물리 상태는 정착한 비성공 상태와 운영자 보류와 결과 불명이다`() {
        val expected = PhysicalState.values().filter { it.isSettled && it != PhysicalState.PHYSICALLY_DONE }.map { it.name }.toSet() +
            setOf(PhysicalState.OPERATOR_HOLD.name, PhysicalState.IN_DOUBT.name)
        assertEquals(expected, StationCoordinator.REVIEW_PHYSICAL_STATES)
        val names = PhysicalState.values().map { it.name }.toSet()
        assertEquals(emptySet(), StationCoordinator.REVIEW_PHYSICAL_STATES - names)
    }
}
