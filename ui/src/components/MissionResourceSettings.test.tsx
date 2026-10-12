import { render,screen,fireEvent,waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach,it,expect,vi } from 'vitest'
import { MissionResourceSettings,MissionResourceNotice } from './MissionResourceSettings'
import { installFakeOps } from '../testing/fakeOps'
import { RequestRecovery } from './RequestRecovery'
const session={mode:'engineer' as const,user:'lee'}
const station={stationId:'inspection-1',name:'공유 검사',readySignal:'ready',completeSignal:'complete',timeoutSeconds:120,owner:null,createdBy:'lee',createdAt:'t1'}
afterEach(()=>{vi.unstubAllGlobals();sessionStorage.clear()})
it('엔지니어는 미션의 필수 자원과 사유를 등록한다',async()=>{
 const fake=installFakeOps({registry:'OK',checkedAt:'t1',robots:[],robotsAsOf:'t1'});fake.answers.set('/api/mission-resources',{status:200,body:{requirements:[]}})
 render(<MissionResourceSettings session={session} stations={[station]} contracts={[]} onSaved={()=>{}}/>);
 await userEvent.click(screen.getByText('미션 자원 요구 등록'));await screen.findByText('등록된 미션 자원 요구가 없습니다.')
 await userEvent.selectOptions(screen.getByLabelText('자원이 필요한 미션'),'InspectAsset');await userEvent.selectOptions(screen.getByLabelText('필수 스테이션'),'inspection-1');fireEvent.change(screen.getByLabelText('자원 요구 등록 사유'),{target:{value:'공유 설비 검사'}})
 fake.answers.set('/api/mission-resources',{status:200,body:{workMasterId:'InspectAsset',stationId:'inspection-1',revision:1,createdBy:'engineer/lee',reason:'공유 설비 검사',createdAt:'t1'}})
 await userEvent.click(screen.getByRole('button',{name:'필수 자원 등록'}));await waitFor(()=>expect(fake.calls.find(c=>c.method==='POST'&&c.url==='/api/mission-resources')?.body).toEqual({workMasterId:'InspectAsset',stationId:'inspection-1',reason:'공유 설비 검사'}))
 expect(await screen.findByText(/InspectAsset → inspection-1/)).toBeVisible()
})
it('운영자는 등록 폼 없이 호출 전 자원 대기를 확인한다',async()=>{
 const fake=installFakeOps({registry:'OK',checkedAt:'t1',robots:[],robotsAsOf:'t1'});fake.answers.set('/api/mission-resources',{status:200,body:{requirements:[{workMasterId:'InspectAsset',stationId:'inspection-1',revision:1}]}})
 render(<MissionResourceNotice session={{mode:'operator',user:'kim'}} workMasterId="InspectAsset"/>);expect(await screen.findByText(/필수 자원 inspection-1/)).toHaveTextContent('준비 완료 전 로봇을 실행하지 않습니다')
})
it('자원 관리 미배정은 일반 재평가 버튼을 노출하지 않는다',()=>{
 render(<RequestRecovery session={{mode:'operator',user:'kim'}} onChanged={()=>{}} receipt={{receiptId:'r',sourceSystemId:'ops:manual',externalOrderId:'k',jobOrderId:'j',state:'UNASSIGNED',detail:null,payload:{},normalizedOrder:{},submission:null,receivedAt:'t1',updatedAt:'t1',requiredStationId:'inspection-1'}}/>);
 expect(screen.queryByRole('button',{name:'미배정 재평가'})).not.toBeInTheDocument();expect(screen.getByRole('link',{name:'공유 자원에서 관측·복구'})).toHaveAttribute('href','#stations')
})
it('자원 요구 조회 실패를 자원 없음으로 표시하지 않는다',async()=>{
 const fake=installFakeOps({registry:'OK',checkedAt:'t1',robots:[],robotsAsOf:'t1'});fake.failing.add('/api/mission-resources')
 render(<MissionResourceNotice session={session} workMasterId="InspectAsset"/>);expect(await screen.findByText(/필수 자원 조회 실패/)).toBeVisible();expect(screen.queryByText(/등록된 필수 자원 없음/)).not.toBeInTheDocument()
})
