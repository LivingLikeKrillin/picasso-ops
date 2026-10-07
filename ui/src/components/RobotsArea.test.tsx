import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Finding } from '../api'
import { installFakeOps, outcome, robotView } from '../testing/fakeOps'

const awaiting: Finding = {
  kind: 'AWAITING_FIRST_REPORT',
  observed: '보고 0회',
  expected: '생존 보고 1회 이상',
  checkedAt: 't1',
  owner: 'SITE',
  inScreen: false,
  action: '기체·어댑터 기동과 사이트 id 확인',
  target: 'humanoid-01',
}

/** 운영 서비스의 `Rejections.of` 가 내는 모양 그대로다. */
const retiredAlready: Finding = {
  kind: 'RETIRED_ALREADY',
  observed: 'HTTP 409, status=RETIRED, 퇴역한 기체다',
  expected: '201 또는 200',
  checkedAt: 't2',
  owner: 'OPERATOR',
  inScreen: true,
  action: '복귀',
  target: 'humanoid-01',
}

const view = (robots = [robotView('humanoid-01', 'CLAIMED', [awaiting]), robotView('quadruped-01', 'RETIRED')]) => ({
  registry: 'OK' as const,
  checkedAt: 't1',
  robots,
  robotsAsOf: 't1',
})

/** 상세의 칸 하나. 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

async function choose(mode: '엔지니어' | '운영자', robotId?: string) {
  render(<App />)
  await userEvent.click(screen.getByLabelText(mode))
  if (robotId) await userEvent.click(await screen.findByRole('button', { name: robotId }))
}

async function declare(robotId: string, serial: string) {
  const form = await screen.findByRole('form', { name: '기체 선언' })
  await userEvent.type(within(form).getByLabelText('robot_id'), robotId)
  await userEvent.type(within(form).getByLabelText('일련번호'), serial)
  await userEvent.click(within(form).getByRole('button', { name: '선언' }))
}

describe('로봇·연결 영역', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('기체를 고르면 상세에 원장 상태와 연결이 따로 보이고 막힘이 5칸으로 보인다', async () => {
    installFakeOps(view())
    await choose('엔지니어', 'humanoid-01')
    const detail = screen.getByRole('region', { name: 'humanoid-01 상세' })
    expect(field(detail, '원장 상태')).toBe('CLAIMED')
    expect(field(detail, '연결')).toBe('보고 없음')
    expect(field(detail, '종류')).toBe('첫 보고 대기')
    expect(field(detail, '관측값과 기대값')).toBe('보고 0회 / 기대: 생존 보고 1회 이상')
    expect(field(detail, '마지막 확인')).toBe('t1')
    expect(field(detail, '해결 담당')).toBe('현장(화면 밖): 기체·어댑터 기동과 사이트 id 확인')
    // 이미 이 기체의 상세 안이므로 자기 자신을 가리키는 링크는 없다.
    expect(within(detail).queryByRole('button', { name: 'humanoid-01 상세' })).not.toBeInTheDocument()
  })

  it('화면 밖 작업을 화면이 명시한다', async () => {
    installFakeOps(view())
    render(<App />)
    expect(await screen.findByText(/화면 밖 작업: 로봇 내부 지도와 웨이포인트 티칭, mimic 기동/)).toBeInTheDocument()
  })

  it('엔지니어 모드에서만 선언 폼이 보이고 사이트 없이 JSON 으로 선언을 보낸다', async () => {
    const fake = installFakeOps(view([]))
    render(<App />)
    await declare('humanoid-01', 'HA-0001')
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots')
    expect(post.body).toEqual({ robotId: 'humanoid-01', serialNumber: 'HA-0001', displayName: null })
    expect(post.headers['X-Ops-Mode']).toBe('engineer')
    expect(post.headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('humanoid-01 선언: 반영됨')).toBeInTheDocument()

    await userEvent.click(screen.getByLabelText('운영자'))
    expect(screen.queryByRole('form', { name: '기체 선언' })).not.toBeInTheDocument()
    expect(screen.getByText('선언은 엔지니어 모드에서 합니다')).toBeInTheDocument()
  })

  it('퇴역은 운영자 모드에서 사유 없이는 보낼 수 없다', async () => {
    const fake = installFakeOps(view())
    await choose('운영자', 'humanoid-01')
    const form = screen.getByRole('form', { name: '퇴역' })
    const button = within(form).getByRole('button', { name: '퇴역' })
    expect(button).toBeDisabled()
    await userEvent.type(within(form).getByLabelText('퇴역 사유'), '   ')
    expect(button).toBeDisabled()
    await userEvent.clear(within(form).getByLabelText('퇴역 사유'))
    await userEvent.type(within(form).getByLabelText('퇴역 사유'), '정비')
    await userEvent.click(button)
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots/humanoid-01/retirement')
    expect(post.body).toEqual({ reason: '정비' })
    expect(post.headers['X-Ops-Mode']).toBe('operator')
    expect(post.headers['Content-Type']).toBe('application/json')
  })

  it('엔지니어 모드에서는 퇴역 폼과 복귀 버튼이 없다', async () => {
    installFakeOps(view())
    await choose('엔지니어', 'humanoid-01')
    expect(screen.queryByRole('form', { name: '퇴역' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'quadruped-01' }))
    expect(screen.queryByRole('button', { name: '복귀' })).not.toBeInTheDocument()
    expect(screen.getByText('퇴역과 복귀는 운영자 모드에서 합니다')).toBeInTheDocument()
  })

  it('퇴역한 기체는 운영자 모드에서 복귀를 보낸다', async () => {
    const fake = installFakeOps(view())
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'DELETE')).toBe(true))
    expect(fake.calls.find((call) => call.method === 'DELETE')!.url).toBe('/api/robots/quadruped-01/retirement')
    expect(await screen.findByText('quadruped-01 복귀: 반영됨')).toBeInTheDocument()
  })

  it('거절은 관측값과 기대값, 해결 담당과 후속 행동으로 보인다', async () => {
    const fake = installFakeOps(view([]))
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: retiredAlready }) }
    render(<App />)
    await declare('humanoid-01', 'HA-0001')
    expect(await screen.findByText('humanoid-01 선언: 거절됨')).toBeInTheDocument()
    expect(screen.getByText('이미 퇴역한 기체')).toBeInTheDocument()
    expect(screen.getByText('HTTP 409, status=RETIRED, 퇴역한 기체다 / 기대: 201 또는 200')).toBeInTheDocument()
    expect(screen.getByText('운영자(화면 안): 복귀')).toBeInTheDocument()
  })

  it('거절 알림의 바로 가기를 누르면 그 기체의 상세가 열린다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: retiredAlready }) }
    render(<App />)
    await declare('humanoid-01', 'HA-0001')
    expect(screen.queryByRole('region', { name: 'humanoid-01 상세' })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: 'humanoid-01 상세' }))
    expect(screen.getByRole('region', { name: 'humanoid-01 상세' })).toBeInTheDocument()
  })

  it('응답이 없고 다시 읽어 보니 반영 안 됐으면 그렇게 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = {
      status: 200,
      body: outcome({ result: 'NO_RESPONSE', confirmation: 'CONFIRMED_NOT_APPLIED', registryStatus: null }),
    }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(await screen.findByText(/quadruped-01 복귀: 응답 없음. 다시 읽어 보니 반영 안 됨/)).toBeInTheDocument()
  })

  it('응답이 없고 재조회도 못 했으면 확인하지 못했다고 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 200, body: outcome({ result: 'NO_RESPONSE', confirmation: null, registryStatus: null }) }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(
      await screen.findByText(/quadruped-01 복귀: 반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다/),
    ).toBeInTheDocument()
  })

  it('운영 서비스의 응답이 비정상이면 보내지 못했다고 단정하지 않고 결과 모름으로 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 502, body: {} }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(await screen.findByText(/quadruped-01 복귀: 결과 모름\(운영 서비스 응답 502\)/)).toBeInTheDocument()
  })

  it('운영 서비스가 먼저 막은 요청은 보내지 않음으로 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 403, body: { error: 'MODE_NOT_ALLOWED', detail: '이 조작은 operator 모드에서 한다' } }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(await screen.findByText('quadruped-01 복귀: 보내지 않음. 이 조작은 operator 모드에서 한다')).toBeInTheDocument()
  })

  it('사유가 없는 400 은 정해진 문장으로 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = {
      status: 400,
      body: { timestamp: 't', status: 400, error: 'Bad Request', path: '/api/robots/quadruped-01/retirement' },
    }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(
      await screen.findByText('quadruped-01 복귀: 보내지 않음. 운영 서비스가 요청을 거절했습니다(HTTP 400)'),
    ).toBeInTheDocument()
  })

  it('토큰 불일치여도 새로 읽은 목록은 직전 값으로 표시하지 않는다', async () => {
    installFakeOps({ ...view(), registry: 'REGISTRY_UNAUTHORIZED' })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('운영자 토큰 설정 확인')
    expect(await screen.findByRole('button', { name: 'humanoid-01' })).toBeInTheDocument()
    expect(screen.queryByText(/직전 값입니다/)).not.toBeInTheDocument()
  })
})
