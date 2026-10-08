import type { ExecutionsView, JobResponse } from '../api'

/** 실행 호스트를 거친 읽기 하나. 못 읽으면 직전 값을 지우지 않고 [error] 로 불통을 표시한다. */
export interface HostRead<T> {
  value: T | null
  error: string | null
}

interface Props {
  read: HostRead<ExecutionsView>
  /** 방금 낸 작업 지시 id. 그 실행 행을 눈에 띄게 한다. */
  highlight: string | null
}

/**
 * 실행 목록(S3a 스펙 §9.2). 머리에 실행 호스트 인스턴스를 보인다. 호스트를 재기동하면 실행이 사라지고 `exec-N` 을 1부터
 * 다시 세므로, 인스턴스가 바뀐 것으로 구별한다. 임무 버전이 null 이면 코드 정의 임무다. 최신 실행부터 보인다.
 */
export function ExecutionList({ read, highlight }: Props) {
  const { value, error } = read
  if (value === null) {
    return <p>모름: 실행 목록을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
  }
  return (
    <>
      {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
      <dl>
        <dt>실행 호스트 인스턴스</dt>
        <dd>{value.instanceId}</dd>
        <dt>마지막 pump</dt>
        <dd>{value.pumpedAt ?? '아직 없음'}</dd>
      </dl>
      {value.executions.length === 0 ? (
        <p>실행이 없습니다</p>
      ) : (
        <table aria-label="실행 목록">
          <thead>
            <tr>
              <th>실행 id</th>
              <th>작업 지시 id</th>
              <th>임무</th>
              <th>임무 버전</th>
              <th>기체</th>
              <th>물리 상태</th>
              <th>단위</th>
              <th>작업 응답</th>
            </tr>
          </thead>
          <tbody>
            {[...value.executions].reverse().map((execution) => (
              <tr
                key={execution.executionId}
                className={execution.jobOrderId === highlight ? 'selected' : undefined}
              >
                <td>{execution.executionId}</td>
                <td>{execution.jobOrderId}</td>
                <td>{execution.workMasterId}</td>
                <td>{execution.missionVersion === null ? '코드 정의' : `버전 ${execution.missionVersion}`}</td>
                <td>{execution.robotId}</td>
                <td>{execution.physicalState}</td>
                <td>
                  <ul>
                    {execution.units.map((unit) => (
                      <li key={unit.unitId}>
                        {unit.unitId} {unit.skillType}: {unit.state}, 근거 {unit.reached}
                      </li>
                    ))}
                  </ul>
                </td>
                <td>{execution.jobResponse === null ? '없음' : <JobResponseCell response={execution.jobResponse} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}

/** 그 실행의 마지막 작업 응답. 상위 시스템이 없어 실행 호스트의 아웃박스에 남은 것이다(S3a 스펙 §7.7). */
function JobResponseCell({ response }: { response: JobResponse }) {
  const incomplete = Object.entries(response.incompleteUnits)
  return (
    <ul>
      <li>
        {response.jobResponseId}: {response.physicalState}, 근거 {response.reachedEvidence}(요구{' '}
        {response.requiredEvidence})
      </li>
      {response.unverifiedUnits.length > 0 && <li>미확인 단위 {response.unverifiedUnits.join(', ')}</li>}
      {incomplete.length > 0 && (
        <li>미완 단위 {incomplete.map(([unitId, reason]) => `${unitId}(${reason})`).join(', ')}</li>
      )}
      {response.inDoubtUnits.length > 0 && <li>불확실 단위 {response.inDoubtUnits.join(', ')}</li>}
      {response.operatorRequired && <li>운영자 개입 필요</li>}
    </ul>
  )
}
