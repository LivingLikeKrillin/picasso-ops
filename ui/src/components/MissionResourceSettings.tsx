import { useEffect,useState } from 'react'
import { createMissionResource,fetchMissionResources } from '../api'
import type { Session,RfStation,MissionInterfaceView,MissionResourceRequirement } from '../api'
export function MissionResourceSettings({session,stations,contracts,onSaved}:{session:Session;stations:RfStation[];contracts:MissionInterfaceView[];onSaved:()=>void}) {
 const [rows,setRows]=useState<MissionResourceRequirement[]|null>(null),[error,setError]=useState<string|null>(null)
 const [wm,setWm]=useState(''),[station,setStation]=useState(''),[reason,setReason]=useState(''),[busy,setBusy]=useState(false)
 useEffect(()=>{let live=true;fetchMissionResources(session).then(v=>{if(!Array.isArray(v.requirements))throw new Error('자원 요구 조회 형식 오류');if(live){setRows(v.requirements);setError(null)}}).catch(e=>{if(live)setError(String(e))});return()=>{live=false}},[session,contracts])
 const missions=[...new Set(['InspectAsset','PrepareSequencedRack',...contracts.map(r=>r.workMasterId)])]
 async function save(){setBusy(true);setError(null);try{const row=await createMissionResource(session,{workMasterId:wm,stationId:station,reason:reason.trim()});setRows([...(rows??[]),row]);setReason('');onSaved()}catch(e){setError(e instanceof Error?e.message:String(e))}finally{setBusy(false)}}
 return <section aria-label="임무 필수 자원"><h3>임무 필수 자원</h3><p>임무의 모든 호출 계약과 수동·직접 제출에 적용됩니다. 작업 요청에 자원을 고정하고 준비 완료 후 실행합니다. 현재는 임무당 스테이션 하나를 불변으로 등록합니다.</p>{error&&<p role="alert">{error}</p>}{rows===null?<p>자원 요구를 아직 읽지 못했습니다.</p>:rows.length===0?<p>등록된 임무 자원 요구가 없습니다.</p>:<ul>{rows.map(r=><li key={r.workMasterId}>{r.workMasterId} → {r.stationId} · 요구 v{r.revision} · {r.createdBy}<p>{r.reason}</p></li>)}</ul>}{session.mode==='engineer'&&<details><summary>임무 자원 요구 등록</summary><form className="station-form" onSubmit={e=>{e.preventDefault();void save()}}><label>자원이 필요한 임무<select required value={wm} onChange={e=>setWm(e.target.value)}><option value="">임무 선택</option>{missions.filter(m=>!rows?.some(r=>r.workMasterId===m)).map(m=><option key={m}>{m}</option>)}</select></label><label>필수 스테이션<select required value={station} onChange={e=>setStation(e.target.value)}><option value="">스테이션 선택</option>{stations.map(r=><option value={r.stationId} key={r.stationId}>{r.name}</option>)}</select></label><label>자원 요구 등록 사유<input required maxLength={1000} value={reason} onChange={e=>setReason(e.target.value)}/></label><p>진행 중·완료 불명 작업 요청이 있으면 등록할 수 없습니다. 등록 후 변경·삭제는 제공하지 않습니다.</p><button disabled={busy||rows===null||!wm||!station||!reason.trim()}>필수 자원 등록</button></form></details>}</section>
}
export function MissionResourceNotice({session,workMasterId}:{session:Session;workMasterId:string}) {
 const [rows,setRows]=useState<MissionResourceRequirement[]|null>(null),[failed,setFailed]=useState(false)
 useEffect(()=>{let live=true;fetchMissionResources(session).then(v=>{if(!Array.isArray(v.requirements))throw new Error('형식 오류');if(live){setRows(v.requirements);setFailed(false)}}).catch(()=>{if(live)setFailed(true)});return()=>{live=false}},[session,workMasterId])
 const row=rows?.find(r=>r.workMasterId===workMasterId)
 return <p aria-label="호출 전 자원 요구">{failed?'필수 자원 조회 실패 · 작업 요청을 받을 때 서버가 요구를 확인합니다.':rows===null?'필수 자원 확인 중':row?`필수 자원 ${row.stationId} · 작업 요청을 받은 뒤 자원과 설비 준비를 기다립니다. 준비 완료 전 로봇을 실행하지 않습니다.`:'등록된 필수 자원 없음 · 작업 요청을 받을 때 서버가 최신 요구를 확인합니다.'}</p>
}
