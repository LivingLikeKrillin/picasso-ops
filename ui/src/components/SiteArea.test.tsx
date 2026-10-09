import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Finding, Session, SiteSettingsView } from '../api'
import { hostTimings, installFakeOps, outcome, robotView, settingsView } from '../testing/fakeOps'
import { SiteArea } from './SiteArea'

const engineer: Session = { mode: 'engineer', user: 'local' }
const operator: Session = { mode: 'operator', user: 'local' }

const version2 = {
  version: 2,
  connectionThresholdSeconds: 60,
  evidenceBeforeSeconds: 45,
  evidenceAfterSeconds: 20,
  inDoubtGraceSeconds: 90,
  stallWindowSeconds: 600,
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

/** 칸은 지금 값으로 채워져 있으므로 지우고 넣는다. */
async function set(label: string, value: string) {
  const form = screen.getByRole('form', { name: '현장 설정 변경' })
  const input = within(form).getByLabelText(label)
  await userEvent.clear(input)
  await userEvent.type(input, value)
  return form
}

async function fill(seconds: string, reason: string) {
  const form = await set('연결 기준 시간(초)', seconds)
  await userEvent.type(within(form).getByLabelText('변경 사유'), reason)
  return form
}

const submitted = (fake: { calls: { method: string; body: unknown }[] }) =>
  fake.calls.filter((call) => call.method === 'PUT').map((call) => call.body)

/** 기준 버전 1 의 값 다섯(마이그레이션이 넣는 값). */
const version1Values = {
  connectionThresholdSeconds: 90,
  evidenceBeforeSeconds: 30,
  evidenceAfterSeconds: 15,
  inDoubtGraceSeconds: 60,
  stallWindowSeconds: 300,
}

describe('현장·자원 영역', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('현재 버전과 연결 기준 시간, 허용 범위, 시간값 넷의 열을 덧붙인 버전 이력을 최신부터 보인다', () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
    const section = screen.getByRole('region', { name: '현장 설정' })
    expect(field(section, '현재 버전')).toBe('2')
    expect(field(section, '연결 기준 시간')).toBe('60초')
    expect(field(section, '허용 범위')).toBe('60~3600초')
    const rows = within(screen.getByRole('table', { name: '현장 설정 버전 이력' })).getAllByRole('row').slice(1)
    // 기존 칸 순서는 S2 그대로이고 시간값 넷은 끝에 붙는다(S3c 스펙 §8). Playwright 는 행 앞부분으로 찾는다.
    expect(rows.map((row) => row.textContent)).toEqual([
      '260초kim엔지니어연결 기준 줄임t245초20초90초600초',
      '190초system엔지니어S1 설정값 이전t030초15초60초300초',
    ])
  })

  it('시간값 넷을 결과 판정 값과 정체 표시 두 묶음으로 나눠 묶음마다 파급을 적는다', () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
    const judging = screen.getByRole('region', { name: '결과 판정 값' })
    expect(field(judging, '근거 윈도우 앞 폭')).toBe('45초')
    expect(field(judging, '근거 윈도우 뒤 폭')).toBe('20초')
    expect(field(judging, 'inDoubtGrace')).toBe('90초')
    expect(within(judging).queryByText('stallWindow', { selector: 'dt' })).not.toBeInTheDocument()
    expect(judging).toHaveTextContent('앞 폭을 늘리면 옛 신호가 완료 근거로 들어옵니다')
    expect(judging).toHaveTextContent('뒤 폭이나 inDoubtGrace 를 줄이면 UNVERIFIED 와 운영자 대기가 늘어납니다')
    const stall = screen.getByRole('region', { name: '정체 표시' })
    expect(field(stall, 'stallWindow')).toBe('600초')
    expect(within(stall).queryByText('inDoubtGrace', { selector: 'dt' })).not.toBeInTheDocument()
    expect(stall).toHaveTextContent(
      'stallWindow 는 정체를 사람에게 보이는 시점만 바꿉니다. 실패 판정이나 자동 조치는 바뀌지 않습니다',
    )
    // 폼도 같은 두 묶음이고 칸마다 허용 범위를 보인다.
    const form = screen.getByRole('form', { name: '현장 설정 변경' })
    const judgingInputs = within(form).getByRole('group', { name: '결과 판정 값' })
    expect(within(judgingInputs).getByLabelText('근거 윈도우 앞 폭(초)')).toHaveValue(45)
    expect(judgingInputs).toHaveTextContent('허용 범위 5~120초')
    expect(judgingInputs).toHaveTextContent('허용 범위 10~600초')
    const stallInputs = within(form).getByRole('group', { name: '정체 표시' })
    expect(within(stallInputs).getByLabelText('stallWindow(초)')).toHaveValue(600)
    expect(stallInputs).toHaveTextContent('허용 범위 30~3600초')
  })

  it('운영자 모드에서는 변경 폼 대신 엔지니어 모드에서 한다고 보인다', () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={twoVersions} opsError={null} session={operator} onChanged={() => undefined} />)
    expect(screen.queryByRole('form', { name: '현장 설정 변경' })).not.toBeInTheDocument()
    expect(screen.getByText('현장 설정 변경은 엔지니어 모드에서 합니다')).toBeInTheDocument()
    // 값과 묶음, 실행 호스트 반영은 운영자 모드에서도 읽는다. 입력 칸은 없다.
    expect(field(screen.getByRole('region', { name: '정체 표시' }), 'stallWindow')).toBe('600초')
    expect(screen.getByText('실행 호스트 반영: 버전 1')).toBeInTheDocument()
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
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
    // 기준 버전 1 의 시간값을 그대로 싣는다. 다시 읽은 버전 2 의 값이 아니다.
    expect(put.body).toEqual({ baseVersion: 1, ...version1Values, connectionThresholdSeconds: 60, reason: '연결 기준 줄임' })
    expect(put.headers['X-Ops-Mode']).toBe('engineer')
    expect(put.headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('연결 기준 시간 60초로 변경: 반영됨')).toBeInTheDocument()
  })

  it('사유를 먼저 넣어도 그 순간의 기준 버전과 값 다섯을 고정하고 뒤에 고친 칸만 바꾼다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    const { rerender } = render(
      <SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />,
    )
    // 처음 고친 것은 사유다. 이 순간 기준 버전 1 과 그 값 다섯이 고정된다.
    const form = screen.getByRole('form', { name: '현장 설정 변경' })
    await userEvent.type(within(form).getByLabelText('변경 사유'), '정체 표시 늦춤')
    // 다시 읽은 버전 2 는 값 다섯이 모두 다르다. 고정한 값이 아니라 이 값이 실리면 남의 변경을 모르고 덮어쓴다.
    rerender(<SiteArea view={twoVersions} opsError={null} session={engineer} onChanged={() => undefined} />)
    await set('stallWindow(초)', '900')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    await waitFor(() => expect(submitted(fake)).toHaveLength(1))
    expect(submitted(fake)[0]).toEqual({
      baseVersion: 1,
      ...version1Values,
      stallWindowSeconds: 900,
      reason: '정체 표시 늦춤',
    })
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

  it('시간값 넷은 칸마다 범위 밖이면 그 칸을 적고 보내지 않으며 양 끝 값은 보낸다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
    const outside: [string, string, string][] = [
      ['근거 윈도우 앞 폭(초)', '4', '근거 윈도우 앞 폭은 5~120초의 정수여야 합니다'],
      ['근거 윈도우 뒤 폭(초)', '121', '근거 윈도우 뒤 폭은 5~120초의 정수여야 합니다'],
      ['inDoubtGrace(초)', '9', 'inDoubtGrace 는 10~600초의 정수여야 합니다'],
      ['stallWindow(초)', '3601', 'stallWindow 는 30~3600초의 정수여야 합니다'],
    ]
    const reason = () => within(screen.getByRole('form', { name: '현장 설정 변경' })).getByLabelText('변경 사유')
    await userEvent.type(reason(), '범위 확인')
    for (const [label, value, message] of outside) {
      const form = await set(label, value)
      await userEvent.click(within(form).getByRole('button', { name: '변경' }))
      // 다른 칸은 지금 값이라 범위 안이다. 알림은 그 칸 하나만 적는다.
      expect(within(form).getByRole('alert').textContent).toBe(message)
      await set(label, '30')
    }
    let form = await set('inDoubtGrace(초)', '15.5')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    expect(within(form).getByRole('alert').textContent).toBe('inDoubtGrace 는 10~600초의 정수여야 합니다')
    expect(submitted(fake)).toEqual([])
    await set('근거 윈도우 앞 폭(초)', '5')
    await set('근거 윈도우 뒤 폭(초)', '120')
    await set('inDoubtGrace(초)', '10')
    form = await set('stallWindow(초)', '3600')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    await waitFor(() => expect(submitted(fake)).toHaveLength(1))
    expect(submitted(fake)[0]).toEqual({
      baseVersion: 1,
      connectionThresholdSeconds: 90,
      evidenceBeforeSeconds: 5,
      evidenceAfterSeconds: 120,
      inDoubtGraceSeconds: 10,
      stallWindowSeconds: 3600,
      reason: '범위 확인',
    })
  })

  it('PUT 은 바꾸지 않은 칸까지 값 다섯을 다 싣는다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
    const form = await set('stallWindow(초)', '600')
    await userEvent.type(within(form).getByLabelText('변경 사유'), '정체 표시 늦춤')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    await waitFor(() => expect(submitted(fake)).toHaveLength(1))
    expect(submitted(fake)[0]).toEqual({
      baseVersion: 1,
      ...version1Values,
      stallWindowSeconds: 600,
      reason: '정체 표시 늦춤',
    })
  })

  it('변경 결과 문구는 기준 버전에서 바뀐 칸만 칸 순서대로 적는다', async () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<SiteArea view={settingsView()} opsError={null} session={engineer} onChanged={() => undefined} />)
    await set('stallWindow(초)', '600')
    let form = await set('inDoubtGrace(초)', '90')
    await userEvent.type(within(form).getByLabelText('변경 사유'), '유예 늘림')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    expect(await screen.findByText('inDoubtGrace 90초, stallWindow 600초로 변경: 반영됨')).toBeInTheDocument()
    form = screen.getByRole('form', { name: '현장 설정 변경' })
    await userEvent.type(within(form).getByLabelText('변경 사유'), '같은 값')
    await userEvent.click(within(form).getByRole('button', { name: '변경' }))
    expect(await screen.findByText('현장 설정 같은 값으로 새 버전 기록: 반영됨')).toBeInTheDocument()
  })

  it('실행 호스트의 적용 버전을 최신 설정 버전과 따로 보이고 호스트가 닿지 않으면 모름이다', () => {
    const lagging = { ...twoVersions, hostTimings: hostTimings() }
    const { rerender } = render(
      <SiteArea view={lagging} opsError={null} session={engineer} onChanged={() => undefined} />,
    )
    const section = screen.getByRole('region', { name: '현장 설정' })
    expect(field(section, '현재 버전')).toBe('2')
    expect(within(section).getByText('실행 호스트 반영: 버전 1')).toBeInTheDocument()
    const caught = {
      ...twoVersions,
      hostTimings: hostTimings({ applied: { version: 2, ...version1Values, stallWindowSeconds: 600 } }),
    }
    rerender(<SiteArea view={caught} opsError={null} session={engineer} onChanged={() => undefined} />)
    expect(within(section).getByText('실행 호스트 반영: 버전 2')).toBeInTheDocument()
    const silent = { ...twoVersions, hostTimings: null }
    rerender(<SiteArea view={silent} opsError={null} session={engineer} onChanged={() => undefined} />)
    expect(within(section).getByText('실행 호스트 반영: 모름')).toBeInTheDocument()
    expect(within(section).queryByText(/실행 호스트 반영: 버전/)).not.toBeInTheDocument()
  })

  it('실행 호스트의 미적용과 읽기 실패, 범위 밖이라 적용하지 않은 버전을 보인다', () => {
    const unapplied = {
      ...twoVersions,
      hostTimings: hostTimings({
        applied: null,
        appliedAt: null,
        readError: 'PSQLException: relation "ops.site_timings_current" does not exist',
      }),
    }
    const { rerender } = render(
      <SiteArea view={unapplied} opsError={null} session={engineer} onChanged={() => undefined} />,
    )
    const section = screen.getByRole('region', { name: '현장 설정' })
    expect(within(section).getByText('실행 호스트 반영: 미적용')).toBeInTheDocument()
    expect(
      within(section).getByText(
        '실행 호스트 읽기 실패: PSQLException: relation "ops.site_timings_current" does not exist',
      ),
    ).toBeInTheDocument()
    const rejected = {
      ...twoVersions,
      hostTimings: hostTimings({
        rejected: { version: 2, reasons: ['stallWindow: 30~3600 초 밖이다 (3601)', 'inDoubtGrace: 10~600 초 밖이다 (9)'] },
      }),
    }
    rerender(<SiteArea view={rejected} opsError={null} session={engineer} onChanged={() => undefined} />)
    expect(within(section).getByText('실행 호스트 반영: 버전 1')).toBeInTheDocument()
    expect(
      within(section).getByText(
        '실행 호스트가 적용하지 않은 버전 2(범위 밖): stallWindow: 30~3600 초 밖이다 (3601); inDoubtGrace: 10~600 초 밖이다 (9)',
      ),
    ).toBeInTheDocument()
    expect(within(section).queryByText(/실행 호스트 읽기 실패/)).not.toBeInTheDocument()
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
