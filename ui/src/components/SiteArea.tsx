import { useState } from 'react'
import type { FormEvent } from 'react'
import { changeSiteSettings } from '../api'
import type { HostTimings, Sent, Session, SiteSettingValues, SiteSettingsRange, SiteSettingsView } from '../api'
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

type Field = keyof SiteSettingValues

interface FieldSpec {
  key: Field
  /** 화면의 칸 이름. 입력 이름은 뒤에 `(초)` 를 붙인다. */
  name: string
  /** 범위 밖 알림의 주어(조사 포함). */
  subject: string
  min: keyof SiteSettingsRange
  max: keyof SiteSettingsRange
}

const CONNECTION: FieldSpec = {
  key: 'connectionThresholdSeconds',
  name: '연결 기준 시간',
  subject: '연결 기준 시간은',
  min: 'minConnectionThresholdSeconds',
  max: 'maxConnectionThresholdSeconds',
}
const BEFORE: FieldSpec = {
  key: 'evidenceBeforeSeconds',
  name: '근거 윈도우 앞 폭',
  subject: '근거 윈도우 앞 폭은',
  min: 'minEvidenceBeforeSeconds',
  max: 'maxEvidenceBeforeSeconds',
}
const AFTER: FieldSpec = {
  key: 'evidenceAfterSeconds',
  name: '근거 윈도우 뒤 폭',
  subject: '근거 윈도우 뒤 폭은',
  min: 'minEvidenceAfterSeconds',
  max: 'maxEvidenceAfterSeconds',
}
const IN_DOUBT: FieldSpec = {
  key: 'inDoubtGraceSeconds',
  name: 'inDoubtGrace',
  subject: 'inDoubtGrace 는',
  min: 'minInDoubtGraceSeconds',
  max: 'maxInDoubtGraceSeconds',
}
const STALL: FieldSpec = {
  key: 'stallWindowSeconds',
  name: 'stallWindow',
  subject: 'stallWindow 는',
  min: 'minStallWindowSeconds',
  max: 'maxStallWindowSeconds',
}

/** 값 칸 다섯. 순서는 운영 서비스 본문의 칸 순서(S3c JSON 계약 §4.1)와 같다. 변경 결과 문구도 이 순서다. */
const FIELDS: readonly FieldSpec[] = [CONNECTION, BEFORE, AFTER, IN_DOUBT, STALL]
const TIMINGS: readonly FieldSpec[] = [BEFORE, AFTER, IN_DOUBT, STALL]

/**
 * 시간값 넷의 묶음 둘(S3c 스펙 §8). 결과 판정을 바꾸는 값과 정체 표시 값은 파급이 달라 따로 보이고, 묶음마다 바꾸면 무엇이
 * 달라지는지 적는다(운영 관리 화면 설계 제안 §9).
 */
const GROUPS: readonly { title: string; fields: readonly FieldSpec[]; effect: string }[] = [
  {
    title: '결과 판정 값',
    fields: [BEFORE, AFTER, IN_DOUBT],
    effect:
      '앞 폭을 늘리면 옛 신호가 완료 근거로 들어옵니다. 뒤 폭이나 inDoubtGrace 를 줄이면 UNVERIFIED 와 운영자 대기가 늘어납니다',
  },
  {
    title: '정체 표시',
    fields: [STALL],
    effect: 'stallWindow 는 정체를 사람에게 보이는 시점만 바꿉니다. 실패 판정이나 자동 조치는 바뀌지 않습니다',
  },
]

/**
 * 변경 결과 문구. 기준 버전에서 바뀐 칸만 칸 순서대로 적는다. 연결 기준 시간만 바꾸면 S2 문구(`연결 기준 시간 120초로 변경`)
 * 그대로다. 같은 값으로 보내도 새 버전이 생기므로(S3c JSON 계약 §4) 그 경우도 따로 적는다.
 */
function changeSummary(before: SiteSettingValues, after: SiteSettingValues): string {
  const changed = FIELDS.filter((f) => after[f.key] !== before[f.key]).map((f) => `${f.name} ${after[f.key]}초`)
  return changed.length === 0 ? '현장 설정 같은 값으로 새 버전 기록' : `${changed.join(', ')}로 변경`
}

/**
 * 현장·자원 영역의 «현장 설정» 구역(S2 스펙 §7, S3c 스펙 §8). 지금 버전과 값, 허용 범위, 실행 호스트의 적용 버전, 버전 이력을
 * 보이고, 엔지니어 모드에서만 바꾼다. 연결 기준 시간은 다음 기체 목록 읽기부터, 시간값 넷은 실행 호스트가 읽은 다음 pump
 * 부터 쓰인다.
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
          <HostApplied host={known.hostTimings ?? null} />
          {GROUPS.map((group) => (
            <section key={group.title} aria-label={group.title}>
              <h3>{group.title}</h3>
              <p>{group.effect}</p>
              <dl>
                {group.fields.map((f) => (
                  <div key={f.key}>
                    <dt>{f.name}</dt>
                    <dd>{known.current[f.key]}초</dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
          {session.mode === 'engineer' ? (
            <SettingsForm
              view={known}
              busy={busy}
              onChange={(base, values, reason) =>
                run(changeSummary(base, values), () => changeSiteSettings(session, base.version, values, reason))
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
                {TIMINGS.map((f) => (
                  <th key={f.key}>{f.name}</th>
                ))}
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
                  {TIMINGS.map((f) => (
                    <td key={f.key}>{record[f.key]}초</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}

/**
 * 실행 호스트가 적용한 현장 설정 버전(S3c JSON 계약 §3 판정 표). 운영 서비스의 최신 버전과 따로 보인다. 호스트는 1초마다
 * 읽으므로 잠깐 다를 수 있다. 호스트가 닿지 않으면 모름이다. 읽기 실패와 범위 밖이라 적용하지 않은 버전은 적용 버전과 함께
 * 나올 수 있어 따로 적는다.
 */
function HostApplied({ host }: { host: HostTimings | null }) {
  if (host === null) return <p>실행 호스트 반영: 모름</p>
  const applied = host.applied ?? null
  const readError = host.readError ?? null
  const rejected = host.rejected ?? null
  return (
    <>
      <p>{applied === null ? '실행 호스트 반영: 미적용' : `실행 호스트 반영: 버전 ${applied.version}`}</p>
      {readError !== null && <p>실행 호스트 읽기 실패: {readError}</p>}
      {rejected !== null && (
        <p>
          실행 호스트가 적용하지 않은 버전 {rejected.version}(범위 밖): {rejected.reasons.join('; ')}
        </p>
      )}
    </>
  )
}

type Draft = Record<Field, string>

const asDraft = (values: SiteSettingValues): Draft => ({
  connectionThresholdSeconds: String(values.connectionThresholdSeconds),
  evidenceBeforeSeconds: String(values.evidenceBeforeSeconds),
  evidenceAfterSeconds: String(values.evidenceAfterSeconds),
  inDoubtGraceSeconds: String(values.inDoubtGraceSeconds),
  stallWindowSeconds: String(values.stallWindowSeconds),
})

type Base = SiteSettingsView['current']

interface FormProps {
  view: SiteSettingsView
  busy: boolean
  /** [base] 는 고치기 시작한 버전의 행이고 [values] 는 폼의 값 다섯이다. */
  onChange: (base: Base, values: SiteSettingValues, reason: string) => void
}

/**
 * 현장 설정 변경 폼. 값 다섯을 지금 값으로 채워 보이고, 보낼 때 다섯을 다 싣는다(S3c 스펙 §6.2). 기준 버전과 처음 값은 고치기
 * 시작한 순간의 버전으로 고정한다. 5초마다 다시 읽은 버전을 보낼 때 실으면, 고치는 사이 다른 사람이 올린 버전을 모르고
 * 덮어쓰게 된다(S2 스펙 §7). 범위 밖 값과 빈 사유는 보내지 않는다.
 */
function SettingsForm({ view, busy, onChange }: FormProps) {
  const [base, setBase] = useState<Base | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const shown = draft ?? asDraft(view.current)

  /** 처음 고치는 순간 기준 버전과 그 값 다섯을 잡는다. [key] 가 null 이면 사유다. */
  const edit = (key: Field | null, value: string) => {
    if (base === null) setBase(view.current)
    if (key === null) {
      if (draft === null) setDraft(shown)
      setReason(value)
    } else {
      setDraft({ ...shown, [key]: value })
    }
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const problems: string[] = []
    const values = {} as SiteSettingValues
    for (const f of FIELDS) {
      const min = view.range[f.min]
      const max = view.range[f.max]
      const text = shown[f.key].trim()
      const value = Number(text)
      if (text === '' || !Number.isInteger(value) || value < min || value > max) {
        problems.push(`${f.subject} ${min}~${max}초의 정수여야 합니다`)
      } else {
        values[f.key] = value
      }
    }
    if (problems.length > 0) {
      setProblem(problems.join('; '))
      return
    }
    if (reason.trim() === '') {
      setProblem('변경 사유를 넣으십시오')
      return
    }
    setProblem(null)
    onChange(base ?? view.current, values, reason.trim())
    // 보낸 뒤에는 다음 변경을 새 기준 버전에서 시작한다. 거부되면 알림이 지금 버전을 보인다.
    setBase(null)
    setDraft(null)
    setReason('')
  }

  const input = (f: FieldSpec) => (
    <div key={f.key}>
      <label>
        {f.name}(초)
        <input type="number" value={shown[f.key]} onChange={(event) => edit(f.key, event.target.value)} />
      </label>{' '}
      <span>
        허용 범위 {view.range[f.min]}~{view.range[f.max]}초
      </span>
    </div>
  )

  return (
    <form aria-label="현장 설정 변경" onSubmit={submit}>
      <p>기준 버전 {(base ?? view.current).version}</p>
      {input(CONNECTION)}
      {GROUPS.map((group) => (
        <fieldset key={group.title}>
          <legend>{group.title}</legend>
          {group.fields.map(input)}
        </fieldset>
      ))}
      <label>
        변경 사유
        <input value={reason} onChange={(event) => edit(null, event.target.value)} />
      </label>
      <button type="submit" disabled={busy}>
        변경
      </button>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}
