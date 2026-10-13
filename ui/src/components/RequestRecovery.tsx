import { useEffect, useRef, useState } from 'react'
import type { Session } from '../api'
import type { WorkReceipt } from './WorkRequests'
interface Recovery { requestId: string; reason: string; expectedUpdatedAt: string }
export function RequestRecovery({ receipt, session, onChanged }: { receipt: WorkReceipt; session: Session; onChanged: () => void }) {
  const storageKey=`picasso.recovery.v1.${session.user}.${receipt.receiptId}`
  const [restored]=useState(()=>{
    try { const raw=sessionStorage.getItem(storageKey); const pending=raw?JSON.parse(raw) as Recovery:null
      if(pending && (typeof pending.requestId!=='string' || typeof pending.reason!=='string' || typeof pending.expectedUpdatedAt!=='string'))throw new Error('저장 형식 오류')
      return {pending,error:null}
    } catch {return {pending:null,error:'보관된 재평가 요청을 읽지 못했습니다. 실행·재평가 이력을 확인하세요.'}}
  })
  const [pending,setPending]=useState<Recovery|null>(restored.pending)
  const [reason,setReason]=useState(restored.pending?.reason??'')
  const [busy,setBusy]=useState(false)
  const [resolved,setResolved]=useState(false)
  const [error,setError]=useState<string|null>(restored.error)
  const [notice,setNotice]=useState<string|null>(null)
  const sending=useRef(false);const alive=useRef(true)
  useEffect(()=>{alive.current=true;return()=>{alive.current=false}},[])
  const safe=receipt.state==='UNASSIGNED' && receipt.submission===null && !receipt.lastExecution && receipt.normalizedOrder!==null
  async function recover() {
    if(sending.current || session.mode!=='operator' || restored.error || (!pending && !safe))return
    const body=pending??{requestId:crypto.randomUUID(),reason:reason.trim(),expectedUpdatedAt:receipt.updatedAt}
    try {sessionStorage.setItem(storageKey,JSON.stringify(body))}catch{setError('요청 보관 실패로 전송하지 않았습니다.');return}
    sending.current=true;setBusy(true);setPending(body);setError(null)
    try {
      const response=await fetch(`/api/work-requests/${receipt.receiptId}/recover`,{method:'POST',headers:{'X-Ops-Mode':session.mode,'X-Ops-User':session.user,'Content-Type':'application/json'},body:JSON.stringify(body)})
      const value=await response.json()
      if(!response.ok) { if(response.status>=400 && response.status<500 && alive.current)setResolved(true); throw new Error(value.detail??`HTTP ${response.status}`) }
      if(value.receipt?.receiptId!==receipt.receiptId || typeof value.duplicate!=='boolean')throw new Error('재평가 응답을 확인할 수 없습니다')
      if(alive.current){setResolved(true);setNotice(`${value.duplicate?'기존 재평가 확인':'재평가 요청 기록됨'} · ${value.receipt.state}`);onChanged()}
    }catch(e:unknown){if(alive.current)setError(`${e instanceof Error?e.message:String(e)} · 기존 재평가 ID로 확인하세요.`)}
    finally{sending.current=false;if(alive.current)setBusy(false)}
  }
  if(receipt.requiredStationId)return <p>필수 자원 {receipt.requiredStationId}의 조정기가 미배정을 재평가합니다. <a href="#stations">공유 자원에서 관측·복구</a></p>
  if(!safe && !pending && receipt.state==='ACCEPTED')return <p>새 제출은 차단됩니다. 저장 결과와 현재 실행·현장 상태를 먼저 확인하세요.</p>
  if(!safe && !pending)return <p>새 제출은 차단됩니다. 전송 결과 불명·호스트 전송 후 미배정은 실행 관측과 현장 확인이 필요합니다.</p>
  return <section className="manual-receipt" aria-label="미배정 안전 재평가"><h4>미배정 재평가</h4><p>호스트 전송 전에 후보가 없어 멈춘 요청만 기존 작업 ID로 다시 배정 판단합니다. 입력은 변경하지 않습니다.</p><label>재평가 판단 사유<input value={reason} maxLength={1000} disabled={busy || !!pending} onChange={e=>setReason(e.target.value)} /></label><div className="actions"><button disabled={busy || !!restored.error || session.mode!=='operator' || (!pending && !reason.trim())} onClick={recover}>{pending?'동일 재평가 요청 확인':'미배정 재평가'}</button>{resolved && <button disabled={busy} onClick={()=>{try{sessionStorage.removeItem(storageKey);setPending(null);setResolved(false);setNotice(null)}catch{setError('보관 해제 실패')}}}>재평가 기록 확인 완료</button>}</div>{pending && <p>재평가 ID: <code>{pending.requestId}</code></p>}{error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}</section>
}
