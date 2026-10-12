export type AreaId = 'stations' | 'overview' | 'requests' | 'site' | 'robots' | 'missions' | 'operations' | 'history' | 'validation'

export interface Area {
  id: AreaId
  label: string
  /** 지금 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). 현장·자원은 S2, 운영은 S3a, 임무·정책은 S3b 에서 열었다. */
  ready: boolean
}

export const AREAS: readonly Area[] = [
  { id: 'overview', label: '운영 개요', ready: true },
  { id: 'requests', label: '작업 요청', ready: true },
  { id: 'stations', label: '공유 자원', ready: true },
  { id: 'operations', label: '운영', ready: true },
  { id: 'robots', label: '로봇', ready: true },
  { id: 'missions', label: '임무', ready: true },
  { id: 'site', label: '현장 설정', ready: true },
  { id: 'history', label: '이력', ready: true },
  { id: 'validation', label: '검증 도구', ready: true },
]
