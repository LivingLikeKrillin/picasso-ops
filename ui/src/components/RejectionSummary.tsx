import { REMEDY_CAUSE_LABEL } from '../labels'
/** The host's own rejection of a work request (ops `rejection`). Null alternatives are not an empty list. */
export interface WorkRejection {
 reason: string | null
 remedy: { kind: 'FOUND'; steps: { skillType: string }[] } | { kind: 'NONE'; cause: string } | null
 remedyWithheld: boolean
 alternativeLocations: string[] | null
 sourceMissing: { material: string | null; source: string; observed: string | null } | null
}
/** Only a source-missing rejection asks for other places, so a null list means "not asked" there and nothing elsewhere. */
function alternatives(rejection: WorkRejection) {
 const listed = rejection.alternativeLocations
 if(listed === null) return rejection.sourceMissing ? '대체 위치 조회 불가' : null
 return listed.length === 0 ? '대체 위치 없음' : `대체 위치: ${listed.join(', ')}`
}
function remedy(rejection: WorkRejection) {
 if(rejection.remedyWithheld) return '조치 제안 보류 · 사람이 먼저 진단'
 const value = rejection.remedy
 if(!value) return null
 return value.kind === 'FOUND' ? `조치 제안 ${value.steps.length}단계: ${value.steps.map(step=>step.skillType).join(' → ')}` : `조치 제안 없음 · ${REMEDY_CAUSE_LABEL[value.cause] ?? value.cause}`
}
export function RejectionSummary({ rejection }: { rejection: WorkRejection }) {
 const missing = rejection.sourceMissing; const other = alternatives(rejection); const fix = remedy(rejection)
 return <section aria-label="거부 사유"><p><strong>거부 사유</strong> {rejection.reason ?? '사유 없음'}</p>
  {missing && <p>출발 자리 {missing.source} · {missing.observed === null ? '비어 있음' : `${missing.observed} 있음`}</p>}
  {other && <p>{other}</p>}{fix && <p>{fix}</p>}</section>
}
