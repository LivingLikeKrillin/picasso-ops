import {render,screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {afterEach,expect,it,vi} from 'vitest'
import {RequestReview} from './RequestReview'
import {CollectorStatus} from './CollectorStatus'
const session={mode:'operator' as const,user:'kim'}
const receipt={receiptId:'r1',sourceSystemId:'mes',externalOrderId:'ORDER-1',jobOrderId:'JO-1',state:'IN_DOUBT',detail:null,payload:{},normalizedOrder:{},submission:null,receivedAt:'t1',updatedAt:'t2'}
afterEach(()=>{vi.unstubAllGlobals();sessionStorage.clear()})
it('확인 기록 응답 유실 후 같은 ID와 관측 버전으로만 재확인한다',async()=>{
 const calls:unknown[]=[];let fail=true
 vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{const body=JSON.parse(init.body as string);calls.push(body);if(fail)throw new Error('connection lost');return new Response(JSON.stringify({requestId:body.requestId,duplicate:true}))}))
 const first=render(<RequestReview receipt={receipt} session={session} onChanged={()=>undefined}/>)
 await userEvent.selectOptions(screen.getByLabelText('현장 관측'),'IN_PROGRESS')
 await userEvent.type(screen.getByLabelText('확인 근거·담당자·인계 내용'),'담당자 확인')
 await userEvent.click(screen.getByRole('button',{name:'현장 확인 기록 저장'}))
 expect(await screen.findByRole('alert')).toHaveTextContent('같은 확인 ID')
 first.unmount();fail=false
 render(<RequestReview receipt={{...receipt,updatedAt:'t3'}} session={session} onChanged={()=>undefined}/>)
 await userEvent.click(screen.getByRole('button',{name:'동일 확인 기록 조회'}))
 expect(await screen.findByRole('status')).toHaveTextContent('기존 현장 확인 기록 확인됨')
 expect(calls[1]).toEqual(calls[0]);expect(calls[0]).toMatchObject({observation:'IN_PROGRESS',expectedUpdatedAt:'t2'})
 expect(screen.getByText(/실행 결과·작업 요청 상태를 변경하거나 재제출을 허가하지 않습니다/)).toBeVisible()
})
it('엔지니어는 확인 기록을 전송할 수 없다',()=>{
 render(<RequestReview receipt={receipt} session={{...session,mode:'engineer'}} onChanged={()=>undefined}/>)
 expect(screen.queryByRole('button',{name:'현장 확인 기록 저장'})).not.toBeInTheDocument()
})
it('수집 커서와 확인한 마지막 번호 사이의 미수집량을 표시한다',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({state:'OK',lastSuccessAt:'t1',observedRequests:2,cursor:{mode:'DURABLE_OUTBOX',sequence:10,highWatermark:17,producerId:'p1'}}))))
 render(<CollectorStatus session={session}/>)
 expect(await screen.findByText(/수집 커서 10/)).toHaveTextContent('관측 시점 미수집 7건')
 expect(screen.getByText(/단절 중 결과도 연결 복구 후 재수집/)).toBeVisible()
})
