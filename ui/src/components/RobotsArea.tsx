import { useState } from 'react'
import { bindRobot, declareRobot, recordSiteNames, reinstateRobot, retireRobot } from '../api'
import type { AdapterListView, ProfileListView, RobotListView, Sent, Session } from '../api'
import { COMMISSIONING_LABEL, CONNECTION_LABEL } from '../labels'
import { AdaptersSection } from './AdaptersSection'
import { DeclareForm } from './DeclareForm'
import { OutcomeNotice } from './OutcomeNotice'
import { ProfilesSection } from './ProfilesSection'
import { RobotDetail } from './RobotDetail'
import { OnboardingGuide } from './OnboardingGuide'

interface Props {
  view: RobotListView | null
  adapters: AdapterListView | null
  profiles: ProfileListView | null
  /** 운영 서비스에 닿지 않으면 [view]·[adapters]·[profiles] 는 직전 값이다. */
  opsError: string | null
  session: Session
  /** 조작이 끝나면 부른다. 목록을 다시 읽는다. */
  onChanged: () => void
  onOpenOperations?: () => void
}

/** 기체 관리와 도입 준비, 어댑터·프로파일 관리. 탭 전환 시 작성 중인 입력을 유지한다. */
export function RobotsArea({ view, adapters, profiles, opsError, session, onChanged, onOpenOperations }: Props) {
  const [tab, setTab] = useState<'fleet' | 'onboarding' | 'adapters' | 'profiles'>('fleet')
  const [selected, setSelected] = useState<string | null>(null)
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

  const current = view?.robots?.find((candidate) => candidate.robot.robotId === selected) ?? null

  return (
    <div className="robot-workspace">
      <div className="robot-toolbar">
        <nav aria-label="로봇 관리">
          {([['fleet', '기체'], ['onboarding', '도입 준비'], ['adapters', '어댑터 관리'], ['profiles', '프로파일 관리']] as const).map(([id, label]) => (
            <button key={id} type="button" aria-current={tab === id ? 'page' : undefined} onClick={() => setTab(id)}>{label}</button>
          ))}
        </nav>
        <button type="button" className="primary" onClick={() => setTab('onboarding')}>로봇 추가</button>
      </div>
      {last !== null && <OutcomeNotice what={last.what} sent={last.sent} onSelect={(id) => { setSelected(id); setTab('fleet') }} />}
      <div hidden={tab !== 'onboarding'}>
        <OnboardingGuide view={view} adapters={adapters} profiles={profiles} opsError={opsError} current={tab === 'onboarding' ? current : null}
          onManage={setTab} onSelect={setSelected} onOpenOperations={onOpenOperations} />
      </div>
      <div className="split robots-layout" hidden={tab !== 'fleet' && tab !== 'onboarding'}>
        <div>
          <section aria-label="기체 목록">
            <h2>{tab === 'onboarding' ? '기체 등록 · 준비 재개' : '기체'}</h2>
            <p>처음 도입하는 유형은 도입 준비에서 어댑터와 프로파일을 먼저 확인하세요. 기존 유형의 기체는 아래에서 바로 등록할 수 있습니다.</p>
            {session.mode === 'engineer' ? (
              <DeclareForm
                busy={busy}
                onDeclare={(robotId, serial, displayName) =>
                  run(`${robotId} 선언`, async () => {
                    const sent = await declareRobot(session, robotId, serial, displayName)
                    if (sent.kind === 'outcome' && (sent.outcome.result === 'SUCCEEDED' || sent.outcome.confirmation === 'CONFIRMED_APPLIED')) setSelected(robotId)
                    return sent
                  })
                }
              />
            ) : (
              <p>선언은 엔지니어 모드에서 합니다</p>
            )}
            <RobotList view={view} opsError={opsError} selected={selected} onSelect={setSelected} />
            <p className="offscreen">
              화면 밖 작업: 로봇 내부 지도와 웨이포인트 티칭, mimic 기동(site/ 런처). 화면은 완료를 대신 체크하지 않습니다
            </p>
          </section>

        </div>
        <section aria-label="상세" id="robot-detail" tabIndex={-1}>
          <h2>상세</h2>

          {current === null ? (
            <p>
              {selected === null
                ? '기체를 고르면 원장 상태와 연결이 여기에 보입니다.'
                : `목록에 없는 기체입니다: ${selected}`}
            </p>
          ) : (
            <RobotDetail
              key={current.robot.robotId}
              view={current}
              basis={
                view?.settingsVersion != null && view.connectionThresholdSeconds != null
                  ? { version: view.settingsVersion, seconds: view.connectionThresholdSeconds }
                  : null
              }
              mode={session.mode}
              busy={busy}
              onRetire={(reason) =>
                run(`${current.robot.robotId} 퇴역`, () => retireRobot(session, current.robot.robotId, reason))
              }
              onReinstate={() =>
                run(`${current.robot.robotId} 복귀`, () => reinstateRobot(session, current.robot.robotId))
              }
              adapters={adapters?.adapters ?? []}
              revisions={profiles?.revisions ?? []}
              onBind={(adapterVersionId, profileRevisionId) =>
                run(`${current.robot.robotId} 바인딩`, () =>
                  bindRobot(session, current.robot.robotId, adapterVersionId, profileRevisionId),
                )
              }
              onRecordSiteNames={() =>
                run(`${current.robot.robotId} 명칭 기록`, () => recordSiteNames(session, current.robot.robotId))
              }
            />
          )}
        </section>
      </div>
      <div hidden={tab !== 'adapters'}><AdaptersSection view={adapters} opsError={opsError} session={session} busy={busy} run={run} /></div>
      <div hidden={tab !== 'profiles'}><ProfilesSection view={profiles} opsError={opsError} session={session} busy={busy} run={run} /></div>
    </div>
  )
}

interface ListProps {
  view: RobotListView | null
  opsError: string | null
  selected: string | null
  onSelect: (robotId: string) => void
}

function RobotList({ view, opsError, selected, onSelect }: ListProps) {
  if (view === null || view.robots === null) {
    return <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
  }
  // 목록을 읽은 시각이 확인 시각과 다르거나 운영 서비스에 닿지 않으면 직전 값이다(스펙 §9).
  // 토큰 불일치는 목록을 새로 읽었으므로 직전 값이 아니다.
  const stale = view.robotsAsOf !== view.checkedAt || opsError !== null
  return (
    <>
      {stale && <p className="stale">직전 값입니다 ({view.robotsAsOf} 기준)</p>}
      {view.robots.length === 0 ? (
        <p>선언된 기체가 없습니다</p>
      ) : (
        <div className="table-scroll" tabIndex={0} role="group" aria-label="표 가로 스크롤"><table>
          <thead>
            <tr>
              <th>robot_id</th>
              <th>원장 상태</th>
              <th>연결</th>
              <th>시운전</th>
              <th>막힘</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map(({ robot, connection, blockers, commissioning }) => (
              <tr key={robot.robotId} className={robot.robotId === selected ? 'selected' : undefined}>
                <td>
                  <button type="button" className="link" onClick={() => onSelect(robot.robotId)}>
                    {robot.robotId}
                  </button>
                </td>
                <td>{robot.status}</td>
                <td>{CONNECTION_LABEL[connection]}</td>
                <td>{commissioning != null ? COMMISSIONING_LABEL[commissioning.state] : '-'}</td>
                <td>{blockers.length}</td>
              </tr>
            ))}
          </tbody>
        </table></div>
      )}
    </>
  )
}
