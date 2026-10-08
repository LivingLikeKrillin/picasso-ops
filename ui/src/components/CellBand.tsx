import type { CellPlace, CellSignal, CellView } from '../api'
import type { HostRead } from './ExecutionList'

interface Props {
  read: HostRead<CellView>
  busy: boolean
  /** 신호 하나를 그 값으로 쓴다. 결과 알림과 다시 읽기는 부르는 쪽이 한다. */
  onWrite: (name: string, value: string) => void
}

/**
 * 셀 대역 표시(S3a 스펙 §6.3·§9.2). 슬롯은 기체가 보고한 배치에서 채우므로 독립 설비 확인이 아니다. 그래서 «셀» 이 아니라
 * «셀 대역» 이라고 적는다. 실행 호스트가 셀 대역을 못 읽으면(`cell` null) 비어 있는 셀로 접지 않고 모름이다.
 *
 * 이름 있는 신호는 따로 표로 보인다(S3b 스펙 §8). 사람이 PLC 역할을 하는 정상 조작이라 두 모드 모두 켜고 끈다(결정 3).
 */
export function CellBand({ read, busy, onWrite }: Props) {
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
            <>
              <p>모름: 실행 호스트가 셀 대역을 읽지 못했습니다</p>
              <p>모름: 신호 값을 읽지 못했습니다</p>
            </>
          ) : (
            <>
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
              <Signals signals={value.cell.signals} busy={busy} onWrite={onWrite} />
            </>
          )}
        </>
      )}
    </>
  )
}

/**
 * 신호 표(S3b 스펙 §8). 안전이 아닌 BOOLEAN 신호만 켜기·끄기를 둔다. 안전 신호는 값만 보인다. 실제 안전 PLC 를 소프트웨어에서
 * 쓸 수 없는 것과 같게 쓰기는 현장 대역이 거부하므로(ADR 32) 화면도 버튼을 두지 않는다. 신호 목록이 없으면 신호가 없는 것으로
 * 접지 않고 모름이다(S3b JSON 계약 §6).
 */
function Signals({ signals, busy, onWrite }: { signals: CellSignal[] | null } & Omit<Props, 'read'>) {
  if (signals === null) return <p>모름: 셀 대역이 신호 목록을 싣지 않았습니다</p>
  if (signals.length === 0) return <p>셀 대역에 신호가 없습니다</p>
  return (
    <table aria-label="셀 대역 신호">
      <thead>
        <tr>
          <th>신호</th>
          <th>종류</th>
          <th>값</th>
          <th>관측 시각</th>
          <th>조작</th>
        </tr>
      </thead>
      <tbody>
        {signals.map((signal) => (
          <tr key={signal.name}>
            <td>{signal.name}</td>
            <td>{signal.kind}</td>
            <td>{signal.value}</td>
            <td>{signal.observedAt ?? '-'}</td>
            <td>
              {signal.safety ? (
                '안전 신호(값만 봅니다)'
              ) : signal.kind === 'BOOLEAN' ? (
                <>
                  <button
                    type="button"
                    aria-label={`${signal.name} 켜기`}
                    disabled={busy}
                    onClick={() => onWrite(signal.name, 'true')}
                  >
                    켜기
                  </button>
                  <button
                    type="button"
                    aria-label={`${signal.name} 끄기`}
                    disabled={busy}
                    onClick={() => onWrite(signal.name, 'false')}
                  >
                    끄기
                  </button>
                </>
              ) : (
                '-'
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
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
