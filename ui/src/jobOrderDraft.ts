import type { CellView, InspectionTarget, JobOrderForm, WorkMasterId } from './api'

/** 작업 지시 폼에 든 값. 임무를 바꿔도 다른 임무의 입력을 지우지 않는다. */
export interface JobOrderDraft {
  workMasterId: WorkMasterId
  /** InspectAsset 의 점검 대상. 입력 그대로이고 보낼 때 앞뒤 공백을 뗀다. */
  targets: InspectionTarget[]
  /** PrepareSequencedRack 에 고른 슬롯. 셀 대역의 슬롯 순서를 따른다. */
  slots: string[]
  /** PrepareSequencedRack 에 고른 자재. 빈 문자열이면 아직 고르지 않았다. */
  material: string
}

export const EMPTY_DRAFT: JobOrderDraft = {
  workMasterId: 'InspectAsset',
  targets: [{ id: '', location: '' }],
  slots: [],
  material: '',
}

type Cell = NonNullable<CellView['cell']>

/** 자재 선택지. 점유된 제시 자리가 든 자재이고, 같은 자재는 한 번만 낸다. */
export function materialsOf(cell: Cell): string[] {
  const materials = cell.presentations.flatMap((place) =>
    place.occupied && place.material !== null ? [place.material] : [],
  )
  return [...new Set(materials)]
}

/**
 * 자재에서 정해지는 제시 자리(S3a 스펙 §7.5). 그 자재를 든 제시 자리 중 셀 대역 목록의 처음 것이다. 채운 슬롯은 집을 자리가
 * 아니므로 보지 않는다.
 */
export function presentationFor(cell: Cell, material: string): string | null {
  return cell.presentations.find((place) => place.occupied && place.material === material)?.id ?? null
}

/** 슬롯 하나를 넣거나 뺀다. 결과는 셀 대역의 슬롯 순서다. */
export function toggleSlot(cell: Cell, chosen: string[], slot: string): string[] {
  const next = chosen.includes(slot) ? chosen.filter((id) => id !== slot) : [...chosen, slot]
  return cell.slots.map((place) => place.id).filter((id) => next.includes(id))
}

export type Built = { kind: 'form'; form: JobOrderForm } | { kind: 'incomplete'; problem: string }

/**
 * 폼 값에서 운영 서비스로 보낼 폼 초안을 만든다(S3a JSON 계약 §9.1). 덜 채운 폼은 보내지 않고 이유를 낸다. 단위 id 겹침과 대상 id
 * 길이는 운영 서비스가 판정하고 화면은 그 거부를 보인다. 같은 규칙을 두 자리에 두지 않기 위해서다.
 *
 * @param cell 셀 대역. null 이면 모름이라 PrepareSequencedRack 의 슬롯과 제시 자리를 정할 수 없다
 */
export function buildForm(draft: JobOrderDraft, cell: Cell | null): Built {
  if (draft.workMasterId === 'InspectAsset') {
    const targets = draft.targets.map((target) => ({ id: target.id.trim(), location: target.location.trim() }))
    if (targets.length === 0) return { kind: 'incomplete', problem: '점검 대상을 하나 이상 넣으십시오' }
    if (targets.some((target) => target.id === '' || target.location === '')) {
      return { kind: 'incomplete', problem: '점검 대상마다 대상 id 와 장소 이름을 넣으십시오' }
    }
    return { kind: 'form', form: { workMasterId: 'InspectAsset', targets } }
  }
  if (cell === null) return { kind: 'incomplete', problem: '셀 대역을 읽지 못해 슬롯과 자재를 고를 수 없습니다' }
  if (draft.slots.length === 0) return { kind: 'incomplete', problem: '슬롯을 하나 이상 고르십시오' }
  if (draft.material === '') return { kind: 'incomplete', problem: '자재를 고르십시오' }
  const presentation = presentationFor(cell, draft.material)
  if (presentation === null) {
    return { kind: 'incomplete', problem: `${draft.material} 를 든 제시 자리가 셀 대역에 없습니다` }
  }
  return {
    kind: 'form',
    form: { workMasterId: 'PrepareSequencedRack', slots: draft.slots, material: draft.material, presentation },
  }
}
