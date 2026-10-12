import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { adapterView, installFakeOps, profileView, robotView } from '../testing/fakeOps'

const ready = () => ({
  ...robotView('new-robot', 'CONFIRMED'),
  commissioning: { state: 'COMPLETE' as const, ledgerConfirmed: true, bound: true, siteNamesReady: true },
})
function serve(stale = false, rows = [ready()]) {
  return installFakeOps({ registry: 'OK', checkedAt: 't2', robots: rows, robotsAsOf: stale ? 't1' : 't2' }, adapterView(), profileView())
}
async function start() {
  await userEvent.click(screen.getByRole('button', { name: '로봇 추가' }))
  return screen.getByRole('region', { name: '로봇 도입 준비' })
}
afterEach(() => vi.unstubAllGlobals())

describe('로봇 도입 여정', () => {
  it('새 통신 유형의 구현·배포 경계를 안내하고 어댑터 등록 후 같은 여정으로 돌아온다', async () => {
    serve()
    render(<App />)
    const guide = await start()
    await userEvent.click(within(guide).getByRole('radio', { name: /새 제조사/ }))
    expect(within(guide).getByText(/등록 폼이 어댑터 코드를 생성하거나 배포하지는 않습니다/)).toBeInTheDocument()
    await userEvent.click(within(guide).getByRole('button', { name: '어댑터 확인·등록' }))
    expect(screen.getByRole('region', { name: '어댑터' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '로봇 도입 준비' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '도입 준비' }))
    expect(screen.getByRole('radio', { name: /새 제조사/ })).toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: '프로파일 시험·활성화' }))
    expect(screen.getByRole('region', { name: '프로파일' })).toBeInTheDocument()
  })

  it('기체 선택과 작성 중인 등록 입력은 관리 탭과 다른 메뉴를 왕복해도 유지한다', async () => {
    serve()
    render(<App />)
    await start()
    await userEvent.selectOptions(screen.getByLabelText('준비를 이어갈 기체'), 'new-robot')
    const form = screen.getByRole('form', { name: '기체 선언' })
    await userEvent.type(within(form).getByLabelText('robot_id'), 'another-robot')
    await userEvent.click(screen.getByRole('button', { name: '어댑터 관리' }))
    await userEvent.click(within(screen.getByRole('navigation', { name: '영역' })).getByRole('button', { name: '이력' }))
    await userEvent.click(within(screen.getByRole('navigation', { name: '영역' })).getByRole('button', { name: '로봇' }))
    await userEvent.click(screen.getByRole('button', { name: '도입 준비' }))
    expect(screen.getByLabelText('준비를 이어갈 기체')).toHaveValue('new-robot')
    expect(within(screen.getByRole('form', { name: '기체 선언' })).getByLabelText('robot_id')).toHaveValue('another-robot')
  })

  it('시운전 완료를 임무 배정 가능으로 간주하지 않고 운영의 판정으로 연결한다', async () => {
    serve()
    render(<App />)
    const guide = await start()
    await userEvent.selectOptions(within(guide).getByLabelText('준비를 이어갈 기체'), 'new-robot')
    expect(within(guide).getByText('시운전 완료가 확인되었습니다. 운영에서 실제 임무의 배정 가능 여부를 확인하세요.')).toBeInTheDocument()
    await userEvent.click(within(guide).getByRole('button', { name: '운영에서 배정 조건 확인' }))
    expect(within(screen.getByRole('navigation', { name: '영역' })).getByRole('button', { name: '운영' })).toHaveAttribute('aria-current', 'page')
  })

  it('직전 관측의 완료 값을 현재 준비 완료로 표시하지 않는다', async () => {
    serve(true)
    render(<App />)
    const guide = await start()
    await userEvent.selectOptions(within(guide).getByLabelText('준비를 이어갈 기체'), 'new-robot')
    expect(within(guide).getAllByText('직전 값 · 재확인 필요')).toHaveLength(3)
    expect(within(guide).queryByText(/시운전 완료가 확인되었습니다/)).not.toBeInTheDocument()
  })

  it('시운전 정보가 없는 구형 응답을 준비 필요나 완료로 추정하지 않는다', async () => {
    installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [robotView('legacy', 'CONFIRMED')], robotsAsOf: 't1' })
    render(<App />)
    const guide = await start()
    await userEvent.selectOptions(within(guide).getByLabelText('준비를 이어갈 기체'), 'legacy')
    expect(within(guide).getAllByText('모름')).toHaveLength(3)
    expect(within(guide).getByText('시운전 판정을 아직 받지 못했습니다.')).toBeInTheDocument()
  })
})
