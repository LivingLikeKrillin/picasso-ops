import type { JobResponseLogRow, JobResponsesView } from '../api'
import { DISPOSITION_LABEL } from '../labels'
import type { HostRead } from './ExecutionList'

interface Props {
  read: HostRead<JobResponsesView>
  /** 고를 수 있는 작업 지시 id. 실행 목록과 읽은 송신 기록에서 모은 것이다. */
  jobOrderIds: string[]
  /** 고른 작업 지시. null 이면 전체다. */
  selected: string | null
  onSelect: (jobOrderId: string | null) => void
}

/** 단위 칸. 빈 묶음은 빼고, 다 비면 `-` 다. 미완 단위는 송신 기록에 사유가 없어 id 만 보인다(S4b 계약 H6). */
function units(row: JobResponseLogRow): string {
  const parts = [
    ['완료', row.completedUnits],
    ['미확인', row.unverifiedUnits],
    ['미완', row.incompleteUnits],
    ['불확실', row.inDoubtUnits],
    ['막은 결함', row.blockedBy],
  ] as const
  const shown = parts.filter(([, ids]) => ids.length > 0).map(([name, ids]) => `${name} ${ids.join(', ')}`)
  return shown.length === 0 ? '-' : shown.join('; ')
}

/**
 * 운영 영역의 «작업 응답 송신 기록» 구역(S4b 스펙 T6·T10, §8). 상위 시스템이 없어 실행 호스트의 송신 기록이 전선 자리를
 * 대신한다. 작업 지시를 고르면 그 작업 지시의 송신 행과 재기동 중복 행을 최근 것부터 보인다. 재기동 중복은 새 인스턴스가
 * 처음 낸 응답이 앞 인스턴스의 마지막 송신과 같아 보내지 않은 것이다. 읽기만 하므로 두 모드가 같다.
 */
export function JobResponseLog({ read, jobOrderIds, selected, onSelect }: Props) {
  const { value, error } = read
  const options = [...new Set([...(selected === null ? [] : [selected]), ...jobOrderIds])]
  const duplicates = value?.responses.filter((row) => row.disposition === 'RESTART_DUPLICATE').length ?? 0
  return (
    <section aria-label="작업 응답 송신 기록">
      <h2>작업 응답 송신 기록</h2>
      <label>
        송신 기록의 작업 지시{' '}
        <select value={selected ?? ''} onChange={(event) => onSelect(event.target.value === '' ? null : event.target.value)}>
          <option value="">전체</option>
          {options.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>
      {value === null ? (
        <p>모름: 송신 기록을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
      ) : (
        <>
          {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
          <p>
            실행 호스트 인스턴스 {value.instanceId}, 송신 기록 {value.total}건 가운데 최신 {value.responses.length}건, 그
            가운데 재기동 중복 {duplicates}건
          </p>
          {value.responses.length === 0 ? (
            <p>송신 기록이 없습니다</p>
          ) : (
            <table aria-label="송신 기록 목록">
              <thead>
                <tr>
                  <th>인스턴스</th>
                  <th>작업 응답 id</th>
                  <th>작업 지시 id</th>
                  <th>실행 id</th>
                  <th>버전</th>
                  <th>물리 상태</th>
                  <th>근거(도달/요구)</th>
                  <th>단위</th>
                  <th>운영자 필요</th>
                  <th>처분</th>
                  <th>적은 시각</th>
                </tr>
              </thead>
              <tbody>
                {value.responses.map((row) => (
                  <tr
                    key={`${row.instanceId}/${row.jobResponseId}`}
                    className={row.disposition === 'RESTART_DUPLICATE' ? 'duplicate' : undefined}
                  >
                    <td>{row.instanceId}</td>
                    <td>{row.jobResponseId}</td>
                    <td>{row.jobOrderId}</td>
                    <td>{row.executionId}</td>
                    <td>{row.version}</td>
                    <td>{row.physicalState}</td>
                    <td>
                      {row.reachedEvidence}/{row.requiredEvidence}
                    </td>
                    <td>{units(row)}</td>
                    <td>{row.operatorRequired ? '필요' : '-'}</td>
                    <td>{DISPOSITION_LABEL[row.disposition] ?? row.disposition}</td>
                    <td>{row.recordedAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  )
}
