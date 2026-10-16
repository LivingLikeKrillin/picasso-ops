import { MissionResourceNotice } from './MissionResourceSettings'
import { WorkRequests } from './WorkRequests'
import { useEffect, useRef, useState } from 'react'
import {
  checkEligibility,
  fetchCell,
  fetchExecutionPage,
  fetchIncidents,
  fetchJobResponses,
  submitManualJobOrder,
  manualJobOutcome,
  writeCellSignal,
} from '../api'
import type {
  CellView,
  Delivered,
  ExecutionPage,
  IncidentsView,
  JobOrderForm,
  JobOrderOutcome,
  ManualReceiptAccepted,
  JobResponsesView,
  Session,
  SignalWriteOutcome,
} from '../api'
import { clearManualRequest, loadManualRequest, saveManualRequest } from '../manualRequests'
import type { PendingManualRequest } from '../manualRequests'
import { EMPTY_DRAFT, buildForm } from '../jobOrderDraft'
import type { JobOrderDraft } from '../jobOrderDraft'
import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
import { CellBand } from './CellBand'
import { EligibilityTable } from './EligibilityTable'
import type { EligibilityRead } from './EligibilityTable'
import { ResidualHolds } from './ExecutionControls'
import { ExecutionList } from './ExecutionList'
import type { HostRead } from './ExecutionList'
import { IncidentSection } from './IncidentSection'
import { JobOrderFormView } from './JobOrderFormView'
import { JobOrderNotice } from './JobOrderNotice'
import { JobResponseLog } from './JobResponseLog'
import { SignalNotice } from './SignalNotice'

interface Props {
  session: Session
  /** 제출이 끝나면 부른다. 조작 기록을 다시 읽는다. */
  onChanged: () => void
}

const NO_ELIGIBILITY: EligibilityRead = { view: null, refusal: null, error: null, formKey: null }

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

/**
 * «운영» 영역(S3a 스펙 §9). 작업 지시 폼, 기체별 배정 가능 표, 실행 목록, 인시던트와 운영자 판단(S4a 스펙 §8.2·§8.3),
 * 셀 대역 표시와 신호 조작(S3b 스펙 §8), 작업 응답 송신 기록(S4b 스펙 §8), 운영자 실행 취소와 잔여 파지 확인(P0-b2).
 *
 * 실행 목록·인시던트·셀·배정 가능은 실행 호스트를 거친다. 그래서 App 의 다섯 조회(`Promise.all`)와 따로, 이 영역이 열려 있을 때만
 * 읽는다(S3a 스펙 §9.3). 호스트가 멈춰도 다섯 조회가 직전 값이 되지 않게 하기 위해서다. 셋은 서로도 따로 실패하고, 못 읽으면
 * 직전 값을 지우지 않고 불통을 표시한다.
 *
 * 배정 가능은 폼이 바뀔 때와 주기마다 다시 읽는다. 연결이 낡으면 표에 보이게 하기 위해서다(S3a 스펙 §9.1). 덜 채운 폼은 묻지
 * 않는다. 폼이 바뀌면 [ELIGIBILITY_DEBOUNCE_MS] 동안 더 바뀌지 않을 때 그 폼으로 한 번 묻는다. 키 입력마다 묻지 않기 위해서다.
 * 주기의 다시 읽기는 기다리지 않는다.
 */
export function OperationsArea({ session, onChanged }: Props) {
  // 주기마다, 그리고 제출이 끝나면 하나 올린다. 셋을 다시 읽는다.
  const [tick, setTick] = useState(0)
  const [executions, setExecutions] = useState<HostRead<ExecutionPage>>({ value: null, error: null })
  const [cell, setCell] = useState<HostRead<CellView>>({ value: null, error: null })
  const [incidents, setIncidents] = useState<HostRead<IncidentsView>>({ value: null, error: null })
  const [responses, setResponses] = useState<HostRead<JobResponsesView>>({ value: null, error: null })
  // 송신 기록에서 고른 작업 지시. null 이면 전체다.
  const [responseFilter, setResponseFilter] = useState<string | null>(null)
  const [eligibility, setEligibility] = useState<EligibilityRead>(NO_ELIGIBILITY)
  const [draft, setDraft] = useState<JobOrderDraft>(EMPTY_DRAFT)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<JobOrderOutcome> } | null>(null)
  const [pendingManual, setPendingManual] = useState<PendingManualRequest | null>(null)
  const [manualReceipt, setManualReceipt] = useState<ManualReceiptAccepted | null>(null)
  const [manualStorageError, setManualStorageError] = useState<string | null>(null)
  const [focusReceipt, setFocusReceipt] = useState<string | null>(null)
  const submitting = useRef(false)
  const submitEpoch = useRef(0)
  const { mode: manualMode, user: manualUser } = session
  const manualIdentity = useRef(`${manualMode}/${manualUser}`)
  useEffect(() => {
    submitEpoch.current += 1
    submitting.current = false
    let alive = true
    queueMicrotask(() => {
      if(!alive) return
      setBusy(false); setLast(null); setManualReceipt(null); setFocusReceipt(null)
      try { setPendingManual(loadManualRequest({mode: manualMode, user: manualUser})); setManualStorageError(null) }
      catch(error) { setPendingManual(null); setManualStorageError(message(error)) }
      manualIdentity.current = `${manualMode}/${manualUser}`
    })
    return () => { alive = false }
  }, [manualMode, manualUser])
  const [signalBusy, setSignalBusy] = useState(false)
  const [signalLast, setSignalLast] = useState<{ what: string; sent: Delivered<SignalWriteOutcome> } | null>(null)

  useEffect(() => {
    const timer = setInterval(() => setTick((value) => value + 1), POLL_MS)
    return () => clearInterval(timer)
  }, [])

  // 신호 조작과 운영자 판단 뒤 pump 가 돈 다음에 한 번 더 읽는 타이머. 영역을 닫으면 치우고, 닫은 뒤에 끝난 조작은 타이머를 걸지 않는다.
  const mounted = useRef(true)
  const settle = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      if (settle.current !== null) clearTimeout(settle.current)
      settle.current = null
    }
  }, [])

  useEffect(() => {
    let alive = true
    // 셋을 묶지 않는다. 셀 대역만 못 읽어도 실행 목록은 새 값이어야 한다.
    fetchExecutionPage(session)
      .then((value) => {
        if (alive) setExecutions({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setExecutions((previous) => ({ ...previous, error: message(error) }))
      })
    fetchCell(session)
      .then((value) => {
        if (alive) setCell({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setCell((previous) => ({ ...previous, error: message(error) }))
      })
    fetchIncidents(session)
      .then((value) => {
        if (alive) setIncidents({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setIncidents((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick])

  // 송신 기록은 고른 작업 지시가 바뀌어도 다시 읽는다. 앞 작업 지시의 늦은 응답은 버린다.
  useEffect(() => {
    let alive = true
    fetchJobResponses(session, responseFilter)
      .then((value) => {
        if (alive) setResponses({ value, error: null })
      })
      .catch((error: unknown) => {
        if (alive) setResponses((previous) => ({ ...previous, error: message(error) }))
      })
    return () => {
      alive = false
    }
  }, [session, tick, responseFilter])

  const selectResponses = (jobOrderId: string | null) => {
    // 다른 작업 지시의 행을 고른 작업 지시의 직전 값으로 보이지 않는다.
    setResponses({ value: null, error: null })
    setResponseFilter(jobOrderId)
  }
  const responseJobOrders = [
    ...[...(executions.value?.executions ?? [])].reverse().map((execution) => execution.jobOrderId),
    ...(executions.value?.settled ?? []).map((row) => row.execution.jobOrderId),
    ...(responses.value?.responses ?? []).map((row) => row.jobOrderId),
  ]

  const built = buildForm(draft, cell.value?.cell ?? null)
  // 폼을 글자로 비교한다. 셀을 다시 읽을 때마다 객체가 바뀌어도 같은 폼이면 다시 묻지 않는다.
  const formKey = built.kind === 'form' ? JSON.stringify(built.form) : null

  // 배정 가능을 물을 폼. 폼이 멈추고 ELIGIBILITY_DEBOUNCE_MS 가 지나야 따라온다. 그 사이 표는 다시 판정하는 중으로 보인다.
  const [settledKey, setSettledKey] = useState<string | null>(null)
  useEffect(() => {
    const timer = setTimeout(() => setSettledKey(formKey), ELIGIBILITY_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [formKey])

  useEffect(() => {
    if (settledKey === null) return
    let alive = true
    checkEligibility(session, JSON.parse(settledKey) as JobOrderForm).then((sent) => {
      if (!alive) return
      if (sent.kind === 'outcome') setEligibility({ view: sent.outcome, refusal: null, error: null, formKey: settledKey })
      else if (sent.kind === 'refused') {
        setEligibility({ view: null, refusal: sent.refusal, error: null, formKey: settledKey })
      } else setEligibility((previous) => ({ ...previous, error: sent.cause }))
    })
    return () => {
      alive = false
    }
  }, [session, tick, settledKey])

  const change = (next: JobOrderDraft) => {
    setDraft(next)
    setProblem(null)
  }

  const sendManual = (request: PendingManualRequest) => {
    if(submitting.current || session.mode !== 'operator' || manualStorageError || manualIdentity.current !== `${session.mode}/${session.user}`) return
    try {
      const stored = loadManualRequest(session)
      if(stored && stored.requestId !== request.requestId) { setProblem('보관된 요청 ID로 작업 요청을 먼저 확인하세요'); return }
      saveManualRequest(session, request)
    }
    catch(error) { setManualStorageError(`요청 ID 보관 실패: ${message(error)}. 작업을 제출하지 않았습니다`); return }
    setPendingManual(request); setProblem(null); submitting.current = true; setBusy(true)
    const epoch = submitEpoch.current
    const what = `${request.form.workMasterId} 작업 지시`
    submitManualJobOrder(session, request.form, request.requestId).then((sent) => {
      if(!mounted.current || epoch !== submitEpoch.current) return
      if(sent.kind === 'outcome') {
        const accepted = sent.outcome
        setManualReceipt(accepted)
        const saved = { ...request, receiptId: accepted.receipt.receiptId,
          canStartNew: ['WAITING_RESOURCE', 'ACCEPTED', 'REJECTED', 'UNASSIGNED'].includes(accepted.receipt.state) }
        setPendingManual(saved)
        try { saveManualRequest(session, saved) }
        catch(error) { setManualStorageError(`작업 요청 응답은 받았으나 브라우저 보관 실패: ${message(error)}`) }
        setLast(accepted.receipt.state==='WAITING_RESOURCE'?null:{ what, sent: { kind: 'outcome', outcome: manualJobOutcome(accepted.receipt) } })
      } else setLast({ what, sent })
    }).finally(() => {
      if(!mounted.current || epoch !== submitEpoch.current) return
      submitting.current = false; setBusy(false); setTick(value => value + 1); onChanged()
    })
  }
  const submit = () => {
    if (built.kind !== 'form') { setProblem(built.problem); return }
    if(pendingManual && JSON.stringify(pendingManual.form) !== JSON.stringify(built.form)) {
      setProblem('보관된 요청과 입력이 다릅니다. 동일 작업 요청을 확인하거나, 확인된 작업 요청에서 새 작업 작성을 선택하세요'); return
    }
    sendManual(pendingManual ?? { requestId: crypto.randomUUID(), form: built.form, canStartNew: false })
  }
  const startNewManual = () => {
    if(!pendingManual?.canStartNew || submitting.current) return
    try { clearManualRequest(session) }
    catch(error) { setManualStorageError(message(error)); return }
    setPendingManual(null); setManualReceipt(null); setLast(null); setProblem(null); setManualStorageError(null)
  }

  // 바뀐 값은 실행 호스트의 다음 pump 부터 보인다. 곧바로 한 번, pump 가 돈 뒤 한 번 더 읽는다. 조작이 잇따르면 마지막 조작
  // 뒤의 한 번이 앞의 것을 대신한다. 신호 조작, 운영자 판단, 실행 취소, 결함 감수, 기체 빈손 확인이 같이 쓴다.
  const rereadNowAndAfterPump = () => {
    setTick((value) => value + 1)
    if (settle.current !== null) clearTimeout(settle.current)
    settle.current = mounted.current
      ? setTimeout(() => {
          settle.current = null
          setTick((value) => value + 1)
        }, SIGNAL_SETTLE_MS)
      : null
    onChanged()
  }

  const writeSignal = (name: string, value: string) => {
    const what = `${name} ${value === 'true' ? '켜기' : '끄기'}`
    setSignalBusy(true)
    writeCellSignal(session, name, value)
      .then((sent) => setSignalLast({ what, sent }))
      .finally(() => {
        setSignalBusy(false)
        rereadNowAndAfterPump()
      })
  }

  const submitted = last?.sent.kind === 'outcome' ? last.sent.outcome.jobOrderId : null

  return (
    <>
      <WorkRequests session={session} refreshKey={tick} focusReceipt={focusReceipt} />
      <div className="split">
        <section aria-label="작업 지시">
          <h2>작업 지시</h2>
          {last !== null && <JobOrderNotice what={last.what} sent={last.sent} />}
          {manualStorageError && <p role="alert">{manualStorageError}</p>}
          {pendingManual && <section className="manual-receipt" aria-label="수동 작업 요청 추적">
            <h3>수동 작업 요청</h3><p>요청 ID: <code>{pendingManual.requestId}</code></p>
            {manualReceipt ? <p>{manualReceipt.duplicate ? '기존 작업 요청 확인 · 추가 실행 없음' : '작업 요청 기록 저장됨'} · {manualReceipt.receipt.state}</p> : <p>보관된 요청입니다. 같은 ID와 원본 입력으로 작업 요청을 확인합니다.</p>}
            {manualReceipt?.receipt.state==='WAITING_RESOURCE'&&<p role="status">작업 요청 저장 완료 · 필수 자원과 설비 준비를 기다립니다. 아직 로봇에 전송하지 않았습니다.</p>}<p>응답 불명·처리 중인 요청은 새 ID로 재제출하지 마세요. 새 작업 작성은 별도 실행을 의미합니다.</p>
            <div className="actions">
              <button type="button" disabled={busy || session.mode !== 'operator' || !!manualStorageError} onClick={() => sendManual(pendingManual)}>동일 작업 요청 확인</button>
              <button type="button" disabled={busy || !pendingManual.canStartNew || session.mode !== 'operator' || !!manualStorageError} onClick={startNewManual}>새 작업 작성</button>
              {pendingManual.receiptId && <button type="button" onClick={() => setFocusReceipt(pendingManual.receiptId!)}>작업 요청·실행 결과 보기</button>}
            </div><details><summary>보관된 요청 입력</summary><pre>{JSON.stringify(pendingManual.form, null, 2)}</pre></details>
          </section>}
          <MissionResourceNotice session={session} workMasterId={draft.workMasterId} />
          <JobOrderFormView
            draft={draft}
            cell={cell.value?.cell ?? null}
            mode={session.mode}
            busy={busy}
            problem={problem}
            onChange={change}
            onSubmit={submit}
          />
        </section>
        <section aria-label="배정 가능">
          <h2>배정 가능</h2>
          <EligibilityTable read={eligibility} formKey={formKey} />
          <ResidualHolds session={session} tick={tick} onConfirmed={rereadNowAndAfterPump} />
        </section>
      </div>
      <section aria-label="실행">
        <h2>실행</h2>
        <ExecutionList read={executions} highlight={submitted} session={session} onCanceled={rereadNowAndAfterPump} />
      </section>
      <IncidentSection read={incidents} session={session} tick={tick} onDecided={rereadNowAndAfterPump} executions={executions.value} />
      <section aria-label="셀 대역">
        <h2>셀 대역</h2>
        {signalLast !== null && <SignalNotice what={signalLast.what} sent={signalLast.sent} />}
        <CellBand read={cell} busy={signalBusy} onWrite={writeSignal} />
      </section>
      <JobResponseLog
        read={responses}
        jobOrderIds={responseJobOrders}
        selected={responseFilter}
        onSelect={selectResponses}
      />
    </>
  )
}
