import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Execution, Session } from '../api'
import { POLL_MS } from '../poll'
import { executionsView, installFakeOps, jobResponseRow, jobResponsesView } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

const responseGets = (fake: FakeOps) =>
  fake.calls.filter((call) => call.method === 'GET' && call.url.startsWith('/api/job-responses')).map((call) => call.url)

function execution(executionId: string, jobOrderId: string): Execution {
  return {
    executionId,
    jobOrderId,
    workMasterId: 'PrepareSequencedRack',
    missionVersion: 2,
    robotId: 'humanoid-01',
    physicalState: 'RUNNING',
    units: [],
    jobResponse: null,
    restoredFrom: null,
  }
}

/** 재기동 앞뒤의 같은 보류 응답 둘과 다른 작업 지시의 완료 응답 하나. 최근에 적은 것부터다. */
function threeResponses() {
  return jobResponsesView([
    jobResponseRow({ instanceId: 'mw-1', disposition: 'RESTART_DUPLICATE', recordedAt: 'w40' }),
    jobResponseRow({
      instanceId: 'mw-0',
      jobResponseId: 'resp-2',
      jobOrderId: 'JO-B',
      executionId: 'exec-2',
      physicalState: 'PHYSICALLY_DONE',
      reachedEvidence: 'E2',
      completedUnits: ['RACK-204.S01', 'rack-arrival'],
      incompleteUnits: [],
      operatorRequired: false,
      recordedAt: 'w35',
    }),
    jobResponseRow({ instanceId: 'mw-0', recordedAt: 'w30' }),
  ])
}

function open(session: Session = operator) {
  return render(<OperationsArea session={session} onChanged={() => undefined} />)
}

describe('작업 응답 송신 기록', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('송신 행과 재기동 중복 행을 최근 것부터 단위와 처분과 함께 보이고 재기동 중복 수를 센다', async () => {
    const fake = installFakeOps(emptyList)
    fake.jobResponses = { ...threeResponses(), total: 7 }
    open()
    const region = screen.getByRole('region', { name: '작업 응답 송신 기록' })
    const table = await within(region).findByRole('table', { name: '송신 기록 목록' })
    expect(region).toHaveTextContent('실행 호스트 인스턴스 mw-1, 송신 기록 7건 가운데 최신 3건, 그 가운데 재기동 중복 1건')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map(cells)).toEqual([
      ['mw-1', 'resp-1', 'JO-20261009-aaaaaaaa', 'exec-1', '1', 'PARTIAL', 'E0/E2', '미완 rack-arrival', '필요', '재기동 중복(송신 안 함)', 'w40'],
      ['mw-0', 'resp-2', 'JO-B', 'exec-2', '1', 'PHYSICALLY_DONE', 'E2/E2', '완료 RACK-204.S01, rack-arrival', '-', '송신', 'w35'],
      ['mw-0', 'resp-1', 'JO-20261009-aaaaaaaa', 'exec-1', '1', 'PARTIAL', 'E0/E2', '미완 rack-arrival', '필요', '송신', 'w30'],
    ])
    expect(rows[0]).toHaveClass('duplicate')
    expect(rows[1]).not.toHaveClass('duplicate')
    expect(responseGets(fake)).toEqual(['/api/job-responses'])
  })

  it('작업 지시를 고르면 그 작업 지시로 다시 읽고 전체를 고르면 쿼리 없이 읽으며 두 모드가 같다', async () => {
    const fake = installFakeOps(emptyList)
    fake.jobResponses = threeResponses()
    fake.executions = executionsView({ executions: [execution('exec-1', 'JO-20261009-aaaaaaaa'), execution('exec-3', 'JO-NEW')] })
    open(engineer)
    const region = screen.getByRole('region', { name: '작업 응답 송신 기록' })
    await within(region).findByRole('table', { name: '송신 기록 목록' })
    const choose = within(region).getByLabelText('송신 기록의 작업 지시')
    // 실행 목록의 작업 지시(최신부터)와 읽은 송신 기록의 작업 지시를 고를 수 있다.
    expect(within(choose).getAllByRole('option').map((option) => option.textContent)).toEqual([
      '전체',
      'JO-NEW',
      'JO-20261009-aaaaaaaa',
      'JO-B',
    ])
    await userEvent.selectOptions(choose, 'JO-B')
    expect(await within(region).findByText(/송신 기록 1건 가운데 최신 1건, 그 가운데 재기동 중복 0건/)).toBeInTheDocument()
    expect(within(within(region).getByRole('table', { name: '송신 기록 목록' })).getAllByRole('row')).toHaveLength(2)
    expect(responseGets(fake).at(-1)).toBe('/api/job-responses?jobOrderId=JO-B')
    await userEvent.selectOptions(choose, '전체')
    expect(await within(region).findByText(/송신 기록 3건 가운데 최신 3건/)).toBeInTheDocument()
    expect(responseGets(fake).at(-1)).toBe('/api/job-responses')
  })

  it('주기마다 다시 읽고 실행 호스트가 503 이면 직전 값과 불통을, 한 번도 못 읽으면 모름을 보인다', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    fake.jobResponses = threeResponses()
    const flush = async () => {
      for (let round = 0; round < 5; round++) await act(async () => undefined)
    }
    open()
    await flush()
    const region = screen.getByRole('region', { name: '작업 응답 송신 기록' })
    expect(within(region).getByRole('table', { name: '송신 기록 목록' })).toBeInTheDocument()
    const before = responseGets(fake).length
    fake.failing.add('/api/job-responses')
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await flush()
    expect(responseGets(fake)).toHaveLength(before + 1)
    expect(region).toHaveTextContent('직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException')
    expect(within(region).getAllByRole('row')).toHaveLength(4)
  })

  it('송신 기록을 한 번도 못 읽으면 모름이고 행이 없으면 없음이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/job-responses')
    const { unmount } = open()
    expect(
      await screen.findByText(
        '모름: 송신 기록을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: 응답 없음: ConnectException)',
      ),
    ).toBeInTheDocument()
    unmount()
    fake.failing.delete('/api/job-responses')
    open()
    expect(await screen.findByText('송신 기록이 없습니다')).toBeInTheDocument()
  })

  it('운영 영역을 열기 전에는 송신 기록을 읽지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(responseGets(fake)).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '운영' }))
    expect(await screen.findByText('송신 기록이 없습니다')).toBeInTheDocument()
    expect(responseGets(fake).length).toBeGreaterThan(0)
  })
})
