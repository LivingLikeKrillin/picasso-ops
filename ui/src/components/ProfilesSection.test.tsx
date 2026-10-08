import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Finding, RobotListView } from '../api'
import { adapterView, installFakeOps, outcome, profileView, revisionView } from '../testing/fakeOps'

const robots: RobotListView = { registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const pass = (ranBy = 'site-runner') => ({ result: 'PASS', ranAt: 't1', ranBy, detail: { checks: 3, failures: [] } })

const tested = () =>
  profileView({
    catalog: {
      contractSemver: '0.9.0',
      skillTypes: [
        { name: 'navigate_to', major: 1, introducedInSemver: '0.1.0', siteReferenceKeys: ['location'] },
        { name: 'pick_place', major: 1, introducedInSemver: '0.1.0', siteReferenceKeys: ['destination'] },
      ],
    },
    revisions: [
      revisionView({ status: 'TESTED', suites: { CONTRACT: pass(), NEGATIVE: pass(), DETERMINISM: pass() } }, 'DONE'),
      revisionView(
        {
          profileRevisionId: 6,
          model: 'quadruped-b',
          revision: 1,
          suites: {
            CONTRACT: pass(),
            NEGATIVE: {
              result: 'FAIL',
              ranAt: 't1',
              ranBy: 'site-runner',
              detail: { checks: 5, failures: [{ check: 'CANCEL_UNSUPPORTED', expected: '거절', observed: '수락' }] },
            },
          },
        },
        'RUNNING',
      ),
      revisionView({ profileRevisionId: 7, revision: 3, status: 'DRAFT', reasons: ['error_type 이 등록되지 않았다'] }),
    ],
  })

/** 운영 서비스의 `ProfileRejections.of` 가 내는 모양 그대로다. */
const refused: Finding = {
  kind: 'ACTIVATION_REFUSED',
  observed: 'HTTP 409, status=VALIDATED, suites={"CONTRACT":"PASS"}',
  expected: '201 또는 200',
  checkedAt: 't2',
  owner: 'ENGINEER',
  inScreen: true,
  action: '시험 요청 또는 시험 결과 확인',
  target: null,
}

async function section() {
  render(<App />)
  await screen.findByText('선언된 기체가 없습니다')
  return screen.getByRole('region', { name: '프로파일' })
}

const posted = (fake: ReturnType<typeof installFakeOps>) => fake.calls.find((call) => call.method === 'POST')!

describe('프로파일', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('카탈로그 한 줄과 리비전마다 상태·스위트 결과와 실행 주체·시험 요청 상태가 보인다', async () => {
    installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    expect(await within(profiles).findByText('스킬 2종, 계약 0.9.0')).toBeInTheDocument()
    const table = within(profiles).getByRole('table', { name: '리비전 목록' })
    expect(within(table).getByRole('row', { name: /humanoid-a 2 TESTED/ })).toHaveTextContent(
      'PASS (site-runner)PASS (site-runner)PASS (site-runner)끝남',
    )
    expect(within(table).getByRole('row', { name: /quadruped-b/ })).toHaveTextContent('실행 중')
  })

  it('FAIL 이면 상세를, DRAFT 이면 검증 실패 사유를 펼쳐 본다', async () => {
    installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    await userEvent.click(await within(profiles).findByText('상세'))
    expect(within(profiles).getByText('CANCEL_UNSUPPORTED: 기대 거절, 관측 수락')).toBeVisible()
    await userEvent.click(within(profiles).getByText('저장됨: 검증 실패'))
    expect(within(profiles).getByText('error_type 이 등록되지 않았다')).toBeVisible()
  })

  it('목록을 읽은 적이 없으면 모름이다', async () => {
    installFakeOps(robots, adapterView(), profileView({ registry: 'REGISTRY_SILENT', catalog: null, revisions: null, asOf: null }))
    const profiles = await section()
    expect(await within(profiles).findByText('모름: 프로파일 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
  })

  it('시험 요청은 엔지니어 모드로 그 리비전의 경로에 보내고 결과가 기종·번호와 함께 보인다', async () => {
    const fake = installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    const row = within(within(profiles).getByRole('table', { name: '리비전 목록' })).getByRole('row', { name: /humanoid-a 2 TESTED/ })
    await userEvent.click(within(row).getByRole('button', { name: '시험 요청' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    expect(posted(fake).url).toBe('/api/profile-revisions/5/test-requests')
    expect(posted(fake).headers['X-Ops-Mode']).toBe('engineer')
    expect(await screen.findByText('picasso-ref/humanoid-a#2 시험 요청: 반영됨')).toBeInTheDocument()
  })

  it('활성화 거부는 상태와 스위트 결과를 관측값으로 보인다', async () => {
    const fake = installFakeOps(robots, adapterView(), tested())
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: refused }) }
    const profiles = await section()
    const row = within(within(profiles).getByRole('table', { name: '리비전 목록' })).getByRole('row', { name: /quadruped-b/ })
    await userEvent.click(within(row).getByRole('button', { name: '활성화' }))
    const notice = (await screen.findByText('picasso-ref/quadruped-b#1 활성화: 거절됨')).closest('[role="status"]') as HTMLElement
    expect(within(notice).getByText('활성화 조건 미달')).toBeInTheDocument()
    expect(within(notice).getByText(/status=VALIDATED/)).toBeInTheDocument()
  })

  it('제출은 고른 파일의 글자를 그대로 보낸다', async () => {
    const fake = installFakeOps(robots)
    const form = within(await section()).getByRole('form', { name: '리비전 제출' })
    const text = '{\n  "vendor" : "picasso-ref",  "model": "humanoid-a", "revision": 2\n}\n'
    await userEvent.upload(within(form).getByLabelText('프로파일 문서'), new File([text], 'humanoid-a.json', { type: 'application/json' }))
    await userEvent.click(within(form).getByRole('button', { name: '제출' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const init = vi.mocked(fetch).mock.calls.find(([, options]) => options?.method === 'POST')![1]!
    expect(init.body).toBe(text)
    expect(posted(fake).url).toBe('/api/profile-revisions')
    expect(posted(fake).headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('picasso-ref/humanoid-a#2 제출: 반영됨')).toBeInTheDocument()
  })

  it('기종·번호를 읽지 못하는 문서는 알림에 파일 이름을 적고 판정은 registry 에 맡긴다', async () => {
    const fake = installFakeOps(robots)
    const form = within(await section()).getByRole('form', { name: '리비전 제출' })
    await userEvent.upload(within(form).getByLabelText('프로파일 문서'), new File(['{"vendor":"x"}'], 'broken.json', { type: 'application/json' }))
    await userEvent.click(within(form).getByRole('button', { name: '제출' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    expect(await screen.findByText('broken.json 제출: 반영됨')).toBeInTheDocument()
  })

  it('프로파일 목록만 못 읽어도 넷 다 직전 값이다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }, adapterView(), tested())
    await section()
    fake.failing.add('/api/profiles')
    // 모드를 바꾸면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(await screen.findByRole('alert')).toHaveTextContent('모름: 운영 서비스에 닿지 않습니다')
    expect(within(screen.getByRole('region', { name: '프로파일' })).getByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
  })

  it('운영자 모드에서는 제출 폼과 조작 버튼이 없고 어느 모드에서 하는지 적는다', async () => {
    installFakeOps(robots, adapterView(), tested())
    const profiles = await section()
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(within(profiles).getByText('프로파일 관리는 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(within(profiles).queryByRole('form')).not.toBeInTheDocument()
    expect(within(profiles).queryByRole('button', { name: '시험 요청' })).not.toBeInTheDocument()
  })
})
