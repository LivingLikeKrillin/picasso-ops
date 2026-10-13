import { render, screen, within, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkRequests } from './WorkRequests'
const session={mode:'operator' as const,user:'kim'}
const receipt={receiptId:'r1',sourceSystemId:'mock-mes',externalOrderId:'MES-204',jobOrderId:'JO-204',state:'UNASSIGNED',detail:'기체가 바쁩니다',payload:{input:{}},normalizedOrder:null,submission:null,receivedAt:'t1',updatedAt:'t1'}
afterEach(()=>vi.unstubAllGlobals())
it('미배정 접수도 상세와 원본 입력을 조회하며 자동 재제출 버튼을 제공하지 않는다',async()=>{
 const fetcher=vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[receipt],offset:0,limit:20}:{receipt,hostRead:'UNAVAILABLE',execution:null,instanceId:null,journalBlocked:false,observedAt:'t2'}),{status:200}))
 vi.stubGlobal('fetch',fetcher)
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 작업 요청 보기'}))
 const detail=await screen.findByRole('article',{name:'작업 요청 상세'})
 expect(await within(detail).findByText('기체가 바쁩니다')).toBeVisible()
 expect(within(detail).getByText('모름 · 호스트 조회 실패')).toBeVisible()
 expect(within(detail).getByText(/새 제출은 차단됩니다/)).toBeVisible()
 expect(fetcher.mock.calls.every(([url])=>url.startsWith('/api/work-requests'))).toBe(true)
})
it('서버 검색·상태 필터와 페이지 이동을 사용한다',async()=>{
 const fetcher=vi.fn(async(_url:string)=>new Response(JSON.stringify({total:21,requests:[receipt],offset:0,limit:20}),{status:200}));vi.stubGlobal('fetch',fetcher)
 render(<WorkRequests session={session}/>);await screen.findByRole('button',{name:'MES-204 작업 요청 보기'})
 await userEvent.type(screen.getByLabelText('외부 주문·출처·내부 ID 검색'),'MES-204');await userEvent.click(screen.getByRole('button',{name:'작업 요청 검색'}))
 await waitFor(()=>expect(fetcher.mock.calls.some(c=>String(c[0]).includes('search=MES-204'))).toBe(true))
 await userEvent.selectOptions(screen.getByLabelText('작업 요청 상태'),'UNASSIGNED');await userEvent.click(screen.getByRole('button',{name:'작업 요청 목록 다음 페이지'}))
 await waitFor(()=>expect(fetcher.mock.calls.some(c=>String(c[0]).includes('state=UNASSIGNED&offset=20'))).toBe(true))
})
it('재조회 실패는 직전 목록으로 표시한다',async()=>{
 let fail=false;vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({total:1,requests:[receipt],offset:0,limit:20}),{status:fail?503:200})))
 render(<WorkRequests session={session}/>);await screen.findByRole('button',{name:'MES-204 작업 요청 보기'});fail=true
 await userEvent.click(screen.getByRole('button',{name:'작업 요청 다시 조회'}));expect(await screen.findByRole('alert')).toHaveTextContent('직전 조회 값')
 expect(screen.getByRole('button',{name:'MES-204 작업 요청 보기'})).toBeVisible()
})

it('이전 버전 ACK가 있어도 최신 결과는 수신 확인 대기로 표시한다',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[receipt],offset:0,limit:20}:{receipt,hostRead:'UNAVAILABLE',execution:null,instanceId:null,journalBlocked:false,observedAt:'t2',latestResult:{version:2,resultHash:'abc',recordedAt:'t2',acknowledgedAt:null,snapshot:{jobResponse:{physicalState:'PARTIAL'}}}}),{status:200})))
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 작업 요청 보기'}))
 expect(await screen.findByText('v2 수신 확인 대기')).toBeVisible()
 expect(screen.getByText(/품질 승인이나 물리 성공을 의미하지/)).toBeVisible()
})

it('로컬 수동 작업 결과를 MES ACK 대기로 표시하지 않는다',async()=>{
 const local={...receipt,sourceSystemId:'ops:manual',state:'ACCEPTED',payload:{requestedBy:'operator/kim'}}
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[local],offset:0,limit:20}:{receipt:local,hostRead:'OK',execution:null,instanceId:'mw',journalBlocked:false,observedAt:'t2',latestResult:{version:1,resultHash:'abc',recordedAt:'t2',acknowledgedAt:null,snapshot:{}}}),{status:200})))
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 작업 요청 보기'}))
 expect(await screen.findByText('로컬 작업 · 외부 수신 확인 대상 아님')).toBeVisible()
 expect(screen.getByText('operator/kim')).toBeVisible()
 expect(screen.queryByText('v1 수신 확인 대기')).not.toBeInTheDocument()
})

it('전용 메뉴 URL의 검색 조건과 접수를 화면을 다시 열어도 복원한다',async()=>{
 window.history.replaceState(null,'','#requests?state=UNASSIGNED&search=MES&receipt=r1')
 const fetcher=vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[receipt],offset:0,limit:20}:{receipt,hostRead:'UNAVAILABLE',execution:null,instanceId:null,journalBlocked:false,observedAt:'t2'}),{status:200}))
 vi.stubGlobal('fetch',fetcher)
 const first=render(<WorkRequests session={session}/> )
 expect(await screen.findByText('기체가 바쁩니다')).toBeVisible()
 expect(screen.getByLabelText('작업 요청 상태')).toHaveValue('UNASSIGNED')
 expect(screen.getByLabelText('외부 주문·출처·내부 ID 검색')).toHaveValue('MES')
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

const rejectedReceipt={...receipt,state:'REJECTED',detail:'공통 작업 요청 검증 완료'}
const missing={material:'ENGINE-COVER-A',source:'SEQ-IN-02.BIN-A',observed:null}
async function rejectedDetail(rejection:unknown) {
 window.history.replaceState(null,'','#')
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[rejectedReceipt],offset:0,limit:20}:{receipt:rejectedReceipt,hostRead:'OK',execution:null,instanceId:'mw',journalBlocked:false,observedAt:'t2',rejection}),{status:200})))
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 작업 요청 보기'}))
 return screen.findByRole('region',{name:'거부 사유'})
}
it('호스트 거부는 제출 전 상세 대신 거부 사유와 대체 위치를 보인다',async()=>{
 const shown=await rejectedDetail({reason:'출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다',remedy:null,remedyWithheld:false,alternativeLocations:['SEQ-IN-03.BIN-B','SEQ-IN-04.BIN-C'],sourceMissing:missing})
 expect(shown).toHaveTextContent('거부 사유 출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다')
 expect(within(shown).getByText('대체 위치: SEQ-IN-03.BIN-B, SEQ-IN-04.BIN-C')).toBeVisible()
 expect(within(shown).getByText('출발 자리 SEQ-IN-02.BIN-A · 비어 있음')).toBeVisible()
 expect(screen.queryByText('공통 작업 요청 검증 완료')).not.toBeInTheDocument()
})
it('대체 위치 조회 불가와 대체 위치 없음을 구분한다',async()=>{
 const silent=await rejectedDetail({reason:'출발 자리 비었다',remedy:null,remedyWithheld:false,alternativeLocations:null,sourceMissing:missing})
 expect(within(silent).getByText('대체 위치 조회 불가')).toBeVisible()
 expect(within(silent).queryByText('대체 위치 없음')).not.toBeInTheDocument()
 cleanup()
 const none=await rejectedDetail({reason:'출발 자리 비었다',remedy:null,remedyWithheld:false,alternativeLocations:[],sourceMissing:missing})
 expect(within(none).getByText('대체 위치 없음')).toBeVisible()
 expect(within(none).queryByText('대체 위치 조회 불가')).not.toBeInTheDocument()
})
it('출발 결품이 아닌 거부는 대체 위치를 말하지 않고 조치 탐색 응답을 요약한다',async()=>{
 const found=await rejectedDetail({reason:'사전 조건이 어긋난다',remedy:{kind:'FOUND',steps:[{skillType:'release'},{skillType:'pick_place'}]},remedyWithheld:false,alternativeLocations:null,sourceMissing:null})
 expect(within(found).getByText('조치 제안 2단계: release → pick_place')).toBeVisible()
 expect(within(found).queryByText(/대체 위치/)).not.toBeInTheDocument()
 cleanup()
 const withheld=await rejectedDetail({reason:'사전 조건이 어긋난다',remedy:null,remedyWithheld:true,alternativeLocations:null,sourceMissing:null})
 expect(within(withheld).getByText('조치 제안 보류 · 사람이 먼저 진단')).toBeVisible()
 cleanup()
 const none=await rejectedDetail({reason:'사전 조건이 어긋난다',remedy:{kind:'NONE',cause:'DEPTH_LIMIT',unmet:[]},remedyWithheld:false,alternativeLocations:null,sourceMissing:null})
 expect(within(none).getByText('조치 제안 없음 · 탐색 상한 도달')).toBeVisible()
})
it('호스트 미배정은 서버가 바꾼 기체별 사유를 상세 문장으로 보인다',async()=>{
 window.history.replaceState(null,'','#')
 const unassigned={...receipt,detail:'호스트 미배정: humanoid-01: 출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다',submission:{outcome:{result:'UNASSIGNED'}}}
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.startsWith('/api/work-requests?')?{total:1,requests:[unassigned],offset:0,limit:20}:{receipt:unassigned,hostRead:'OK',execution:null,instanceId:'mw',journalBlocked:false,observedAt:'t2',rejection:null}),{status:200})))
 render(<WorkRequests session={session}/>);await userEvent.click(await screen.findByRole('button',{name:'MES-204 작업 요청 보기'}))
 const detail=await screen.findByRole('article',{name:'작업 요청 상세'})
 expect(await within(detail).findByText('호스트 미배정: humanoid-01: 출발 자리 SEQ-IN-02.BIN-A 에 ENGINE-COVER-A 이 없다')).toBeVisible()
 expect(within(detail).queryByRole('region',{name:'거부 사유'})).not.toBeInTheDocument()
})
