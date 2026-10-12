import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { draftRow, installFakeOps } from '../testing/fakeOps'
import { MissionsArea } from './MissionsArea'
const session = { mode: 'engineer' as const, user: 'lee' }
function open(withDraft = false) {
  const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
  if (withDraft) fake.mission.drafts = [draftRow()]
  render(<MissionsArea session={session} onChanged={() => undefined} />)
  return fake
}
const nav = (name: string) => userEvent.click(within(screen.getByRole('navigation', { name: '임무 관리' })).getByRole('button', { name }))
afterEach(() => vi.unstubAllGlobals())
describe('미션 업무와 호출 연결', () => {
  it('기본 목록은 지원 업무 두 개와 편집 가능 범위를 보여준다', async () => {
    open()
    expect(await screen.findByRole('heading', { name: '자산 점검' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '서열 랙 준비' })).toBeVisible()
    expect(screen.queryByRole('textbox', { name: '임무 정의 JSON' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '템플릿으로 초안 작성' }))
    expect(screen.getByRole('textbox', { name: '임무 정의 JSON' })).toBeVisible()
  })
  it('메뉴 왕복 중 작성 중인 정의를 보존하고 버전 공개 의미를 구분한다', async () => {
    open(); await nav('실행 정의')
    const editor = screen.getByRole('textbox', { name: '임무 정의 JSON' })
    await userEvent.clear(editor); await userEvent.type(editor, 'draft in progress')
    await nav('호출 연결'); await nav('버전·배포')
    expect(screen.getByRole('region', { name: '공개와 배포 상태' })).toHaveTextContent('호출 연결에서 검증된 임무 버전을 배포하고 계약을 공개합니다')
    await nav('실행 정의'); expect(editor).toHaveValue('draft in progress')
  })
  it('버전 화면에서 초안을 열면 실행 정의 편집으로 이동한다', async () => {
    open(true)
    await nav('버전·배포')
    await userEvent.click(await screen.findByRole('button', { name: '초안 7 열기' }))
    expect(screen.getByRole('textbox', { name: '임무 정의 JSON' })).toBeVisible()
    expect(screen.getByRole('textbox', { name: '임무 정의 JSON' })).toHaveValue(draftRow().definition)
  })
  it('서버 미리보기만 호출하고 제출하지 않으며 변경된 입력의 결과는 폐기한다', async () => {
    const fake = open()
    fake.answers.set('/api/job-orders/preview', { status: 200, body: { jobOrderId: 'JO-PREVIEW', requiredEvidence: 'E2' } })
    await screen.findByRole('heading', { name: '서열 랙 준비' }); await nav('호출 연결')
    expect(screen.getByRole('table', { name: '입력 필드 매핑' })).toHaveTextContent('slots[]')
    await userEvent.click(screen.getByRole('button', { name: '입력 검증·변환 미리보기' }))
    expect(await screen.findByLabelText('변환된 작업 지시 JSON')).toHaveTextContent('JO-PREVIEW')
    expect(fake.calls.filter(c => c.method === 'POST').map(c => c.url)).toEqual(['/api/job-orders/preview'])
    await userEvent.type(screen.getByRole('textbox', { name: '샘플 요청 JSON' }), ' ')
    expect(screen.getByRole('status')).toHaveTextContent('다시 검증')
  })
  it('잘못된 JSON과 다른 업무 입력은 서버에 보내지 않는다', async () => {
    const fake = open(); await screen.findByRole('heading', { name: '서열 랙 준비' }); await nav('호출 연결')
    const input = screen.getByRole('textbox', { name: '샘플 요청 JSON' })
    await userEvent.clear(input); await userEvent.type(input, 'invalid')
    await userEvent.click(screen.getByRole('button', { name: '입력 검증·변환 미리보기' }))
    expect(screen.getByRole('alert')).toHaveTextContent('올바른 JSON')
    await userEvent.clear(input); await userEvent.paste('{"workMasterId":"InspectAsset"}')
    await userEvent.click(screen.getByRole('button', { name: '입력 검증·변환 미리보기' }))
    expect(screen.getByRole('alert')).toHaveTextContent('선택한 업무와 일치')
    expect(fake.calls.filter(c => c.method === 'POST')).toEqual([])
  })
  it('서버의 업무 입력 거절을 표시하고 성공 미리보기를 남기지 않는다', async () => {
    const fake = open(); fake.answers.set('/api/job-orders/preview', { status: 400, body: { error: 'UNIT_ID_CONFLICT', detail: '단위 id 가 겹친다: S1' } })
    await screen.findByRole('heading', { name: '서열 랙 준비' }); await nav('호출 연결')
    await userEvent.click(screen.getByRole('button', { name: '입력 검증·변환 미리보기' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('단위 id 가 겹친다')
    expect(screen.queryByLabelText('변환된 작업 지시 JSON')).not.toBeInTheDocument()
  })
  it('코드 정의 업무는 편집을 제안하지 않고 호출 계약으로 연결한다', async () => {
    open(); const heading = await screen.findByRole('heading', { name: '자산 점검' })
    await userEvent.click(within(heading.closest('article')!).getByRole('button', { name: '실행 정의 보기' }))
    expect(screen.getByText('InspectAsset은 현재 코드 정의입니다. 이 화면에서는 실행 정의를 편집할 수 없습니다.')).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: '입력 계약 확인' }))
    expect(screen.getByRole('table', { name: '입력 필드 매핑' })).toHaveTextContent('targets[].location')
  })
  it('계약 조회 실패에서 다시 조회할 수 있다', async () => {
    const fake = installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' })
    fake.failing.add('/api/job-contracts')
    render(<MissionsArea session={session} onChanged={() => undefined} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('계약 목록을 읽지 못했습니다')
    fake.failing.delete('/api/job-contracts'); await userEvent.click(screen.getByRole('button', { name: '계약 다시 조회' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: '서열 랙 준비' })).toBeVisible())
  })
})
