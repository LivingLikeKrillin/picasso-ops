import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkRequests } from './WorkRequests'
const session={mode:'operator' as const,user:'kim'}
const receipt={receiptId:'r1',sourceSystemId:'mock-mes',externalOrderId:'MES-204',jobOrderId:'JO-204',state:'UNASSIGNED',detail:'기체가 바쁩니다',payload:{input:{}},normalizedOrder:null,submission:null,receivedAt:'t1',updatedAt:'t1'}
afterEach(()=>vi.unstubAllGlobals())
it('미배정 접수도 상세와 원본 입력을 조회하며 자동 재제출 버튼을 제공하지 않는다',async()=>{
 const fetcher=vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[receipt],offset:0,limit:20}:{receipt,hostRead:'UNAVAILABLE',execution:null,instanceId:null,journalBlocked:false,observedAt:'t2'}),{status:200}))
 vi.stubGlobal('fetch',fetcher)
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 접수 보기'}))
 const detail=await screen.findByRole('article',{name:'작업 요청 상세'})
 expect(await within(detail).findByText('기체가 바쁩니다')).toBeVisible()
 expect(within(detail).getByText('모름 · 호스트 조회 실패')).toBeVisible()
 expect(within(detail).getByText(/새 제출은 차단됩니다/)).toBeVisible()
 expect(fetcher.mock.calls.every(([url])=>url.startsWith('/api/work-requests'))).toBe(true)
})
it('서버 검색·상태 필터와 페이지 이동을 사용한다',async()=>{
 const fetcher=vi.fn(async(_url:string)=>new Response(JSON.stringify({total:21,requests:[receipt],offset:0,limit:20}),{status:200}));vi.stubGlobal('fetch',fetcher)
 render(<WorkRequests session={session}/>);await screen.findByRole('button',{name:'MES-204 접수 보기'})
 await userEvent.type(screen.getByLabelText('주문·출처·내부 ID 검색'),'MES-204');await userEvent.click(screen.getByRole('button',{name:'접수 검색'}))
 await waitFor(()=>expect(fetcher.mock.calls.some(c=>String(c[0]).includes('search=MES-204'))).toBe(true))
 await userEvent.selectOptions(screen.getByLabelText('접수 상태'),'UNASSIGNED');await userEvent.click(screen.getByRole('button',{name:'접수 다음'}))
 await waitFor(()=>expect(fetcher.mock.calls.some(c=>String(c[0]).includes('state=UNASSIGNED&offset=20'))).toBe(true))
})
it('재조회 실패는 직전 목록으로 표시한다',async()=>{
 let fail=false;vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({total:1,requests:[receipt],offset:0,limit:20}),{status:fail?503:200})))
 render(<WorkRequests session={session}/>);await screen.findByRole('button',{name:'MES-204 접수 보기'});fail=true
 await userEvent.click(screen.getByRole('button',{name:'접수 다시 조회'}));expect(await screen.findByRole('alert')).toHaveTextContent('직전 조회 값')
 expect(screen.getByRole('button',{name:'MES-204 접수 보기'})).toBeVisible()
})

it('이전 버전 ACK가 있어도 최신 결과는 수신 확인 대기로 표시한다',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[receipt],offset:0,limit:20}:{receipt,hostRead:'UNAVAILABLE',execution:null,instanceId:null,journalBlocked:false,observedAt:'t2',latestResult:{version:2,resultHash:'abc',recordedAt:'t2',acknowledgedAt:null,snapshot:{jobResponse:{physicalState:'PARTIAL'}}}}),{status:200})))
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 접수 보기'}))
 expect(await screen.findByText('v2 수신 확인 대기')).toBeVisible()
 expect(screen.getByText(/품질 승인이나 물리 성공을 의미하지/)).toBeVisible()
})

it('로컬 수동 작업 결과를 MES ACK 대기로 표시하지 않는다',async()=>{
 const local={...receipt,sourceSystemId:'ops:manual',state:'ACCEPTED',payload:{requestedBy:'operator/kim'}}
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[local],offset:0,limit:20}:{receipt:local,hostRead:'OK',execution:null,instanceId:'mw',journalBlocked:false,observedAt:'t2',latestResult:{version:1,resultHash:'abc',recordedAt:'t2',acknowledgedAt:null,snapshot:{}}}),{status:200})))
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 접수 보기'}))
 expect(await screen.findByText('로컬 작업 · 외부 ACK 대상 아님')).toBeVisible()
 expect(screen.getByText('operator/kim')).toBeVisible()
 expect(screen.queryByText('v1 수신 확인 대기')).not.toBeInTheDocument()
})

it('전용 메뉴 URL의 검색 조건과 접수를 화면을 다시 열어도 복원한다',async()=>{
 window.history.replaceState(null,'','#requests?state=UNASSIGNED&search=MES&receipt=r1')
 const fetcher=vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[receipt],offset:0,limit:20}:{receipt,hostRead:'UNAVAILABLE',execution:null,instanceId:null,journalBlocked:false,observedAt:'t2'}),{status:200}))
 vi.stubGlobal('fetch',fetcher)
 const first=render(<WorkRequests session={session}/> )
 expect(await screen.findByText('기체가 바쁩니다')).toBeVisible()
 expect(screen.getByLabelText('접수 상태')).toHaveValue('UNASSIGNED')
 expect(screen.getByLabelText('주문·출처·내부 ID 검색')).toHaveValue('MES')
 first.unmount();render(<WorkRequests session={session}/> )
 expect(await screen.findByText('기체가 바쁩니다')).toBeVisible()
 expect(fetcher.mock.calls.some(([url])=>url.includes('search=MES&state=UNASSIGNED'))).toBe(true)
})

it('호스트 재시작으로 현재 실행이 없어도 저장된 물리 완료와 근거를 요약한다',async()=>{
 window.history.replaceState(null,'','#requests?receipt=r1')
 const accepted={...receipt,state:'ACCEPTED'}
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[accepted],offset:0,limit:20}:{receipt:accepted,hostRead:'OK',execution:null,instanceId:'new-host',journalBlocked:false,observedAt:'t3',latestResult:{version:1,resultHash:'abc',recordedAt:'t2',acknowledgedAt:null,snapshot:{robotId:'humanoid-01',jobResponse:{physicalState:'PHYSICALLY_DONE',requiredEvidence:'E0',reachedEvidence:'E0'}}}}))))
 render(<WorkRequests session={session}/>)
 const summary=await screen.findByRole('region',{name:'저장 결과의 완료 근거'})
 expect(summary).toHaveTextContent('PHYSICALLY_DONE');expect(summary).toHaveTextContent('E0 / E0');expect(summary).toHaveTextContent('humanoid-01')
 expect(screen.getByText('현재 실행 확인 안 됨')).toBeVisible()
 expect(screen.getByText(/저장 결과와 현재 실행·현장 상태를 먼저 확인/)).toBeVisible()
})
