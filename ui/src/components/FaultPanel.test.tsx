import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { FaultInjectionOutcome, RobotListView, Session } from '../api'
import { faultInjected, installFakeOps, robotView } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { FaultPanel } from './FaultPanel'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const robots: RobotListView = {
  registry: 'OK',
  checkedAt: 't1',
  robots: [robotView('humanoid-01', 'ACTIVE'), robotView('old-01', 'RETIRED'), robotView('quadruped-01', 'ACTIVE')],
  robotsAsOf: 't1',
}

const FAULTS = '/api/faults'

const posts = (fake: FakeOps) => fake.calls.filter((call) => call.method === 'POST' && call.url === FAULTS)

function open(session: Session = engineer, onChanged: () => void = () => undefined) {
  return render(<FaultPanel robots={robots} session={session} onChanged={onChanged} />)
}

async function inject(reason: string) {
  const form = screen.getByRole('form', { name: '장애 주입 폼' })
  if (reason !== '') await userEvent.type(within(form).getByLabelText('장애 주입 사유'), reason)
  await userEvent.click(within(form).getByRole('button', { name: '장애 넣기' }))
}

describe('장애 주입', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('퇴역하지 않은 기체만 고르고 스킬 실패는 상태 없이 사유와 함께 엔지니어 모드로 보내며 받아들임을 보인다', async () => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, { status: 200, body: faultInjected() })
    const onChanged = vi.fn()
    open(engineer, onChanged)
    const form = screen.getByRole('form', { name: '장애 주입 폼' })
    const robot = within(form).getByLabelText('기체')
    expect(within(robot).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'humanoid-01',
      'quadruped-01',
    ])
    expect(
      within(within(form).getByLabelText('장애 종류')).getAllByRole('option').map((option) => option.textContent),
    ).toEqual(['스킬 실패(진행 중 태스크)', '연결 상태'])
    expect(within(form).queryByLabelText('연결 상태')).not.toBeInTheDocument()

    await inject('  스킬 실패 시연  ')
    expect(await screen.findByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
      'humanoid-01 스킬 실패: 받아들임(태스크 JO-1#RACK-204.S01, RETRIABLE)',
    )
    const post = posts(fake).at(-1)!
    expect(post.body).toEqual({ robotId: 'humanoid-01', kind: 'SKILL_EXECUTION_FAILED', reason: '스킬 실패 시연' })
    expect(post.headers['X-Ops-Mode']).toBe('engineer')
    expect(post.headers['X-Ops-User']).toBe('lee')
    expect(post.headers['Content-Type']).toBe('application/json')
    expect(onChanged).toHaveBeenCalled()
  })

  it('연결 상태는 상태 셋 가운데 고른 것을 싣고 같은 상태를 다시 넣으면 바뀐 것 없음으로 보인다', async () => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, {
      status: 200,
      body: faultInjected({
        robotId: 'quadruped-01',
        kind: 'CONNECTION',
        state: 'CONNECTION_BROKEN',
        fault: { robotId: 'quadruped-01', kind: 'CONNECTION', state: 'CONNECTION_BROKEN', changed: true },
      }),
    })
    open()
    const form = screen.getByRole('form', { name: '장애 주입 폼' })
    await userEvent.selectOptions(within(form).getByLabelText('기체'), 'quadruped-01')
    await userEvent.selectOptions(within(form).getByLabelText('장애 종류'), '연결 상태')
    const state = within(form).getByLabelText('연결 상태')
    expect(within(state).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'OFFLINE',
      'CONNECTION_BROKEN',
      'ONLINE(복구)',
    ])
    await userEvent.selectOptions(state, 'CONNECTION_BROKEN')
    await inject('묵은 값 시연')
    expect(await screen.findByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
      'quadruped-01 연결 상태 CONNECTION_BROKEN: 받아들임(연결 상태 CONNECTION_BROKEN)',
    )
    expect(posts(fake).at(-1)!.body).toEqual({
      robotId: 'quadruped-01',
      kind: 'CONNECTION',
      state: 'CONNECTION_BROKEN',
      reason: '묵은 값 시연',
    })

    fake.answers.set(FAULTS, {
      status: 200,
      body: faultInjected({
        robotId: 'quadruped-01',
        kind: 'CONNECTION',
        state: 'CONNECTION_BROKEN',
        fault: { robotId: 'quadruped-01', kind: 'CONNECTION', state: 'CONNECTION_BROKEN', changed: false },
      }),
    })
    await inject('한 번 더')
    await waitFor(() =>
      expect(screen.getByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
        'quadruped-01 연결 상태 CONNECTION_BROKEN: 받아들임(이미 CONNECTION_BROKEN 상태라 바뀐 것 없음)',
      ),
    )
  })

  it('사유가 비었거나 공백뿐이면 보내지 않는다', async () => {
    const fake = installFakeOps(robots)
    open()
    await inject('')
    expect(screen.getByRole('alert')).toHaveTextContent('장애 주입 사유를 넣으십시오')
    await inject('   ')
    expect(screen.getByRole('alert')).toHaveTextContent('장애 주입 사유를 넣으십시오')
    expect(posts(fake)).toEqual([])
  })

  it('운영자 모드에서는 폼 없이 엔지니어 모드에서 한다고 보인다', () => {
    installFakeOps(robots)
    open(operator)
    const region = screen.getByRole('region', { name: '장애 주입' })
    expect(within(region).getByText('장애 주입은 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(within(region).queryByRole('form')).not.toBeInTheDocument()
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
  })

  it.each<[FaultInjectionOutcome, string]>([
    [
      faultInjected({
        result: 'REJECTED',
        fault: null,
        rejection: { status: 409, error: 'NO_RUNNING_TASK', detail: '진행 중인 pick_place 태스크가 없다: humanoid-01' },
      }),
      'humanoid-01 스킬 실패: 현장이 거부함(진행 중 태스크 없음). 진행 중인 pick_place 태스크가 없다: humanoid-01',
    ],
    [
      faultInjected({
        result: 'REJECTED',
        fault: null,
        rejection: { status: 404, error: 'UNKNOWN_ROBOT', detail: '이 현장에 없는 기체다' },
      }),
      'humanoid-01 스킬 실패: 현장이 거부함(모르는 기체). 이 현장에 없는 기체다',
    ],
    [
      faultInjected({
        result: 'REJECTED',
        fault: null,
        rejection: { status: 400, error: 'UNSUPPORTED_FAULT', detail: '받지 않는 종류다' },
      }),
      'humanoid-01 스킬 실패: 현장이 거부함(받지 않는 장애 종류). 받지 않는 종류다',
    ],
    [
      // 거부 본문을 읽지 못한 4xx(스프링 기본 415 등)는 이름과 문장이 없다.
      faultInjected({ result: 'REJECTED', fault: null, rejection: { status: 415, error: null, detail: null } }),
      'humanoid-01 스킬 실패: 현장이 거부함(이유 없음)',
    ],
    [
      faultInjected({ result: 'NO_RESPONSE', fault: null }),
      'humanoid-01 스킬 실패: 응답 없음. 넣었는지 모릅니다. 실행 목록과 기체 목록에서 확인하십시오',
    ],
  ])('현장의 거부는 이유를 풀어 적고 응답 없음은 하나로 보이며 둘 다 받아들임이 아니다(%#)', async (body, text) => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, { status: 200, body })
    open()
    await inject('시연')
    const shown = await screen.findByRole('status', { name: '장애 주입 결과' })
    expect(shown).toHaveTextContent(text)
    expect(shown).not.toHaveTextContent('받아들임')
    expect(shown).not.toHaveTextContent('null')
  })

  it('운영 서비스의 사전 거부는 보내지 않음과 그 이유다', async () => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, {
      status: 403,
      body: { error: 'MODE_NOT_ALLOWED', detail: '이 조작은 engineer 모드에서 한다' },
    })
    open()
    await inject('시연')
    expect(await screen.findByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
      'humanoid-01 스킬 실패: 보내지 않음(이 모드에서 할 수 없는 조작). 이 조작은 engineer 모드에서 한다',
    )
  })

  it('현장·자원 영역에 현장 설정과 따로 장애 주입 구역이 있고 기체 목록을 다시 읽어 고를 기체를 채운다', async () => {
    installFakeOps(robots)
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '현장·자원' }))
    const region = screen.getByRole('region', { name: '장애 주입' })
    expect(screen.getByRole('region', { name: '현장 설정' })).toBeInTheDocument()
    const robot = await within(region).findByLabelText('기체')
    expect(within(robot).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'humanoid-01',
      'quadruped-01',
    ])
  })
})
