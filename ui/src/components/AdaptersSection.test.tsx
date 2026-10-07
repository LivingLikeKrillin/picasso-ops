import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Adapter, AdapterInstance, Finding, RobotListView } from '../api'
import { adapterView, installFakeOps, outcome } from '../testing/fakeOps'

const robots: RobotListView = { registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const fleet: Adapter = {
  adapterId: 1,
  vendor: 'acme',
  name: 'fleet',
  versions: [
    {
      adapterVersionId: 10,
      version: '1.0.0',
      contractSemver: '0.9.0',
      conformance: 'UNTESTED',
      registeredAt: 't0',
      registeredBy: 'engineer/lee',
    },
  ],
}

const gateway: AdapterInstance = {
  instanceId: 'fleet-gw-01',
  siteId: 'site-01',
  fleetEndpoint: null,
  registeredAt: 't0',
  registeredBy: 'engineer/lee',
  adapter: 'acme/fleet',
  version: '1.0.0',
  contractSemver: '0.9.0',
  conformance: 'UNTESTED',
  discoveredRobots: 0,
}

/** 운영 서비스의 `AdapterRejections.of` 가 내는 모양 그대로다. */
const conflict: Finding = {
  kind: 'VERSION_CONFLICT',
  observed: 'HTTP 409, existing_contract_semver=0.9.0, 같은 버전이 다른 계약 semver 로 이미 있다',
  expected: '201 또는 200',
  checkedAt: 't2',
  owner: 'ENGINEER',
  inScreen: true,
  action: '다른 버전 번호로',
  target: null,
}

const listed = () => adapterView({ adapters: [fleet], instances: [gateway] })

/** 화면을 띄우고 첫 읽기가 끝날 때까지 기다린다. 기체 목록(빈 목록)이 보이면 어댑터 목록도 함께 읽혔다. */
async function section() {
  render(<App />)
  await screen.findByText('선언된 기체가 없습니다')
  return screen.getByRole('region', { name: '어댑터' })
}

function posted(fake: ReturnType<typeof installFakeOps>) {
  return fake.calls.find((call) => call.method === 'POST')!
}

describe('어댑터', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('인스턴스와 제품·빌드 목록이 적합성 UNTESTED 와 함께 보인다', async () => {
    installFakeOps(robots, listed())
    const adapters = await section()
    const instances = await within(adapters).findByRole('table', { name: '인스턴스 목록' })
    expect(within(instances).getByRole('row', { name: /fleet-gw-01/ })).toHaveTextContent(
      'fleet-gw-01acme/fleet 1.0.00.9.0UNTESTED직결0',
    )
    const builds = within(adapters).getByRole('table', { name: '제품·빌드 목록' })
    expect(within(builds).getByRole('row', { name: /acme\/fleet/ })).toHaveTextContent('acme/fleet1.0.00.9.0UNTESTED')
  })

  it('목록을 읽은 적이 없으면 없음이 아니라 모름을 보이고, 직전 값이면 그렇게 표시한다', async () => {
    installFakeOps(robots, adapterView({ registry: 'REGISTRY_SILENT', adapters: null, instances: null, asOf: null }))
    const adapters = await section()
    expect(await within(adapters).findByText('모름: 어댑터 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
    expect(within(adapters).queryByText('등록된 제품이 없습니다')).not.toBeInTheDocument()

    vi.unstubAllGlobals()
    installFakeOps(robots, adapterView({ registry: 'REGISTRY_SILENT', checkedAt: 't2', adapters: [fleet], instances: [], asOf: 't1' }))
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(await within(adapters).findByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
    expect(within(adapters).getByText('등록된 인스턴스가 없습니다')).toBeInTheDocument()
  })

  it('운영자 모드에서는 등록 폼이 없고 어느 모드에서 하는지 적는다', async () => {
    installFakeOps(robots, listed())
    const adapters = await section()
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(within(adapters).getByText('어댑터 등록은 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(within(adapters).queryByRole('form')).not.toBeInTheDocument()
  })

  it('제품 선언은 엔지니어 모드로 JSON 을 보내고 결과가 제품 이름과 함께 보인다', async () => {
    const fake = installFakeOps(robots)
    const form = within(await section()).getByRole('form', { name: '제품 선언' })
    await userEvent.type(within(form).getByLabelText('vendor'), 'acme')
    await userEvent.type(within(form).getByLabelText('name'), 'fleet')
    await userEvent.click(within(form).getByRole('button', { name: '제품 선언' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = posted(fake)
    expect(post.url).toBe('/api/adapters')
    expect(post.body).toEqual({ vendor: 'acme', name: 'fleet' })
    expect(post.headers['X-Ops-Mode']).toBe('engineer')
    expect(post.headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('acme/fleet 제품 선언: 반영됨')).toBeInTheDocument()
  })

  it('빌드 선언은 고른 제품의 경로로 버전과 계약 semver 를 보낸다', async () => {
    const fake = installFakeOps(robots, listed())
    const form = within(await section()).getByRole('form', { name: '빌드 선언' })
    const button = within(form).getByRole('button', { name: '빌드 선언' })
    await userEvent.type(within(form).getByLabelText('버전'), '1.1.0')
    await userEvent.type(within(form).getByLabelText('계약 semver'), '0.9.0')
    // 제품을 고르기 전에는 보낼 수 없다.
    expect(button).toBeDisabled()
    await userEvent.selectOptions(within(form).getByLabelText('제품'), 'acme/fleet')
    await userEvent.click(button)
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = posted(fake)
    expect(post.url).toBe('/api/adapters/1/versions')
    expect(post.body).toEqual({ version: '1.1.0', contractSemver: '0.9.0' })
    expect(await screen.findByText('acme/fleet 1.1.0 빌드 선언: 반영됨')).toBeInTheDocument()
  })

  it('인스턴스 등록은 고른 빌드 id 를 보내고 빈 플릿 주소는 null 이며 사이트는 싣지 않는다', async () => {
    const fake = installFakeOps(robots, listed())
    const form = within(await section()).getByRole('form', { name: '인스턴스 등록' })
    const button = within(form).getByRole('button', { name: '인스턴스 등록' })
    await userEvent.type(within(form).getByLabelText('instance_id'), 'fleet-gw-02')
    expect(button).toBeDisabled()
    await userEvent.selectOptions(within(form).getByLabelText('빌드'), 'acme/fleet 1.0.0')
    await userEvent.click(button)
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = posted(fake)
    expect(post.url).toBe('/api/adapter-instances')
    expect(post.body).toEqual({ instanceId: 'fleet-gw-02', adapterVersionId: 10, fleetEndpoint: null })
    expect(await screen.findByText('fleet-gw-02 인스턴스 등록: 반영됨')).toBeInTheDocument()
  })

  it('409 거절은 기존 계약값과 엔지니어의 후속 행동으로 보이고 기체 상세로 가는 바로 가기는 없다', async () => {
    const fake = installFakeOps(robots, listed())
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: conflict }) }
    const form = within(await section()).getByRole('form', { name: '빌드 선언' })
    await userEvent.selectOptions(within(form).getByLabelText('제품'), 'acme/fleet')
    await userEvent.type(within(form).getByLabelText('버전'), '1.0.0')
    await userEvent.type(within(form).getByLabelText('계약 semver'), '1.0.0')
    await userEvent.click(within(form).getByRole('button', { name: '빌드 선언' }))
    const notice = (await screen.findByText('acme/fleet 1.0.0 빌드 선언: 거절됨')).closest('[role="status"]') as HTMLElement
    expect(within(notice).getByText('같은 버전에 다른 계약값')).toBeInTheDocument()
    expect(within(notice).getByText(/existing_contract_semver=0\.9\.0/)).toBeInTheDocument()
    expect(within(notice).getByText('엔지니어(화면 안): 다른 버전 번호로')).toBeInTheDocument()
    expect(within(notice).queryByText('바로 가기')).not.toBeInTheDocument()
  })
})
