import { useState } from 'react'
import { declareRobot, reinstateRobot, retireRobot } from '../api'
import type { RobotListView, Sent, Session } from '../api'
import { CONNECTION_LABEL } from '../labels'
import { DeclareForm } from './DeclareForm'
import { OutcomeNotice } from './OutcomeNotice'
import { RobotDetail } from './RobotDetail'

interface Props {
  view: RobotListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  /** 조작이 끝나면 부른다. 목록을 다시 읽는다. */
  onChanged: () => void
}

/** 로봇·연결 영역. 왼쪽 목록과 오른쪽 상세(스펙 §8, 결정 6). */
export function RobotsArea({ view, opsError, session, onChanged }: Props) {
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
    <div className="split">
      <section aria-label="기체 목록">
        <h2>기체</h2>
        {session.mode === 'engineer' ? (
          <DeclareForm
            busy={busy}
            onDeclare={(robotId, serial, displayName) =>
              run(`${robotId} 선언`, () => declareRobot(session, robotId, serial, displayName))
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
      <section aria-label="상세">
        <h2>상세</h2>
        {last !== null && <OutcomeNotice what={last.what} sent={last.sent} onSelect={setSelected} />}
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
            mode={session.mode}
            busy={busy}
            onRetire={(reason) =>
              run(`${current.robot.robotId} 퇴역`, () => retireRobot(session, current.robot.robotId, reason))
            }
            onReinstate={() =>
              run(`${current.robot.robotId} 복귀`, () => reinstateRobot(session, current.robot.robotId))
            }
          />
        )}
      </section>
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
        <table>
          <thead>
            <tr>
              <th>robot_id</th>
              <th>원장 상태</th>
              <th>연결</th>
              <th>막힘</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map(({ robot, connection, blockers }) => (
              <tr key={robot.robotId} className={robot.robotId === selected ? 'selected' : undefined}>
                <td>
                  <button type="button" className="link" onClick={() => onSelect(robot.robotId)}>
                    {robot.robotId}
                  </button>
                </td>
                <td>{robot.status}</td>
                <td>{CONNECTION_LABEL[connection]}</td>
                <td>{blockers.length}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
