const object=(value:unknown):Record<string,unknown> => value!==null && typeof value==='object' && !Array.isArray(value) ? value as Record<string,unknown> : {}
const text=(value:unknown)=>typeof value==='string' && value ? value : '확인 안 됨'
/** A persisted response is historical evidence; absence from the current host is a separate observation. */
export function StoredResultSummary({snapshot}:{snapshot:unknown}) {
 const saved=object(snapshot);const response=object(saved.jobResponse)
 return <section aria-label="저장 결과의 완료 근거"><dl><dt>결과에 기록된 물리 상태</dt><dd>{text(response.physicalState)}</dd><dt>요구 근거 / 도달 근거</dt><dd>{text(response.requiredEvidence)} / {text(response.reachedEvidence)}</dd><dt>결과에 기록된 기체</dt><dd>{text(saved.robotId)}</dd></dl><p>저장된 결과 버전의 관측입니다. 현재 호스트 조회와 품질 승인 여부는 별도로 확인하세요.</p></section>
}
