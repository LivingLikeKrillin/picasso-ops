import type { ExecutionsView, JobResponse, RestoreRow } from '../api'

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
 *
 * 재기동 뒤(S4b 스펙 T10, §8)에는 목록 위에 이번 기동의 복원 보고를 보이고, 다시 지은 실행의 실행 id 옆에 바로 앞 인스턴스의
 * 실행 id 를 «이전 exec-k» 로 붙인다. 새 인스턴스의 `exec-N` 은 1부터 다시 세므로 같은 id 가 다른 실행일 수 있다.
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
      {value.restore != null && value.restore.rows.length > 0 && (
        <RestoreReport at={value.restore.at} rows={value.restore.rows} />
      )}
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
                <td>
                  {execution.executionId}
                  {execution.restoredFrom != null && (
                    <span className="restored" title={`이전 인스턴스 ${execution.restoredFrom.instanceId}`}>
                      {' '}
                      (이전 {execution.restoredFrom.executionId})
                    </span>
                  )}
                </td>
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

/**
 * 이번 기동의 복원 보고(S4b 계약 H1). 다시 지은 것, 기체 스냅숏을 못 읽어 미룬 것(실행 호스트가 pump 마다 다시 시도),
 * 포기한 것을 나누고, 미룬 것과 포기한 것은 실행 호스트의 사유를 그대로 보인다. 포기한 실행의 기체는 고아 태스크가 끝날
 * 때까지 배정 가능에서 빠진다.
 */
function RestoreReport({ at, rows }: { at: string; rows: RestoreRow[] }) {
  const restored = rows.filter((row) => row.result === 'RESTORED')
  const deferred = rows.filter((row) => row.result === 'DEFERRED')
  const gaveUp = rows.filter((row) => row.result === 'GAVE_UP')
  const unsettled = (row: RestoreRow) => `${row.jobOrderId}(${row.robotId}, 이전 ${row.previousExecutionId}): ${row.reason ?? '사유 없음'}`
  return (
    <section aria-label="재기동 복원 보고" className="restore">
      <p>
        <strong>재기동: 이전 인스턴스의 실행 {restored.length}건을 다시 지었습니다</strong>(복원 시각 {at})
      </p>
      {restored.length > 0 && (
        <ul aria-label="다시 지은 실행">
          {restored.map((row) => (
            <li key={row.jobOrderId}>
              {row.jobOrderId}({row.robotId}): 이전 {row.previousExecutionId} → {row.executionId}
            </li>
          ))}
        </ul>
      )}
      {deferred.length > 0 && (
        <>
          <p>미룬 실행 {deferred.length}건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다</p>
          <ul aria-label="미룬 실행">
            {deferred.map((row) => (
              <li key={row.jobOrderId}>{unsettled(row)}</li>
            ))}
          </ul>
        </>
      )}
      {gaveUp.length > 0 && (
        <>
          <p>포기한 실행 {gaveUp.length}건. 다시 짓지 않습니다. 기체의 남은 태스크를 운영자가 확인하십시오</p>
          <ul aria-label="포기한 실행">
            {gaveUp.map((row) => (
              <li key={row.jobOrderId}>{unsettled(row)}</li>
            ))}
          </ul>
        </>
      )}
    </section>
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
