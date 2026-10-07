export type Mode = 'engineer' | 'operator'
export type RegistryState = 'OK' | 'REGISTRY_SILENT' | 'REGISTRY_UNAUTHORIZED'
export type Connection = 'FRESH' | 'STALE' | 'NO_REPORT'
export type Owner = 'SITE' | 'OPERATOR' | 'ENGINEER' | 'NONE'

export interface Robot {
  robotId: string
  siteId: string
  serialNumber: string | null
  displayName: string | null
  status: string
  lastReportedAt: string | null
  retiredAt: string | null
  retiredReason: string | null
  reportingAfterRetirement: boolean
}

/** 막힘이나 거절 한 건. 화면에 내는 칸 5개와 맞춘다(스펙 §7.4). */
export interface Finding {
  kind: string
  observed: string
  expected: string
  checkedAt: string
  owner: Owner
  inScreen: boolean
  action: string
  target: string | null
}

/**
 * 활성 바인딩 한 줄(registry `/diag/bindings`). 명칭 상태는 registry 의 5값이다. 사람의 기록(`siteNamesRegistered*`)과
 * 기체의 답(`siteNamesReported*`)을 따로 싣는다(P2·S1d 스펙 §9).
 */
export interface Binding {
  robotId: string
  vendor: string
  model: string
  profileRevisionId: number
  revision: number
  adapterName: string
  adapterVersion: string
  conformanceStatus: string
  active: boolean
  siteNames: string
  siteNameKeys: string[]
  adapterVersionId: number
  boundBy: string
  boundAt: string
  siteNamesRegisteredBy: string | null
  siteNamesRegisteredAt: string | null
  siteNamesReportedAt: string | null
  siteNamesCount: number | null
  siteNamesUnsupported: boolean | null
}

export type CommissioningState = 'COMPLETE' | 'INCOMPLETE' | 'RETIRED'

/** 시운전 판정(결정 6). 세 조건을 따로 들고 있어 «시운전» 카드가 체크 목록으로 보인다. */
export interface Commissioning {
  state: CommissioningState
  ledgerConfirmed: boolean
  bound: boolean
  siteNamesReady: boolean
}

/** 소프트웨어 대조(`MATCH`·`MISMATCH`·`UNREPORTED`). 시운전을 막지 않고 보이기만 한다. */
export interface Software {
  robotId: string
  declared: string | null
  reported: string | null
  verdict: string
}

/**
 * 기체 한 대. 원장 상태는 registry 값 그대로, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3).
 * 바인딩·시운전·소프트웨어 대조는 S1d 의 칸이다(P2·S1d 스펙 §8.1). 운영 서비스가 싣지 않으면 없다.
 */
export interface RobotView {
  robot: Robot
  connection: Connection
  blockers: Finding[]
  binding?: Binding | null
  commissioning?: Commissioning | null
  software?: Software | null
}

/** 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface RobotListView {
  registry: RegistryState
  checkedAt: string
  robots: RobotView[] | null
  robotsAsOf: string | null
}

/** 어댑터 빌드 하나. `conformance` 는 registry 값 그대로다(S1 은 `UNTESTED` 만 본다). */
export interface AdapterBuild {
  adapterVersionId: number
  version: string
  contractSemver: string
  conformance: string
  registeredAt: string | null
  registeredBy: string | null
}

/** 어댑터 제품 하나와 그 빌드들. */
export interface Adapter {
  adapterId: number
  vendor: string
  name: string
  versions: AdapterBuild[]
}

/** 이 사이트에 등록된 어댑터 인스턴스. 빌드는 제품 이름(`vendor/name`)과 버전으로만 가리킨다. */
export interface AdapterInstance {
  instanceId: string
  siteId: string
  fleetEndpoint: string | null
  registeredAt: string | null
  registeredBy: string | null
  adapter: string
  version: string
  contractSemver: string
  conformance: string
  discoveredRobots: number
}

/** 운영 서비스의 `GET /api/adapters`. 목록이 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface AdapterListView {
  registry: RegistryState
  checkedAt: string
  adapters: Adapter[] | null
  instances: AdapterInstance[] | null
  asOf: string | null
}

/** 계약이 아는 스킬 종류. 카탈로그는 registry 가 기동 때 계약에서 채운다. */
export interface SkillType {
  name: string
  major: number
  introducedInSemver: string
  siteReferenceKeys: string[]
}

export interface Catalog {
  contractSemver: string
  skillTypes: SkillType[]
}

/** 스위트 하나의 최신 실행. `detail` 은 실행기가 낸 JSON 그대로다(`checks`·`failures`). */
export interface SuiteRun {
  result: string
  ranAt: string
  ranBy: string
  detail: { checks?: number; failures?: { check: string; expected: string; observed: string }[] } | null
}

export interface TestRequest {
  requestId: number
  requestedBy: string
  requestedAt: string
  claimedBy: string | null
  claimedAt: string | null
  claimExpiresAt: string | null
  completedAt: string | null
}

export interface Revision {
  profileRevisionId: number
  vendor: string
  model: string
  revision: number
  status: string
  reasons: string[]
  documentHash: string
  createdBy: string | null
  createdAt: string | null
  activatedBy: string | null
  activatedAt: string | null
  suites: Record<string, SuiteRun>
  latestTestRequest: TestRequest | null
}

/** 시험 요청 상태(P2·S1d 스펙 §9). 운영 서비스가 목록을 읽은 시각과 만료 시각으로 정한다. */
export type TestRequestState = 'NONE' | 'WAITING' | 'RUNNING' | 'EXPIRED' | 'DONE'

export interface RevisionView {
  revision: Revision
  testRequest: TestRequestState
}

/** 운영 서비스의 `GET /api/profiles`. 목록이 null 이면 모름이다(스펙 §9). */
export interface ProfileListView {
  registry: RegistryState
  checkedAt: string
  catalog: Catalog | null
  revisions: RevisionView[] | null
  asOf: string | null
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

/** 조작 한 번의 답. registry 의 판단은 여기에 있다(스펙 §7.4·§9). */
export interface OperationOutcome {
  requestId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  rejection: Finding | null
  unauthorized: boolean
  registryStatus: number | null
}

/** 운영 서비스가 registry 에 보내기 전에 막은 요청의 답. */
export interface PreRejection {
  error: string
  detail: string
}

/**
 * 조작을 보낸 결과. registry 에 닿았으면 `outcome`, 운영 서비스가 먼저 막았으면 `refused` 다.
 * 그 밖의 실패(연결 끊김, 프록시 오류, 운영 서비스 500)는 `unknown` 이다. 요청이 registry 까지 갔는지 모르므로
 * 보내지 못했다고 단정하지 않는다(스펙 §9).
 */
export type Sent =
  | { kind: 'outcome'; outcome: OperationOutcome }
  | { kind: 'refused'; refusal: PreRejection }
  | { kind: 'unknown'; cause: string }

/** 사용자 이름 규칙. 운영 서비스의 `Actor` 와 같다. 헤더는 ASCII 만 실을 수 있고 `/` 는 모드와 사용자를 가르는 자리다. */
export const USER_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

/** 인증 없이 화면이 싣는 모드와 사용자(스펙 §7.1·§8). */
export interface Session {
  mode: Mode
  user: string
}

const actorHeaders = (session: Session) => ({ 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user })

async function getJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, { headers: actorHeaders(session) })
  if (!response.ok) throw new Error(`운영 서비스 응답 ${response.status}`)
  return (await response.json()) as T
}

async function send(method: string, path: string, session: Session, body?: unknown): Promise<Sent> {
  return deliver(path, {
    method,
    headers:
      body === undefined
        ? actorHeaders(session)
        : { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

/**
 * 프로파일 문서를 고른 파일의 글자 그대로 보낸다. 다시 직렬화하면 registry 가 매기는 문서 해시가 달라져, 같은 문서의
 * 재제출이 다른 문서로 보인다(P2·S1d 스펙 §8.2).
 */
function sendDocument(path: string, session: Session, text: string): Promise<Sent> {
  return deliver(path, {
    method: 'POST',
    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: text,
  })
}

async function deliver(path: string, init: RequestInit): Promise<Sent> {
  try {
    const response = await fetch(path, init)
    if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as OperationOutcome }
    if (response.status === 400 || response.status === 403) {
      // 스프링이 직접 막은 400 의 본문에는 detail 이 없다. 그때도 사유 칸을 비우지 않는다.
      const refusal = (await response.json()) as Partial<PreRejection>
      const detail =
        typeof refusal.detail === 'string' && refusal.detail !== ''
          ? refusal.detail
          : `운영 서비스가 요청을 거절했습니다(HTTP ${response.status})`
      return { kind: 'refused', refusal: { error: refusal.error ?? '', detail } }
    }
    return { kind: 'unknown', cause: `운영 서비스 응답 ${response.status}` }
  } catch (error: unknown) {
    return { kind: 'unknown', cause: error instanceof Error ? error.message : String(error) }
  }
}

const retirementPath = (robotId: string) => `/api/robots/${encodeURIComponent(robotId)}/retirement`

export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
export const fetchOperations = (session: Session) =>
  getJson<OperationRecord[]>('/api/operations', session)
export const declareRobot = (
  session: Session,
  robotId: string,
  serialNumber: string,
  displayName: string | null,
) => send('POST', '/api/robots', session, { robotId, serialNumber, displayName })
export const retireRobot = (session: Session, robotId: string, reason: string) =>
  send('POST', retirementPath(robotId), session, { reason })
export const reinstateRobot = (session: Session, robotId: string) =>
  send('DELETE', retirementPath(robotId), session)

export const fetchAdapters = (session: Session) => getJson<AdapterListView>('/api/adapters', session)
export const declareAdapter = (session: Session, vendor: string, name: string) =>
  send('POST', '/api/adapters', session, { vendor, name })
export const declareBuild = (session: Session, adapterId: number, version: string, contractSemver: string) =>
  send('POST', `/api/adapters/${adapterId}/versions`, session, { version, contractSemver })
export const registerInstance = (
  session: Session,
  instanceId: string,
  adapterVersionId: number,
  fleetEndpoint: string | null,
) => send('POST', '/api/adapter-instances', session, { instanceId, adapterVersionId, fleetEndpoint })

export const fetchProfiles = (session: Session) => getJson<ProfileListView>('/api/profiles', session)
export const submitRevision = (session: Session, document: string) =>
  sendDocument('/api/profile-revisions', session, document)
export const requestTest = (session: Session, profileRevisionId: number) =>
  send('POST', `/api/profile-revisions/${profileRevisionId}/test-requests`, session)
export const activateRevision = (session: Session, profileRevisionId: number) =>
  send('POST', `/api/profile-revisions/${profileRevisionId}/activation`, session)
export const bindRobot = (session: Session, robotId: string, adapterVersionId: number, profileRevisionId: number) =>
  send('POST', `/api/robots/${encodeURIComponent(robotId)}/binding`, session, { adapterVersionId, profileRevisionId })
export const recordSiteNames = (session: Session, robotId: string) =>
  send('POST', `/api/robots/${encodeURIComponent(robotId)}/site-names`, session)
