import { render,screen,within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach,expect,it,vi } from 'vitest'
import { MissionCreator } from './MissionCreator'
import { InterfaceDeployment } from './InterfaceDeployment'
import { missionTemplate } from '../missionTemplates'
import type { MissionInterfaceView } from '../api'
afterEach(()=>vi.unstubAllGlobals())
it('새 미션 ID와 입력 스키마 및 실행 단계가 함께 등록된다',async()=>{
 const created=vi.fn();const requests:unknown[]=[]
 vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{const body=JSON.parse(init.body as string);requests.push(body);return new Response(JSON.stringify(body),{status:200})}))
 render(<MissionCreator session={{mode:'engineer',user:'lee'}} onCreated={created} onClose={()=>undefined}/> )
 await userEvent.clear(screen.getByLabelText('새 미션 ID'));await userEvent.type(screen.getByLabelText('새 미션 ID'),'LineAssetCheck')
 await userEvent.click(screen.getByRole('button',{name:'미션 등록 후 실행 정의 열기'}))
 expect(created).toHaveBeenCalledWith(expect.objectContaining({workMasterId:'LineAssetCheck'}))
 expect(requests[0]).toMatchObject({inputSchema:{required:['targets']},definition:{workMasterId:'LineAssetCheck',steps:[{skill:'inspect',forEach:'mission_target'}]}})
})
it('숫자 버전이 배포되지 않은 신규 미션에는 CODE 배포를 제공하지 않는다',async()=>{
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>new Response(JSON.stringify(url.endsWith('/deployments')?{deployments:[]}:{versions:[{version:1}]}),{status:200})))
 const row:MissionInterfaceView={interfaceId:'line-check',version:1,name:'점검',workMasterId:'LineAssetCheck',mapping:{targets:'/assets'},sample:{},status:'DRAFT',createdBy:'lee',createdAt:'t',missionVersionPolicy:'PINNED_DEPLOYMENT',deployment:null}
 render(<InterfaceDeployment row={row} session={{mode:'engineer',user:'lee'}} onSaved={()=>undefined}/> )
 const select=screen.getByLabelText('배포할 미션 버전')
 expect(await within(select).findByRole('option',{name:'미션 v1'})).toBeVisible()
 expect(within(select).queryByRole('option',{name:'코드 정의 고정'})).not.toBeInTheDocument()
 expect(screen.getByRole('button',{name:'새 실행 배포 적용'})).toBeDisabled()
})
it('이동 템플릿은 입력 location를 이동 스킬 파라미터에 연결한다',()=>{
 const profile=missionTemplate('move','LineDelivery','라인 이동','설명')
 expect(profile.definition.steps).toEqual([expect.objectContaining({skill:'navigate_to',parameters:{location:{from:'ITEM_PROPERTY',property:'location'}}})])
})

it('등록 응답 유실 뒤 화면을 다시 열어도 같은 미션 ID와 원본 입력으로 확인한다',async()=>{
 const calls:unknown[]=[];let fail=true
 vi.stubGlobal('fetch',vi.fn(async(_url:string,init:RequestInit)=>{const body=JSON.parse(init.body as string);calls.push(body);if(fail)throw new Error('응답 유실');return new Response(JSON.stringify(body),{status:200})}))
 const props={session:{mode:'engineer' as const,user:'lee'},onCreated:vi.fn(),onClose:()=>undefined}
 const first=render(<MissionCreator {...props}/> )
 await userEvent.clear(screen.getByLabelText('새 미션 ID'));await userEvent.type(screen.getByLabelText('새 미션 ID'),'RecoveredMission')
 await userEvent.click(screen.getByRole('button',{name:'미션 등록 후 실행 정의 열기'}))
 expect(await screen.findByRole('alert')).toHaveTextContent('응답 유실')
 first.unmount();fail=false;render(<MissionCreator {...props}/> )
 expect(screen.getByLabelText('새 미션 ID')).toHaveValue('RecoveredMission')
 await userEvent.click(screen.getByRole('button',{name:'미션 등록 후 실행 정의 열기'}))
 expect(calls[1]).toEqual(calls[0]);expect(props.onCreated).toHaveBeenCalledTimes(1)
})
