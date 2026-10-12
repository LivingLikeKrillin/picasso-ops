import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import type { MissionInterfaceView } from '../api'
import { eligibilityView, installFakeOps } from '../testing/fakeOps'
import { MissionInterfaces } from './MissionInterfaces'
const engineer = { mode: 'engineer' as const, user: 'lee' }
const operator = { mode: 'operator' as const, user: 'kim' }
const view: MissionInterfaceView = { interfaceId: 'prepare-rack', version: 1, name: '서열 랙 준비 요청', workMasterId: 'PrepareSequencedRack', mapping: { slots: '/rackSlots', material: '/materialCode', presentation: '/source' }, sample: { rackSlots: ['S1'], materialCode: 'M', source: 'P' }, status: 'DRAFT', createdBy: 'lee', createdAt: 't1', missionVersionPolicy: 'ACTIVE_AT_SUBMISSION' }
function fixture() { const fake=installFakeOps({ registry: 'OK', checkedAt: 't1', robots: [], robotsAsOf: 't1' }); fake.eligibility=eligibilityView({ robots: [{robotId:'humanoid-01',commissioning:'COMPLETE',connection:'FRESH',settingsVersion:1,eligible:true,reasons:[],host:{robotId:'humanoid-01',skillFit:'FIT',missingSkills:[],requiredSkills:['inspect'],availableSkills:['inspect','navigate_to'],runningExecutionId:null,passed:true,reasons:[]}}] }); return fake }
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear() })
it('편집한 매핑은 서버에서 검증하고 입력 변경 시 미리보기를 무효화한다', async () => {
  const fake = fixture(); fake.answers.set('/api/mission-interfaces/preview', { status: 200, body: { jobOrderId: 'JO-PREVIEW' } })
  render(<MissionInterfaces session={engineer} onChanged={() => undefined} />)
  await userEvent.click(screen.getByRole('button', { name: '호출 계약 만들기' }))
  const field = screen.getByLabelText('slots 원본 경로')
  await userEvent.clear(field); await userEvent.type(field, '/order/slots')
  await userEvent.click(screen.getByRole('button', { name: '매핑 검증·미리보기' }))
  expect(await screen.findByLabelText('계약 매핑 미리보기')).toHaveTextContent('JO-PREVIEW')
  expect(fake.calls.find(c => c.url === '/api/mission-interfaces/preview')?.body).toMatchObject({ mapping: { slots: '/order/slots' } })
  await userEvent.type(field, '2')
  expect(screen.getByRole('status')).toHaveTextContent('다시 검증')
  expect(fake.calls.some(c => c.url.endsWith('/invoke'))).toBe(false)
})
it('저장한 버전은 선택하고 사유를 포함해 실제 공개 상태 API에 보낸다', async () => {
  const fake = fixture()
  fake.answers.set('/api/mission-interfaces', { status: 200, body: view })
  fake.answers.set('/api/mission-interfaces/prepare-rack/1/state', { status: 200, body: { ...view, status: 'ENABLED' } })
  render(<MissionInterfaces session={engineer} onChanged={() => undefined} />)
  await userEvent.click(screen.getByRole('button', { name: '호출 계약 만들기' }))
  await userEvent.click(screen.getByRole('button', { name: '계약 초안 저장' }))
  const selected = await screen.findByRole('region', { name: '선택한 호출 계약' })
  expect(screen.getByRole('button', { name: '계약 초안 저장' })).toBeDisabled()
  await userEvent.type(within(selected).getByLabelText('공개 변경 사유'), '샘플 검증 완료')
  await userEvent.click(within(selected).getByRole('button', { name: '호출 계약 공개' }))
  expect(await screen.findByRole('button', { name: '공개 중지' })).toBeDisabled()
  expect(fake.calls.find(c => c.url.endsWith('/state'))?.body).toEqual({ expectedStatus: 'DRAFT', status: 'ENABLED', reason: '샘플 검증 완료' })
  expect(within(selected).getByLabelText('공개 변경 사유')).toHaveValue('')
})
it('상태 충돌 시 사유와 원래 상태를 보존한다', async () => {
  const fake = fixture(); fake.interfaces = [view]
  fake.answers.set('/api/mission-interfaces/prepare-rack/1/state', { status: 409, body: { detail: '상태가 변경되었습니다' } })
  render(<MissionInterfaces session={engineer} onChanged={() => undefined} />)
  await userEvent.click(await screen.findByRole('button', { name: 'prepare-rack v1 보기' }))
  await userEvent.type(screen.getByLabelText('공개 변경 사유'), '검토 완료')
  await userEvent.click(screen.getByRole('button', { name: '호출 계약 공개' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('상태가 변경되었습니다')
  expect(screen.getByLabelText('공개 변경 사유')).toHaveValue('검토 완료')
  expect(screen.getByRole('button', { name: '호출 계약 공개' })).toBeEnabled()
})
it('응답 소실 뒤 화면을 다시 열어도 같은 계약 요청 ID로 접수를 확인한다', async () => {
  const fake = fixture(); fake.interfaces = [{ ...view, status: 'ENABLED' }]
  const endpoint='/api/mission-interfaces/prepare-rack/1/invoke'
  fake.answers.set(endpoint,{status:500,body:{detail:'timeout'}})
  const first=render(<MissionInterfaces session={operator} onChanged={() => undefined} />)
  await userEvent.click(await screen.findByRole('button',{name:'prepare-rack v1 보기'}))
  await userEvent.click(screen.getByText('공개된 계약으로 작업 요청'))
  await userEvent.click(screen.getByRole('button',{name:'이 입력으로 배정 가능 확인'}))
  await screen.findByText(/현재 배정 가능한 기체가 있습니다/)
  await userEvent.click(screen.getByLabelText('이 입력으로 실제 작업을 지시합니다'))
  await userEvent.click(screen.getByRole('button',{name:'공개 계약으로 작업 지시'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('기존 요청 ID')
  const original=fake.calls.find(c=>c.url===endpoint)!.body as {requestId:string;input:unknown}
  expect(screen.getByRole('button',{name:'새 계약 요청 작성'})).toBeDisabled()
  first.unmount()
  fake.answers.set(endpoint,{status:200,body:{receipt:{receiptId:'receipt-1',sourceSystemId:'ops:interface',externalOrderId:original.requestId,jobOrderId:'JO-1',state:'ACCEPTED',submission:null},duplicate:true}})
  render(<MissionInterfaces session={operator} onChanged={() => undefined} />)
  await userEvent.click(await screen.findByRole('button',{name:'prepare-rack v1 보기'}))
  await userEvent.click(screen.getByText('공개된 계약으로 작업 요청'))
  expect(fake.calls.filter(c=>c.url===endpoint)).toHaveLength(1)
  await userEvent.click(screen.getByRole('button',{name:'동일 계약 요청 접수 확인'}))
  expect(await screen.findByRole('status',{name:'계약 호출 결과'})).toHaveTextContent('추가 실행 없음')
  expect(fake.calls.filter(c=>c.url===endpoint).at(-1)?.body).toEqual(original)
  expect(screen.getByRole('link',{name:'접수·실행 결과 보기'})).toHaveAttribute('href','#requests?receipt=receipt-1')
  expect(screen.getByRole('button',{name:'새 계약 요청 작성'})).toBeEnabled()
})
it('공개되지 않은 계약은 호출할 수 없다', async () => {
  const fake = fixture(); fake.interfaces = [view]
  render(<MissionInterfaces session={operator} onChanged={() => undefined} />)
  await userEvent.click(await screen.findByRole('button', { name: 'prepare-rack v1 보기' }))
  await userEvent.click(screen.getByText('공개된 계약으로 작업 요청'))
  await userEvent.click(screen.getByLabelText('이 입력으로 실제 작업을 지시합니다'))
  expect(screen.getByRole('button', { name: '공개 계약으로 작업 지시' })).toBeDisabled()
})
it('버전 중복 저장 거절 시 작성한 내용을 유지한다', async () => {
  const fake = fixture(); fake.answers.set('/api/mission-interfaces', { status: 409, body: { detail: '이미 존재하는 계약 버전입니다' } })
  render(<MissionInterfaces session={engineer} onChanged={() => undefined} />)
  await userEvent.click(screen.getByRole('button', { name: '호출 계약 만들기' }))
  await userEvent.click(screen.getByRole('button', { name: '계약 초안 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('이미 존재하는 계약 버전')
  expect(screen.getByLabelText('호출 식별자')).toHaveValue('prepare-rack')
  expect(screen.getByRole('button', { name: '계약 초안 저장' })).toBeEnabled()
})

it('저장 버전 복제는 다음 버전 번호와 매핑을 가져오고 작성 폼 접기 후에도 보존한다', async () => {
  const fake = fixture(); fake.interfaces = [view, { ...view, version: 3 }]
  render(<MissionInterfaces session={engineer} onChanged={() => undefined} />)
  await userEvent.click(await screen.findByRole('button', { name: 'prepare-rack v1 보기' }))
  await userEvent.click(screen.getByRole('button', { name: '새 버전으로 복제' }))
  expect(screen.getByLabelText('계약 버전')).toHaveValue(4)
  expect(screen.getByLabelText('slots 원본 경로')).toHaveValue('/rackSlots')
  await userEvent.clear(screen.getByLabelText('계약 이름')); await userEvent.type(screen.getByLabelText('계약 이름'), '변경 요청')
  await userEvent.click(screen.getByRole('button', { name: '작성 폼 접기' }))
  await userEvent.click(screen.getByRole('button', { name: '호출 계약 만들기' }))
  expect(screen.getByLabelText('계약 이름')).toHaveValue('변경 요청')
})

it('배정 판정은 입력 변경과 조회 실패 시 무효화하며 새 요청을 막는다', async () => {
  const fake=fixture();fake.interfaces=[{...view,status:'ENABLED'}]
  render(<MissionInterfaces session={operator} onChanged={()=>undefined}/>)
  await userEvent.click(await screen.findByRole('button',{name:'prepare-rack v1 보기'}))
  await userEvent.click(screen.getByText('공개된 계약으로 작업 요청'))
  await userEvent.click(screen.getByLabelText('이 입력으로 실제 작업을 지시합니다'))
  expect(screen.getByRole('button',{name:'공개 계약으로 작업 지시'})).toBeDisabled()
  await userEvent.click(screen.getByRole('button',{name:'이 입력으로 배정 가능 확인'}))
  expect(await screen.findByRole('table',{name:'기체별 배정 가능'})).toHaveTextContent('inspect, navigate_to')
  await userEvent.click(screen.getByLabelText('이 입력으로 실제 작업을 지시합니다'))
  expect(screen.getByRole('button',{name:'공개 계약으로 작업 지시'})).toBeEnabled()
  await userEvent.type(screen.getByLabelText('계약 호출 입력'),' ')
  expect(screen.queryByRole('table',{name:'기체별 배정 가능'})).not.toBeInTheDocument()
  expect(screen.getByRole('button',{name:'공개 계약으로 작업 지시'})).toBeDisabled()
  const original=JSON.stringify(view.sample,null,2)
  await userEvent.clear(screen.getByLabelText('계약 호출 입력'))
  fireEvent.change(screen.getByLabelText('계약 호출 입력'),{target:{value:original}})
  expect(screen.getByRole('button',{name:'공개 계약으로 작업 지시'})).toBeDisabled()
  fake.answers.set('/api/mission-interfaces/prepare-rack/1/eligibility',{status:503,body:{detail:'offline'}})
  await userEvent.click(screen.getByRole('button',{name:'이 입력으로 배정 가능 확인'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('offline')
  expect(screen.getByRole('button',{name:'공개 계약으로 작업 지시'})).toBeDisabled()
})
it('호스트 판정이 모름이면 기체 없음이나 배정 가능으로 접지 않는다',async()=>{
  const fake=fixture();fake.interfaces=[{...view,status:'ENABLED'}];fake.eligibility=eligibilityView({host:'HOST_SILENT',robots:null})
  render(<MissionInterfaces session={operator} onChanged={()=>undefined}/>)
  await userEvent.click(await screen.findByRole('button',{name:'prepare-rack v1 보기'}));await userEvent.click(screen.getByText('공개된 계약으로 작업 요청'))
  await userEvent.click(screen.getByRole('button',{name:'이 입력으로 배정 가능 확인'}))
  expect(await screen.findByText('모름: 기체 목록을 아직 읽지 못했습니다')).toBeVisible()
  expect(screen.getByRole('button',{name:'공개 계약으로 작업 지시'})).toBeDisabled()
})

it('다시 조회한 배포 버전이 바뀌면 입력이 같아도 이전 배정 판정을 사용하지 않는다',async()=>{
  const fake=fixture();fake.interfaces=[{...view,status:'ENABLED'}]
  render(<MissionInterfaces session={operator} onChanged={()=>undefined}/>)
  await userEvent.click(await screen.findByRole('button',{name:'prepare-rack v1 보기'}));await userEvent.click(screen.getByText('공개된 계약으로 작업 요청'))
  await userEvent.click(screen.getByRole('button',{name:'이 입력으로 배정 가능 확인'}))
  await screen.findByText(/현재 배정 가능한 기체가 있습니다/)
  fake.interfaces=[{...view,status:'ENABLED',deployment:{interfaceId:view.interfaceId,interfaceVersion:1,mappingVersion:1,deploymentVersion:2,missionVersion:'CODE',createdBy:'lee',reason:'변경',createdAt:'t2'}}]
  await userEvent.click(screen.getByRole('button',{name:'호출 계약 다시 조회'}))
  expect(await screen.findByText(/입력 또는 배포 상태가 변경되었습니다/)).toBeVisible()
  expect(screen.queryByRole('table',{name:'기체별 배정 가능'})).not.toBeInTheDocument()
  expect(screen.getByRole('button',{name:'공개 계약으로 작업 지시'})).toBeDisabled()
})

it('필수 자원 미션은 기체가 사용 중이어도 검증된 입력을 대기열에 접수할 수 있다',async()=>{
 const fake=fixture();fake.interfaces=[{...view,status:'ENABLED',requiredStationId:'inspection-1'}]
 fake.eligibility=eligibilityView({robots:[]})
 render(<MissionInterfaces session={operator} onChanged={()=>undefined}/>);
 await userEvent.click(await screen.findByRole('button',{name:'prepare-rack v1 보기'}));await userEvent.click(screen.getByText('공개된 계약으로 작업 요청'))
 const submit=screen.getByRole('button',{name:'공개 계약으로 작업 지시'});expect(submit).toBeDisabled()
 await userEvent.click(screen.getByRole('button',{name:'이 입력으로 배정 가능 확인'}));expect(await screen.findByText(/검증된 입력으로 자원 대기열에 접수/)).toBeVisible()
 await userEvent.click(screen.getByLabelText('이 입력으로 실제 작업을 지시합니다'));expect(submit).toBeEnabled()
 fireEvent.change(screen.getByLabelText('계약 호출 입력'),{target:{value:'{}'}});expect(submit).toBeDisabled()
})
