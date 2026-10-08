export type AreaId = 'site' | 'robots' | 'missions' | 'operations' | 'history'

export interface Area {
  id: AreaId
  label: string
  /** 지금 동작하는 영역. 나머지는 다음 단계로 표시한다(스펙 §8). 현장·자원은 S2, 운영은 S3a 에서 열었다. */
  ready: boolean
}

export const AREAS: readonly Area[] = [
  { id: 'site', label: '현장·자원', ready: true },
  { id: 'robots', label: '로봇·연결', ready: true },
  { id: 'missions', label: '임무·정책', ready: false },
  { id: 'operations', label: '운영', ready: true },
  { id: 'history', label: '이력', ready: true },
]
