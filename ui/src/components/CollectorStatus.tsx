import { useEffect, useState } from 'react'
import type { Session } from '../api'
import { POLL_MS } from '../poll'
interface Status { cursor?:{mode:string;sequence:number;highWatermark:number;producerId:string|null}; state:string; lastSuccessAt:string|null; lastAttemptAt:string|null; observedRequests:number|null; error:string|null }
export function CollectorStatus({session}:{session:Session}) {
 const [status,setStatus]=useState<Status|null>(null);const [error,setError]=useState<string|null>(null)
 useEffect(()=>{let alive=true
  const load=async()=>{try{const response=await fetch('/api/work-requests/collector',{headers:{'X-Ops-Mode':session.mode,'X-Ops-User':session.user}});if(!response.ok)throw new Error(`HTTP ${response.status}`);const value=await response.json() as Status;if(typeof value.state!=='string')throw new Error('상태 응답 오류');if(alive){setStatus(value);setError(null)}}catch(e:unknown){if(alive)setError(e instanceof Error?e.message:String(e))}}
  void load();const timer=setInterval(load,POLL_MS);return()=>{alive=false;clearInterval(timer)}
 },[session])
 return <section aria-label="결과 자동 수집"><h3>결과 자동 수집</h3>{error && <p role="alert">수집기 조회 모름: {error}{status && ' · 아래는 직전 값입니다.'}</p>}{status ? <><p>{status.state==='OK'?'수집 중':status.state==='WAITING'?'첫 수집 대기':'수집 실패 · 확인 필요'}</p><p>최근 성공: {status.lastSuccessAt??'없음'} · 최근 관측 요청 {status.observedRequests??'모름'}건</p>{status.error && <p role="alert">{status.error} · 마지막 시도 {status.lastAttemptAt}</p>}</> : <p>수집 상태 모름</p>}{status?.cursor?.mode==='DURABLE_OUTBOX' ? <><p>호스트에 저장된 결과를 마지막 커서부터 순서대로 수집합니다. 단절 중 결과도 연결 복구 후 재수집합니다.</p><p>수집 커서 {status.cursor.sequence} / 확인한 마지막 번호 {status.cursor.highWatermark} · 관측 시점 미수집 {Math.max(0,status.cursor.highWatermark-status.cursor.sequence)}건</p></> : <p>수집 방식 확인 중 · 마지막 성공 시각과 현재 연결 상태를 함께 확인하세요.</p>}</section>
}
