import { useState } from 'react'
import type { FormEvent } from 'react'
import { changeSiteSettings } from '../api'
import type { Sent, Session, SiteSettingsView } from '../api'
import { OutcomeNotice } from './OutcomeNotice'

interface Props {
  view: SiteSettingsView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  /** 조작이 끝나면 부른다. 다시 읽는다. */
  onChanged: () => void
}

const MODE_LABEL: Record<string, string> = { ENGINEER: '엔지니어', OPERATOR: '운영자' }

/**
 * 현장·자원 영역의 «현장 설정» 구역(S2 스펙 §7). 지금 버전과 값, 허용 범위, 버전 이력을 보이고, 엔지니어 모드에서만 바꾼다.
 * 바꾼 값은 다음 기체 목록 읽기부터 연결 칸과 막힘의 판정에 쓰인다.
 */
export function SiteArea({ view, opsError, session, onChanged }: Props) {
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Sent } | null>(null)

  const run = (what: string, operation: () => Promise<Sent>) => {
    setBusy(true)
    operation()
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        onChanged()
      })
  }

  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const known = view !== null && view.current != null && Array.isArray(view.history) ? view : null

  return (
    <section aria-label="현장 설정">
      <h2>현장 설정</h2>
      {last !== null && <OutcomeNotice what={last.what} sent={last.sent} onSelect={() => undefined} />}
      {known === null ? (
        <p>모름: 현장 설정을 아직 읽지 못했습니다</p>
      ) : (
        <>
          {opsError !== null && <p className="stale">직전 값입니다</p>}
          <dl>
            <dt>현재 버전</dt>
            <dd>{known.current.version}</dd>
            <dt>연결 기준 시간</dt>
            <dd>{known.current.connectionThresholdSeconds}초</dd>
            <dt>허용 범위</dt>
            <dd>
              {known.range.minConnectionThresholdSeconds}~{known.range.maxConnectionThresholdSeconds}초
            </dd>
          </dl>
          {session.mode === 'engineer' ? (
            <SettingsForm
              view={known}
              busy={busy}
              onChange={(base, seconds, reason) =>
                run(`연결 기준 시간 ${seconds}초로 변경`, () => changeSiteSettings(session, base, seconds, reason))
              }
            />
          ) : (
            <p>현장 설정 변경은 엔지니어 모드에서 합니다</p>
          )}
          <h3>버전 이력</h3>
          <table aria-label="현장 설정 버전 이력">
            <thead>
              <tr>
                <th>버전</th>
                <th>연결 기준 시간</th>
                <th>사용자</th>
                <th>모드</th>
                <th>사유</th>
                <th>기록 시각</th>
              </tr>
            </thead>
            <tbody>
              {known.history.map((record) => (
                <tr key={record.version}>
                  <td>{record.version}</td>
                  <td>{record.connectionThresholdSeconds}초</td>
                  <td>{record.user}</td>
                  <td>{MODE_LABEL[record.mode] ?? record.mode}</td>
                  <td>{record.reason}</td>
                  <td>{record.recordedAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}

interface FormProps {
  view: SiteSettingsView
  busy: boolean
  onChange: (baseVersion: number, seconds: number, reason: string) => void
}

/**
 * 연결 기준 시간 변경 폼. 기준 버전은 고치기 시작한 순간의 버전으로 고정한다. 5초마다 다시 읽은 버전을 보낼 때 실으면,
 * 고치는 사이 다른 사람이 올린 버전을 모르고 덮어쓰게 된다(S2 스펙 §7). 범위 밖 값과 빈 사유는 보내지 않는다.
 */
function SettingsForm({ view, busy, onChange }: FormProps) {
  const [base, setBase] = useState<number | null>(null)
  const [seconds, setSeconds] = useState('')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const { minConnectionThresholdSeconds: min, maxConnectionThresholdSeconds: max } = view.range

  const editing = () => {
    if (base === null) setBase(view.current.version)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const value = Number(seconds)
    if (!Number.isInteger(value) || value < min || value > max) {
      setProblem(`연결 기준 시간은 ${min}~${max}초의 정수여야 합니다`)
      return
    }
    if (reason.trim() === '') {
      setProblem('변경 사유를 넣으십시오')
      return
    }
    setProblem(null)
    onChange(base ?? view.current.version, value, reason.trim())
    // 보낸 뒤에는 다음 변경을 새 기준 버전에서 시작한다. 거부되면 알림이 지금 버전을 보인다.
    setBase(null)
    setSeconds('')
    setReason('')
  }

  return (
    <form aria-label="현장 설정 변경" onSubmit={submit}>
      <p>기준 버전 {base ?? view.current.version}</p>
      <label>
        연결 기준 시간(초)
        <input
          type="number"
          value={seconds}
          onChange={(event) => {
            editing()
            setSeconds(event.target.value)
          }}
        />
      </label>
      <label>
        변경 사유
        <input
          value={reason}
          onChange={(event) => {
            editing()
            setReason(event.target.value)
          }}
        />
      </label>
      <button type="submit" disabled={busy}>
        변경
      </button>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}
