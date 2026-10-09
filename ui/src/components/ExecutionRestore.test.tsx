import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Execution, Session } from '../api'
import { executionsView, installFakeOps, restoreRow } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

/** 실행 한 줄(S3a JSON 계약 §5, S4b 계약 H1). 기본은 새로 받은 도는 실행이다. */
function execution(partial: Partial<Execution> = {}): Execution {
  return {
    executionId: 'exec-1',
    jobOrderId: 'JO-20261009-aaaaaaaa',
    workMasterId: 'PrepareSequencedRack',
    missionVersion: 2,
    robotId: 'humanoid-01',
    physicalState: 'RUNNING',
    units: [{ unitId: 'rack-arrival', skillType: 'equipment_wait', state: 'RUNNING', reached: 'E0' }],
    jobResponse: null,
    restoredFrom: null,
    ...partial,
  }
}

const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

function open() {
  return render(<OperationsArea session={operator} onChanged={() => undefined} />)
}

describe('재기동 뒤 실행 목록', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('재기동 복원 보고는 다시 지은 수와 미룬 것·포기한 것의 사유를 실행 목록 위에 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      instanceId: 'mw-1',
      executions: [execution({ restoredFrom: { instanceId: 'mw-0', executionId: 'exec-3' } })],
      restore: {
        at: 'h1',
        rows: [
          restoreRow(),
          restoreRow({
            jobOrderId: 'JO-B',
            robotId: 'quadruped-01',
            previousExecutionId: 'exec-4',
            result: 'DEFERRED',
            executionId: null,
            reason: '기체 스냅숏을 못 읽어 다시 짓지 않는다: robot=quadruped-01, 재작업 횟수와 지난 설비 대기를 정할 근거가 없다',
          }),
          restoreRow({
            jobOrderId: 'JO-C',
            previousExecutionId: 'exec-5',
            result: 'GAVE_UP',
            executionId: null,
            reason: '임무 버전 행이 없다: PrepareSequencedRack 버전 4',
          }),
        ],
      },
    })
    open()
    const region = screen.getByRole('region', { name: '실행' })
    const band = await within(region).findByRole('region', { name: '재기동 복원 보고' })
    expect(band).toHaveTextContent('재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다(복원 시각 h1)')
    expect(within(band).getByRole('list', { name: '다시 지은 실행' })).toHaveTextContent(
      'JO-20261009-aaaaaaaa(humanoid-01): 이전 exec-3 → exec-1',
    )
    expect(band).toHaveTextContent('미룬 실행 1건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다')
    expect(within(band).getByRole('list', { name: '미룬 실행' })).toHaveTextContent(
      'JO-B(quadruped-01, 이전 exec-4): 기체 스냅숏을 못 읽어 다시 짓지 않는다: robot=quadruped-01',
    )
    expect(band).toHaveTextContent('포기한 실행 1건. 다시 짓지 않습니다. 기체의 남은 태스크를 운영자가 확인하십시오')
    expect(within(band).getByRole('list', { name: '포기한 실행' })).toHaveTextContent(
      'JO-C(humanoid-01, 이전 exec-5): 임무 버전 행이 없다: PrepareSequencedRack 버전 4',
    )
    // 띠는 실행 목록 표 위에 있다.
    const table = within(region).getByRole('table', { name: '실행 목록' })
    expect(band.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('다시 지은 실행 행은 실행 id 옆에 이전 exec-k 를 보이고 새로 받은 실행에는 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({
      executions: [
        execution({ restoredFrom: { instanceId: 'mw-0', executionId: 'exec-3' } }),
        execution({ executionId: 'exec-2', jobOrderId: 'JO-NEW' }),
      ],
      restore: { at: 'h1', rows: [restoreRow()] },
    })
    open()
    const table = await screen.findByRole('table', { name: '실행 목록' })
    const [newer, restored] = within(table).getAllByRole('row').slice(1)
    expect(cells(newer)[0]).toBe('exec-2')
    expect(cells(restored)[0]).toBe('exec-1 (이전 exec-3)')
    expect(within(restored).getByTitle('이전 인스턴스 mw-0')).toHaveTextContent('(이전 exec-3)')
  })

  it('다시 지을 것이 없었거나 복원 보고가 없으면 띠가 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.executions = executionsView({ executions: [execution()], restore: { at: 'h1', rows: [] } })
    const { rerender } = open()
    await screen.findByRole('table', { name: '실행 목록' })
    expect(screen.queryByRole('region', { name: '재기동 복원 보고' })).toBeNull()
    expect(screen.queryByText(/이전 exec-/)).toBeNull()

    fake.executions = executionsView({ executions: [execution({ executionId: 'exec-9' })] })
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    await screen.findByRole('cell', { name: 'exec-9' })
    expect(screen.queryByRole('region', { name: '재기동 복원 보고' })).toBeNull()
  })
})
