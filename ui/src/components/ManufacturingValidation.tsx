import { useState } from 'react'
import type { Session } from '../api'
import type { WorkReceipt } from './WorkRequests'
import { StoredResultSummary } from './StoredResultSummary'

interface Detail {
  receipt: WorkReceipt
  latestResult?: { version: number; recordedAt: string; snapshot: unknown } | null
  hostRead: string
}
const object = (value: unknown): Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
const scalar = (value: unknown): string | null => (typeof value === 'string' && value.length > 0) || (typeof value === 'number' && Number.isFinite(value)) ? String(value) : null
function identity(detail: Detail): (string | null)[] {
  const payload = object(detail.receipt.payload)
  const order = object(detail.receipt.normalizedOrder)
  const deployment = object(order.deployment)
  return [scalar(payload.interfaceId), scalar(payload.interfaceVersion), scalar(order.workMasterId), scalar(order.missionVersion), scalar(deployment.deploymentVersion)]
}
const fields = ['호출 계약', '계약 버전', '임무', '고정 임무 버전', '실행 배포 버전']
const message = (error: unknown) => error instanceof Error ? error.message : String(error)

/** Compares persisted receipts, without creating work or treating an equal contract as proof of completion. */
export function ManufacturingValidation({ session }: { session: Session }) {
  const [search, setSearch] = useState('')
  const [receipts, setReceipts] = useState<WorkReceipt[]>([])
  const [listError, setListError] = useState<string | null>(null)
  const [ids, setIds] = useState<[string, string]>(['', ''])
  const [pair, setPair] = useState<[Detail, Detail] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const headers = { 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user }
  async function read<T>(url: string): Promise<T> {
    const response = await fetch(url, { headers })
    if (!response.ok) throw new Error(`조회 HTTP ${response.status}`)
    return response.json() as Promise<T>
  }
  const left = pair ? identity(pair[0]) : []
  const right = pair ? identity(pair[1]) : []
  const known = pair !== null && [...left, ...right].every(value => value !== null)
  const same = known && left.every((value, index) => value === right[index])
  return <section className="manufacturing-validation" aria-label="제조 시나리오 검증">
    <div className="mission-section-heading"><div><h2>호출 계약과 실행 근거 비교</h2><p>같은 임무가 다른 로봇에 배정되었는지, 완료 근거가 어떻게 달라졌는지 작업 요청 기록으로 확인합니다.</p></div></div>
    <ol className="validation-journey">
      <li><strong>공통 임무</strong><p>두 기종이 지원하는 스테이션 검사 계약을 고정합니다.</p><a href="#missions">임무·호출 계약</a></li>
      <li><strong>케이퍼빌리티 기반 배정</strong><p>두 검사를 연속 호출해 가용 기체에 배정합니다. 집기 미지원 기체의 배제 사유도 확인합니다.</p><a href="#operations">배정·실행 확인</a></li>
      <li><strong>예외와 재작업</strong><p>모의 랙 도착 신호가 누락되면 보류 사유를 확인하고 운영자가 근거를 남겨 재작업합니다.</p><a href="#operations">보류·인시던트 판단</a></li>
      <li><strong>상위 결과</strong><p>원래 작업 요청과 작업 ID를 유지하며 저장 결과를 확인합니다.</p><a href="#requests">작업 요청·결과 추적</a></li>
    </ol>
    <p>참조 시뮬레이터 시나리오입니다. E0 검사와 설비 관측 E2 공급은 요구 근거가 다른 임무이며, 기종 자체가 근거 등급을 결정하지 않습니다.</p>
    <form className="actions" aria-label="비교할 작업 요청 검색" onSubmit={async event => {
      event.preventDefault(); setListError(null)
      try { const page = await read<{ requests: WorkReceipt[] }>(`/api/work-requests?search=${encodeURIComponent(search.trim())}&offset=0&limit=20`)
        if (!Array.isArray(page.requests)) throw new Error('작업 요청 목록 형식 오류')
        setReceipts(page.requests)
      } catch (cause) { setListError(message(cause)) }
    }}><label>비교할 외부 주문 검색<input value={search} maxLength={160} onChange={event => setSearch(event.target.value)} /></label><button type="submit">비교 작업 요청 검색</button><a href="#requests">전체 작업 요청에서 ID 확인</a></form>
    {listError && <p role="alert">{listError} · 목록은 직전 검색 값입니다.</p>}
    <p>검색 결과의 최근 20건에서 선택하거나 전체 작업 요청의 ID를 입력하세요.</p>
    <form className="actions" aria-label="두 작업 요청 비교" onSubmit={async event => {
      event.preventDefault(); if (busy) return
      setBusy(true); setPair(null); setError(null)
      try {
        if (ids[0].trim() === ids[1].trim()) throw new Error('서로 다른 두 작업 요청을 선택하세요.')
        const result = await Promise.all(ids.map(id => read<Detail>(`/api/work-requests/${encodeURIComponent(id.trim())}`)))
        if (result.some((detail, index) => detail.receipt?.receiptId !== ids[index].trim())) throw new Error('작업 요청 상세 식별자가 일치하지 않습니다.')
        setPair([result[0], result[1]])
      } catch (cause) { setError(message(cause)) } finally { setBusy(false) }
    }}>
      {[0, 1].map(index => <label key={index}>작업 요청 {index === 0 ? 'A' : 'B'} ID<input required maxLength={64} list="validation-receipts" disabled={busy} value={ids[index]} onChange={event => { const next: [string, string] = [...ids]; next[index] = event.target.value; setIds(next); setPair(null); setError(null) }} /></label>)}
      <datalist id="validation-receipts">{receipts.map(receipt => <option key={receipt.receiptId} value={receipt.receiptId}>{receipt.externalOrderId} · {receipt.state}</option>)}</datalist>
      <button type="submit" disabled={busy}>{busy ? '비교 조회 중' : '두 작업 요청 비교하기'}</button>
    </form>
    {error && <p role="alert">{error} · 비교 판정을 표시하지 않습니다.</p>}
    {pair && <div aria-label="작업 요청 비교 결과">
      <p role="status">{!known ? '계약·실행 배포 정보를 모두 확인할 수 없습니다.' : same ? '동일 계약·동일 실행 배포입니다.' : '계약 또는 실행 배포가 다릅니다.'} 완료 여부는 각 저장 결과에서 확인하세요.</p>
      <div className="table-scroll" role="group" aria-label="계약 비교 표 스크롤" tabIndex={0}><table aria-label="계약·배포 비교"><thead><tr><th>비교 항목</th><th>작업 요청 A</th><th>작업 요청 B</th></tr></thead><tbody>{fields.map((field, index) => <tr key={field}><th>{field}</th><td>{left[index] ?? '확인 안 됨'}</td><td>{right[index] ?? '확인 안 됨'}</td></tr>)}</tbody></table></div>
      <div className="validation-results">{pair.map((detail, index) => <article key={detail.receipt.receiptId} aria-label={`작업 요청 ${index === 0 ? 'A' : 'B'} 근거`}>
        <h3>{index === 0 ? 'A' : 'B'} · {detail.receipt.externalOrderId}</h3><p>작업 요청 상태 {detail.receipt.state}</p><p>현재 호스트 조회 {detail.hostRead === 'OK' ? '조회됨' : '모름'}</p>
        {detail.latestResult ? <><p>저장 결과 v{detail.latestResult.version} · {detail.latestResult.recordedAt}</p><StoredResultSummary snapshot={detail.latestResult.snapshot} /><details><summary>저장 결과 원문</summary><pre>{JSON.stringify(detail.latestResult.snapshot, null, 2)}</pre></details></> : <p>저장 결과가 아직 없습니다. 호스트 수락을 완료로 판정하지 않습니다.</p>}
        <a href={`#requests?receipt=${encodeURIComponent(detail.receipt.receiptId)}`}>이 작업 요청의 결과·확인 이력</a>
      </article>)}</div>
      <p>비교 버튼을 누른 시점의 조회입니다. 현장 상태가 달라지면 다시 조회하세요.</p>
    </div>}
  </section>
}
