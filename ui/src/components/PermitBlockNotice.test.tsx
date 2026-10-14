import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import type { Execution, PermitBlock } from '../api'
import { PERMIT_BLOCK_TEXT as TEXT } from '../labels'
import { ExecutionList } from './ExecutionList'
import { WorkRequests } from './WorkRequests'

afterEach(() => vi.unstubAllGlobals())

const block: PermitBlock = {
  unitId: 'T1.travel',
  withheld: 'START',
  since: '2026-10-14T00:00:00Z',
  robots: [
    {
      robotId: 'humanoid-01',
      permit: 'INHIBITED',
      reasons: [
        {
          source: 'cell/guard_closed',
          cause: 'REPORTED',
          subject: 'guard_closed=false',
          resumeBasis: '안전 계통이 guard_closed 을 true 으로 되돌릴 때',
          observedAt: '2026-10-14T00:00:01Z',
        },
      ],
    },
  ],
}

function execution(partial: Partial<Execution>): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-1',
    workMasterId: 'InspectAsset',
    missionVersion: null,
    robotId: 'humanoid-01',
    physicalState: 'RUNNING',
    units: [{ unitId: 'T1.travel', skillType: 'navigate_to', state: 'PENDING', reached: 'NONE' }],
    jobResponse: null,
    ...partial,
  }
}

it('작업 허가로 막힌 실행은 막은 기체와 주체와 풀릴 근거를 운영자 보류와 다른 표시로 보이고 조작 버튼이 없다', () => {
  const held = execution({ executionId: 'exec-2', jobOrderId: 'JO-2', physicalState: 'OPERATOR_HOLD' })
  render(
    <ExecutionList
      read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: block }), held] }, error: null }}
      highlight={null}
    />,
  )
  const notices = screen.getAllByRole('region', { name: TEXT.heading })
  expect(notices).toHaveLength(1)
  const notice = notices[0]
  expect(within(notice).getByText('T1.travel')).toBeVisible()
  expect(within(notice).getByText('단위 출발')).toBeVisible()
  expect(within(notice).getByText('기체 humanoid-01: 막음')).toBeVisible()
  expect(within(notice).getByText(/막는 주체 guard_closed=false/)).toBeVisible()
  expect(within(notice).getByText(/풀릴 근거 안전 계통이 guard_closed 을 true 으로 되돌릴 때/)).toBeVisible()
  expect(within(notice).getByText(/원천 보고/)).toBeVisible()
  expect(within(notice).getByText(TEXT.notOperator)).toBeVisible()
  expect(within(notice).getByText(TEXT.notSafety)).toBeVisible()
  expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
  // 운영자 보류 실행의 행에는 작업 허가 표시가 없다.
  const heldRow = screen.getByRole('row', { name: /exec-2/ })
  expect(within(heldRow).queryByRole('region', { name: TEXT.heading })).not.toBeInTheDocument()
  expect(within(heldRow).getByText('OPERATOR_HOLD')).toBeVisible()
})

it('모름(UNKNOWN)은 까닭과 원천 없음을 그대로 보인다', () => {
  const unknown: PermitBlock = {
    ...block,
    withheld: 'REVISION_UPDATE',
    robots: [{ robotId: 'humanoid-01', permit: 'UNKNOWN', reasons: [{ source: null, cause: 'NOT_COVERED', subject: null, resumeBasis: '이 기체를 덮는 허가 원천이 생길 때', observedAt: null }] }],
  }
  render(<ExecutionList read={{ value: { instanceId: 'mw-1', pumpedAt: 't', executions: [execution({ permitBlock: unknown })] }, error: null }} highlight={null} />)
  const notice = screen.getByRole('region', { name: TEXT.heading })
  expect(within(notice).getByText('개정 갱신')).toBeVisible()
  expect(within(notice).getByText('기체 humanoid-01: 모름(막음)')).toBeVisible()
  expect(within(notice).getByText(/까닭 허가 원천 없음 · 막는 주체 없음/)).toBeVisible()
})

it('작업 요청 상세는 현재 실행의 작업 허가 대기를 보인다', async () => {
  const receipt = { receiptId: 'r1', sourceSystemId: 'ops:manual', externalOrderId: 'M-1', jobOrderId: 'JO-1', state: 'ACCEPTED', detail: null, payload: {}, normalizedOrder: null, submission: null, receivedAt: 't1', updatedAt: 't1' }
  const detail = { receipt, hostRead: 'OK', execution: { executionId: 'exec-1', physicalState: 'RUNNING', robotId: 'humanoid-01', permitBlock: block }, instanceId: 'mw-1', journalBlocked: false, observedAt: 't2' }
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url.startsWith('/api/work-requests?') ? { total: 1, requests: [receipt], offset: 0, limit: 20 } : detail), { status: 200 })))
  render(<WorkRequests session={{ mode: 'operator', user: 'kim' }} />)
  await userEvent.click(await screen.findByRole('button', { name: 'M-1 작업 요청 보기' }))
  const article = await screen.findByRole('article', { name: '작업 요청 상세' })
  const notice = await within(article).findByRole('region', { name: TEXT.heading })
  expect(within(notice).getByText('기체 humanoid-01: 막음')).toBeVisible()
  expect(within(notice).getByText(/막는 주체 guard_closed=false/)).toBeVisible()
})
