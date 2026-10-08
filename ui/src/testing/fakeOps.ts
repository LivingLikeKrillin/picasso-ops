import { vi } from 'vitest'
import type {
  AdapterListView,
  Binding,
  CellView,
  EligibilityView,
  ExecutionsView,
  Finding,
  OperationOutcome,
  PreRejection,
  ProfileListView,
  Revision,
  SiteSettingsView,
  RevisionView,
  RobotListView,
  RobotView,
  TestRequestState,
} from '../api'

/** 운영 서비스 대역이 받은 요청 한 건. */
export interface Call {
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
}

/** 시험이 고칠 수 있는 대역의 상태. 조작마다 [answer] 를 돌려준다. */
export interface FakeOps {
  calls: Call[]
  view: RobotListView
  adapters: AdapterListView
  profiles: ProfileListView
  settings: SiteSettingsView
  /** 실행 호스트를 거치는 읽기(S3a). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
  executions: ExecutionsView
  cell: CellView
  /** `POST /api/job-orders/eligibility` 의 답. 폼 거부(400)를 만들려면 [eligibilityStatus] 와 본문을 바꾼다. */
  eligibility: EligibilityView | PreRejection
  eligibilityStatus: number
  /**
   * 여기 든 경로의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. 실행 호스트를 거치는 경로는 운영
   * 서비스처럼 `HOST_SILENT` 본문을 싣는다. 배정 가능 판정은 POST 지만 읽기이므로 여기 들면 503 이다.
   */
  failing: Set<string>
  answer: { status: number; body: unknown }
}

/** 실행 호스트를 거치는 경로. 503 일 때 운영 서비스가 `HOST_SILENT` 를 싣는다(S3a JSON 계약 §9.4). */
const HOST_PATHS = new Set(['/api/executions', '/api/cell'])
const ELIGIBILITY_PATH = '/api/job-orders/eligibility'

/** 실행 목록. 기본은 실행이 없는 호스트 인스턴스 하나다. */
export function executionsView(partial: Partial<ExecutionsView> = {}): ExecutionsView {
  return { instanceId: 'mw-1', pumpedAt: 't1', executions: [], ...partial }
}

/** 셀 대역. 기본은 고정 픽스처(제시 자리 하나, 빈 슬롯 넷)다(S3a JSON 계약 §1). */
export function cellView(): CellView {
  const slot = (id: string) => ({ id, occupied: false, material: null, observedAt: null })
  return {
    cell: {
      presentations: [{ id: 'SEQ-IN-02.BIN-A', occupied: true, material: 'ENGINE-COVER-A', observedAt: null }],
      slots: ['RACK-204.S01', 'RACK-204.S02', 'RACK-204.S03', 'RACK-204.S04'].map(slot),
    },
  }
}

/** 배정 가능 판정. 기본은 기체가 없는 사이트다. */
export function eligibilityView(partial: Partial<EligibilityView> = {}): EligibilityView {
  return { checkedAt: 't1', registry: 'OK', robotsAsOf: 't1', host: 'OK', robots: [], ...partial }
}

/** 어댑터 목록. 기본은 제품도 인스턴스도 없는 «없음» 이다. */
export function adapterView(partial: Partial<AdapterListView> = {}): AdapterListView {
  return { registry: 'OK', checkedAt: 't1', adapters: [], instances: [], asOf: 't1', ...partial }
}

/** 프로파일 목록. 기본은 카탈로그만 있고 개정판이 없는 «없음» 이다. */
export function profileView(partial: Partial<ProfileListView> = {}): ProfileListView {
  return {
    registry: 'OK',
    checkedAt: 't1',
    catalog: { contractSemver: '0.9.0', skillTypes: [] },
    revisions: [],
    asOf: 't1',
    ...partial,
  }
}

/** 현장 설정. 기본은 마이그레이션이 넣는 버전 1 의 90초 하나다(S2 스펙 §5). */
export function settingsView(partial: Partial<SiteSettingsView> = {}): SiteSettingsView {
  const first = {
    version: 1,
    connectionThresholdSeconds: 90,
    mode: 'ENGINEER',
    user: 'system',
    reason: 'S1 설정값 이전',
    recordedAt: 't0',
  }
  return {
    current: first,
    range: { minConnectionThresholdSeconds: 60, maxConnectionThresholdSeconds: 3600 },
    history: [first],
    ...partial,
  }
}

export function revisionView(partial: Partial<Revision> = {}, testRequest: TestRequestState = 'NONE'): RevisionView {
  return {
    revision: {
      profileRevisionId: 5,
      vendor: 'picasso-ref',
      model: 'humanoid-a',
      revision: 2,
      status: 'VALIDATED',
      reasons: [],
      documentHash: 'h',
      createdBy: 'engineer/kim',
      createdAt: 't0',
      activatedBy: null,
      activatedAt: null,
      suites: {},
      latestTestRequest: null,
      ...partial,
    },
    testRequest,
  }
}

/** 활성 바인딩 한 줄. 기본은 명칭이 확인된 바인딩이다. */
export function binding(partial: Partial<Binding> = {}): Binding {
  return {
    robotId: 'humanoid-01',
    vendor: 'picasso-ref',
    model: 'humanoid-a',
    profileRevisionId: 5,
    revision: 2,
    adapterName: 'acme/fleet',
    adapterVersion: '1.0.0',
    conformanceStatus: 'UNTESTED',
    active: true,
    siteNames: 'CONFIRMED',
    siteNameKeys: ['destination', 'location'],
    adapterVersionId: 10,
    boundBy: 'engineer/kim',
    boundAt: 't0',
    siteNamesRegisteredBy: 'engineer/kim',
    siteNamesRegisteredAt: 't0',
    siteNamesReportedAt: 't1',
    siteNamesCount: 2,
    siteNamesUnsupported: false,
    ...partial,
  }
}

export function robotView(robotId: string, status: string, blockers: Finding[] = []): RobotView {
  return {
    robot: {
      robotId,
      siteId: 'site-01',
      serialNumber: 'SN',
      displayName: null,
      status,
      lastReportedAt: status === 'CLAIMED' ? null : 't0',
      retiredAt: status === 'RETIRED' ? 't0' : null,
      retiredReason: status === 'RETIRED' ? '정비' : null,
      reportingAfterRetirement: false,
    },
    connection: status === 'CLAIMED' ? 'NO_REPORT' : 'FRESH',
    blockers,
  }
}

export function outcome(partial: Partial<OperationOutcome>): OperationOutcome {
  return {
    requestId: 'r-1',
    result: 'SUCCEEDED',
    confirmation: null,
    rejection: null,
    unauthorized: false,
    registryStatus: 200,
    ...partial,
  }
}

/** 브라우저처럼 ISO-8859-1 밖의 문자가 헤더에 있으면 보내기 전에 던지는 fetch 대역을 끼운다. */
export function installFakeOps(
  view: RobotListView,
  adapters: AdapterListView = adapterView(),
  profiles: ProfileListView = profileView(),
  settings: SiteSettingsView = settingsView(),
): FakeOps {
  const fake: FakeOps = {
    calls: [],
    view,
    adapters,
    profiles,
    settings,
    executions: executionsView(),
    cell: cellView(),
    eligibility: eligibilityView(),
    eligibilityStatus: 200,
    failing: new Set(),
    answer: { status: 200, body: outcome({}) },
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      for (const value of Object.values(headers)) {
        if ([...value].some((ch) => ch.charCodeAt(0) > 0xff)) {
          throw new TypeError("Failed to execute 'fetch': String contains non ISO-8859-1 code point.")
        }
      }
      const method = init?.method ?? 'GET'
      fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
      if (fake.failing.has(url) && (method === 'GET' || url === ELIGIBILITY_PATH)) {
        const body = HOST_PATHS.has(url)
          ? JSON.stringify({ error: 'HOST_SILENT', detail: '실행 호스트가 답하지 않는다: 응답 없음: ConnectException' })
          : ''
        return new Response(body, { status: 503 })
      }
      if (method === 'GET') {
        const body =
          url === '/api/robots'
            ? fake.view
            : url === '/api/adapters'
              ? fake.adapters
              : url === '/api/profiles'
                ? fake.profiles
                : url === '/api/site-settings'
                  ? fake.settings
                  : url === '/api/executions'
                    ? fake.executions
                    : url === '/api/cell'
                      ? fake.cell
                      : []
        return new Response(JSON.stringify(body), { status: 200 })
      }
      if (url === ELIGIBILITY_PATH) {
        return new Response(JSON.stringify(fake.eligibility), { status: fake.eligibilityStatus })
      }
      return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
    }),
  )
  return fake
}
