import type { OperationRecord } from '../api'

interface Props {
  records: OperationRecord[] | null
  /** 운영 서비스에 닿지 않으면 [records] 는 직전 값이다. */
  opsError: string | null
}

/** 이력 영역. 조작 기록(행위자·사유·결과)을 보인다(스펙 §8). */
export function HistoryArea({ records, opsError }: Props) {
  if (records === null) return <p>모름: 조작 기록을 아직 읽지 못했습니다</p>
  return (
    <>
      {opsError !== null && <p className="stale">직전 값입니다</p>}
      {records.length === 0 ? (
        <p>조작 기록이 없습니다</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>시각</th>
              <th>행위자</th>
              <th>대상</th>
              <th>사유</th>
              <th>결과</th>
            </tr>
          </thead>
          <tbody>
            {records.map((record) => (
              <tr key={`${record.requestId}-${record.recordedAt}`}>
                <td>{record.recordedAt}</td>
                <td>
                  {record.mode === 'ENGINEER' ? '엔지니어' : '운영자'}/{record.user}
                </td>
                <td>{record.target}</td>
                <td>{record.reason ?? ''}</td>
                <td>{record.result}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
