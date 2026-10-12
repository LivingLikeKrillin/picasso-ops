import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { OverviewArea } from './OverviewArea'
import { RequestRecovery } from './RequestRecovery'
import { InterfaceDeployment } from './InterfaceDeployment'
import type { MissionInterfaceView } from '../api'
import type { WorkReceipt } from './WorkRequests'
const operator={mode:'operator' as const,user:'kim'}
const row:WorkReceipt={receiptId:'receipt-1',sourceSystemId:'mes',externalOrderId:'ORDER-1',jobOrderId:'JO-1',state:'UNASSIGNED',detail:null,payload:{},normalizedOrder:{workMasterId:'InspectAsset'},submission:null,receivedAt:'t1',updatedAt:'t2'}
afterEach(()=>{vi.unstubAllGlobals();sessionStorage.clear();window.history.replaceState(null,'',window.location.pathname)})
it('개요는 접수 집계와 호스트 실패를 구분하고 지표를 실제 검색 조건으로 연결한다',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
  const body=url==='/api/work-requests/summary'?{total:8,states:{UNASSIGNED:2,RECEIVED:1,IN_DOUBT:1},unacknowledgedResults:3,latest:[row],observedAt:'t3'}:url==='/api/work-requests/collector'?{state:'UNAVAILABLE',lastSuccessAt:'t1',lastAttemptAt:'t2',observedRequests:1,error:'호스트 관측 실패'}:{detail:'offline'}
  return new Response(JSON.stringify(body),{status:url==='/api/executions'||url==='/api/incidents'?503:200})
 }))
 render(<OverviewArea session={operator}/> )
 expect(await screen.findByRole('link',{name:/전체 작업 요청 8/})).toHaveAttribute('href','#requests')
 expect(screen.getByRole('link',{name:/미배정 2/})).toHaveAttribute('href','#requests?state=UNASSIGNED')
 expect(screen.getByRole('link',{name:/제출 확인 필요 2/})).toHaveAttribute('href','#requests?state=NEEDS_CONFIRMATION')
 expect(screen.getByRole('link',{name:/외부 결과 수신 미확인 3/})).toHaveAttribute('href','#requests?result=unacknowledged')
 expect(await screen.findByText('실행 상태 모름 · 관측을 기다립니다.')).toBeVisible()
 expect(screen.getByRole('region',{name:'결과 자동 수집'})).toHaveTextContent('수집 실패 · 확인 필요')
})
it('개요 재조회 실패는 직전 집계를 유지하고 오래된 값임을 표시한다',async()=>{
 let fail=false
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url==='/api/work-requests/summary'?{total:5,states:{},latest:[],unacknowledgedResults:0,observedAt:'t1'}:url==='/api/executions'?{instanceId:'host',executions:[{physicalState:'OPERATOR_HOLD'},{physicalState:'IN_DOUBT'}]}:url==='/api/incidents'?{instanceId:'host',incidents:[]}: {state:'WAITING'}),{status:fail?503:200})))
 render(<OverviewArea session={operator}/> )
 expect(await screen.findByRole('link',{name:/전체 작업 요청 5/})).toBeVisible()
 expect(screen.getByText('판단·확인 대기').nextElementSibling).toHaveTextContent('2')
 fail=true;await userEvent.click(screen.getByRole('button',{name:'개요 다시 조회'}))
 expect(await screen.findByText(/작업 요청 집계:.*직전 조회 값/)).toBeVisible()
 expect(screen.getByRole('link',{name:/전체 작업 요청 5/})).toHaveTextContent('직전 값')
})
it('미배정 재평가 응답이 사라져도 보관한 판단 ID와 사유로만 다시 확인한다',async()=>{
 const calls:unknown[]=[];let fail=true
 vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{calls.push(JSON.parse(init.body as string));return new Response(JSON.stringify(fail?{detail:'timeout'}:{receipt:{...row,state:'ACCEPTED'},duplicate:true}),{status:fail?500:200})}))
 const first=render(<RequestRecovery receipt={row} session={operator} onChanged={()=>undefined}/> )
 await userEvent.type(screen.getByLabelText('재평가 판단 사유'),'기체 준비 확인')
 await userEvent.click(screen.getByRole('button',{name:'미배정 재평가'}))
 expect(await screen.findByRole('alert')).toHaveTextContent('기존 재평가 ID')
 first.unmount();fail=false
 render(<RequestRecovery receipt={{...row,state:'DISPATCHING'}} session={operator} onChanged={()=>undefined}/> )
 expect(calls).toHaveLength(1)
 await userEvent.click(screen.getByRole('button',{name:'동일 재평가 요청 확인'}))
 expect(await screen.findByRole('status')).toHaveTextContent('기존 재평가 확인')
 expect(calls[1]).toEqual(calls[0]);expect(calls[0]).toMatchObject({reason:'기체 준비 확인',expectedUpdatedAt:'t2'})
})
it('불명 접수에는 새 전송을 허용하는 재평가 버튼이 없다',()=>{
 render(<RequestRecovery receipt={{...row,state:'IN_DOUBT'}} session={operator} onChanged={()=>undefined}/> )
 expect(screen.queryByRole('button',{name:'미배정 재평가'})).not.toBeInTheDocument()
 expect(screen.getByText(/전송 결과 불명.*현장 확인/)).toBeVisible()
})
it('배포 적용은 조회한 배포 버전과 판단 사유를 서버에 보낸다',async()=>{
 const deployment={interfaceId:'check',interfaceVersion:1,mappingVersion:1,deploymentVersion:1,missionVersion:'CODE',createdBy:'engineer/lee',reason:'초기',createdAt:'t1'}
 const view:MissionInterfaceView={interfaceId:'check',version:1,name:'점검',workMasterId:'InspectAsset',mapping:{targets:'/assets'},sample:{assets:[]},status:'ENABLED',createdBy:'lee',createdAt:'t1',missionVersionPolicy:'PINNED_DEPLOYMENT',deployment}
 const calls:{url:string;body:unknown}[]=[];const saved=vi.fn()
 vi.stubGlobal('fetch',vi.fn(async(url:string,init?:RequestInit)=>{if(init?.body)calls.push({url,body:JSON.parse(init.body as string)});return new Response(JSON.stringify(url.endsWith('/deploy')?{...view,deployment:{...deployment,deploymentVersion:2}}:{deployments:[deployment]}),{status:200})}))
 render(<InterfaceDeployment row={view} session={{mode:'engineer',user:'lee'}} onSaved={saved}/> )
 const panel=screen.getByRole('region',{name:'실행 배포'})
 fireEvent.change(within(panel).getByLabelText('배포 판단 사유'),{target:{value:'이후 요청 롤백'}})
 await userEvent.click(within(panel).getByRole('button',{name:'새 실행 배포 적용'}))
 expect(calls[0]).toEqual({url:'/api/mission-interfaces/check/1/deploy',body:{missionVersion:'CODE',reason:'이후 요청 롤백',expectedDeploymentVersion:1}})
 expect(saved).toHaveBeenCalledWith(expect.objectContaining({deployment:expect.objectContaining({deploymentVersion:2})}))
})
