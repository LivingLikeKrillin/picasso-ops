import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Finding, Session, SiteSettingsView } from '../api'
import { installFakeOps, outcome, robotView, settingsView } from '../testing/fakeOps'
import { SiteArea } from './SiteArea'

const engineer: Session = { mode: 'engineer', user: 'local' }
const operator: Session = { mode: 'operator', user: 'local' }

const version2 = {
  version: 2,
  connectionThresholdSeconds: 60,
  mode: 'ENGINEER',
  user: 'kim',
  reason: '연결 기준 줄임',
  recordedAt: 't2',
}

const twoVersions: SiteSettingsView = settingsView({
  current: version2,
  history: [version2, settingsView().current],
})

/** 운영 서비스의 `SiteSettingsOperations` 가 내는 모양 그대로다. */
const conflict: Finding = {
  kind: 'SETTINGS_VERSION_CONFLICT',
  observed: '현재 버전 2',
  expected: '기준 버전 1',
  checkedAt: 't3',
  owner: 'ENGINEER',
  inScreen: true,
  action: '현재 값을 다시 읽고 다시',
  target: null,
  basisVersion: null,
}

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

async function fill(seconds: string, reason: string) {
  const form = screen.getByRole('form', { name: '현장 설정 변경' })
  await userEvent.type(within(form).getByLabelText('연결 기준 시간(초)'), seconds)
  await userEvent.type(within(form).getByLabelText('변경 사유'), reason)
  return form
}

describe('현장·자원 영역', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('현재 버전과 연결 기준 시간, 허용 범위, 버전 이력을 최신부터 보인다', () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
    const section = screen.getByRole('region', { name: '현장 설정' })
    expect(field(section, '현재 버전')).toBe('2')
    expect(field(section, '연결 기준 시간')).toBe('60초')
    expect(field(section, '허용 범위')).toBe('60~3600초')
    const rows = within(screen.getByRole('table', { name: '현장 설정 버전 이력' })).getAllByRole('row').slice(1)
    expect(rows.map((row) => row.textContent)).toEqual([
      '260초kim엔지니어연결 기준 줄임t2',
      '190초system엔지니어S1 설정값 이전t0',
    ])
  })

  it('운영자 모드에서는 변경 폼 대신 엔지니어 모드에서 한다고 보인다', () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={twoVersions} opsError={null} session={operator} onChanged={() => undefined} />)
    expect(screen.queryByRole('form', { name: '현장 설정 변경' })).not.toBeInTheDocument()
    expect(screen.getByText('현장 설정 변경은 엔지니어 모드에서 합니다')).toBeInTheDocument()
  })

  it('기준 버전은 고치기 시작한 버전으로 고정해 엔지니어 모드로 보낸다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    const { rerender } = render(
      <SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />,
    )
    const form = await fill('60', '연결 기준 줄임')
    // 고치는 사이 다른 사람이 버전 2 를 올렸다. 다시 읽은 버전이 아니라 고치기 시작한 버전 1 을 보낸다.
    rerender(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
    expect(within(form).getByText('기준 버전 1')).toBeInTheDocument()
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'PUT')).toBe(true))
    const put = fake.calls.find((call) => call.method === 'PUT')!
    expect(put.url).toBe('/api/site-settings')
    expect(put.body).toEqual({ baseVersion: 1, connectionThresholdSeconds: 60, reason: '연결 기준 줄임' })
    expect(put.headers['X-Ops-Mode']).toBe('engineer')
    expect(put.headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('연결 기준 시간 60초로 변경: 반영됨')).toBeInTheDocument()
  })

  it('범위 밖 값과 빈 사유는 보내지 않는다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
    let form = await fill('59', '짧게')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    expect(within(form).getByRole('alert')).toHaveTextContent('연결 기준 시간은 60~3600초의 정수여야 합니다')
    await userEvent.clear(within(form).getByLabelText('연결 기준 시간(초)'))
    await userEvent.clear(within(form).getByLabelText('변경 사유'))
    form = await fill('120', ' ')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    expect(within(form).getByRole('alert')).toHaveTextContent('변경 사유를 넣으십시오')
    expect(fake.calls.some((call) => call.method === 'PUT')).toBe(false)
  })

  it('버전 충돌 거부는 관측값과 후속 행동, 근거 버전 해당 없음으로 보인다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', rejection: conflict }) }
    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
    const form = await fill('120', '늦은 변경')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    const notice = await screen.findByRole('status')
    expect(notice).toHaveTextContent('연결 기준 시간 120초로 변경: 거절됨')
    expect(field(notice, '종류')).toBe('현장 설정 버전 충돌')
    expect(field(notice, '관측값과 기대값')).toBe('현재 버전 2 / 기대: 기준 버전 1')
    expect(field(notice, '근거 버전')).toBe('해당 없음')
    expect(field(notice, '해결 담당')).toBe('엔지니어(화면 안): 현재 값을 다시 읽고 다시')
  })

  it('현장 설정을 읽지 못했으면 모름을 보이고 폼을 내지 않는다', () => {
    render(<SiteArea view={null} opsError="닿지 않음" session={engineer} onChanged={() => undefined} />)
    expect(screen.getByText('모름: 현장 설정을 아직 읽지 못했습니다')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: '현장 설정 변경' })).not.toBeInTheDocument()
  })

  it('현장 설정을 못 읽으면 다섯 읽기 모두 직전 값으로 남는다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    fake.failing.add('/api/site-settings')
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '현장·자원' }))
    expect(await screen.findByText('모름: 현장 설정을 아직 읽지 못했습니다')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '로봇·연결' }))
    expect(screen.getByText('모름: 기체 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
  })

  it('기체 상세는 연결 판정 기준을, 오래됨 막힘은 근거 버전을 보인다', async () => {
    const stale: Finding = {
      kind: 'REPORT_STALE',
      observed: '마지막 보고 t0',
      expected: '60초 안의 보고',
      checkedAt: 't1',
      owner: 'SITE',
      inScreen: false,
      action: '연결 확인',
      target: 'humanoid-01',
      basisVersion: 2,
    }
    installFakeOps({
      registry: 'OK',
      checkedAt: 't1',
      robots: [{ ...robotView('humanoid-01', 'CONFIRMED', [stale]), connection: 'STALE' }],
      robotsAsOf: 't1',
      settingsVersion: 2,
      connectionThresholdSeconds: 60,
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'humanoid-01' }))
    const detail = screen.getByRole('region', { name: 'humanoid-01 상세' })
    expect(field(detail, '연결')).toBe('오래됨')
    expect(field(detail, '연결 판정 기준')).toBe('기준 60초, 현장 설정 버전 2')
    expect(field(detail, '근거 버전')).toBe('현장 설정 버전 2')
  })
})
