import type { CellPlace, CellView } from '../api'
import type { HostRead } from './ExecutionList'

interface Props {
  read: HostRead<CellView>
}

/**
 * 셀 대역 표시(S3a 스펙 §6.3·§9.2). 슬롯은 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아니다. 그래서 «셀» 이 아니라
 * «셀 대역» 이라고 적는다. 실행 호스트가 셀 대역을 못 읽으면(`cell` null) 비어 있는 셀로 접지 않고 모름이다.
 */
export function CellBand({ read }: Props) {
  const { value, error } = read
  return (
    <>
      <p className="offscreen">셀 대역: 슬롯은 기체가 보고한 배치로 채웁니다. 독립 설비 확인이 아닙니다</p>
      {value === null ? (
        <p>모름: 셀 대역을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
      ) : (
        <>
          {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
          {value.cell === null ? (
            <p>모름: 실행 호스트가 셀 대역을 읽지 못했습니다</p>
          ) : (
            <table aria-label="셀 대역 자리">
              <thead>
                <tr>
                  <th>자리</th>
                  <th>종류</th>
                  <th>점유</th>
                  <th>자재</th>
                  <th>관측 시각</th>
                </tr>
              </thead>
              <tbody>
                {value.cell.presentations.map((place) => (
                  <Place key={place.id} place={place} kind="제시 자리" />
                ))}
                {value.cell.slots.map((place) => (
                  <Place key={place.id} place={place} kind="슬롯" />
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </>
  )
}

function Place({ place, kind }: { place: CellPlace; kind: string }) {
  return (
    <tr>
      <td>{place.id}</td>
      <td>{kind}</td>
      <td>{place.occupied ? '점유' : '비어 있음'}</td>
      <td>{place.material ?? '-'}</td>
      <td>{place.observedAt ?? '-'}</td>
    </tr>
  )
}
