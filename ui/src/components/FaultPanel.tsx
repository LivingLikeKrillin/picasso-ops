import { useState } from 'react'
import type { FormEvent } from 'react'
import { injectFault } from '../api'
import type { ConnectionFaultState, Delivered, FaultInjectionOutcome, FaultKind, RobotListView, Session } from '../api'
import { FAULT_KIND_LABEL, kindLabel, rejectionText } from '../labels'

interface Props {
  /** 기체 목록(App 의 다섯 조회). 운영 서비스에 닿지 않았으면 null 이다. */
  robots: RobotListView | null
  session: Session
  /** 조작이 끝나면 부른다. 조작 기록을 다시 읽는다. */
  onChanged: () => void
}

/** 연결 상태 셋(S4a JSON 계약 §1.1). 표시 문구는 계약의 이름 그대로이고 ONLINE 에만 복구를 붙인다. */
const STATES: readonly { value: ConnectionFaultState; label: string }[] = [
  { value: 'OFFLINE', label: 'OFFLINE' },
  { value: 'CONNECTION_BROKEN', label: 'CONNECTION_BROKEN' },
  { value: 'ONLINE', label: 'ONLINE(복구)' },
]

const KINDS: readonly FaultKind[] = ['SKILL_EXECUTION_FAILED', 'CONNECTION']

/**
 * 현장·자원 영역의 «장애 주입» 구역(S4a 스펙 §8.1). 기체 하나에 스킬 실패나 연결 상태를 넣는다. 엔지니어 모드에서만 조작하고
 * 운영자 모드에서는 현장 설정처럼 읽기만 한다. 지울 때까지 유지되는 결함과 전송 장애는 현장이 받지 않으므로 종류에 두지 않는다.
 *
 * 장애 주입은 재조회하지 않는다(S4a JSON 계약 §9.4). 그래서 받아들임도 효과를 보증하지 않고, 효과는 실행 목록과 기체 목록에서
 * 확인하게 한다.
 */
export function FaultPanel({ robots, session, onChanged }: Props) {
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<FaultInjectionOutcome> } | null>(null)

  const inject = (robotId: string, kind: FaultKind, state: ConnectionFaultState | null, reason: string) => {
    const what = kind === 'CONNECTION' ? `${robotId} 연결 상태 ${state}` : `${robotId} ${FAULT_KIND_LABEL[kind]}`
    setBusy(true)
    injectFault(session, robotId, kind, state, reason)
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        onChanged()
      })
  }

  // 퇴역한 기체는 고르지 않는다. 현장에 없는 기체는 현장이 거부하고 그 이유를 보인다.
  const ids =
    robots === null || robots.robots === null
      ? null
      : robots.robots.filter((view) => view.robot.status !== 'RETIRED').map((view) => view.robot.robotId)

  return (
    <section aria-label="장애 주입">
      <h2>장애 주입</h2>
      <p>
        기체 하나에 장애를 넣습니다. 받아들임은 현장이 장애를 넣었다는 뜻이고, 그 효과는 운영 영역의 실행 목록과 인시던트,
        로봇·연결 영역의 기체 목록에서 확인합니다
      </p>
      {last !== null && <FaultNotice what={last.what} sent={last.sent} />}
      {session.mode !== 'engineer' ? (
        <p>장애 주입은 엔지니어 모드에서 합니다</p>
      ) : ids === null ? (
        <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
      ) : ids.length === 0 ? (
        <p>장애를 넣을 기체가 없습니다</p>
      ) : (
        <FaultForm ids={ids} busy={busy} onInject={inject} />
      )}
    </section>
  )
}

interface FormProps {
  ids: string[]
  busy: boolean
  onInject: (robotId: string, kind: FaultKind, state: ConnectionFaultState | null, reason: string) => void
}

function FaultForm({ ids, busy, onInject }: FormProps) {
  const [picked, setPicked] = useState<string | null>(null)
  const [kind, setKind] = useState<FaultKind>('SKILL_EXECUTION_FAILED')
  const [state, setState] = useState<ConnectionFaultState>('OFFLINE')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  // 다시 읽은 목록에서 고른 기체가 빠지면 첫 기체로 돌아간다.
  const robotId = picked !== null && ids.includes(picked) ? picked : ids[0]

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (reason.trim() === '') {
      setProblem('장애 주입 사유를 넣으십시오')
      return
    }
    setProblem(null)
    onInject(robotId, kind, kind === 'CONNECTION' ? state : null, reason.trim())
    setReason('')
  }

  return (
    <form aria-label="장애 주입 폼" onSubmit={submit}>
      <label>
        기체
        <select value={robotId} onChange={(event) => setPicked(event.target.value)}>
          {ids.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>{' '}
      <label>
        장애 종류
        <select value={kind} onChange={(event) => setKind(event.target.value as FaultKind)}>
          {KINDS.map((value) => (
            <option key={value} value={value}>
              {value === 'SKILL_EXECUTION_FAILED' ? '스킬 실패(진행 중 태스크)' : FAULT_KIND_LABEL[value]}
            </option>
          ))}
        </select>
      </label>{' '}
      {kind === 'CONNECTION' && (
        <label>
          연결 상태
          <select value={state} onChange={(event) => setState(event.target.value as ConnectionFaultState)}>
            {STATES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      )}
      <div>
        <label>
          장애 주입 사유
          <input value={reason} onChange={(event) => setReason(event.target.value)} />
        </label>{' '}
        <button type="submit" disabled={busy}>
          장애 넣기
        </button>
      </div>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}

/**
 * 장애 주입의 결과(S4a 스펙 §8.1). 현장 불통과 실행 호스트 불통은 운영 서비스가 둘 다 `NO_RESPONSE` 로 내므로 화면에서는
 * «응답 없음» 하나다. 재조회하지 않으므로 응답 없음은 반영 여부를 끝내 모른다.
 */
export function FaultNotice({ what, sent }: { what: string; sent: Delivered<FaultInjectionOutcome> }) {
  return (
    <p role="status" aria-label="장애 주입 결과">
      {what}: {describe(sent)}
    </p>
  )
}

function describe(sent: Delivered<FaultInjectionOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 실행 목록과 기체 목록에서 확인하십시오`
  const { result, fault, rejection } = sent.outcome
  if (result === 'SUCCEEDED' && fault !== null) {
    if (fault.kind === 'SKILL_EXECUTION_FAILED') {
      const again = fault.raised ? '' : '. 같은 결함이 이미 서 있었습니다'
      return `받아들임(태스크 ${fault.taskId}, ${fault.taskState})${again}`
    }
    return fault.changed ? `받아들임(연결 상태 ${fault.state})` : `받아들임(이미 ${fault.state} 상태라 바뀐 것 없음)`
  }
  if (result === 'SUCCEEDED') return '받아들임'
  if (result === 'REJECTED' && rejection !== null) {
    return rejectionText('현장이 거부함', rejection)
  }
  return '응답 없음. 넣었는지 모릅니다. 실행 목록과 기체 목록에서 확인하십시오'
}
