import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Adapter, Finding, RobotView } from '../api'
import { adapterView, binding, installFakeOps, profileView, revisionView, robotView } from '../testing/fakeOps'

const fleet: Adapter = {
  adapterId: 1,
  vendor: 'acme',
  name: 'fleet',
  versions: [
    { adapterVersionId: 10, version: '1.0.0', contractSemver: '0.9.0', conformance: 'UNTESTED', registeredAt: 't0', registeredBy: 'engineer/lee' },
  ],
}

const contradicted: Finding = {
  kind: 'SITE_NAMES_CONTRADICTED',
  observed: '기록 t0, 기체가 아는 명칭 0개',
  expected: '기체가 아는 명칭 1개 이상',
  checkedAt: 't1',
  owner: 'SITE',
  inScreen: false,
  action: '현장에서 명칭 티칭을 다시. 다음 보고로 풀림',
  target: 'quadruped-01',
}

const complete = (): RobotView => ({
  ...robotView('humanoid-01', 'CONFIRMED'),
  binding: binding(),
  commissioning: { state: 'COMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: true },
  software: { robotId: 'humanoid-01', declared: null, reported: null, verdict: 'UNREPORTED' },
})

const blocked = (): RobotView => ({
  ...robotView('quadruped-01', 'CONFIRMED', [contradicted]),
  binding: binding({ robotId: 'quadruped-01', model: 'quadruped-b', revision: 1, siteNames: 'CONTRADICTED', siteNameKeys: ['location'], siteNamesCount: 0 }),
  commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: false },
  software: null,
})

const unbound = (): RobotView => ({
  ...robotView('humanoid-02', 'CONFIRMED'),
  binding: null,
  commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: false, siteNamesReady: false },
  software: null,
})

const view = () => ({ registry: 'OK' as const, checkedAt: 't1', robots: [complete(), blocked(), unbound()], robotsAsOf: 't1' })

const profiles = () =>
  profileView({
    revisions: [
      revisionView({ status: 'ACTIVE' }),
      revisionView({ profileRevisionId: 8, revision: 3, status: 'TESTED' }),
    ],
  })

function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

async function open(robotId: string, mode: '엔지니어' | '운영자' = '엔지니어') {
  render(<App />)
  await userEvent.click(screen.getByLabelText(mode))
  await userEvent.click(await screen.findByRole('button', { name: robotId }))
  return screen.getByRole('region', { name: `${robotId} 상세` })
}

describe('시운전', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('목록에 시운전 칸이 연결 칸과 따로 보인다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    render(<App />)
    const list = await screen.findByRole('region', { name: '기체 목록' })
    expect(await within(list).findByRole('row', { name: /humanoid-01/ })).toHaveTextContent('humanoid-01CONFIRMED신선완료0')
    expect(within(list).getByRole('row', { name: /quadruped-01/ })).toHaveTextContent('quadruped-01CONFIRMED신선미완1')
  })

  it('상세의 카드 3개가 바인딩, 사람의 기록과 기체의 응답, 세 조건의 체크 목록을 보인다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('humanoid-01')
    const bindingCard = within(detail).getByRole('region', { name: '바인딩' })
    expect(field(bindingCard, '빌드')).toBe('acme/fleet 1.0.0')
    expect(field(bindingCard, '리비전')).toBe('picasso-ref/humanoid-a#2')
    const names = within(detail).getByRole('region', { name: '사이트 명칭' })
    expect(field(names, '사람이 기록함')).toBe('engineer/kim t0')
    expect(field(names, '기체가 답함')).toBe('아는 명칭 2개 (t1)')
    expect(within(names).getByText(/명칭 티칭은 화면 밖 현장 작업입니다/)).toBeInTheDocument()
    const card = within(detail).getByRole('region', { name: '시운전' })
    expect(within(card).getByRole('heading', { name: '시운전: 완료' })).toBeInTheDocument()
    expect(within(card).getByText(/\[v\] 활성 바인딩 \(근거 \/diag\/bindings\)/)).toBeInTheDocument()
    expect(within(card).getByText(/소프트웨어 대조 보고 없음, 어댑터 적합성 UNTESTED/)).toBeInTheDocument()
  })

  it('명칭이 어긋난 기체는 막힘 카드가 현장의 화면 밖 일로 보이고 체크 목록에 빠진 조건이 보인다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('quadruped-01')
    expect(field(detail, '종류')).toBe('기체가 아는 명칭 없음')
    expect(field(detail, '해결 담당')).toBe('현장(화면 밖): 현장에서 명칭 티칭을 다시. 다음 보고로 풀림')
    const card = within(detail).getByRole('region', { name: '시운전' })
    expect(within(card).getByText(/\[ \] 명칭 상태 CONFIRMED 또는 NOT_REQUIRED/)).toBeInTheDocument()
  })

  it('사람이 기록하지 않았으면 기체가 답했어도 기록 없음으로 보인다', async () => {
    // 기록 시각과 보고 시각이 다른 칸이다. 둘 다 있는 바인딩만 보면 두 칸을 바꿔 읽어도 같은 갈래로 간다.
    const unregistered: RobotView = {
      ...robotView('humanoid-03', 'CONFIRMED'),
      binding: binding({ robotId: 'humanoid-03', siteNames: 'UNREGISTERED', siteNamesRegisteredBy: null, siteNamesRegisteredAt: null }),
      commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: false },
      software: null,
    }
    installFakeOps({ ...view(), robots: [unregistered] }, adapterView({ adapters: [fleet] }), profiles())
    const names = within(await open('humanoid-03')).getByRole('region', { name: '사이트 명칭' })
    expect(field(names, '사람이 기록함')).toBe('기록 없음')
    expect(field(names, '기체가 답함')).toBe('아는 명칭 2개 (t1)')
  })

  it('기체의 응답은 아직 응답 없음과 명칭을 지원하지 않음을 가른다', async () => {
    const robots = ['humanoid-04', 'humanoid-05'].map<RobotView>((robotId, index) => ({
      ...robotView(robotId, 'CONFIRMED'),
      binding:
        index === 0
          ? binding({ robotId, siteNames: 'CLAIMED', siteNamesReportedAt: null, siteNamesCount: null, siteNamesUnsupported: null })
          : binding({ robotId, siteNames: 'CONTRADICTED', siteNamesCount: 0, siteNamesUnsupported: true }),
      commissioning: { state: 'INCOMPLETE', ledgerConfirmed: true, bound: true, siteNamesReady: false },
      software: { robotId, declared: '1.0', reported: '1.1', verdict: 'MISMATCH' },
    }))
    installFakeOps({ ...view(), robots }, adapterView({ adapters: [fleet] }), profiles())
    const first = await open('humanoid-04')
    expect(field(within(first).getByRole('region', { name: '사이트 명칭' }), '기체가 답함')).toBe('아직 응답 없음')
    expect(within(first).getByText(/소프트웨어 대조 불일치/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'humanoid-05' }))
    const second = screen.getByRole('region', { name: 'humanoid-05 상세' })
    expect(field(within(second).getByRole('region', { name: '사이트 명칭' }), '기체가 답함')).toBe('명칭을 지원하지 않음 (t1)')
  })

  it('바인딩 폼은 활성 리비전만 고르게 하고 고른 빌드·리비전 id 를 보낸다', async () => {
    const fake = installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('humanoid-02')
    const form = within(detail).getByRole('form', { name: '바인딩' })
    const revisions = within(form).getByLabelText('리비전')
    expect(within(revisions).getAllByRole('option').map((option) => option.textContent)).toEqual(['고르십시오', 'picasso-ref/humanoid-a#2'])
    await userEvent.selectOptions(within(form).getByLabelText('빌드'), 'acme/fleet 1.0.0')
    await userEvent.selectOptions(revisions, 'picasso-ref/humanoid-a#2')
    await userEvent.click(within(form).getByRole('button', { name: '바인딩' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots/humanoid-02/binding')
    expect(post.body).toEqual({ adapterVersionId: 10, profileRevisionId: 5 })
    expect(await screen.findByText('humanoid-02 바인딩: 반영됨')).toBeInTheDocument()
  })

  it('명칭 등록 기록은 본문 없이 그 기체의 경로로 보낸다', async () => {
    const fake = installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('quadruped-01')
    await userEvent.click(within(detail).getByRole('button', { name: '명칭 등록 기록' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots/quadruped-01/site-names')
    expect(post.body).toBeUndefined()
    expect(await screen.findByText('quadruped-01 명칭 기록: 반영됨')).toBeInTheDocument()
  })

  it('운영자 모드에서는 바인딩 폼과 명칭 기록 버튼이 없다', async () => {
    installFakeOps(view(), adapterView({ adapters: [fleet] }), profiles())
    const detail = await open('quadruped-01', '운영자')
    expect(within(detail).queryByRole('form', { name: '바인딩' })).not.toBeInTheDocument()
    expect(within(detail).queryByRole('button', { name: '명칭 등록 기록' })).not.toBeInTheDocument()
  })
})
