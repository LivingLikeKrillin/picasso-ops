import { useState } from 'react'
import type { FormEvent } from 'react'
import type { Adapter, Mode, RevisionView, RobotView } from '../api'
import { COMMISSIONING_LABEL, SOFTWARE_LABEL } from '../labels'

interface Props {
  view: RobotView
  adapters: Adapter[]
  revisions: RevisionView[]
  mode: Mode
  busy: boolean
  onBind: (adapterVersionId: number, profileRevisionId: number) => void
  onRecordSiteNames: () => void
}

/**
 * 기체 상세의 카드 3개(P2·S1d 스펙 §9): «바인딩», «사이트 명칭», «시운전». 운영 서비스가 바인딩 칸을 싣지 않으면 그리지
 * 않는다. «시운전 완료» 와 «배정 가능» 을 섞지 않는다 — 화면은 «배정 가능» 을 쓰지 않는다(결정 5).
 */
export function CommissioningCards({ view, adapters, revisions, mode, busy, onBind, onRecordSiteNames }: Props) {
  if (view.commissioning == null) return null
  const binding = view.binding ?? null
  const commissioning = view.commissioning
  const engineer = mode === 'engineer'
  const retired = view.robot.status === 'RETIRED'

  return (
    <>
      <section aria-label="바인딩" className="card">
        <h4>바인딩</h4>
        {binding === null ? (
          <p>활성 바인딩 없음</p>
        ) : (
          <dl>
            <dt>빌드</dt>
            <dd>
              {binding.adapterName} {binding.adapterVersion}
            </dd>
            <dt>개정판</dt>
            <dd>
              {binding.vendor}/{binding.model}#{binding.revision}
            </dd>
            <dt>바인딩한 이</dt>
            <dd>
              {binding.boundBy} {binding.boundAt}
            </dd>
          </dl>
        )}
        {engineer && !retired && <BindForm adapters={adapters} revisions={revisions} busy={busy} onBind={onBind} />}
      </section>

      <section aria-label="사이트 명칭" className="card">
        <h4>사이트 명칭</h4>
        {binding === null ? (
          <p>바인딩이 없어 요구할 명칭이 없습니다</p>
        ) : (
          <dl>
            <dt>명칭 상태</dt>
            <dd>{binding.siteNames}</dd>
            <dt>요구 키</dt>
            <dd>{binding.siteNameKeys.length === 0 ? '없음' : binding.siteNameKeys.join(', ')}</dd>
            <dt>사람이 기록함</dt>
            <dd>
              {binding.siteNamesRegisteredAt === null
                ? '기록 없음'
                : `${binding.siteNamesRegisteredBy} ${binding.siteNamesRegisteredAt}`}
            </dd>
            <dt>기체가 답함</dt>
            <dd>
              {binding.siteNamesReportedAt === null
                ? '아직 답 없음'
                : binding.siteNamesUnsupported
                  ? `명칭을 지원하지 않음 (${binding.siteNamesReportedAt})`
                  : `아는 명칭 ${binding.siteNamesCount ?? 0}개 (${binding.siteNamesReportedAt})`}
            </dd>
          </dl>
        )}
        <p className="offscreen">명칭 티칭은 화면 밖 현장 작업입니다. 화면은 티칭한 사실을 기록할 뿐 대신 하지 않습니다</p>
        {engineer && binding !== null && (
          <button type="button" disabled={busy} onClick={onRecordSiteNames}>
            명칭 등록 기록
          </button>
        )}
      </section>

      <section aria-label="시운전" className="card">
        <h4>시운전: {COMMISSIONING_LABEL[commissioning.state]}</h4>
        <ul>
          <li>
            {mark(commissioning.ledgerConfirmed)} 원장 상태 CONFIRMED, 퇴역 아님 (근거 /diag/robots)
          </li>
          <li>{mark(commissioning.bound)} 활성 바인딩 (근거 /diag/bindings)</li>
          <li>
            {mark(commissioning.siteNamesReady)} 명칭 상태 CONFIRMED 또는 NOT_REQUIRED (근거 /diag/bindings)
          </li>
        </ul>
        <p>
          참고(막지 않음): 소프트웨어 대조{' '}
          {view.software == null ? '보고 없음' : (SOFTWARE_LABEL[view.software.verdict] ?? view.software.verdict)}, 어댑터 적합성{' '}
          {binding?.conformanceStatus ?? '-'}
        </p>
      </section>
    </>
  )
}

const mark = (ok: boolean) => (ok ? '[v]' : '[ ]')

interface BindProps {
  adapters: Adapter[]
  revisions: RevisionView[]
  busy: boolean
  onBind: (adapterVersionId: number, profileRevisionId: number) => void
}

/** 바인딩 폼(엔지니어 모드). 빌드는 어댑터 목록에서, 개정판은 활성 개정판에서 고른다. */
function BindForm({ adapters, revisions, busy, onBind }: BindProps) {
  const [buildId, setBuildId] = useState('')
  const [revisionId, setRevisionId] = useState('')
  const active = revisions.filter((row) => row.revision.status === 'ACTIVE')
  const ready = buildId !== '' && revisionId !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (ready) onBind(Number(buildId), Number(revisionId))
  }

  return (
    <form aria-label="바인딩" onSubmit={submit}>
      <label>
        빌드
        <select value={buildId} onChange={(event) => setBuildId(event.target.value)}>
          <option value="">고르십시오</option>
          {adapters.flatMap((adapter) =>
            adapter.versions.map((build) => (
              <option key={build.adapterVersionId} value={build.adapterVersionId}>
                {adapter.vendor}/{adapter.name} {build.version}
              </option>
            )),
          )}
        </select>
      </label>
      <label>
        개정판
        <select value={revisionId} onChange={(event) => setRevisionId(event.target.value)}>
          <option value="">고르십시오</option>
          {active.map((row) => (
            <option key={row.revision.profileRevisionId} value={row.revision.profileRevisionId}>
              {row.revision.vendor}/{row.revision.model}#{row.revision.revision}
            </option>
          ))}
        </select>
      </label>
      <button type="submit" disabled={!ready || busy}>
        바인딩
      </button>
    </form>
  )
}
