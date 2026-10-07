import { useState } from 'react'
import type { Adapter, Mode, RevisionView, RobotView } from '../api'
import { CONNECTION_LABEL } from '../labels'
import { CommissioningCards } from './CommissioningCards'
import { FindingCard } from './FindingCard'

interface Props {
  view: RobotView
  mode: Mode
  busy: boolean
  onRetire: (reason: string) => void
  onReinstate: () => void
  adapters: Adapter[]
  revisions: RevisionView[]
  onBind: (adapterVersionId: number, profileRevisionId: number) => void
  onRecordSiteNames: () => void
}

/**
 * 기체 상세. 상태 2칸(원장 상태, 연결)을 합치지 않고 따로 보인다(스펙 §7.3). 퇴역·복귀는 운영자 모드에서 한다(스펙 §8).
 * 퇴역 사유는 필수다. 사유가 비면 요청을 보내지 않는다.
 */
export function RobotDetail({
  view,
  mode,
  busy,
  onRetire,
  onReinstate,
  adapters,
  revisions,
  onBind,
  onRecordSiteNames,
}: Props) {
  const [reason, setReason] = useState('')
  const { robot } = view
  const retired = robot.status === 'RETIRED'
  return (
    <section aria-label={`${robot.robotId} 상세`}>
      <h3>{robot.robotId}</h3>
      <dl>
        <dt>원장 상태</dt>
        <dd>{robot.status}</dd>
        <dt>연결</dt>
        <dd>{CONNECTION_LABEL[view.connection]}</dd>
        <dt>마지막 보고</dt>
        <dd>{robot.lastReportedAt ?? '보고 없음'}</dd>
        {retired && (
          <>
            <dt>퇴역</dt>
            <dd>
              {robot.retiredAt} ({robot.retiredReason})
            </dd>
          </>
        )}
      </dl>
      <h4>막힘</h4>
      {view.blockers.length === 0 ? (
        <p>막힘 없음</p>
      ) : (
        view.blockers.map((finding) => <FindingCard key={finding.kind} finding={finding} />)
      )}
      <CommissioningCards
        view={view}
        adapters={adapters}
        revisions={revisions}
        mode={mode}
        busy={busy}
        onBind={onBind}
        onRecordSiteNames={onRecordSiteNames}
      />
      {mode !== 'operator' ? (
        <p>퇴역과 복귀는 운영자 모드에서 합니다</p>
      ) : retired ? (
        <button type="button" disabled={busy} onClick={onReinstate}>
          복귀
        </button>
      ) : (
        <form
          aria-label="퇴역"
          onSubmit={(event) => {
            event.preventDefault()
            if (reason.trim() !== '') onRetire(reason.trim())
          }}
        >
          <label>
            퇴역 사유
            <input value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
          <button type="submit" disabled={busy || reason.trim() === ''}>
            퇴역
          </button>
        </form>
      )}
    </section>
  )
}
