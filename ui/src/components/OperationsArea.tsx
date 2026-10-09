import { useEffect, useRef, useState } from 'react'
import {
  checkEligibility,
  fetchCell,
  fetchExecutions,
  fetchIncidents,
  fetchJobResponses,
  submitJobOrder,
  writeCellSignal,
} from '../api'
import type {
  CellView,
  Delivered,
  ExecutionsView,
  IncidentsView,
  JobOrderForm,
  JobOrderOutcome,
  JobResponsesView,
  Session,
  SignalWriteOutcome,
} from '../api'
import { EMPTY_DRAFT, buildForm } from '../jobOrderDraft'
import type { JobOrderDraft } from '../jobOrderDraft'
import { ELIGIBILITY_DEBOUNCE_MS, POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
import { CellBand } from './CellBand'
import { EligibilityTable } from './EligibilityTable'
import type { EligibilityRead } from './EligibilityTable'
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
 * 셀 대역 표시와 신호 조작(S3b 스펙 §8), 작업 응답 송신 기록(S4b 스펙 §8).
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
  const [executions, setExecutions] = useState<HostRead<ExecutionsView>>({ value: null, error: null })
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
    fetchExecutions(session)
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

  // 송신 기록은 고른 작업 지시가 바뀌어도 다시 읽는다. 앞 작업 지시의 늦은 답은 버린다.
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

  const submit = () => {
    if (built.kind !== 'form') {
      setProblem(built.problem)
      return
    }
    const what = `${built.form.workMasterId} 작업 지시`
    setBusy(true)
    submitJobOrder(session, built.form)
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        setTick((value) => value + 1)
        onChanged()
      })
  }

  // 바뀐 값은 실행 호스트의 다음 pump 부터 보인다. 곧바로 한 번, pump 가 돈 뒤 한 번 더 읽는다. 조작이 잇따르면 마지막 조작
  // 뒤의 한 번이 앞의 것을 대신한다. 신호 조작과 운영자 판단이 같이 쓴다.
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
      <div className="split">
        <section aria-label="작업 지시">
          <h2>작업 지시</h2>
          {last !== null && <JobOrderNotice what={last.what} sent={last.sent} />}
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
        </section>
      </div>
      <section aria-label="실행">
        <h2>실행</h2>
        <ExecutionList read={executions} highlight={submitted} />
      </section>
      <IncidentSection read={incidents} session={session} tick={tick} onDecided={rereadNowAndAfterPump} />
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
