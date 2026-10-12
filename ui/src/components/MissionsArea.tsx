import { useEffect, useState } from 'react'
import { fetchJobContracts, previewJobOrder } from '../api'
import type { JobContract, MissionOverview, Session } from '../api'
import { MissionDefinitionEditor } from './MissionDefinitionEditor'
import { MissionCreator } from './MissionCreator'
import { MissionInterfaces } from './MissionInterfaces'

const panels = ['미션 목록', '실행 정의', '호출 연결', '버전·배포'] as const
type Panel = typeof panels[number]

export function MissionsArea({ session, onChanged }: { session: Session; onChanged: () => void }) {
  const [creating,setCreating]=useState(false)
  const [panel, setPanel] = useState<Panel>('미션 목록')
  const [contracts, setContracts] = useState<JobContract[] | null>(null)
  const [retry, setRetry] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState('PrepareSequencedRack')
  const [overview, setOverview] = useState<MissionOverview | null>(null)
  useEffect(() => {
    let alive = true
    fetchJobContracts(session).then(value => { if (alive) { setContracts(value.contracts); setError(null) } })
      .catch((e: unknown) => { if (alive) setError(String(e)) })
    return () => { alive = false }
  }, [session, retry])
  const contract = contracts?.find(c => c.workMasterId === selected)
  return <div className="mission-workspace">
    <section className="mission-intro" aria-label="미션 관리 개요">
      <span className="mission-eyebrow">MISSION MANAGEMENT</span>
      <h2>업무 요청을 로봇의 실행으로 연결하세요</h2>
      <p>지원 업무의 입력 계약을 확인하고 실행 정의를 검증합니다. 호출 연결에서 입력이 작업 지시로 변환되는 과정을 확인할 수 있습니다.</p>
      <nav aria-label="미션 관리">{panels.map(name => <button type="button" key={name} aria-current={panel === name ? 'page' : undefined} onClick={() => setPanel(name)}>{name}</button>)}</nav>
      {error && <div><p role="alert">계약 목록을 읽지 못했습니다: {error}</p><button type="button" onClick={() => { setError(null); setRetry(n => n + 1) }}>계약 다시 조회</button></div>}
    </section>
    {creating && <MissionCreator session={session} onClose={()=>setCreating(false)} onCreated={row=>{setCreating(false);setRetry(n=>n+1);setSelected(row.workMasterId);setPanel('실행 정의');onChanged()}} />}
    <div hidden={panel !== '미션 목록'}>
      <section aria-label="지원 미션 목록">
        <div className="mission-section-heading"><div><h2>지원 미션</h2><p>현재 런타임이 지원하는 업무입니다. 편집 가능 범위와 호출 계약을 함께 확인하세요.</p></div>
          {session.mode === 'engineer' && <button className="primary" onClick={()=>setCreating(true)}>새 미션 만들기</button>}
          {session.mode === 'engineer' && <button type="button" className="primary" onClick={() => { setSelected('PrepareSequencedRack'); setPanel('실행 정의') }}>템플릿으로 초안 작성</button>}
        </div>
        {contracts === null ? !error && <p>지원 업무 계약을 조회하고 있습니다.</p> : <div className="mission-catalog">{contracts.map(c => <article className="mission-card" key={c.workMasterId}>
          <span className="mission-eyebrow">{c.editable ? '실행 정의 편집 지원' : '코드 정의 · 읽기 전용'}</span>
          <h3>{c.name}</h3><code>{c.workMasterId}</code><p>{c.description}</p>
          <dl><dt>요구 근거</dt><dd>{c.requiredEvidence}</dd><dt>호출 경로</dt><dd>{c.profile ? '버전별 호출 계약 API' : 'Ops 수동 작업 요청 API'}</dd><dt>활성 정의</dt><dd>{c.editable ? overview === null || overview.workMasterId!==c.workMasterId ? '조회 전 · 호스트 상태 확인 필요' : overview.active.source==='UNPUBLISHED'?'활성 버전 없음':overview.active.version === null ? '코드 정의' : `버전 ${overview.active.version}` : '코드 정의'}</dd></dl>
          <div className="actions"><button type="button" onClick={() => { setSelected(c.workMasterId); setPanel('실행 정의') }}>실행 정의 보기</button><button type="button" onClick={() => { setSelected(c.workMasterId); setPanel('호출 연결') }}>호출 연결 보기</button></div>
        </article>)}</div>}
      </section>
    </div>
    <div hidden={panel !== '호출 연결'}>
      <MissionInterfaces session={session} onChanged={onChanged} contracts={contracts??[]} initialWorkMaster={selected} />
      <section aria-label="호출 인터페이스 연결">
        <h2>호출 인터페이스 연결</h2><p>버전별 입력 매핑을 저장·공개하고 기존 작업 지시 흐름으로 연결합니다. 아래 기본 업무 계약은 원래 Ops API의 입력 규격입니다.</p>
        <label>연결할 업무<select value={selected} onChange={e => setSelected(e.target.value)}>{(contracts ?? []).map(c => <option key={c.workMasterId} value={c.workMasterId}>{c.name}</option>)}</select></label>
        {contract && <ContractPreview key={contract.workMasterId} contract={contract} session={session} />}
      </section>
    </div>
    <div hidden={panel !== '실행 정의' || selected !== 'InspectAsset'}><section><h2>자산 점검</h2><p>InspectAsset은 현재 코드 정의입니다. 이 화면에서는 실행 정의를 편집할 수 없습니다.</p><button type="button" onClick={() => setPanel('호출 연결')}>입력 계약 확인</button></section></div>
    <div hidden={!((panel === '실행 정의' && selected !== 'InspectAsset') || panel === '버전·배포')}>
      {panel === '버전·배포' && <section aria-label="공개와 배포 상태"><h2>버전과 적용 범위</h2><p>아래 이력은 선택한 미션의 실행 정의 활성화 이력입니다. 활성화한 정의는 다음 작업 지시부터 사용하며, 실행 중인 작업은 원래 버전을 유지합니다.</p><p>호출 연결에서 검증된 미션 버전을 배포하고 계약을 공개합니다. 활성화와 외부 API 공개는 별도 절차입니다.</p></section>}
      {selected!=='InspectAsset' && <MissionDefinitionEditor key={selected} workMasterId={selected} session={session} onChanged={onChanged} onOverview={setOverview} onEditRequest={() => setPanel('실행 정의')} sectionMode={panel === '버전·배포' ? 'versions' : 'definition'} />}
    </div>
  </div>
}

function ContractPreview({ contract, session }: { contract: JobContract; session: Session }) {
  const [sample, setSample] = useState(JSON.stringify(contract.sample, null, 2))
  const [result, setResult] = useState<{ input: string; value: unknown } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  async function preview() {
    setError(null); setResult(null)
    let body: unknown
    try { body = JSON.parse(sample) } catch { setError('샘플 요청이 올바른 JSON이 아닙니다.'); return }
    if (!body || typeof body !== 'object' || !('workMasterId' in body) || body.workMasterId !== contract.workMasterId) { setError('샘플의 workMasterId가 선택한 업무와 일치해야 합니다.'); return }
    const input = sample
    setBusy(true)
    try { setResult({ input, value: await previewJobOrder(session, body) }) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }
  return <div className="contract-detail">
    <div className="contract-route"><span>POST</span><code>/api/job-orders</code><strong>운영자 모드에서 제출</strong></div>
    <p>업무 선택: <code>{contract.workMasterId}</code> · {contract.profile ? '등록된 입력 스키마와 변환 규칙을 사용합니다.' : '기본 업무 필드 연결은 코드로 관리합니다.'} 미리보기는 요청을 저장하거나 로봇을 실행하지 않습니다.</p>
    <div className="table-scroll" tabIndex={0} role="group" aria-label="입력 매핑 표 스크롤"><table aria-label="입력 필드 매핑"><thead><tr><th>요청 필드</th><th>자료형·제약</th><th>내부 작업 지시</th></tr></thead><tbody>{contract.fields.map(field => <tr key={field.path}><td><code>{field.path}</code></td><td>{field.constraint}</td><td><code>{field.target}</code></td></tr>)}</tbody></table></div>
    <div className="contract-preview-grid"><div><label className="editor">샘플 요청 JSON<textarea rows={14} spellCheck={false} value={sample} disabled={busy} onChange={e => { setSample(e.target.value); setError(null) }} /></label><button type="button" className="primary" disabled={busy} onClick={preview}>{busy ? '검증 중…' : '입력 검증·변환 미리보기'}</button>{error && <p role="alert">{error}</p>}</div>
      <div className="preview-output"><h3>변환된 작업 지시</h3>{result === null ? <p>샘플 입력을 검증하면 실제 서버의 변환 결과가 여기에 표시됩니다.</p> : result.input !== sample ? <p role="status">입력이 변경되었습니다. 다시 검증해 주세요.</p> : <><p>입력 검증 통과 · 배정·실행·완료를 의미하지 않습니다.</p><pre aria-label="변환된 작업 지시 JSON">{JSON.stringify(result.value, null, 2)}</pre></>}</div>
    </div>
    <details><summary>결과 계약과 외부 공개 범위</summary><p>기존 제출 응답은 jobOrderId와 호스트 수락 결과를 제공합니다. 실행은 /api/executions, 송신 기록은 /api/job-responses?jobOrderId=… 에서 조회합니다. 물리 상태와 근거 충족, 사람 판단, 송신 상태는 각각 확인해야 합니다. 상위 접수의 결과 버전과 수신 확인은 작업 요청 상세에서 확인합니다.</p></details>
  </div>
}
