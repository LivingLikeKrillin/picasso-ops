import type { RobotListView } from '../api'

interface Props {
  view: RobotListView | null
  opsError: string | null
}

/** 화면 전체 상태. 기체 행마다 반복하지 않고 상단 한 자리에 둔다(스펙 §7.4·§8). */
export function RegistryBanner({ view, opsError }: Props) {
  if (opsError !== null) {
    return (
      <div role="alert" className="banner unknown">
        모름: 운영 서비스에 닿지 않습니다 ({opsError})
      </div>
    )
  }
  if (view === null) return <div className="banner">확인 중</div>
  switch (view.registry) {
    case 'OK':
      return <div className="banner ok">registry 응답 확인 {view.checkedAt}</div>
    case 'REGISTRY_SILENT':
      return (
        <div role="alert" className="banner unknown">
          모름: registry 가 답하지 않습니다. 해결 담당 엔지니어, registry 상태 확인. 확인 시각{' '}
          {view.checkedAt}
        </div>
      )
    case 'REGISTRY_UNAUTHORIZED':
      return (
        <div role="alert" className="banner unknown">
          모름: registry 가 운영자 토큰을 거절합니다. 해결 담당 엔지니어, 운영자 토큰 설정 확인. 확인
          시각 {view.checkedAt}
        </div>
      )
  }
}
