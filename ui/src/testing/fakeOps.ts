import { vi } from 'vitest'
import type {
  AdapterListView,
  Binding,
  Finding,
  OperationOutcome,
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
  /** 여기 든 경로의 GET 은 503 이다. 운영 서비스의 일부 읽기만 실패하는 경우를 만든다. */
  failing: Set<string>
  answer: { status: number; body: unknown }
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
      if (method === 'GET') {
        if (fake.failing.has(url)) return new Response('', { status: 503 })
        const body =
          url === '/api/robots'
            ? fake.view
            : url === '/api/adapters'
              ? fake.adapters
              : url === '/api/profiles'
                ? fake.profiles
                : url === '/api/site-settings'
                  ? fake.settings
                  : []
        return new Response(JSON.stringify(body), { status: 200 })
      }
      return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
    }),
  )
  return fake
}
