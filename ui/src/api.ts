export type Mode = 'engineer' | 'operator'
export type RegistryState = 'OK' | 'REGISTRY_SILENT' | 'REGISTRY_UNAUTHORIZED'

export interface Robot {
  robotId: string
  siteId: string
  serialNumber: string | null
  status: string
  lastReportedAt: string | null
  retiredAt: string | null
  reportingAfterRetirement: boolean
}

/** 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface RobotListView {
  registry: RegistryState
  checkedAt: string
  robots: Robot[] | null
  robotsAsOf: string | null
}

export interface OperationRecord {
  requestId: string
  mode: 'ENGINEER' | 'OPERATOR'
  user: string
  target: string
  reason: string | null
  result: string
  recordedAt: string
}

/** 사용자 이름 규칙. 운영 서비스의 `Actor` 와 같다. 헤더는 ASCII 만 실을 수 있고 `/` 는 모드와 사용자를 가르는 자리다. */
export const USER_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

/** 인증 없이 화면이 싣는 모드와 사용자(스펙 §7.1·§8). */
export interface Session {
  mode: Mode
  user: string
}

async function getJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, {
    headers: { 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user },
  })
  if (!response.ok) throw new Error(`운영 서비스 응답 ${response.status}`)
  return (await response.json()) as T
}

export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
export const fetchOperations = (session: Session) =>
  getJson<OperationRecord[]>('/api/operations', session)
