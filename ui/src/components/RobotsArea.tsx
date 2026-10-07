import type { RobotListView } from '../api'

interface Props {
  view: RobotListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
}

/** 로봇·연결 영역. 왼쪽 목록과 오른쪽 상세(스펙 §8, 결정 6). 상세와 조작은 S1b 에서 채운다. */
export function RobotsArea({ view, opsError }: Props) {
  return (
    <div className="split">
      <section aria-label="기체 목록">
        <h2>기체</h2>
        <RobotList view={view} opsError={opsError} />
      </section>
      <section aria-label="상세">
        <h2>상세</h2>
        <p>기체를 고르면 원장 상태와 연결이 여기에 보입니다.</p>
      </section>
    </div>
  )
}

function RobotList({ view, opsError }: Props) {
  if (view === null || view.robots === null) {
    return <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
  }
  // registry 가 침묵하거나 운영 서비스에 닿지 않으면 보이는 목록은 직전 값이다(스펙 §9).
  const stale = view.registry !== 'OK' || opsError !== null
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
              <th>마지막 보고</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map((robot) => (
              <tr key={robot.robotId}>
                <td>{robot.robotId}</td>
                <td>{robot.status}</td>
                <td>{robot.lastReportedAt ?? '보고 없음'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
