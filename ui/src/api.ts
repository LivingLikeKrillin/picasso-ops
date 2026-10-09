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

/**
 * 막힘이나 거부 한 건. 화면에 내는 칸 5개와 맞춘다(스펙 §7.4). S2 에서 근거 버전을 더했다(S2 스펙 §6.4).
 * 근거 버전은 현장 설정에 기대는 판정(오래됨)에만 있고, 나머지는 없거나 null 이다.
 */
export interface Finding {
  kind: string
  observed: string
  expected: string
  checkedAt: string
  owner: Owner
  inScreen: boolean
  action: string
  target: string | null
  basisVersion?: number | null
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

/**
 * 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9).
 * `settingsVersion`·`connectionThresholdSeconds` 는 연결 칸과 막힘을 판정한 현장 설정 버전과 그 기준 시간이다(S2 스펙 §6.4).
 */
export interface RobotListView {
  registry: RegistryState
  checkedAt: string
  robots: RobotView[] | null
  robotsAsOf: string | null
  settingsVersion?: number | null
  connectionThresholdSeconds?: number | null
}

/** 미들웨어 시간값 넷(S3c JSON 계약 공통 규칙). 초 단위 정수다. */
export interface SiteTimingValues {
  evidenceBeforeSeconds: number
  evidenceAfterSeconds: number
  inDoubtGraceSeconds: number
  stallWindowSeconds: number
}

/** 현장 설정의 값 칸 다섯(S3c JSON 계약 §4). 화면은 PUT 에 늘 다섯을 다 싣는다. */
export interface SiteSettingValues extends SiteTimingValues {
  connectionThresholdSeconds: number
}

/** 현장 설정 버전 한 행(S3c JSON 계약 §3). `mode` 는 운영 서비스의 열거 값(`ENGINEER`)이다. */
export interface SiteSettingsRecord extends SiteSettingValues {
  version: number
  mode: string
  user: string
  reason: string
  recordedAt: string
}

/** 허용 범위(초, 양 끝 포함). 평평한 한 객체다(S3c JSON 계약 §3·§5). */
export interface SiteSettingsRange {
  minConnectionThresholdSeconds: number
  maxConnectionThresholdSeconds: number
  minEvidenceBeforeSeconds: number
  maxEvidenceBeforeSeconds: number
  minEvidenceAfterSeconds: number
  maxEvidenceAfterSeconds: number
  minInDoubtGraceSeconds: number
  maxInDoubtGraceSeconds: number
  minStallWindowSeconds: number
  maxStallWindowSeconds: number
}

/**
 * 실행 호스트의 현장 시간값 적용 상태(S3c JSON 계약 §7). 운영 서비스가 호스트 본문을 그대로 넘긴다. `applied` 가 null 이면
 * 미적용이고, `rejected` 는 범위 밖이라 적용하지 않은 버전과 이유다. 시각은 호스트 시계다.
 */
export interface HostTimings {
  applied: ({ version: number } & SiteTimingValues) | null
  appliedAt: string | null
  lastReadAt: string | null
  readError: string | null
  rejected: { version: number; reasons: string[] } | null
}

/**
 * 운영 서비스의 `GET /api/site-settings`(S3c JSON 계약 §3). `history` 는 최신부터다. `hostTimings` 는 호스트가 닿지 않으면
 * null 이다.
 */
export interface SiteSettingsView {
  current: SiteSettingsRecord
  range: SiteSettingsRange
  history: SiteSettingsRecord[]
  hostTimings: HostTimings | null
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
 * 요청을 보낸 결과. 운영 서비스가 2xx 로 답했으면 `outcome`, 운영 서비스가 먼저 막았으면(400·403) `refused` 다.
 * 그 밖의 실패(연결 끊김, 프록시 오류, 운영 서비스 500)는 `unknown` 이다. 요청이 상대(registry, 실행 호스트)까지 갔는지
 * 모르므로 보내지 못했다고 단정하지 않는다(스펙 §9).
 */
export type Delivered<T> =
  | { kind: 'outcome'; outcome: T }
  | { kind: 'refused'; refusal: PreRejection }
  | { kind: 'unknown'; cause: string }

/** registry 쓰기 조작을 보낸 결과. registry 에 닿았으면 `outcome` 이다. */
export type Sent = Delivered<OperationOutcome>

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

/** 운영 서비스가 registry·실행 호스트에 보내기 전에 막는 상태 코드(스펙 §9). */
const preRejected = (status: number) => status === 400 || status === 403

async function deliver<T = OperationOutcome>(
  path: string,
  init: RequestInit,
  refused: (status: number) => boolean = preRejected,
): Promise<Delivered<T>> {
  try {
    const response = await fetch(path, init)
    if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as T }
    if (refused(response.status)) {
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
export const fetchSiteSettings = (session: Session) => getJson<SiteSettingsView>('/api/site-settings', session)
export const changeSiteSettings = (session: Session, baseVersion: number, values: SiteSettingValues, reason: string) =>
  send('PUT', '/api/site-settings', session, { baseVersion, ...values, reason })
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

/** 작업 지시 폼이 내는 임무(S3a 스펙 §9.1). DeliverContainer 는 플릿 포트 구현이 없어 내지 않는다. */
export type WorkMasterId = 'InspectAsset' | 'PrepareSequencedRack'

/** InspectAsset 의 점검 대상 하나. `location` 은 기체가 아는 명칭이어야 하나 화면이 검사하지 않는다. */
export interface InspectionTarget {
  id: string
  location: string
}

/**
 * 작업 지시 폼 초안(S3a JSON 계약 §9.1). 판정과 제출이 같은 본문을 쓴다. 작업 지시 본문(작업 지시 id, 요구 근거 등급, 장비 요구)은
 * 운영 서비스가 만든다.
 */
export type JobOrderForm =
  | { workMasterId: 'InspectAsset'; targets: InspectionTarget[] }
  | { workMasterId: 'PrepareSequencedRack'; slots: string[]; material: string; presentation: string }

export type SkillFit = 'FIT' | 'MISSING' | 'UNKNOWN'

/** 실행 호스트의 기체 판정(S3a JSON 계약 §2.2). `UNKNOWN` 은 호스트가 기체 케이퍼빌리티를 못 물어본 것이고 모름이다. */
export interface HostEligibility {
  robotId: string
  skillFit: SkillFit
  missingSkills: string[]
  runningExecutionId: string | null
  passed: boolean
  reasons: string[]
}

/**
 * 기체 한 대의 배정 가능 판정(S3a JSON 계약 §9.2). 시운전·연결은 운영 서비스가, 스킬 적합·도는 실행은 실행 호스트가 판정한다.
 * null 은 모름이고, 모름이 하나라도 있으면 배정 가능이 아니다.
 */
export interface RobotEligibility {
  robotId: string
  commissioning: CommissioningState | null
  connection: Connection | null
  settingsVersion: number | null
  host: HostEligibility | null
  eligible: boolean
  reasons: string[]
}

export type HostState = 'OK' | 'HOST_SILENT'

/** 운영 서비스의 `POST /api/job-orders/eligibility`. `robots` 가 null 이면 기체 목록을 한 번도 못 읽은 모름이다. */
export interface EligibilityView {
  checkedAt: string
  registry: RegistryState
  robotsAsOf: string | null
  host: HostState
  robots: RobotEligibility[] | null
}

export type HostSubmitResult = 'ACCEPTED' | 'IDEMPOTENT' | 'REJECTED' | 'UNASSIGNED'

/** 실행 호스트의 제출 결과(S3a JSON 계약 §4). `refusals` 는 미배정일 때 기체별 관문 사유, `excluded` 는 호스트가 판정에서 뺀 기체다. */
export interface HostSubmitOutcome {
  result: HostSubmitResult
  executionId: string | null
  robotId: string | null
  rejectionReason: string | null
  refusals: { robotId: string; reason: string }[]
  excluded: HostEligibility[]
}

/**
 * 작업 지시 제출의 200 응답(S3a JSON 계약 §9.3). 기존 [OperationOutcome] 과 모양이 달라 따로 읽는다(S3a 스펙 §8).
 * `outcome` 은 실행 호스트의 응답이고, 호스트가 안 닿았으면 null 이다.
 */
export interface JobOrderOutcome {
  requestId: string
  jobOrderId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: HostSubmitOutcome | null
}

/** 실행의 단위 하나(S3a JSON 계약 §5). `reached` 는 그 단위가 얻은 근거 등급이다. */
export interface ExecutionUnit {
  unitId: string
  skillType: string
  state: string
  reached: string
}

/** 실행의 마지막 작업 응답(S3a JSON 계약 §5). 상위 시스템이 없어 실행 호스트의 아웃박스에 남는다. */
export interface JobResponse {
  jobResponseId: string
  version: number
  physicalState: string
  requiredEvidence: string
  reachedEvidence: string
  completedUnits: string[]
  unverifiedUnits: string[]
  incompleteUnits: Record<string, string>
  inDoubtUnits: string[]
  operatorRequired: boolean
  residualHold: string
  blockedBy: string[]
  connection: string
}

/**
 * 실행 하나(S3a JSON 계약 §5, S4b 계약 H1). `missionVersion` 이 null 이면 코드 정의 임무다. `restoredFrom` 은 이번 기동이
 * 다시 지은 실행의 바로 앞 인스턴스 실행이고, 새로 받은 실행이면 null 이다. 옛 호스트는 칸을 싣지 않는다.
 */
export interface Execution {
  executionId: string
  jobOrderId: string
  workMasterId: string
  missionVersion: number | null
  robotId: string
  physicalState: string
  units: ExecutionUnit[]
  jobResponse: JobResponse | null
  restoredFrom?: { instanceId: string; executionId: string } | null
}

/** 다시 짓기 결과 셋(S4b 스펙 T3). */
export type RestoreResult = 'RESTORED' | 'DEFERRED' | 'GAVE_UP'

/** 복원 보고의 행 하나(S4b 계약 H1, 7칸). `executionId` 는 RESTORED 일 때만, `reason` 은 그 밖일 때만 있다. */
export interface RestoreRow {
  jobOrderId: string
  robotId: string
  previousInstanceId: string
  previousExecutionId: string
  result: RestoreResult
  executionId: string | null
  reason: string | null
}

/**
 * 운영 서비스의 `GET /api/executions`(실행 호스트 본문 그대로). `instanceId` 가 바뀌면 호스트가 재기동한 것이다. `restore` 는
 * 이번 기동의 복원 보고다(S4b 계약 H1). 다시 지을 것이 없었으면 `rows` 가 비어 있다.
 */
export interface ExecutionsView {
  instanceId: string
  pumpedAt: string | null
  executions: Execution[]
  restore?: { at: string; rows: RestoreRow[] } | null
}

/** 셀 대역의 자리 하나(S3a JSON 계약 §1). 제시 자리의 `observedAt` 은 늘 null 이다. */
export interface CellPlace {
  id: string
  occupied: boolean
  material: string | null
  observedAt: string | null
}

export type SignalKind = 'BOOLEAN' | 'TEXT'

/**
 * 셀 대역의 이름 있는 신호 하나(S3b JSON 계약 §1). 값은 종류와 상관없이 늘 문자열이다(`"true"`). 안전 신호는 현장이 쓰기를
 * 거부한다(ADR 32). `observedAt` 이 null 이면 현장이 시각을 주지 않은 처음 값이다.
 */
export interface CellSignal {
  name: string
  location: string | null
  kind: SignalKind
  safety: boolean
  value: string
  observedAt: string | null
}

/**
 * 운영 서비스의 `GET /api/cell`. `cell` 이 null 이면 실행 호스트가 셀 대역을 못 읽은 모름이다. `signals` 가 null 이면 셀 대역이
 * 신호 목록을 싣지 않은 것이고 이것도 모름이다(S3b JSON 계약 §6).
 */
export interface CellView {
  cell: { presentations: CellPlace[]; slots: CellPlace[]; signals: CellSignal[] | null } | null
}

/**
 * 실행 호스트를 거치는 읽기. 운영 서비스가 호스트에 닿지 못하면 503 과 `{error, detail}` 이므로, 그 detail 을 오류 문구로 쓴다.
 * 기존 다섯 읽기와 따로 실패한다(S3a 스펙 §9.3).
 */
async function getHostJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, { headers: actorHeaders(session) })
  if (response.ok) return (await response.json()) as T
  const body = (await response.json().catch(() => null)) as Partial<PreRejection> | null
  throw new Error(
    typeof body?.detail === 'string' && body.detail !== '' ? body.detail : `운영 서비스 응답 ${response.status}`,
  )
}

const postJobOrderForm = <T>(path: string, session: Session, form: JobOrderForm) =>
  deliver<T>(path, {
    method: 'POST',
    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  })

export const fetchExecutions = (session: Session) => getHostJson<ExecutionsView>('/api/executions', session)
export const fetchCell = (session: Session) => getHostJson<CellView>('/api/cell', session)
export const checkEligibility = (session: Session, form: JobOrderForm) =>
  postJobOrderForm<EligibilityView>('/api/job-orders/eligibility', session, form)
export const submitJobOrder = (session: Session, form: JobOrderForm) =>
  postJobOrderForm<JobOrderOutcome>('/api/job-orders', session, form)

/** 화면이 편집하는 임무. 작업 지시 폼과 실행 호스트가 두 임무로 고정이라 하나만 편집한다(S3b 스펙 T6). */
export const EDITABLE_WORK_MASTER = 'PrepareSequencedRack'

/** 모의 실행의 단위 하나(S3b JSON 계약 §4.5). `route` 가 `SIGNAL` 이면 설비 대기 단위다. */
export interface MockRunUnit {
  unitId: string
  route: 'ROBOT' | 'SIGNAL'
  skillType: string
  state: string
  reached: string
  failureClass: string | null
}

/** 모의 실행 실패의 하위 범주(S3b JSON 계약 §4.5). 통과면 null 이다. */
export type MockRunFailure = 'DEFINITION' | 'SUBMISSION_REJECTED' | 'NOT_SETTLED' | 'WALL_CLOCK_LIMIT' | 'EXECUTION_FAILED'

/**
 * 모의 실행 결과(S3b JSON 계약 §4.5). 가상 기체 하나와 이상적 현장으로 표본 작업 지시 하나를 끝까지 돌린 것이다. 실행이 서지
 * 않았으면(`DEFINITION`·`SUBMISSION_REJECTED`) `physicalState` 가 null 이고 `units` 가 비었다.
 */
export interface MockRunResult {
  passed: boolean
  failure: MockRunFailure | null
  detail: string | null
  robotId: string
  sample: { jobOrderId: string; requiredEvidence: string; slots: string[]; material: string; presentation: string } | null
  physicalState: string | null
  units: MockRunUnit[]
  virtualElapsedSeconds: number
  wallElapsedMillis: number
}

/** 모의 실행 한 행(S3b JSON 계약 §3.3). 시각은 DB 시각이다. */
export interface MockRunView {
  mockRunId: number
  draftId: number
  passed: boolean
  result: MockRunResult
  requestId: string
  startedAt: string
  finishedAt: string
}

/** 초안 한 행(S3b JSON 계약 §3.3). `definition` 은 저장한 글자 그대로이고 읽을 수 없는 문서일 수도 있다. */
export interface DraftView {
  draftId: number
  workMasterId: string
  definition: string
  savedBy: string
  requestId: string
  savedAt: string
  lastMockRun: MockRunView | null
}

/** 임무 버전 한 행(S3b JSON 계약 §3.3). 번호는 WorkMaster 마다 1부터다. */
export interface VersionView {
  workMasterId: string
  version: number
  draftId: number
  definition: string
  activatedBy: string
  reason: string
  requestId: string
  activatedAt: string
}

/**
 * 운영 서비스의 `GET /api/missions/{workMasterId}`(S3b JSON 계약 §4.1). 활성 버전이 없으면 `source` 가 `CODE` 이고 정의 JSON 이
 * 없다(코드 정의). `versions` 는 높은 번호부터, `drafts` 는 최근 것부터다.
 */
export interface MissionOverview {
  workMasterId: string
  active: { version: number | null; source: 'CODE' | 'DATA'; detail: VersionView | null }
  versions: VersionView[]
  drafts: DraftView[]
}

/** 시작용 정의 하나(S3b JSON 계약 §4.2). */
export interface MissionTemplate {
  id: string
  title: string
  definition: string
}

export interface MissionTemplates {
  workMasterId: string
  templates: MissionTemplate[]
}

/**
 * 판정 입력을 몰라 판정하지 않은 것(S3b JSON 계약 §3.2). 거부가 아니라 모름이다. `inputs` 는 `SIGNAL_SPEC`·`SITE_SKILLS`·
 * `SAMPLE_ORDER` 이고 `detail` 은 화면용 한국어다.
 */
export interface InputUnknown {
  inputs: string[]
  robots: string[]
  detail: string
}

/** 실행 호스트 판정의 공통 칸(S3b JSON 계약 §4.4~§4.6). 결과는 `result` 로 가린다. 거부 목록은 운영 서비스가 `findings` 로 옮긴다. */
interface HostJudgment<R extends string> {
  result: R
  draftId: number
  workMasterId: string
  checkedAt: string
  unknown: InputUnknown | null
}

export type HostValidation = HostJudgment<'PASSED' | 'REFUSED' | 'INPUT_UNKNOWN'>

export interface HostMockRun extends HostJudgment<'PASSED' | 'FAILED' | 'REFUSED' | 'INPUT_UNKNOWN'> {
  mockRun: MockRunView | null
}

export interface HostActivation extends HostJudgment<'ACTIVATED' | 'REFUSED' | 'MOCK_RUN_REQUIRED' | 'INPUT_UNKNOWN'> {
  version: number | null
  lastMockRun: MockRunView | null
  activated: VersionView | null
}

/** 초안 저장의 호스트 본문(S3b JSON 계약 §4.3). */
export interface DraftSaved {
  draft: DraftView
}

/**
 * 호스트가 4xx 로 막은 쓰기(S3b JSON 계약 §10.1). 200 본문에 실린다. 4xx 본문을 `{error, detail}` 로 읽지 못하면(스프링 기본
 * 415 등) 두 칸이 null 이다(S4a JSON 계약 §9.1).
 */
export interface HostRejection {
  status: number
  error: string | null
  detail: string | null
}

/**
 * 초안 저장·모의 실행·활성화의 200 응답(S3b JSON 계약 §10.4). `result` 는 조작 기록의 결과이고 호스트의 판단은 `outcome` 에
 * 있다. 거부 카드는 호스트 결과가 `REFUSED` 일 때만 `findings` 에 온다.
 */
export interface MissionOperationOutcome<T> {
  requestId: string
  workMasterId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: T | null
  findings: Finding[]
  rejection: HostRejection | null
}

/** 검증의 200 응답(S3b JSON 계약 §10.5). 검증은 조작이 아니라 요청 id·결과·확인이 없다. */
export interface MissionValidationReply {
  workMasterId: string
  outcome: HostValidation
  findings: Finding[]
}

/** 신호 조작의 200 응답(S3b JSON 계약 §10.8). 현장의 거부(안전 신호, 틀린 값)는 `rejection` 에 온다. */
export interface SignalWriteOutcome {
  requestId: string
  name: string
  value: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  signal: CellSignal | null
  rejection: HostRejection | null
}

/**
 * 임무·신호 조작의 사전 거부(S3b JSON 계약 §10.1). 400·403 에 더해, 시운전 완료 기체를 몰라 실행 호스트를 부르지 않은 503
 * (`COMMISSIONED_ROBOTS_UNKNOWN`)과 검증이 넘기는 호스트 4xx·`HOST_SILENT` 도 `{error, detail}` 본문의 사전 거부다.
 */
const missionPreRejected = (status: number) => (status >= 400 && status < 500) || status === 503

const postMission = <T>(path: string, session: Session, body: unknown) =>
  deliver<T>(
    path,
    {
      method: 'POST',
      headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
    missionPreRejected,
  )

const missionPath = (workMasterId: string) => `/api/missions/${encodeURIComponent(workMasterId)}`
const draftPath = (workMasterId: string, draftId: number) => `${missionPath(workMasterId)}/drafts/${draftId}`

export const fetchMission = (session: Session, workMasterId: string) =>
  getHostJson<MissionOverview>(missionPath(workMasterId), session)
export const fetchMissionTemplates = (session: Session, workMasterId: string) =>
  getHostJson<MissionTemplates>(`/api/missions/templates/${encodeURIComponent(workMasterId)}`, session)
/** 편집기의 글자 그대로 보낸다. 초안은 자유롭다. 읽을 수 없는 문서도 저장되고 검증이 거부한다(S3b 스펙 §9). */
export const saveMissionDraft = (session: Session, workMasterId: string, definition: string) =>
  postMission<MissionOperationOutcome<DraftSaved>>(`${missionPath(workMasterId)}/drafts`, session, { definition })
export const validateMissionDraft = (session: Session, workMasterId: string, draftId: number) =>
  postMission<MissionValidationReply>(`${draftPath(workMasterId, draftId)}/validate`, session, {})
export const mockRunMissionDraft = (session: Session, workMasterId: string, draftId: number) =>
  postMission<MissionOperationOutcome<HostMockRun>>(`${draftPath(workMasterId, draftId)}/mock-run`, session, {})
export const activateMissionDraft = (session: Session, workMasterId: string, draftId: number, reason: string) =>
  postMission<MissionOperationOutcome<HostActivation>>(`${draftPath(workMasterId, draftId)}/activate`, session, {
    reason,
  })
export const writeCellSignal = (session: Session, name: string, value: string) =>
  postMission<SignalWriteOutcome>(`/api/cell/signals/${encodeURIComponent(name)}`, session, { value })

/** 사람이 인시던트의 단위에 낸 판단(S4a JSON 계약 §3). `at` 은 호스트 시계, `wallClockAt` 은 실제 시각이다. */
export interface IncidentResolution {
  decision: HoldDecision
  at: string
  wallClockAt: string
  decidedBy: { id: string; kind: string }
}

/** 인시던트 줄의 결함 요약(S4a JSON 계약 §3). `errorHint` 는 빈 문자열일 수 있다. */
export interface FaultSummary {
  failureClass: string
  errorType: string
  errorHint: string
}

/**
 * 인시던트 한 줄(S4a JSON 계약 §3, 19칸). `unresolved` 는 봉인 때 한 번 정해지고 판단 뒤에도 그대로다. «미해결» 은
 * `unresolved` 이고 `resolution` 이 없을 때이고, «보류 중» 강조는 `held` 만 본다. `missionVersion` 이 null 이면 코드 정의다.
 */
export interface IncidentRow {
  incidentId: string
  executionId: string
  jobOrderId: string
  robotId: string
  unitId: string
  at: string
  failureClass: string | null
  route: string
  missionVersion: number | null
  siteSettingsVersion: number | null
  evidenceBeforeSeconds: number
  evidenceAfterSeconds: number
  inDoubtGraceSeconds: number | null
  stallWindowSeconds: number | null
  unresolved: boolean
  resolution: IncidentResolution | null
  fault: FaultSummary | null
  held: boolean
  confirmedWithoutEvidence: boolean
}

/** 이전 인스턴스의 인시던트 사본 한 줄(S4b 계약 H3, 20칸). 그 사본의 `instanceId` 를 앞에 둔다. 늘 보류가 아니다. */
export interface EarlierIncidentRow extends IncidentRow {
  instanceId: string
}

/**
 * 운영 서비스의 `GET /api/incidents`(S4a JSON 계약 §9.2, S4b 계약 H3). `incidents` 는 지금 인스턴스의 것이고 최신부터이며
 * `total` 은 자르기 전의 수다. `earlier` 는 재기동 앞 인스턴스들의 사본이고 최근에 적은 것부터다.
 */
export interface IncidentsView {
  instanceId: string
  total: number
  incidents: IncidentRow[]
  earlierTotal?: number
  earlier?: EarlierIncidentRow[]
}

/** 결함 원문(S4a JSON 계약 §4, 9칸). `vendorDetail`·`errorHint`·`activeUntilTime` 은 빈 문자열일 수 있다. */
export interface FaultDetail {
  failureClass: string
  errorType: string
  vendorDetail: string
  errorHint: string
  references: { key: string; value: string }[]
  canContinueCurrentTask: boolean
  canAcceptNewTask: boolean
  activeUntilKind: string
  activeUntilTime: string
}

/** 근거 윈도우 안의 관측 하나(S4a JSON 계약 §4). `local` 이면 미들웨어가 적은 관측이다. */
export interface EvidenceEvent {
  sequence: number
  occurredAt: string
  kind: string
  detail: string
  local: boolean
}

/** 인시던트의 의도 전체(S4a JSON 계약 §4, 17칸). 버전과 시간값은 목록 줄과 같은 뜻이다. */
export interface IncidentIntent {
  workMasterId: string
  orderVersion: number
  orderParameters: Record<string, string>
  materials: { materialDefinitionId: string; quantity: number }[]
  equipment: { id: string; equipmentUse: string; properties: Record<string, string> }[]
  capabilityMaxEvidence: string
  evidenceBeforeSeconds: number
  evidenceAfterSeconds: number
  skillType: string
  unitParameters: Record<string, string>
  source: string | null
  destination: string | null
  expectedIdentity: string | null
  missionVersion: number | null
  siteSettingsVersion: number | null
  inDoubtGraceSeconds: number | null
  stallWindowSeconds: number | null
}

/**
 * 운영 서비스의 `GET /api/incidents/{incidentId}`(S4a JSON 계약 §4, 29칸). `unitState` 는 그 단위의 지금 상태이고 나머지 근거
 * 칸은 봉인 때의 관측이다.
 */
export interface IncidentDetail {
  instanceId: string
  incidentId: string
  executionId: string
  jobOrderId: string
  robotId: string
  unitId: string
  at: string
  wallClockAt: string
  failureClass: string | null
  route: string
  unresolved: boolean
  resolution: IncidentResolution | null
  held: boolean
  confirmedWithoutEvidence: boolean
  unitState: string | null
  fault: FaultDetail | null
  blockedBy: FaultDetail[]
  requiredEvidence: string
  reachedEvidence: string
  verification: string
  step: { at: number; plan: string[]; completed: string[] }
  evidenceWindow: EvidenceEvent[]
  windowTruncated: boolean
  preconditionSubjects: string[]
  expectedHold: string | null
  observedHold: string
  effectMismatch: string | null
  linkBroken: boolean
  intent: IncidentIntent
}

/** 상세 읽기의 결과. 실행 호스트가 그 id 를 모르면(재기동으로 사라진 id 포함) `missing` 이다(S4a JSON 계약 §9.3). */
export type IncidentLookup = { kind: 'found'; detail: IncidentDetail } | { kind: 'missing'; detail: string }

export const fetchIncidents = (session: Session) => getHostJson<IncidentsView>('/api/incidents', session)

/**
 * 404 `INCIDENT_NOT_FOUND` 만 없음이다. 그 밖의 실패(503 `HOST_SILENT` 포함)는 못 읽음이라 던진다. 인스턴스를 늘 싣는다
 * (S4b 계약 H4). 재기동 뒤 `incident-N` 을 다시 세므로, 인스턴스 없이 읽으면 같은 id 의 다른 인시던트를 읽는다.
 */
export async function fetchIncident(session: Session, instanceId: string, incidentId: string): Promise<IncidentLookup> {
  const response = await fetch(
    `/api/incidents/${encodeURIComponent(incidentId)}?instanceId=${encodeURIComponent(instanceId)}`,
    { headers: actorHeaders(session) },
  )
  if (response.ok) return { kind: 'found', detail: (await response.json()) as IncidentDetail }
  const body = (await response.json().catch(() => null)) as Partial<PreRejection> | null
  const detail = typeof body?.detail === 'string' && body.detail !== '' ? body.detail : `운영 서비스 응답 ${response.status}`
  if (response.status === 404 && body?.error === 'INCIDENT_NOT_FOUND') return { kind: 'missing', detail }
  throw new Error(detail)
}

/** 화면이 고르게 하는 장애 종류 둘과 연결 상태 셋(S4a JSON 계약 §1.1). 그 밖의 값은 현장이 거부하므로 두지 않는다. */
export type FaultKind = 'SKILL_EXECUTION_FAILED' | 'CONNECTION'
export type ConnectionFaultState = 'OFFLINE' | 'CONNECTION_BROKEN' | 'ONLINE'

/** 현장이 받아들인 장애 주입의 본문(S4a JSON 계약 §1.2). */
export type SiteFaultAnswer =
  | { robotId: string; kind: 'SKILL_EXECUTION_FAILED'; taskId: string; taskState: string; raised: boolean }
  | { robotId: string; kind: 'CONNECTION'; state: string; changed: boolean }

/**
 * 장애 주입의 200 응답(S4a JSON 계약 §9.4). 재조회하지 않으므로 `confirmation` 은 늘 null 이다. 현장의 거부는 `rejection`
 * 이고, 현장 불통과 호스트 불통은 둘 다 `NO_RESPONSE` 다.
 */
export interface FaultInjectionOutcome {
  requestId: string
  robotId: string
  kind: string
  state: string | null
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: null
  fault: SiteFaultAnswer | null
  rejection: HostRejection | null
}

export type HoldDecision = 'CONFIRM_DONE' | 'REWORK'

/** 실행 호스트 판단의 200 본문(S4a JSON 계약 §5.2). */
export interface HoldResolveAnswer {
  result: string
  detail: string | null
  incidentId: string | null
  requestId: string | null
}

/**
 * 운영자 판단의 200 응답(S4a JSON 계약 §9.5). 화면은 `result` 가 아니라 `outcome`(picasso 이름 그대로)으로 가른다. `outcome`
 * 은 호스트가 200 으로 답했을 때만 있다.
 */
export interface HoldResolveOutcome {
  requestId: string
  executionId: string
  unitId: string
  decision: HoldDecision
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  outcome: 'Resolved' | 'NotHeld' | 'Refused' | null
  answer: HoldResolveAnswer | null
  rejection: HostRejection | null
}

/** 사전 거부(400 `REASON_REQUIRED`·403 `MODE_NOT_ALLOWED` 등)는 `refused` 다(S4a JSON 계약 §9.1). */
const postOps = <T>(path: string, session: Session, body: unknown) =>
  deliver<T>(path, {
    method: 'POST',
    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

/** 연결 상태일 때만 `state` 를 싣는다. 사유는 앞뒤 공백을 뗀 값이다. */
export const injectFault = (
  session: Session,
  robotId: string,
  kind: FaultKind,
  state: ConnectionFaultState | null,
  reason: string,
) =>
  postOps<FaultInjectionOutcome>(
    '/api/faults',
    session,
    kind === 'CONNECTION' ? { robotId, kind, state, reason } : { robotId, kind, reason },
  )

/**
 * 승인자는 싣지 않는다. 운영 서비스가 `X-Ops-User` 로 정한다(S4a JSON 계약 §9.5, ADR 43). 인스턴스는 상세의 `instanceId`
 * 이고, 실행 호스트가 그 사이 재기동했으면 409 `INSTANCE_MISMATCH` 로 막는다(S4b 스펙 T8).
 */
export const resolveHold = (
  session: Session,
  target: { instanceId: string; executionId: string; unitId: string },
  decision: HoldDecision,
  reason: string,
) =>
  postOps<HoldResolveOutcome>(
    `/api/executions/${encodeURIComponent(target.executionId)}/units/${encodeURIComponent(target.unitId)}/resolve`,
    session,
    { decision, instanceId: target.instanceId, reason },
  )

/** 송신 기록 행의 처분(S4b 스펙 T6). 재기동 중복은 송신하지 않은 것이다. */
export type JobResponseDisposition = 'SENT' | 'RESTART_DUPLICATE'

/** 작업 응답 송신 기록 한 행(S4b 계약 H6, 17칸). 단위 배열은 정렬됐고 `incompleteUnits` 는 사유 없는 id 다. */
export interface JobResponseLogRow {
  instanceId: string
  jobResponseId: string
  jobOrderId: string
  executionId: string
  version: number
  physicalState: string
  requiredEvidence: string
  reachedEvidence: string
  completedUnits: string[]
  unverifiedUnits: string[]
  inDoubtUnits: string[]
  incompleteUnits: string[]
  operatorRequired: boolean
  residualHold: string
  blockedBy: string[]
  disposition: JobResponseDisposition
  recordedAt: string
}

/** 운영 서비스의 `GET /api/job-responses`(S4b 계약 H6). `responses` 는 최근에 적은 것부터이고 `total` 은 자르기 전의 수다. */
export interface JobResponsesView {
  instanceId: string
  total: number
  responses: JobResponseLogRow[]
}

/** 작업 지시를 고르지 않으면(null) 전체다. 건수는 실행 호스트 기본(50)이다. */
export const fetchJobResponses = (session: Session, jobOrderId: string | null) =>
  getHostJson<JobResponsesView>(
    jobOrderId === null ? '/api/job-responses' : `/api/job-responses?jobOrderId=${encodeURIComponent(jobOrderId)}`,
    session,
  )
