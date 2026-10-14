import { useRef, useState } from 'react'
import type { Session } from '../api'
import { sourceUnobservedLabel } from '../labels'
import type { WorkReceipt } from './WorkRequests'

/** A source the host could not observe (ops `pendingSources`). The work request holds until a person confirms it. */
export interface PendingSource { source: string; material: string | null; occupied: boolean | null; robotIds: string[] }
/** One recorded source confirmation (ops `sourceConfirmations`). Append-only. */
export interface SourceConfirmationRow { requestId: string; source: string; identity: string; actor: string; reason: string; createdAt: string }
interface Confirmation { requestId: string; source: string; identity: string; reason: string; expectedUpdatedAt: string }

/**
 * 출발 미관측의 확인 대기와 사람의 출발 자리 확인. 결품(출발 결품)과 다른 자리에 보인다. 확인을 기록하면 같은 작업 지시를 확인과 함께 다시
 * 제출한다. 공유 자원 작업은 그 자원 작업의 경로로 보낸다. 같은 요청 ID로만 다시 보낸다.
 */
export function SourceConfirmation({ receipt, pending, history, runId, session, onChanged }: { receipt: WorkReceipt; pending: PendingSource[]; history: SourceConfirmationRow[]; runId: string | null; session: Session; onChanged: () => void }) {
 const [source, setSource] = useState(pending[0]?.source ?? '')
 const chosen = pending.find(row => row.source === source) ?? pending[0]
 const [identity, setIdentity] = useState(chosen?.material ?? '')
 const [reason, setReason] = useState('')
 const [sent, setSent] = useState<Confirmation | null>(null)
 const [busy, setBusy] = useState(false)
 const [error, setError] = useState<string | null>(null)
 const [notice, setNotice] = useState<string | null>(null)
 const sending = useRef(false)
 async function confirm() {
  if (sending.current || session.mode !== 'operator' || !chosen) return
  const body = sent ?? { requestId: crypto.randomUUID(), source: chosen.source, identity: identity.trim(), reason: reason.trim(), expectedUpdatedAt: receipt.updatedAt }
  sending.current = true; setBusy(true); setSent(body); setError(null)
  try {
   const url = runId ? `/api/stations/runs/${runId}/source-confirmations` : `/api/work-requests/${receipt.receiptId}/source-confirmations`
   const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user }, body: JSON.stringify(body) })
   const value = await response.json()
   // 4xx: the server refused and recorded nothing, so a new confirmation gets a new ID. Anything else keeps the same ID.
   if (!response.ok) { if (response.status >= 400 && response.status < 500) setSent(null); throw new Error(value.detail ?? `HTTP ${response.status}`) }
   setNotice(`출발 자리 확인 기록됨 · ${value.receipt?.state ?? value.state ?? '상태 확인 필요'}`); setSent(null); onChanged()
  } catch (e: unknown) { setError(`${e instanceof Error ? e.message : String(e)} · 같은 확인 ID로 다시 확인하세요.`) }
  finally { sending.current = false; setBusy(false) }
 }
 return <section aria-label="출발 미관측">
  <h4>출발 미관측 · 확인 필요</h4>
  <p>결품이 아닙니다. 설비가 출발 자리의 자재를 확인해 주지 않았습니다. 사람이 출발 자리를 확인해 기록하기 전에는 다시 제출하지 않습니다.</p>
  <ul>{pending.map(row => <li key={row.source}>{sourceUnobservedLabel(row)} · 기체 {row.robotIds.join(', ')}</li>)}</ul>
  {session.mode === 'operator' ? <>
   <fieldset disabled={busy || !!sent}>
    {pending.length > 1 && <label>확인할 출발 자리<select value={chosen?.source} onChange={e => { setSource(e.target.value); setIdentity(pending.find(row => row.source === e.target.value)?.material ?? '') }}>{pending.map(row => <option key={row.source} value={row.source}>{row.source}</option>)}</select></label>}
    <label>확인한 자재<input value={identity} maxLength={160} onChange={e => setIdentity(e.target.value)} /></label>
    <label>확인 근거·담당자<input value={reason} maxLength={1000} onChange={e => setReason(e.target.value)} /></label>
   </fieldset>
   <div className="actions"><button disabled={busy || (!sent && (!identity.trim() || !reason.trim()))} onClick={confirm}>{sent ? '같은 확인 다시 보내기' : '출발 자리 확인 기록'}</button></div>
   {sent && <p>확인 ID: <code>{sent.requestId}</code></p>}
  </> : <p>출발 자리 확인은 운영자 모드에서 기록합니다.</p>}
  {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
  {history.length > 0 && <SourceConfirmationHistory history={history} />}
 </section>
}

/** 기록된 출발 자리 확인. 확인 대기가 끝난 뒤에도 상세에 남는다. */
export function SourceConfirmationHistory({ history }: { history: SourceConfirmationRow[] }) {
 return <section aria-label="출발 자리 확인 이력"><h4>출발 자리 확인 이력</h4><ul>{history.map(row => <li key={row.requestId}>{row.createdAt} · {row.actor} · {row.source} = {row.identity}<p>{row.reason}</p></li>)}</ul></section>
}
