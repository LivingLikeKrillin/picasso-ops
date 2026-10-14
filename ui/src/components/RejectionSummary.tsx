import { REMEDY_CAUSE_LABEL, alternativesLabel, sourceMissingLabel, sourceUnobservedLabel } from '../labels'
import type { SourceMissing, SourceUnobserved, UnassignedRefusal } from '../api'
/** The host's own rejection of a work request (ops `rejection`). Null alternatives are not an empty list. */
export interface WorkRejection {
 reason: string | null
 remedy: { kind: 'FOUND'; steps: { skillType: string }[] } | { kind: 'NONE'; cause: string } | null
 remedyWithheld: boolean
 alternativeLocations: string[] | null
 sourceMissing: SourceMissing | null
 /** 출발 미관측(결품이 아니다). 칸이 없던 서버 본문은 undefined 다. */
 sourceUnobserved?: SourceUnobserved | null
}
function remedy(rejection: WorkRejection) {
 if(rejection.remedyWithheld) return '조치 제안 보류 · 사람이 먼저 진단'
 const value = rejection.remedy
 if(!value) return null
 return value.kind === 'FOUND' ? `조치 제안 ${value.steps.length}단계: ${value.steps.map(step=>step.skillType).join(' → ')}` : `조치 제안 없음 · ${REMEDY_CAUSE_LABEL[value.cause] ?? value.cause}`
}
export function RejectionSummary({ rejection }: { rejection: WorkRejection }) {
 const missing = rejection.sourceMissing; const other = alternativesLabel(rejection); const fix = remedy(rejection)
 return <section aria-label="거부 사유"><p><strong>거부 사유</strong> {rejection.reason ?? '사유 없음'}</p>
  {missing && <p>{sourceMissingLabel(missing)}</p>}
  {rejection.sourceUnobserved && <p>{sourceUnobservedLabel(rejection.sourceUnobserved)}</p>}
  {other && <p>{other}</p>}{fix && <p>{fix}</p>}</section>
}
/** Per-robot source-missing answers of a host UNASSIGNED. Robots refused for another reason carry none and are left out. */
export function UnassignedSourceMissing({ refusals }: { refusals: UnassignedRefusal[] }) {
 const missing = refusals.filter(refusal => refusal.sourceMissing)
 if(missing.length === 0) return null
 return <section aria-label="기체별 출발 결품"><h4>기체별 출발 결품</h4><ul>{missing.map(refusal => <li key={refusal.robotId}><strong>{refusal.robotId}</strong>
  <p>{sourceMissingLabel(refusal.sourceMissing!)}</p><p>{alternativesLabel(refusal)}</p></li>)}</ul></section>
}
