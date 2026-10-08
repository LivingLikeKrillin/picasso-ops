import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { OperationRecord, RobotListView, RobotView } from './api'

const robot: RobotView = {
  robot: {
    robotId: 'humanoid-01',
    siteId: 'site-01',
    serialNumber: 'HA-0001',
    displayName: null,
    status: 'CLAIMED',
    lastReportedAt: null,
    retiredAt: null,
    retiredReason: null,
    reportingAfterRetirement: false,
  },
  connection: 'NO_REPORT',
  blockers: [],
}

function serve(view: RobotListView, records: OperationRecord[] = []) {
  const calls: { url: string; headers: Record<string, string> }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      // 브라우저처럼, ISO-8859-1 밖의 문자가 헤더에 있으면 보내기 전에 던진다.
      for (const value of Object.values(headers)) {
        if ([...value].some((ch) => ch.charCodeAt(0) > 0xff)) {
          throw new TypeError("Failed to execute 'fetch': String contains non ISO-8859-1 code point.")
        }
      }
      calls.push({ url, headers })
      const body = url === '/api/robots' ? view : records
      return new Response(JSON.stringify(body), { status: 200 })
    }),
  )
  return calls
}

describe('App', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('메뉴가 5영역이고 모두 열려 있다', () => {
    serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    const nav = screen.getByRole('navigation', { name: '영역' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual([
      '현장·자원',
      '로봇·연결',
      '임무·정책',
      '운영',
      '이력',
    ])
  })

  it('임무·정책 영역을 열 때만 임무 버전을 읽는다', async () => {
    const calls = serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(calls.filter((call) => call.url.startsWith('/api/missions'))).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '임무·정책' }))
    await waitFor(() =>
      expect(calls.map((call) => call.url)).toEqual(
        expect.arrayContaining(['/api/missions/PrepareSequencedRack', '/api/missions/templates/PrepareSequencedRack']),
      ),
    )
    expect(screen.getByRole('region', { name: '임무 PrepareSequencedRack' })).toBeInTheDocument()
  })

  it('목록을 읽은 적이 없으면 없음이 아니라 모름을 보인다', async () => {
    serve({ registry: 'REGISTRY_SILENT', checkedAt: 't1', robots: null, robotsAsOf: null })
    render(<App />)
    expect(await screen.findByText('모름: 기체 목록을 아직 읽지 못했습니다')).toBeInTheDocument()
    expect(screen.queryByText('선언된 기체가 없습니다')).not.toBeInTheDocument()
  })

  it('빈 목록은 없음으로 보인다', async () => {
    serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
  })

  it('registry 가 답하지 않으면 전체 상태가 모름이고 직전 목록을 지우지 않는다', async () => {
    serve({ registry: 'REGISTRY_SILENT', checkedAt: 't2', robots: [robot], robotsAsOf: 't1' })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('모름: registry 가 답하지 않습니다')
    expect(await screen.findByText('humanoid-01')).toBeInTheDocument()
    expect(screen.getByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
  })

  it('운영자 토큰 거부는 전체 상태 한 자리에만 보인다', async () => {
    serve({ registry: 'REGISTRY_UNAUTHORIZED', checkedAt: 't2', robots: [robot], robotsAsOf: 't1' })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('운영자 토큰 설정 확인')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })

  it('모드를 바꾸면 요청 헤더의 모드가 바뀐다', async () => {
    const calls = serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    await waitFor(() => expect(calls.length).toBeGreaterThan(0))
    expect(calls[0].headers['X-Ops-Mode']).toBe('engineer')
    await userEvent.click(screen.getByLabelText('운영자'))
    await waitFor(() => expect(calls.at(-1)?.headers['X-Ops-Mode']).toBe('operator'))
  })

  it('운영 서비스에 닿지 않으면 모름을 알리고 직전 목록을 직전 값으로 표시한다', async () => {
    let down = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (down) return new Response('', { status: 503 })
        const body =
          url === '/api/robots'
            ? { registry: 'OK', checkedAt: 't1', robots: [robot], robotsAsOf: 't1' }
            : []
        return new Response(JSON.stringify(body), { status: 200 })
      }),
    )
    render(<App />)
    expect(await screen.findByText('humanoid-01')).toBeInTheDocument()
    expect(screen.queryByText(/직전 값입니다/)).not.toBeInTheDocument()

    down = true
    // 모드를 바꾸면 다시 읽는다. 주기(5초)를 기다리지 않는다.
    await userEvent.click(screen.getByLabelText('운영자'))
    expect(await screen.findByRole('alert')).toHaveTextContent('모름: 운영 서비스에 닿지 않습니다')
    expect(screen.getByText('humanoid-01')).toBeInTheDocument()
    expect(screen.getByText('직전 값입니다 (t1 기준)')).toBeInTheDocument()
  })

  it('헤더에 못 싣는 사용자 이름은 요청에 쓰지 않고 입력란에서 알린다', async () => {
    const calls = serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    render(<App />)
    await waitFor(() => expect(calls.length).toBeGreaterThan(0))
    const input = screen.getByLabelText('사용자')
    await userEvent.clear(input)
    await userEvent.type(input, '김')
    expect(screen.getByText(/사용자 이름은 영문/)).toBeInTheDocument()
    // 모드를 바꾸면 다시 읽는다. 그 요청에는 직전의 유효한 이름이 실린다.
    await userEvent.click(screen.getByLabelText('운영자'))
    await waitFor(() => expect(calls.at(-1)?.headers['X-Ops-Mode']).toBe('operator'))
    expect(calls.at(-1)?.headers['X-Ops-User']).toBe('local')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('이력 영역이 조작 기록을 행위자와 함께 보인다', async () => {
    serve({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }, [
      {
        requestId: 'r-1',
        mode: 'OPERATOR',
        user: 'kim',
        target: 'robot humanoid-01',
        reason: '정비',
        result: 'SUCCEEDED',
        recordedAt: 't1',
      },
    ])
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '이력' }))
    expect(await screen.findByText('운영자/kim')).toBeInTheDocument()
    expect(screen.getByText('정비')).toBeInTheDocument()
  })
})
