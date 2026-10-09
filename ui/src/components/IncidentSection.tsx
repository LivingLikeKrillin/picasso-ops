import { useEffect, useState } from 'react'
import { fetchIncident, resolveHold } from '../api'
import type {
  Delivered,
  FaultDetail,
  HoldDecision,
  HoldResolveOutcome,
  IncidentDetail,
  IncidentLookup,
  IncidentResolution,
  IncidentRow,
  IncidentsView,
  Session,
} from '../api'
import { DECISION_LABEL, kindLabel, rejectionText } from '../labels'
import type { HostRead } from './ExecutionList'

interface Props {
  read: HostRead<IncidentsView>
  session: Session
  /** 주기와 조작마다 오른다. 고른 인시던트의 상세도 같이 다시 읽는다. */
  tick: number
  /** 판단을 보낸 뒤 부른다. 인시던트와 실행 목록을 곧바로, 그리고 조금 뒤 한 번 더 읽는다. */
  onDecided: () => void
}

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

const DECISIONS: readonly HoldDecision[] = ['CONFIRM_DONE', 'REWORK']

/** 임무 버전 칸. null 은 코드 정의 임무다(S3a JSON 계약 §5). */
const missionVersion = (version: number | null) => (version === null ? '코드 정의' : `버전 ${version}`)
/** 현장 설정 버전 칸. null 은 봉인 때 버전을 몰랐던 것이다. */
const settingsVersion = (version: number | null) => (version === null ? '모름' : `버전 ${version}`)
const seconds = (value: number | null) => (value === null ? '모름' : `${value}초`)

/**
 * 목록의 판단 칸(S4a 스펙 §8.2). «미해결» 은 봉인 때 보류·불확실이었고 판단이 없는 것이다. 단위가 실패로 끝나 사람이 판단할
 * 것이 없는 인시던트는 판단 대상이 아니다.
 */
function judgement(row: IncidentRow): string {
  if (row.resolution !== null) {
    return row.confirmedWithoutEvidence ? '판단됨(설비 근거 없이 완료 확인)' : '판단됨'
  }
  return row.unresolved ? '미해결' : '판단 대상 아님'
}

/**
 * 운영 영역의 «인시던트» 구역(S4a 스펙 §8.2·§8.3). 목록은 실행 호스트가 준 순서(최신부터)이고, 줄을 고르면 상세를 읽는다.
 * 관측(봉인 때의 근거)과 사람의 판단을 따로 보인다(운영 관리 화면 설계 제안 §9). 판단 버튼은 보류 중인 단위의 상세에만,
 * 운영자 모드에서만 있다.
 */
export function IncidentSection({ read, session, tick, onDecided }: Props) {
  const [selected, setSelected] = useState<string | null>(null)
  const [detail, setDetail] = useState<{ id: string; lookup: IncidentLookup | null; error: string | null } | null>(null)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<HoldResolveOutcome> } | null>(null)

  useEffect(() => {
    if (selected === null) return
    let alive = true
    fetchIncident(session, selected)
      .then((lookup) => {
        if (alive) setDetail({ id: selected, lookup, error: null })
      })
      .catch((error: unknown) => {
        if (!alive) return
        // 못 읽으면 같은 인시던트의 직전 상세를 지우지 않는다.
        setDetail((previous) =>
          previous !== null && previous.id === selected
            ? { ...previous, error: message(error) }
            : { id: selected, lookup: null, error: message(error) },
        )
      })
    return () => {
      alive = false
    }
  }, [session, tick, selected])

  const decide = (target: IncidentDetail, decision: HoldDecision, reason: string) => {
    const what = `${target.executionId}/${target.unitId} ${DECISION_LABEL[decision]}`
    setBusy(true)
    resolveHold(session, target.executionId, target.unitId, decision, reason)
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        onDecided()
      })
  }

  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 목록이 없다. 없는 목록도 모름이다.
  const value = read.value !== null && Array.isArray(read.value.incidents) ? read.value : null
  const { error } = read
  const shown = detail !== null && detail.id === selected ? detail : null

  return (
    <section aria-label="인시던트">
      <h2>인시던트</h2>
      {last !== null && <DecisionNotice what={last.what} sent={last.sent} />}
      {value === null ? (
        <p>모름: 인시던트 목록을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
      ) : (
        <>
          {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
          <p>
            실행 호스트 인스턴스 {value.instanceId}, 인시던트 {value.total}건 가운데 최신 {value.incidents.length}건
          </p>
          {value.incidents.length === 0 ? (
            <p>인시던트가 없습니다</p>
          ) : (
            <IncidentTable rows={value.incidents} selected={selected} onSelect={setSelected} />
          )}
        </>
      )}
      {selected !== null && (
        <section aria-label="인시던트 상세">
          <h3>{selected} 상세</h3>
          {shown === null || (shown.lookup === null && shown.error === null) ? (
            <p>상세를 읽는 중입니다</p>
          ) : shown.lookup === null ? (
            <p>모름: 상세를 읽지 못했습니다 ({shown.error})</p>
          ) : shown.lookup.kind === 'missing' ? (
            <p>실행 호스트에 이 인시던트가 없습니다. 실행 호스트를 재기동했을 수 있습니다</p>
          ) : (
            <>
              {shown.error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {shown.error}</p>}
              <Detail
                detail={shown.lookup.detail}
                session={session}
                busy={busy}
                onDecide={(decision, reason) => {
                  if (shown.lookup?.kind === 'found') decide(shown.lookup.detail, decision, reason)
                }}
              />
            </>
          )}
        </section>
      )}
    </section>
  )
}

function IncidentTable({
  rows,
  selected,
  onSelect,
}: {
  rows: IncidentRow[]
  selected: string | null
  onSelect: (incidentId: string) => void
}) {
  return (
    <table aria-label="인시던트 목록">
      <thead>
        <tr>
          <th>인시던트</th>
          <th>발생 시각</th>
          <th>기체</th>
          <th>실행 id</th>
          <th>단위</th>
          <th>실패 종류</th>
          <th>경로</th>
          <th>현장 설정 버전</th>
          <th>임무 버전</th>
          <th>판단</th>
          <th>보류</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.incidentId}
            className={[row.incidentId === selected ? 'selected' : '', row.held ? 'held' : ''].join(' ').trim() || undefined}
          >
            <td>
              <button className="link" aria-label={`${row.incidentId} 상세 보기`} onClick={() => onSelect(row.incidentId)}>
                {row.incidentId}
              </button>
            </td>
            <td>{row.at}</td>
            <td>{row.robotId}</td>
            <td>{row.executionId}</td>
            <td>{row.unitId}</td>
            <td>{row.failureClass ?? '-'}</td>
            <td>{row.route}</td>
            <td>{settingsVersion(row.siteSettingsVersion)}</td>
            <td>{missionVersion(row.missionVersion)}</td>
            <td>{judgement(row)}</td>
            <td>{row.held ? <strong>보류 중</strong> : '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

interface DetailProps {
  detail: IncidentDetail
  session: Session
  busy: boolean
  onDecide: (decision: HoldDecision, reason: string) => void
}

/**
 * 인시던트 상세(S4a 스펙 §8.2). 판단은 «사람의 판단» 에, 봉인 때의 근거는 «관측» 에 따로 둔다. 확인 결과는 코드 이름 그대로
 * 보인다. `NOT_REQUESTED` 를 «설비 확인 안 함» 으로 풀면, 설비 슬롯을 실제로 읽은 인시던트를 틀리게 읽힌다(S4a JSON 계약 §4).
 */
function Detail({ detail, session, busy, onDecide }: DetailProps) {
  const { intent, step } = detail
  const position = step.at === 0 ? `계획에 없음(계획 ${step.plan.length}개)` : `${step.at}/${step.plan.length}`
  return (
    <>
      <Judgement resolution={detail.resolution} withoutEvidence={detail.confirmedWithoutEvidence} />
      <section aria-label="관측" className="observed">
        <h4>관측(봉인 때 기록)</h4>
        <dl>
          <dt>발생 시각(호스트 시계)</dt>
          <dd>{detail.at}</dd>
          <dt>봉인한 실제 시각</dt>
          <dd>{detail.wallClockAt}</dd>
          <dt>기체</dt>
          <dd>{detail.robotId}</dd>
          <dt>실행</dt>
          <dd>
            {detail.executionId}(작업 지시 {detail.jobOrderId})
          </dd>
          <dt>단위</dt>
          <dd>{detail.unitId}</dd>
          <dt>단위의 지금 상태</dt>
          <dd>{detail.unitState ?? '모름(실행 없음)'}</dd>
          <dt>실패 종류</dt>
          <dd>{detail.failureClass ?? '-'}</dd>
          <dt>경로</dt>
          <dd>{detail.route}</dd>
          <dt>현장 설정 버전</dt>
          <dd>{settingsVersion(intent.siteSettingsVersion)}</dd>
          <dt>시간값</dt>
          <dd>
            근거 윈도우 앞 폭 {seconds(intent.evidenceBeforeSeconds)}, 뒤 폭 {seconds(intent.evidenceAfterSeconds)},
            inDoubtGrace {seconds(intent.inDoubtGraceSeconds)}, stallWindow {seconds(intent.stallWindowSeconds)}
          </dd>
          <dt>임무 버전</dt>
          <dd>
            {intent.workMasterId} {missionVersion(intent.missionVersion)}
          </dd>
          <dt>단계 위치</dt>
          <dd>
            {position}. 계획 {step.plan.join(' → ') || '-'}. 끝난 단위 {step.completed.join(', ') || '없음'}
          </dd>
          <dt>필요 근거 등급</dt>
          <dd>{detail.requiredEvidence}</dd>
          <dt>도달 근거 등급</dt>
          <dd>{detail.reachedEvidence}</dd>
          <dt>확인 결과(코드 이름)</dt>
          <dd>{detail.verification}</dd>
          {detail.preconditionSubjects.length > 0 && (
            <>
              <dt>위반된 사전 조건</dt>
              <dd>{detail.preconditionSubjects.join(', ')}</dd>
            </>
          )}
          <dt>파지(기대/관측)</dt>
          <dd>
            {detail.expectedHold ?? '-'} / {detail.observedHold}
          </dd>
          {detail.effectMismatch !== null && (
            <>
              <dt>효과와 관측의 어긋남</dt>
              <dd>{detail.effectMismatch}</dd>
            </>
          )}
          {detail.linkBroken && (
            <>
              <dt>연결</dt>
              <dd>연결이 끊긴 채 돌던 중</dd>
            </>
          )}
        </dl>
        <h4>결함</h4>
        {detail.fault === null ? (
          <p>없음(결함 없이 실패를 알림)</p>
        ) : (
          <FaultCard fault={detail.fault} label="이 단위의 결함" />
        )}
        <h4>실행을 막던 결함(blockedBy)</h4>
        {detail.blockedBy.length === 0 ? (
          <p>없음</p>
        ) : (
          detail.blockedBy.map((fault, index) => (
            <FaultCard key={index} fault={fault} label={`실행을 막던 결함 ${index + 1}`} />
          ))
        )}
        <h4>
          근거 윈도우(앞 {seconds(intent.evidenceBeforeSeconds)}, 뒤 {seconds(intent.evidenceAfterSeconds)})
        </h4>
        {detail.windowTruncated && <p>윈도우 밖이라 버린 관측이 있습니다</p>}
        {detail.evidenceWindow.length === 0 ? (
          <p>윈도우 안의 관측이 없습니다</p>
        ) : (
          <table aria-label="근거 윈도우">
            <thead>
              <tr>
                <th>순번</th>
                <th>시각</th>
                <th>종류</th>
                <th>내용</th>
                <th>출처</th>
              </tr>
            </thead>
            <tbody>
              {detail.evidenceWindow.map((event, index) => (
                <tr key={`${event.sequence}-${index}`}>
                  <td>{event.sequence}</td>
                  <td>{event.occurredAt}</td>
                  <td>{event.kind}</td>
                  <td>{event.detail}</td>
                  <td>{event.local ? '미들웨어 기록' : '현장 관측'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {detail.held && <DecisionForm key={detail.incidentId} detail={detail} session={session} busy={busy} onDecide={onDecide} />}
    </>
  )
}

/**
 * 사람의 판단(운영 관리 화면 설계 제안 §9). 관측과 다른 모양으로, 판단자와 판단한 실제 시각을 함께 보인다. 판단이 없으면 이
 * 상세의 값은 모두 관측이다.
 */
function Judgement({ resolution, withoutEvidence }: { resolution: IncidentResolution | null; withoutEvidence: boolean }) {
  if (resolution === null) return <p>사람의 판단 없음. 아래 값은 모두 관측입니다</p>
  return (
    <section aria-label="사람의 판단" className="asserted">
      <p>
        사람이 판단함: {resolution.decidedBy.id}, {resolution.wallClockAt}
      </p>
      <p>
        결정 {DECISION_LABEL[resolution.decision] ?? resolution.decision}({resolution.decision}), 호스트 시각{' '}
        {resolution.at}
      </p>
      {withoutEvidence && (
        <p>
          <strong>설비 근거 없이 완료 확인</strong>
        </p>
      )}
    </section>
  )
}

function FaultCard({ fault, label }: { fault: FaultDetail; label: string }) {
  return (
    <dl aria-label={label}>
      <dt>분류</dt>
      <dd>
        {fault.failureClass}({fault.errorType})
      </dd>
      <dt>조치 힌트</dt>
      <dd>{fault.errorHint === '' ? '없음' : fault.errorHint}</dd>
      {fault.vendorDetail !== '' && (
        <>
          <dt>벤더 원문</dt>
          <dd>{fault.vendorDetail}</dd>
        </>
      )}
      <dt>참조</dt>
      <dd>{fault.references.map((ref) => `${ref.key}=${ref.value}`).join(', ') || '없음'}</dd>
      <dt>지금 태스크 계속</dt>
      <dd>{fault.canContinueCurrentTask ? '가능' : '불가'}</dd>
      <dt>새 태스크 받기</dt>
      <dd>{fault.canAcceptNewTask ? '가능' : '불가'}</dd>
      <dt>유지</dt>
      <dd>
        {fault.activeUntilKind}
        {fault.activeUntilTime !== '' && ` ${fault.activeUntilTime}`}
      </dd>
    </dl>
  )
}

interface DecisionProps {
  detail: IncidentDetail
  session: Session
  busy: boolean
  onDecide: (decision: HoldDecision, reason: string) => void
}

/**
 * 운영자 판단(S4a 스펙 §8.3). 보류 중인 단위에만 있고 운영자 모드에서만 조작한다. 판단자는 운영 서비스가 행위자로 정하므로
 * 화면이 고르지 않는다(ADR 43). 사유는 조작 기록에만 남는다.
 */
function DecisionForm({ detail, session, busy, onDecide }: DecisionProps) {
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  if (session.mode !== 'operator') return <p>보류 중입니다. 운영자 판단은 운영자 모드에서 합니다</p>

  const send = (decision: HoldDecision) => {
    if (reason.trim() === '') {
      setProblem('판단 사유를 넣으십시오')
      return
    }
    setProblem(null)
    onDecide(decision, reason.trim())
    setReason('')
  }

  return (
    <form aria-label="운영자 판단" onSubmit={(event) => event.preventDefault()}>
      <p>
        {detail.executionId}/{detail.unitId} 보류를 판단합니다. 판단자는 {session.user} 입니다
      </p>
      <label>
        판단 사유
        <input value={reason} onChange={(event) => setReason(event.target.value)} />
      </label>{' '}
      <span className="actions">
        {DECISIONS.map((decision) => (
          <button key={decision} type="button" disabled={busy} onClick={() => send(decision)}>
            {DECISION_LABEL[decision]}
          </button>
        ))}
      </span>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}

/**
 * 판단의 결과(S4a JSON 계약 §9.5). picasso 결과 이름(`outcome`)으로 가른다. 응답 없음은 운영 서비스가 인시던트를 다시 읽어
 * 대조한 결과를 보이고, 확인되기 전에는 판단이 섰다고 보이지 않는다.
 */
export function DecisionNotice({ what, sent }: { what: string; sent: Delivered<HoldResolveOutcome> }) {
  return (
    <p role="status" aria-label="판단 결과">
      {what}: {describeDecision(sent)}
    </p>
  )
}

function describeDecision(sent: Delivered<HoldResolveOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 인시던트 목록에서 확인하십시오`
  const { outcome, answer, rejection, confirmation } = sent.outcome
  switch (outcome) {
    case 'Resolved':
      return answer?.incidentId ? `판단이 섰습니다(${answer.incidentId})` : '판단이 섰습니다'
    case 'NotHeld':
      return '보류 단위가 아닙니다'
    case 'Refused':
      return answer?.detail ? `에이전트 판단은 거부됩니다. ${answer.detail}` : '에이전트 판단은 거부됩니다'
  }
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 판단이 붙음'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 판단이 붙지 않음. 늦게 붙을 수 있으니 인시던트 목록에서 확인하십시오'
    default:
      return '응답 없음. 판단이 붙었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 인시던트 목록에서 확인하십시오'
  }
}
