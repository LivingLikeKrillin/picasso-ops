import type { MockRunView } from '../api'
import { mockRunVerdict } from '../labels'

/**
 * 모의 실행 하나의 결과(S3b 스펙 §8). 통과 여부, 실패 이유, 표본 작업 지시와 단위별 표다. 모의 실행은 이상적 현장에서 정의가 끝까지
 * 도는지를 볼 뿐 현장 사실을 보증하지 않는다(S3b 스펙 T8).
 */
export function MockRunReport({ run }: { run: MockRunView }) {
  const { result } = run
  return (
    <>
      <p>
        모의 실행 {run.mockRunId}: {mockRunVerdict(run)}
        {result.detail !== null && `. ${result.detail}`}
      </p>
      <dl>
        <dt>표본 작업 지시</dt>
        <dd>
          {result.sample === null
            ? '없음'
            : `${result.sample.jobOrderId}, 슬롯 ${result.sample.slots.join(', ')}, 자재 ${result.sample.material}, 요구 근거 ${result.sample.requiredEvidence}`}
        </dd>
        <dt>물리 상태</dt>
        <dd>{result.physicalState ?? '실행이 서지 않음'}</dd>
        <dt>가상 경과</dt>
        <dd>{result.virtualElapsedSeconds}초</dd>
      </dl>
      {result.units.length > 0 && (
        <table aria-label={`모의 실행 ${run.mockRunId} 단위`}>
          <thead>
            <tr>
              <th>단위</th>
              <th>경로</th>
              <th>스킬</th>
              <th>상태</th>
              <th>근거</th>
              <th>실패 분류</th>
            </tr>
          </thead>
          <tbody>
            {result.units.map((unit) => (
              <tr key={unit.unitId}>
                <td>{unit.unitId}</td>
                <td>{unit.route === 'SIGNAL' ? '설비 대기' : '기체'}</td>
                <td>{unit.skillType}</td>
                <td>{unit.state}</td>
                <td>{unit.reached}</td>
                <td>{unit.failureClass ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
