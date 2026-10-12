import { useEffect, useState } from 'react'
import { deployMissionInterface, fetchInterfaceDeployments, fetchMission } from '../api'
import type { InterfaceDeploymentView, MissionInterfaceView, Session } from '../api'
export function InterfaceDeployment({ row, session, onSaved }: { row:MissionInterfaceView; session:Session; onSaved:(row:MissionInterfaceView)=>void }) {
 const [versions,setVersions]=useState<number[]>([])
 const [history,setHistory]=useState<InterfaceDeploymentView[]|null>(null)
 const [mission,setMission]=useState(row.deployment?.missionVersion??(['InspectAsset','PrepareSequencedRack'].includes(row.workMasterId)?'CODE':''))
 const [reason,setReason]=useState('');const [busy,setBusy]=useState(false);const [error,setError]=useState<string|null>(null)
 useEffect(()=>{let alive=true
  fetchInterfaceDeployments(session,row).then(value=>{if(!Array.isArray(value.deployments))throw new Error('배포 이력 응답 오류');if(alive){setHistory(value.deployments);setError(null)}}).catch((e:unknown)=>{if(alive)setError(e instanceof Error?e.message:String(e))})
  if(row.workMasterId!=='InspectAsset')fetchMission(session,row.workMasterId).then(value=>{if(alive)setVersions(value.versions.map(version=>version.version))}).catch(()=>{if(alive)setError('호스트 미션 버전을 읽지 못했습니다. 배포 전에 다시 조회하세요.')})
  return()=>{alive=false}
 },[session,row])
 async function deploy() {
  setBusy(true);setError(null)
  try{onSaved(await deployMissionInterface(session,row,mission,reason.trim()));setReason('')}
  catch(e:unknown){setError(`${e instanceof Error?e.message:String(e)} · 응답 불명 시 계약을 다시 조회하고 배포 이력을 확인하세요.`)}
  finally{setBusy(false)}
 }
 return <section className="manual-receipt" aria-label="실행 배포"><h4>실행 배포 · {row.deployment?`v${row.deployment.deploymentVersion}`:'배포 전'}</h4><p>계약 v{row.version} · 매핑 v{row.deployment?.mappingVersion} · 미션 {row.deployment?.missionVersion==='CODE'?'코드 정의':`v${row.deployment?.missionVersion}`}</p><p>미션 필수 자원: {row.requiredStationId??'미지정'} · 공유 자원 메뉴에서 미션 단위로 등록합니다.</p><p>새 배포는 이후 접수부터 적용됩니다. 기존 접수와 재평가는 원래 배포를 유지합니다. 이전 미션을 선택해 새 배포를 만들면 이후 요청을 롤백할 수 있습니다.</p>
 {session.mode==='engineer' && <div className="actions"><label>배포할 미션 버전<select value={mission} disabled={busy} onChange={e=>setMission(e.target.value)}>{['InspectAsset','PrepareSequencedRack'].includes(row.workMasterId)?<option value="CODE">코드 정의 고정</option>:<option value="">검증된 미션 버전 선택</option>}{versions.map(version=><option key={version} value={String(version)}>미션 v{version}</option>)}</select></label><label>배포 판단 사유<input value={reason} maxLength={1000} disabled={busy} onChange={e=>setReason(e.target.value)} /></label><button disabled={busy || !reason.trim() || !mission} onClick={deploy}>새 실행 배포 적용</button></div>}
 {error && <p role="alert">{error}</p>}<details><summary>불변 배포 이력</summary>{history ? <ul>{history.map(item=><li key={item.deploymentVersion}>배포 v{item.deploymentVersion} · 미션 {item.missionVersion} · {item.createdBy} · {item.reason} · {item.createdAt}</li>)}</ul> : <p>배포 이력을 아직 읽지 못했습니다.</p>}</details></section>
}
