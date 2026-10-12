import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { OperationsArea } from './OperationsArea'
import { installFakeOps } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
const session={mode:'operator' as const,user:'kim'}
const empty={registry:'OK' as const,checkedAt:'t1',robots:[],robotsAsOf:'t1'}
const accepted={requestId:'backend-request',jobOrderId:'JO-manual',result:'SUCCEEDED',confirmation:null,outcome:{result:'ACCEPTED',executionId:'exec-1',robotId:'humanoid-01',rejectionReason:null,refusals:[],excluded:[]}}
const posts=(fake:FakeOps)=>fake.calls.filter(call=>call.method==='POST' && call.url==='/api/work-requests/manual')
const open=()=>render(<OperationsArea session={session} onChanged={()=>undefined}/>)
async function fill(){await userEvent.type(screen.getByLabelText('대상 1 id'),'T1');await userEvent.type(screen.getByLabelText('대상 1 장소'),'bay-7')}
afterEach(()=>{sessionStorage.clear();vi.restoreAllMocks();vi.unstubAllGlobals()})
it('응답 소실 뒤 새로고침해도 보관된 ID와 입력으로 확인하고 새 작업은 명시적으로 만든다',async()=>{
 const fake=installFakeOps(empty);fake.answer={status:500,body:{}};const first=open();await fill()
 await userEvent.click(screen.getByRole('button',{name:'작업 지시 내기'}))
 expect(await screen.findByRole('status',{name:'제출 결과'})).toHaveTextContent('결과 모름')
 const original=posts(fake)[0].body
 expect(screen.getByRole('button',{name:'새 작업 작성'})).toBeDisabled()
 first.unmount();open();fake.answer={status:200,body:accepted}
 await userEvent.click(await screen.findByRole('button',{name:'동일 작업 요청 확인'}))
 await waitFor(()=>expect(posts(fake)).toHaveLength(2));expect(posts(fake)[1].body).toEqual(original)
 await waitFor(()=>expect(screen.getByRole('button',{name:'새 작업 작성'})).toBeEnabled())
 await userEvent.click(screen.getByRole('button',{name:'작업 요청·실행 결과 보기'}))
 const detail=await screen.findByRole('article',{name:'작업 요청 상세'})
 expect(await within(detail).findByText('operator/kim')).toBeVisible()
 await userEvent.click(screen.getByRole('button',{name:'새 작업 작성'}));await fill()
 await userEvent.click(screen.getByRole('button',{name:'작업 지시 내기'}))
 await waitFor(()=>expect(posts(fake)).toHaveLength(3))
 expect((posts(fake)[2].body as {requestId:string}).requestId).not.toBe((original as {requestId:string}).requestId)
})
it('불명 요청의 입력을 고쳐도 새 ID로 실행하지 않으며 확인에는 원본을 사용한다',async()=>{
 const fake=installFakeOps(empty);fake.answer={status:500,body:{}};open();await fill()
 await userEvent.click(screen.getByRole('button',{name:'작업 지시 내기'}));await screen.findByRole('status',{name:'제출 결과'})
 await userEvent.type(screen.getByLabelText('대상 1 장소'),'-changed')
 await userEvent.click(screen.getByRole('button',{name:'작업 지시 내기'}))
 expect(await screen.findByRole('alert')).toHaveTextContent('보관된 요청과 입력이 다릅니다')
 expect(posts(fake)).toHaveLength(1)
 await userEvent.click(screen.getByRole('button',{name:'동일 작업 요청 확인'}))
 await waitFor(()=>expect(posts(fake)).toHaveLength(2));expect(posts(fake)[1].body).toEqual(posts(fake)[0].body)
})
it('요청 ID 저장 실패 시 서버에 제출하지 않는다',async()=>{
 const fake=installFakeOps(empty);open();await fill()
 vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('quota')})
 await userEvent.click(screen.getByRole('button',{name:'작업 지시 내기'}))
 expect(await screen.findByRole('alert')).toHaveTextContent('작업을 제출하지 않았습니다')
 expect(posts(fake)).toHaveLength(0)
})
it('잘못된 성공 응답은 접수 성공으로 표시하지 않고 기존 요청을 유지한다',async()=>{
 const fake=installFakeOps(empty);fake.answer={status:202,body:{receipt:null}};open();await fill()
 await userEvent.click(screen.getByRole('button',{name:'작업 지시 내기'}))
 expect(await screen.findByRole('status',{name:'제출 결과'})).toHaveTextContent('작업 요청 응답 형식')
 expect(screen.getByRole('button',{name:'새 작업 작성'})).toBeDisabled()
 expect(sessionStorage.length).toBe(1)
})
