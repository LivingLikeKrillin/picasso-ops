import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Execution, ExecutionCancelOutcome, ResidualHold, RobotEmptyOutcome, Session } from '../api'
import { CANCEL_RESULT_LABEL, EXECUTION_CONTROL_TEXT as TEXT } from '../labels'
import { executionsView, installFakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const operator: Session = { mode: 'operator', user: 'kim' }
const engineer: Session = { mode: 'engineer', user: 'lee' }
const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }
const CANCEL = '/api/executions/exec-1/cancel'
const CLEAR = '/api/robots/humanoid-01/residual-holds/7/clear'

function execution(partial: Partial<Execution> = {}): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-1',
    workMasterId: 'PrepareSequencedRack',
    missionVersion: null,
    robotId: 'humanoid-01',
    physicalState: 'PARTIAL',
    units: [{ unitId: 'RACK-204.S01', skillType: 'pick_place', state: 'DONE', reached: 'E0' }],
    jobResponse: null,
    restoredFrom: null,
    cancel: null,
    ...partial,
  }
}

function hold(partial: Partial<ResidualHold> = {}): ResidualHold {
  return {
    markerId: 7,
    robotId: 'humanoid-01',
    jobOrderId: 'JO-1',
    executionId: 'exec-1',
    instanceId: 'mw-1',
    unitId: 'RACK-204.S02',
    objectRef: 'C-1',
    recordedAt: '2026-10-13T09:00:00Z',
    ...partial,
  }
}

function canceled(partial: Partial<ExecutionCancelOutcome> = {}): ExecutionCancelOutcome {
  return {
    requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
    executionId: 'exec-1',
    result: 'SUCCEEDED',
    confirmation: null,
    outcome: 'CANCELED',
    answer: { result: 'CANCELED', executionId: 'exec-1', physicalState: 'PARTIAL', detail: null, requestId: null },
    rejection: null,
    ...partial,
  }
}

function emptied(partial: Partial<RobotEmptyOutcome> = {}): RobotEmptyOutcome {
  return {
    requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
    robotId: 'humanoid-01',
    markerId: 7,
    result: 'SUCCEEDED',
    confirmation: null,
    answer: {},
    rejection: null,
    ...partial,
  }
}

const open = (session: Session = operator) => render(<OperationsArea session={session} onChanged={() => undefined} />)
const posts = (fake: ReturnType<typeof installFakeOps>, path: string) => fake.calls.filter((call) => call.method === 'POST' && call.url === path)

describe('운영자 실행 취소', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('사유와 현장 확인이 있어야 보내고 승인자 없이 인스턴스와 사유만 싣는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    fake.answers.set(CANCEL, { status: 200, body: canceled() })
    open()
    const form = await screen.findByRole('form', { name: TEXT.cancelHeading })
    const button = within(form).getByRole('button', { name: TEXT.cancelButton })
    expect(form).toHaveTextContent(TEXT.partialNote)
    expect(button).toBeDisabled()
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.cancelTarget), 'exec-1')
    await userEvent.type(within(form).getByLabelText(TEXT.cancelReason), ' 둘째 슬롯 결품 ')
    expect(button).toBeDisabled()
    await userEvent.click(within(form).getByLabelText(TEXT.cancelConfirm))
    await userEvent.click(button)

    expect(posts(fake, CANCEL).map((call) => call.body)).toEqual([{ instanceId: 'mw-1', reason: '둘째 슬롯 결품' }])
    expect(await within(form).findByRole('status', { name: '실행 취소 결과' })).toHaveTextContent(
      `exec-1(JO-1) ${TEXT.cancelHeading}: ${CANCEL_RESULT_LABEL.CANCELED}`,
    )
  })

  it('정착했거나 이미 취소를 건 실행은 고를 수 없고 건 취소는 물리 상태 칸에 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      executions: [
        execution({ executionId: 'exec-1', physicalState: 'ABORTED' }),
        execution({
          executionId: 'exec-2',
          jobOrderId: 'JO-2',
          physicalState: 'CANCELING',
          cancel: { approverId: 'kim', approverKind: 'PERSON', reason: '결품', physicalState: 'PARTIAL', recordedAt: 't2' },
        }),
        execution({ executionId: 'exec-3', jobOrderId: 'JO-3', physicalState: 'RUNNING' }),
      ],
    })
    open()
    const form = await screen.findByRole('form', { name: TEXT.cancelHeading })
    const options = within(within(form).getByLabelText(TEXT.cancelTarget)).getAllByRole('option').map((option) => option.getAttribute('value'))
    expect(options).toEqual(['', 'exec-3'])
    const table = screen.getByRole('table', { name: '실행 목록' })
    expect(within(table).getByText(/취소 요청: kim, 결품/)).toBeInTheDocument()
  })

  it('실행 호스트의 4xx 거부는 그 이름으로 알린다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    fake.answers.set(CANCEL, {
      status: 200,
      body: canceled({ result: 'REJECTED', outcome: null, answer: null, rejection: { status: 409, error: 'INSTANCE_MISMATCH', detail: '인스턴스가 다르다' } }),
    })
    open()
    const form = await screen.findByRole('form', { name: TEXT.cancelHeading })
    await userEvent.selectOptions(within(form).getByLabelText(TEXT.cancelTarget), 'exec-1')
    await userEvent.type(within(form).getByLabelText(TEXT.cancelReason), '닫기')
    await userEvent.click(within(form).getByLabelText(TEXT.cancelConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.cancelButton }))
    expect(await within(form).findByRole('status', { name: '실행 취소 결과' })).toHaveTextContent('실행 호스트가 재기동해 인스턴스가 다름')
  })

  it('엔지니어 모드에는 취소 폼이 없고 운영자 모드에서 한다는 안내만 있다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()] })
    open(engineer)
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByRole('form', { name: TEXT.cancelHeading })).toBeNull()
    expect(screen.getByText(TEXT.cancelOperatorOnly)).toBeInTheDocument()
  })
})

describe('잔여 파지 확인', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('표시를 보이고 물리 확인 체크와 사유가 있어야 기체 빈손 확인을 보낸다', async () => {
    const fake = installFakeOps(emptyList)
    fake.residualHolds = { holds: [hold()] }
    fake.answers.set(CLEAR, { status: 200, body: emptied() })
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    const table = await within(region).findByRole('table', { name: '미해소 잔여 파지' })
    const row = within(table).getAllByRole('row')[1]
    expect(within(row).getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'humanoid-01', 'JO-1', 'exec-1', 'RACK-204.S02', 'C-1', '2026-10-13T09:00:00Z', TEXT.emptyButton,
    ])
    await userEvent.click(within(row).getByRole('button', { name: TEXT.emptyButton }))
    const form = within(region).getByRole('form', { name: TEXT.emptyButton })
    expect(within(form).getByRole('note')).toHaveTextContent(TEXT.emptyWarning)
    const send = within(form).getByRole('button', { name: TEXT.emptyButton })
    await userEvent.type(within(form).getByLabelText(TEXT.emptyReason), '그리퍼 비어 있음 육안 확인')
    expect(send).toBeDisabled()
    await userEvent.click(within(form).getByLabelText(TEXT.emptyConfirm))
    fake.residualHolds = { holds: [] }
    await userEvent.click(send)

    expect(posts(fake, CLEAR).map((call) => call.body)).toEqual([{ reason: '그리퍼 비어 있음 육안 확인' }])
    expect(await within(region).findByRole('status', { name: '빈손 확인 결과' })).toHaveTextContent(TEXT.emptyDone)
    expect(await within(region).findByText(TEXT.holdsEmpty)).toBeInTheDocument()
  })

  it('이미 해소한 표시는 그 이름으로 알리고 엔지니어 모드에는 확인 버튼이 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.residualHolds = { holds: [hold({ objectRef: null })] }
    fake.answers.set(CLEAR, {
      status: 200,
      body: emptied({ result: 'REJECTED', rejection: { status: 409, error: 'RESIDUAL_HOLD_ALREADY_CLEARED', detail: '이미 해소했다' } }),
    })
    const { unmount } = open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    expect(await within(region).findByText(TEXT.objectUnnamed)).toBeInTheDocument()
    await userEvent.click(within(region).getByRole('button', { name: TEXT.emptyButton }))
    const form = within(region).getByRole('form', { name: TEXT.emptyButton })
    await userEvent.type(within(form).getByLabelText(TEXT.emptyReason), '확인')
    await userEvent.click(within(form).getByLabelText(TEXT.emptyConfirm))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.emptyButton }))
    expect(await within(region).findByRole('status', { name: '빈손 확인 결과' })).toHaveTextContent('이미 해소한 잔여 파지 표시')
    unmount()

    open(engineer)
    const read = await screen.findByRole('region', { name: TEXT.holdsHeading })
    await within(read).findByRole('table', { name: '미해소 잔여 파지' })
    expect(within(read).queryByRole('button', { name: TEXT.emptyButton })).toBeNull()
    expect(within(read).getByText(TEXT.emptyOperatorOnly)).toBeInTheDocument()
  })

  it('표시가 없으면 없다고 보인다', async () => {
    installFakeOps(emptyList)
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    expect(await within(region).findByText(TEXT.holdsEmpty)).toBeInTheDocument()
  })

  it('표시를 못 읽으면 모름이고 실행 호스트 불통 사유를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/robots/residual-holds')
    open()
    const region = await screen.findByRole('region', { name: TEXT.holdsHeading })
    expect(await within(region).findByText(/^모름: 잔여 파지 표시를 아직 읽지 못했습니다 \(실행 호스트가 답하지 않는다/)).toBeInTheDocument()
  })
})
