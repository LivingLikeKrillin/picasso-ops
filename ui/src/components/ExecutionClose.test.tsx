import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Session } from '../api'
import { CLOSE_BASIS_LABEL, CLOSE_RESULT_LABEL, EXECUTION_CONTROL_TEXT as TEXT, UNRESTORED_TEXT } from '../labels'
import { closeOutcome, closeRecord, closeRefused, executionsView, installFakeOps, unrestoredRow } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const operator: Session = { mode: 'operator', user: 'kim' }
const engineer: Session = { mode: 'engineer', user: 'lee' }
const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }
const CLOSE = '/api/job-orders/JO-20261009-cccccccc/close'

const open = (session: Session = operator) => render(<OperationsArea session={session} onChanged={() => undefined} />)
const posts = (fake: ReturnType<typeof installFakeOps>, path: string) => fake.calls.filter((call) => call.method === 'POST' && call.url === path)

/** 복원 못 한 실행 행을 실행 목록 페이지에 싣는다(결함 14). */
function withRows(...rows: ReturnType<typeof unrestoredRow>[]) {
  const fake = installFakeOps(emptyList)
  fake.executions = executionsView()
  fake.recent = { settled: [], nextBefore: null, olderTotal: 0, unrestored: rows }
  return fake
}

async function openForm(jobOrderId = 'JO-20261009-cccccccc') {
  const section = await screen.findByRole('region', { name: UNRESTORED_TEXT.heading })
  const row = within(within(section).getByRole('table', { name: UNRESTORED_TEXT.table })).getAllByRole('row').find((candidate) => within(candidate).queryByText(jobOrderId))!
  await userEvent.click(within(row).getByRole('button', { name: UNRESTORED_TEXT.closeButton }))
  return screen.getByRole('form', { name: TEXT.closeHeading })
}

describe('복원 못 한 실행 종결', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('종결할 수 있는 행은 작업 지시, 기체, 이전 실행, 결과, 사유를 보이고 종결하기를 눌러야 폼이 열린다', async () => {
    withRows(unrestoredRow())
    open()
    const section = await screen.findByRole('region', { name: UNRESTORED_TEXT.heading })
    const [row] = within(within(section).getByRole('table', { name: UNRESTORED_TEXT.table })).getAllByRole('row').slice(1)
    expect(within(row).getAllByRole('cell').slice(0, 5).map((cell) => cell.textContent)).toEqual([
      'JO-20261009-cccccccc',
      'humanoid-01',
      'exec-7',
      UNRESTORED_TEXT.result.GAVE_UP,
      '판단 기록을 읽지 못해 리빌드하지 않는다: record=3, 형식 오류',
    ])
    expect(within(row).getByTitle('인스턴스 mw-0')).toHaveTextContent('exec-7')
    expect(screen.queryByRole('form', { name: TEXT.closeHeading })).toBeNull()
    const form = await openForm()
    expect(form).toHaveTextContent(TEXT.closeWarning)
    expect(form).not.toHaveTextContent(TEXT.closeDeferredNote)
  })

  it('사유가 있어야 보내고 증언을 체크하지 않으면 attestedStopped 는 거짓이며 사유와 종결할 실행을 싣는다', async () => {
    const fake = withRows(unrestoredRow())
    fake.answers.set(CLOSE, { status: 200, body: closeOutcome() })
    open()
    const form = await openForm()
    const button = within(form).getByRole('button', { name: TEXT.closeButton })
    expect(button).toBeDisabled()
    await userEvent.type(within(form).getByLabelText(TEXT.closeReason), '  현장에서 정지를 확인했다 ')
    expect(button).toBeEnabled()
    await userEvent.click(button)

    expect(posts(fake, CLOSE).map((call) => call.body)).toEqual([
      { executionInstanceId: 'mw-0', executionId: 'exec-7', reason: '현장에서 정지를 확인했다', attestedStopped: false },
    ])
    expect(await screen.findByRole('status', { name: TEXT.closeResultAria })).toHaveTextContent(
      `JO-20261009-cccccccc: ${CLOSE_RESULT_LABEL.CLOSED}(${CLOSE_BASIS_LABEL.SNAPSHOT})`,
    )
    // 종결했으면 사유 칸을 비운다.
    expect(within(form).getByLabelText(TEXT.closeReason)).toHaveValue('')
  })

  it('종결해서 목록을 다시 읽으면 그 행과 폼이 사라져도 종결 결과 줄은 남는다', async () => {
    const fake = withRows(unrestoredRow())
    fake.answers.set(CLOSE, { status: 200, body: closeOutcome() })
    open()
    const form = await openForm()
    await userEvent.type(within(form).getByLabelText(TEXT.closeReason), '현장에서 정지를 확인했다')
    // 종결하면 다음 읽기에서 그 행이 목록에서 빠진다.
    fake.recent = { ...fake.recent, unrestored: [] }
    await userEvent.click(within(form).getByRole('button', { name: TEXT.closeButton }))

    await waitFor(() => expect(screen.queryByRole('region', { name: UNRESTORED_TEXT.heading })).toBeNull())
    expect(screen.queryByRole('form', { name: TEXT.closeHeading })).toBeNull()
    expect(screen.getByRole('status', { name: TEXT.closeResultAria })).toHaveTextContent(
      `JO-20261009-cccccccc: ${CLOSE_RESULT_LABEL.CLOSED}(${CLOSE_BASIS_LABEL.SNAPSHOT})`,
    )
  })

  it('종결해서 그 행이 종결할 수 없는 행으로 바뀌어도 종결 결과 줄은 남는다', async () => {
    const fake = withRows(unrestoredRow({ result: 'DEFERRED' }))
    fake.answers.set(CLOSE, { status: 200, body: closeOutcome({ answer: { ...closeOutcome().answer!, basis: 'ATTESTED' } }) })
    open()
    const form = await openForm()
    await userEvent.type(within(form).getByLabelText(TEXT.closeReason), '현장에서 정지를 봤다')
    await userEvent.click(within(form).getByLabelText(TEXT.closeAttest))
    fake.recent = {
      ...fake.recent,
      unrestored: [unrestoredRow({ result: 'CLOSED', closable: false, close: closeRecord({ basis: 'ATTESTED', attestedStopped: true }) })],
    }
    await userEvent.click(within(form).getByRole('button', { name: TEXT.closeButton }))

    await waitFor(() => expect(screen.queryByRole('form', { name: TEXT.closeHeading })).toBeNull())
    expect(screen.getByRole('status', { name: TEXT.closeResultAria })).toHaveTextContent(CLOSE_BASIS_LABEL.ATTESTED)
  })

  it('증언 체크는 attestedStopped 참으로 간다', async () => {
    const fake = withRows(unrestoredRow({ result: 'DEFERRED', reason: '기체 스냅숏을 못 읽어 리빌드하지 않는다: robot=humanoid-01' }))
    fake.answers.set(CLOSE, { status: 200, body: closeOutcome({ answer: { ...closeOutcome().answer!, basis: 'ATTESTED' } }) })
    open()
    const form = await openForm()
    expect(form).toHaveTextContent(TEXT.closeDeferredNote)
    await userEvent.type(within(form).getByLabelText(TEXT.closeReason), '현장에서 정지를 봤다')
    await userEvent.click(within(form).getByLabelText(TEXT.closeAttest))
    await userEvent.click(within(form).getByRole('button', { name: TEXT.closeButton }))
    expect(posts(fake, CLOSE).map((call) => call.body)).toEqual([
      { executionInstanceId: 'mw-0', executionId: 'exec-7', reason: '현장에서 정지를 봤다', attestedStopped: true },
    ])
    expect(await screen.findByRole('status', { name: TEXT.closeResultAria })).toHaveTextContent(CLOSE_BASIS_LABEL.ATTESTED)
  })

  it('살아 있는 다른 인스턴스가 든 행은 까닭을 보이고 종결하기 버튼이 없다', async () => {
    withRows(unrestoredRow({ result: 'DEFERRED', closable: false, notClosableReason: '살아 있는 다른 인스턴스가 든 실행이라 리빌드하지 않는다: instance=mw-other' }))
    open()
    const section = await screen.findByRole('region', { name: UNRESTORED_TEXT.heading })
    expect(section).toHaveTextContent('instance=mw-other')
    expect(within(section).queryByRole('button', { name: UNRESTORED_TEXT.closeButton })).toBeNull()
    expect(screen.queryByRole('form', { name: TEXT.closeHeading })).toBeNull()
  })

  it('기체 스냅숏을 못 읽었거나 기체가 도는 중이라는 호스트의 답은 사유와 함께 그대로 보이고 사유 칸을 지우지 않는다', async () => {
    const fake = withRows(unrestoredRow())
    open()
    const form = await openForm()
    await userEvent.type(within(form).getByLabelText(TEXT.closeReason), '정지를 봤다')

    fake.answers.set(CLOSE, { status: 200, body: closeRefused('STOP_UNCONFIRMED', '기체 스냅숏을 읽지 못했다. 로봇이 멈춘 것을 현장에서 확인해 증언해야 운영자 종결한다') })
    await userEvent.click(within(form).getByRole('button', { name: TEXT.closeButton }))
    const status = await screen.findByRole('status', { name: TEXT.closeResultAria })
    expect(status).toHaveTextContent(CLOSE_RESULT_LABEL.STOP_UNCONFIRMED)
    expect(status).toHaveTextContent('기체 스냅숏을 읽지 못했다')
    expect(within(form).getByLabelText(TEXT.closeReason)).toHaveValue('정지를 봤다')

    fake.answers.set(CLOSE, { status: 200, body: closeRefused('TASK_LIVE', '기체가 해당 작업 지시의 태스크를 실행하고 있다: JO-20261009-cccccccc#T1=TASK_STATE_RUNNING') })
    await userEvent.click(within(form).getByRole('button', { name: TEXT.closeButton }))
    expect(await screen.findByText(/TASK_STATE_RUNNING/)).toBeInTheDocument()
    expect(screen.getByRole('status', { name: TEXT.closeResultAria })).toHaveTextContent(CLOSE_RESULT_LABEL.TASK_LIVE)
    expect(posts(fake, CLOSE)).toHaveLength(2)
  })

  it('정지 증언으로 종결했으나 스냅숏이 아직 동의하지 않은 행은 승인자와 사유를 보이고 배정 제외를 알리며 종결하기 버튼이 없다', async () => {
    withRows(
      unrestoredRow({
        result: 'CLOSED',
        closable: false,
        close: closeRecord({ basis: 'ATTESTED', attestedStopped: true, reason: '현장에서 정지를 봤다' }),
      }),
    )
    open()
    const section = await screen.findByRole('region', { name: UNRESTORED_TEXT.heading })
    expect(section).toHaveTextContent(UNRESTORED_TEXT.closedBy('kim', '현장에서 정지를 봤다'))
    expect(section).toHaveTextContent(UNRESTORED_TEXT.excludedUntilSnapshot)
    expect(within(section).queryByRole('button', { name: UNRESTORED_TEXT.closeButton })).toBeNull()
  })

  it('엔지니어 모드에는 종결 폼이 없고 운영자 모드에서 한다는 안내만 있다', async () => {
    withRows(unrestoredRow())
    open(engineer)
    const section = await screen.findByRole('region', { name: UNRESTORED_TEXT.heading })
    expect(within(section).queryByRole('button', { name: UNRESTORED_TEXT.closeButton })).toBeNull()
    expect(section).toHaveTextContent(UNRESTORED_TEXT.operatorOnly)
  })

  it('복원 못 한 실행이 없으면 구역이 없다', async () => {
    withRows()
    open()
    await screen.findByText('실행이 없습니다')
    expect(screen.queryByRole('region', { name: UNRESTORED_TEXT.heading })).toBeNull()
  })
})
