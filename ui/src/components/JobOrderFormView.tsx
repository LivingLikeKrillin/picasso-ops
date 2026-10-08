import type { CellView, Mode, WorkMasterId } from '../api'
import { materialsOf, presentationFor, toggleSlot } from '../jobOrderDraft'
import type { JobOrderDraft } from '../jobOrderDraft'

interface Props {
  draft: JobOrderDraft
  /** 셀 대역. null 이면 모름이다(아직 못 읽었거나 실행 호스트가 셀 대역을 못 읽음). */
  cell: CellView['cell']
  mode: Mode
  busy: boolean
  /** 제출을 눌렀는데 보내지 않은 이유. */
  problem: string | null
  onChange: (draft: JobOrderDraft) => void
  onSubmit: () => void
}

const WORK_MASTERS: readonly WorkMasterId[] = ['InspectAsset', 'PrepareSequencedRack']

/**
 * 작업 지시 폼(S3a 스펙 §9.1). 임무마다 입력이 다르다. 입력은 모드와 관계없이 보여 엔지니어도 배정 가능을 볼 수 있고,
 * 제출만 운영자 모드에서 한다(결정 3). 장소 이름과 슬롯이 기체가 아는 명칭인지는 화면이 검사하지 않는다(스펙 §12).
 */
export function JobOrderFormView({ draft, cell, mode, busy, problem, onChange, onSubmit }: Props) {
  return (
    <form
      aria-label="작업 지시 폼"
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit()
      }}
    >
      <label>
        임무
        <select
          value={draft.workMasterId}
          onChange={(event) => onChange({ ...draft, workMasterId: event.target.value as WorkMasterId })}
        >
          {WORK_MASTERS.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>
      {draft.workMasterId === 'InspectAsset' ? (
        <Targets draft={draft} onChange={onChange} />
      ) : (
        <RackChoice draft={draft} cell={cell} onChange={onChange} />
      )}
      {mode === 'operator' ? (
        <button type="submit" disabled={busy}>
          작업 지시 내기
        </button>
      ) : (
        <p>작업 지시는 운영자 모드에서 냅니다</p>
      )}
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}

interface PartProps {
  draft: JobOrderDraft
  onChange: (draft: JobOrderDraft) => void
}

/** InspectAsset 의 점검 대상 목록. 대상마다 이동 단위와 점검 단위가 생긴다. */
function Targets({ draft, onChange }: PartProps) {
  const set = (index: number, field: 'id' | 'location', value: string) =>
    onChange({
      ...draft,
      targets: draft.targets.map((target, i) => (i === index ? { ...target, [field]: value } : target)),
    })
  return (
    <fieldset>
      <legend>점검 대상</legend>
      {draft.targets.map((target, index) => (
        <div key={index}>
          <label>
            대상 {index + 1} id
            <input value={target.id} onChange={(event) => set(index, 'id', event.target.value)} />
          </label>
          <label>
            대상 {index + 1} 장소
            <input value={target.location} onChange={(event) => set(index, 'location', event.target.value)} />
          </label>
          <button
            type="button"
            onClick={() => onChange({ ...draft, targets: draft.targets.filter((_, i) => i !== index) })}
          >
            대상 {index + 1} 빼기
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={() => onChange({ ...draft, targets: [...draft.targets, { id: '', location: '' }] })}
      >
        대상 더하기
      </button>
      <p className="offscreen">장소 이름은 기체가 아는 명칭이어야 합니다. 화면은 검사하지 않습니다</p>
    </fieldset>
  )
}

/** PrepareSequencedRack 의 슬롯과 자재. 둘 다 셀 대역에서 고른다. 제시 자리는 자재에서 정해진다. */
function RackChoice({ draft, cell, onChange }: PartProps & { cell: CellView['cell'] }) {
  if (cell === null) return <p>모름: 셀 대역을 읽지 못해 슬롯과 자재를 고를 수 없습니다</p>
  const presentation = draft.material === '' ? null : presentationFor(cell, draft.material)
  return (
    <>
      <fieldset>
        <legend>슬롯</legend>
        {cell.slots.map((slot) => (
          <label key={slot.id}>
            <input
              type="checkbox"
              checked={draft.slots.includes(slot.id)}
              onChange={() => onChange({ ...draft, slots: toggleSlot(cell, draft.slots, slot.id) })}
            />
            {slot.id}
          </label>
        ))}
      </fieldset>
      <label>
        자재
        <select value={draft.material} onChange={(event) => onChange({ ...draft, material: event.target.value })}>
          <option value="">고르십시오</option>
          {materialsOf(cell).map((material) => (
            <option key={material} value={material}>
              {material}
            </option>
          ))}
        </select>
      </label>
      <p>제시 자리 {presentation ?? '-'}</p>
    </>
  )
}
