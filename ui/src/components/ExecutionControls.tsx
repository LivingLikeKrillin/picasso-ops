import { useEffect, useState } from 'react'
import { cancelExecution, confirmRobotEmpty, fetchResidualHolds } from '../api'
import type { Delivered, Execution, ExecutionCancelOutcome, ResidualHold, ResidualHoldsView, RobotEmptyOutcome, Session } from '../api'
import { CANCEL_RESULT_LABEL, EXECUTION_CONTROL_TEXT as TEXT, kindLabel, rejectionText } from '../labels'
import type { HostRead } from './ExecutionList'

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

/** 더 돌지 않는 물리 상태(picasso `PhysicalState.pumps` 가 거짓). 취소 중인 실행도 다시 취소하지 않는다. */
const NOT_CANCELABLE = new Set(['PHYSICALLY_DONE', 'UNVERIFIED', 'FAILED', 'ABORTED', 'CANCELING'])

/** 화면이 취소를 권할 실행. 실행 호스트가 다시 판정하므로 이것은 고르기 편의다. */
const cancelable = (execution: Execution) => !NOT_CANCELABLE.has(execution.physicalState) && execution.cancel == null

interface CancelProps {
  instanceId: string
  executions: Execution[]
  session: Session
  /** 조작이 끝나면 부른다. 실행 목록을 지금 한 번, pump 뒤 한 번 더 읽는다. */
  onCanceled: () => void
}

/**
 * 운영자 실행 취소(P0-b2). 운영자 모드에서만 조작한다. 승인자는 운영 서비스가 행위자로 정하므로 화면이 고르지 않는다(ADR 43). 사유와
 * 현장 확인이 있어야 보낸다. 인스턴스는 실행 목록의 것이고, 실행 호스트가 그 사이 재기동했으면 409 로 막힌다.
 */
export function CancelExecutionForm({ instanceId, executions, session, onCanceled }: CancelProps) {
  const [target, setTarget] = useState('')
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<ExecutionCancelOutcome> } | null>(null)
  const choices = executions.filter(cancelable)
  if (session.mode !== 'operator') return choices.length > 0 ? <p>{TEXT.cancelOperatorOnly}</p> : null

  const chosen = choices.find((execution) => execution.executionId === target) ?? null
  const send = () => {
    if (chosen === null || reason.trim() === '' || !confirmed) return
    const what = `${chosen.executionId}(${chosen.jobOrderId}) ${TEXT.cancelHeading}`
    setBusy(true)
    cancelExecution(session, { instanceId, executionId: chosen.executionId }, reason.trim())
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        setReason('')
        setConfirmed(false)
        setTarget('')
        onCanceled()
      })
  }

  return (
    <form aria-label={TEXT.cancelHeading} onSubmit={(event) => event.preventDefault()}>
      <h3>{TEXT.cancelHeading}</h3>
      {last !== null && (
        <p role="status" aria-label="실행 취소 결과">
          {last.what}: {describeCancel(last.sent)}
        </p>
      )}
      {choices.length === 0 ? null : (
        <>
          <p>{TEXT.cancelWarning}</p>
          <p>{TEXT.partialNote}</p>
          <label>
            {TEXT.cancelTarget}
            <select value={target} disabled={busy} onChange={(event) => setTarget(event.target.value)}>
              <option value="">{TEXT.cancelChoose}</option>
              {choices.map((execution) => (
                <option key={execution.executionId} value={execution.executionId}>
                  {execution.executionId} · {execution.jobOrderId} · {execution.robotId} · {execution.physicalState}
                </option>
              ))}
            </select>
          </label>{' '}
          <label>
            {TEXT.cancelReason}
            <input maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
          </label>{' '}
          <label>
            <input type="checkbox" checked={confirmed} disabled={busy} onChange={(event) => setConfirmed(event.target.checked)} />
            {TEXT.cancelConfirm}
          </label>{' '}
          <button type="button" disabled={busy || chosen === null || reason.trim() === '' || !confirmed} onClick={send}>
            {TEXT.cancelButton}
          </button>
        </>
      )}
    </form>
  )
}

function describeCancel(sent: Delivered<ExecutionCancelOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 실행 목록에서 확인하십시오`
  const { outcome, answer, rejection, confirmation } = sent.outcome
  if (outcome !== null) {
    const label = CANCEL_RESULT_LABEL[outcome] ?? outcome
    return outcome !== 'CANCELED' && answer?.detail ? `${label}. ${answer.detail}` : label
  }
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 취소가 걸림'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 취소가 걸리지 않음. 실행 목록에서 확인하십시오'
    default:
      return '응답 없음. 취소가 걸렸을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 실행 목록에서 확인하십시오'
  }
}

interface HoldsProps {
  session: Session
  /** 주기와 조작마다 올라간다. 오르면 다시 읽는다. */
  tick: number
  /** 빈손 확인이 끝나면 부른다. 배정 가능과 조작 기록을 다시 읽게 한다. */
  onConfirmed: () => void
}

/**
 * 미해소 잔여 파지 표시와 기체 빈손 확인(P0-b2). 표시가 있는 기체는 실행 호스트 판정이 배정에서 뺀다(배정 가능 표의 이유 칸에도
 * 보인다). 확인은 물리 확인이라 사유와 현장 확인 체크가 있어야 보낸다. 운영자 모드에서만 조작한다. 못 읽으면 직전 값을 지우지 않는다.
 */
export function ResidualHolds({ session, tick, onConfirmed }: HoldsProps) {
  const [read, setRead] = useState<HostRead<ResidualHoldsView>>({ value: null, error: null })
  const [selected, setSelected] = useState<number | null>(null)
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<RobotEmptyOutcome> } | null>(null)
  const [own, setOwn] = useState(0)

  useEffect(() => {
    let alive = true
    fetchResidualHolds(session)
      .then((value) => {
        if (alive) setRead({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setRead((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick, own])

  // 운영 서비스가 다른 모양을 주면 목록이 없다. 없는 목록도 모름이다.
  const holds = read.value !== null && Array.isArray(read.value.holds) ? read.value.holds : null
  const chosen = holds?.find((hold) => hold.markerId === selected) ?? null

  const send = (hold: ResidualHold) => {
    if (reason.trim() === '' || !confirmed) return
    const what = `${hold.robotId} ${TEXT.emptyButton}(${hold.jobOrderId} ${hold.unitId})`
    setBusy(true)
    confirmRobotEmpty(session, hold, reason.trim())
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        setReason('')
        setConfirmed(false)
        setSelected(null)
        setOwn((value) => value + 1)
        onConfirmed()
      })
  }

  return (
    <section aria-label={TEXT.holdsHeading}>
      <h3>{TEXT.holdsHeading}</h3>
      <p>{TEXT.holdsIntro}</p>
      {last !== null && (
        <p role="status" aria-label="빈손 확인 결과">
          {last.what}: {describeEmpty(last.sent)}
        </p>
      )}
      {holds === null ? (
        <p>
          {TEXT.holdsUnknown}
          {read.error !== null && ` (${read.error})`}
        </p>
      ) : (
        <>
          {read.error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {read.error}</p>}
          {holds.length === 0 ? (
            <p>{TEXT.holdsEmpty}</p>
          ) : (
            <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤">
              <table aria-label="미해소 잔여 파지">
                <thead>
                  <tr>
                    <th>기체</th>
                    <th>작업 지시</th>
                    <th>실행</th>
                    <th>단위</th>
                    <th>든 것</th>
                    <th>기록 시각</th>
                    <th>확인</th>
                  </tr>
                </thead>
                <tbody>
                  {holds.map((hold) => (
                    <tr key={hold.markerId} className={hold.markerId === selected ? 'selected' : undefined}>
                      <td>{hold.robotId}</td>
                      <td>{hold.jobOrderId}</td>
                      <td title={`인스턴스 ${hold.instanceId}`}>{hold.executionId}</td>
                      <td>{hold.unitId}</td>
                      <td>{hold.objectRef ?? TEXT.objectUnnamed}</td>
                      <td>{hold.recordedAt}</td>
                      <td>
                        {session.mode === 'operator' ? (
                          <button type="button" disabled={busy} onClick={() => { setSelected(hold.markerId); setReason(''); setConfirmed(false) }}>
                            {TEXT.emptyButton}
                          </button>
                        ) : (
                          '운영자 모드'
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {session.mode !== 'operator' && holds.length > 0 && <p>{TEXT.emptyOperatorOnly}</p>}
          {session.mode === 'operator' && chosen !== null && (
            <form aria-label={TEXT.emptyButton} onSubmit={(event) => event.preventDefault()}>
              <p>
                {chosen.robotId}: {chosen.jobOrderId} {chosen.unitId}({chosen.objectRef ?? TEXT.objectUnnamed}). 확인자는 {session.user}입니다.
              </p>
              <p role="note">{TEXT.emptyWarning}</p>
              <label>
                {TEXT.emptyReason}
                <input maxLength={1000} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} />
              </label>{' '}
              <label>
                <input type="checkbox" checked={confirmed} disabled={busy} onChange={(event) => setConfirmed(event.target.checked)} />
                {TEXT.emptyConfirm}
              </label>{' '}
              <button type="button" disabled={busy || reason.trim() === '' || !confirmed} onClick={() => send(chosen)}>
                {TEXT.emptyButton}
              </button>
            </form>
          )}
        </>
      )}
    </section>
  )
}

function describeEmpty(sent: Delivered<RobotEmptyOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 잔여 파지 표에서 확인하십시오`
  const { result, rejection, confirmation } = sent.outcome
  if (result === 'SUCCEEDED') return TEXT.emptyDone
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 표시가 해소됨'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 표시가 그대로임. 잔여 파지 표에서 확인하십시오'
    default:
      return '응답 없음. 해소됐을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 잔여 파지 표에서 확인하십시오'
  }
}
