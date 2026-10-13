import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import type { UnassignedRefusal } from '../api'
import { JobOrderNotice } from './JobOrderNotice'

const missing = { material: 'ENGINE-COVER-A', source: 'SEQ-IN-02.BIN-A', observed: null }
function show(refusals: UnassignedRefusal[]) {
  render(<JobOrderNotice what="PrepareSequencedRack 작업 지시" sent={{ kind: 'outcome', outcome: {
    requestId: 'q1', jobOrderId: 'JO-1', result: 'REJECTED', confirmation: null,
    outcome: { result: 'UNASSIGNED', executionId: null, robotId: null, rejectionReason: null, refusals, excluded: [] },
  } }} />)
  return within(screen.getByRole('list', { name: '기체별 미배정 사유' })).getAllByRole('listitem')
}

it('미배정의 기체별 사유에 출발 자리와 대체 위치를 붙이고 출발 결품이 아닌 기체에는 붙이지 않는다', () => {
  const rows = show([
    { robotId: 'humanoid-01', reason: '출발 자리 비었다', alternativeLocations: ['SEQ-IN-03.BIN-B'], sourceMissing: missing },
    { robotId: 'humanoid-02', reason: '출발 자리 비었다', alternativeLocations: [], sourceMissing: missing },
    { robotId: 'humanoid-03', reason: '자재 선언과 배정이 어긋난다', alternativeLocations: null, sourceMissing: null },
  ])
  expect(within(rows[0]).getByText('출발 자리 SEQ-IN-02.BIN-A · 비어 있음')).toBeVisible()
  expect(within(rows[0]).getByText('대체 위치: SEQ-IN-03.BIN-B')).toBeVisible()
  expect(within(rows[1]).getByText('대체 위치 없음')).toBeVisible()
  expect(rows[2]).toHaveTextContent('humanoid-03: 자재 선언과 배정이 어긋난다')
  expect(within(rows[2]).queryByText(/출발 자리|대체 위치/)).not.toBeInTheDocument()
})

it('미배정의 기체별 대체 위치 조회 불가를 대체 위치 없음으로 접지 않고 칸이 없던 호스트 본문도 읽는다', () => {
  const rows = show([
    { robotId: 'humanoid-01', reason: '출발 자리 비었다', alternativeLocations: null, sourceMissing: missing },
    { robotId: 'humanoid-02', reason: '작업 구역 점유' },
  ])
  expect(within(rows[0]).getByText('대체 위치 조회 불가')).toBeVisible()
  expect(within(rows[0]).queryByText('대체 위치 없음')).not.toBeInTheDocument()
  expect(rows[1]).toHaveTextContent('humanoid-02: 작업 구역 점유')
  expect(within(rows[1]).queryByText(/대체 위치/)).not.toBeInTheDocument()
})
