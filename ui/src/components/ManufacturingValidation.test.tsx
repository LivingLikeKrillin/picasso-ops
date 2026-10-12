import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { ManufacturingValidation } from './ManufacturingValidation'
const session = { mode: 'operator' as const, user: 'kim' }
const detail = (id: string, robotId: string, deploymentVersion = 1) => ({
  receipt: { receiptId: id, externalOrderId: `ORDER-${id}`, state: 'ACCEPTED', payload: { interfaceId: 'station-inspection', interfaceVersion: 1 }, normalizedOrder: { workMasterId: 'StationInspection', missionVersion: '1', deployment: { deploymentVersion } } },
  hostRead: 'OK', latestResult: { version: 2, recordedAt: '2026-10-11T00:00:00Z', snapshot: { robotId, jobResponse: { physicalState: 'PHYSICALLY_DONE', requiredEvidence: 'E0', reachedEvidence: 'E0' } } },
})
function serve(a: unknown = detail('a', 'humanoid-01'), b: unknown = detail('b', 'quadruped-01')) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => { void init; return new Response(JSON.stringify(url.endsWith('/a') ? a : b)) })
  vi.stubGlobal('fetch', fetch)
  return fetch
}
async function compare() {
  await userEvent.type(screen.getByLabelText('접수 A ID'), 'a')
  await userEvent.type(screen.getByLabelText('접수 B ID'), 'b')
  await userEvent.click(screen.getByRole('button', { name: '두 접수 비교하기' }))
}
afterEach(() => vi.unstubAllGlobals())
it('동일 계약의 다른 기체와 저장된 근거를 표시하며 쓰기 요청을 하지 않는다', async () => {
  const fetch = serve()
  render(<ManufacturingValidation session={session} />)
  await compare()
  expect(await screen.findByRole('status')).toHaveTextContent('동일 계약·동일 실행 배포입니다')
  expect(within(screen.getByRole('article', { name: '접수 A 근거' })).getByText('humanoid-01')).toBeVisible()
  expect(within(screen.getByRole('article', { name: '접수 B 근거' })).getByText('quadruped-01')).toBeVisible()
  expect(fetch.mock.calls.every(call => !(call[1] as RequestInit | undefined)?.method)).toBe(true)
  expect(screen.getByText(/기종 자체가 근거 등급을 결정하지 않습니다/)).toBeVisible()
})
it('배포 버전이 다르면 같은 업무 이름으로 같다고 판정하지 않는다', async () => {
  serve(detail('a', 'humanoid-01'), detail('b', 'quadruped-01', 2))
  render(<ManufacturingValidation session={session} />)
  await compare()
  expect(await screen.findByRole('status')).toHaveTextContent('계약 또는 실행 배포가 다릅니다')
})
it('계약 식별자가 없거나 저장 결과가 없으면 확인되지 않은 정보를 완료로 표시하지 않는다', async () => {
  serve({ ...detail('a', 'humanoid-01'), receipt: { ...detail('a', 'humanoid-01').receipt, payload: {} }, latestResult: null })
  render(<ManufacturingValidation session={session} />)
  await compare()
  expect(await screen.findByRole('status')).toHaveTextContent('정보를 모두 확인할 수 없습니다')
  expect(screen.getByText(/저장 결과가 아직 없습니다/)).toBeVisible()
})
it('조회 실패 뒤에는 이전 비교 결론을 남기지 않고 입력 변경도 판정을 지운다', async () => {
  const fetch = serve()
  render(<ManufacturingValidation session={session} />)
  await compare()
  await screen.findByRole('status')
  fetch.mockRejectedValue(new Error('offline'))
  await userEvent.click(screen.getByRole('button', { name: '두 접수 비교하기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('offline')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  fetch.mockImplementation(async (url: string) => new Response(JSON.stringify(url.endsWith('/a') ? detail('a', 'humanoid-01') : detail('b', 'quadruped-01'))))
  await userEvent.click(screen.getByRole('button', { name: '두 접수 비교하기' }))
  await screen.findByRole('status')
  await userEvent.type(screen.getByLabelText('접수 B ID'), 'c')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})
it('동일 접수 두 개는 비교하지 않으며 다른 ID 응답도 거부한다', async () => {
  serve(detail('wrong', 'humanoid-01'))
  render(<ManufacturingValidation session={session} />)
  await compare()
  expect(await screen.findByRole('alert')).toHaveTextContent('식별자가 일치하지 않습니다')
  await userEvent.clear(screen.getByLabelText('접수 B ID'))
  await userEvent.type(screen.getByLabelText('접수 B ID'), 'a')
  await userEvent.click(screen.getByRole('button', { name: '두 접수 비교하기' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('서로 다른 두 접수')
})
