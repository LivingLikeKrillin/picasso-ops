import { StationWorkspace } from './components/StationWorkspace'
import { ManufacturingValidation } from './components/ManufacturingValidation'
import { OverviewArea } from './components/OverviewArea'
import { WorkRequests } from './components/WorkRequests'
import { useEffect, useState } from 'react'
import { fetchAdapters, fetchOperations, fetchProfiles, fetchRobots, fetchSiteSettings } from './api'
import type { AdapterListView, OperationRecord, ProfileListView, RobotListView, Session, SiteSettingsView } from './api'
import { AREAS } from './areas'
import type { AreaId } from './areas'
import { FaultPanel } from './components/FaultPanel'
import { HistoryArea } from './components/HistoryArea'
import { MissionsArea } from './components/MissionsArea'
import { ModeSwitch } from './components/ModeSwitch'
import { OperationsArea } from './components/OperationsArea'
import { RegistryBanner } from './components/RegistryBanner'
import { RobotsArea } from './components/RobotsArea'
import { SiteArea } from './components/SiteArea'
import { POLL_MS } from './poll'

const AREA_DESCRIPTIONS: Record<AreaId, string> = {
  stations: '공유 스테이션의 점유와 준비 조건, 임무 실행·완료 근거를 조정합니다.',
  overview: '작업 요청과 로봇 실행의 현재 관측, 확인이 필요한 작업을 살펴봅니다.',
  requests: '외부 주문과 운영자 요청을 검색하고 작업 요청부터 실행 결과까지 추적합니다.',
  validation: '모의 현장에 장애를 주입하고 예외 상황 대응을 검증합니다.',
  site: '현장 설정의 저장 버전과 실행 호스트의 적용 상태를 확인합니다.',
  robots: '기체와 어댑터를 연결하고 운영에 필요한 준비 상태를 확인합니다.',
  missions: '임무를 검증하고 버전별 변경을 운영에 반영합니다.',
  operations: '작업의 진행과 완료 근거를 확인하고 필요한 판단을 기록합니다.',
  history: '누가 무엇을 변경했는지 조작 기록을 확인합니다.',
}

const AREA_ICONS: Record<AreaId, string> = {
  validation: 'M9 3h6M10 3v6L4 20h16L14 9V3M8 15h8',
  site: 'M3 20V8l6-4v16M9 10l6-4v14M15 12h6v8H3M6 9v2m6 0v2m6 2v2',
  robots: 'M8 4h8v3H8zM5 9h14v10H5zM9 13h.01M15 13h.01M9 17h6M12 2v2M2 12v4m20-4v4',
  missions: 'M6 3h12v18H6zM9 7h6M9 12h6M9 17h3',
  stations: 'M4 20h16M6 20V9l6-5 6 5v11M10 20v-5h4v5',
  overview: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  requests: 'M5 3h14v18H5zM8 7h8M8 12h8M8 17h5',
  operations: 'M4 19V5m0 14h16M8 14l4-5 4 3 4-7',
  history: 'M4 7V3m0 4h4M4 7a9 9 0 1 1-1 9M12 7v5l3 2',
}

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

export default function App() {
  const [session, setSession] = useState<Session>({ mode: 'engineer', user: 'local' })
  const routeArea = (): AreaId => {
    const id = window.location.hash.slice(1).split('?')[0]
    return AREAS.some(item => item.id === id) ? id as AreaId : 'robots'
  }
  const [area, updateArea] = useState<AreaId>(routeArea)
  const setArea = (next: AreaId) => { window.location.assign(`#${next}`); updateArea(next) }
  useEffect(() => {
    const changed = () => { const id=window.location.hash.slice(1).split('?')[0]; if(AREAS.some(item=>item.id===id))updateArea(id as AreaId) }
    window.addEventListener('hashchange', changed)
    return () => window.removeEventListener('hashchange', changed)
  }, [])
  const [view, setView] = useState<RobotListView | null>(null)
  const [adapters, setAdapters] = useState<AdapterListView | null>(null)
  const [profiles, setProfiles] = useState<ProfileListView | null>(null)
  const [records, setRecords] = useState<OperationRecord[] | null>(null)
  const [settings, setSettings] = useState<SiteSettingsView | null>(null)
  const [opsError, setOpsError] = useState<string | null>(null)
  // 조작이 끝나면 하나 올린다. 목록을 주기(5초)를 기다리지 않고 다시 읽는다.
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9). 다섯 중 하나라도 못 읽으면 다섯 다
    // 직전 값이다(P2·S1d 스펙 §8.1, S2 스펙 §7). 실행 목록·셀·배정 가능·임무 버전은 실행 호스트를 거치므로 여기 넣지 않는다.
    // 호스트가 멈춰도 다섯이 직전 값이 되지 않게 «운영»·«임무·정책» 영역이 따로 읽는다(S3a 스펙 §9.3).
    const load = () => {
      Promise.all([
        fetchRobots(session),
        fetchAdapters(session),
        fetchProfiles(session),
        fetchOperations(session),
        fetchSiteSettings(session),
      ])
        .then(([nextView, nextAdapters, nextProfiles, nextRecords, nextSettings]) => {
          if (!alive) return
          setView(nextView)
          setAdapters(nextAdapters)
          setProfiles(nextProfiles)
          setRecords(nextRecords)
          setSettings(nextSettings)
          setOpsError(null)
        })
        .catch((error: unknown) => {
          if (alive) setOpsError(message(error))
        })
    }
    load()
    const timer = setInterval(load, POLL_MS)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [session, tick])

  const current = AREAS.find((candidate) => candidate.id === area) ?? AREAS[1]

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content" onClick={event=>{event.preventDefault();const main=document.getElementById('main-content');main?.focus();main?.scrollIntoView()}}>본문으로 이동</a>
      <aside className="sidebar">
        <div className="brand">
          <img src="/favicon.svg" alt="" width="36" height="36" />
          <div><h1>Picasso <span>Ops</span></h1><p>Robot operations</p></div>
        </div>
        <p className="nav-caption">워크스페이스</p>
        <nav aria-label="영역">
          {AREAS.map((candidate) => (
            <button
              key={candidate.id}
              aria-current={candidate.id === area ? 'page' : undefined}
              onClick={() => setArea(candidate.id)}
            >
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={AREA_ICONS[candidate.id]} /></svg>
              {candidate.label}
              {!candidate.ready && <small> 다음 단계</small>}
            </button>
          ))}
        </nav>
        <div className="sidebar-note"><span className="environment-dot" />PoC workspace<p>모의 현장 기반 운영 검증</p></div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">워크스페이스 <span>/</span> <strong>{current.label}</strong></div>
          <ModeSwitch session={session} onChange={setSession} />
        </header>
        <main id="main-content" tabIndex={-1}>
          <div className="page-heading"><div><p className="eyebrow">PICASSO OPERATIONS</p><h2>{current.label}</h2><p>{AREA_DESCRIPTIONS[current.id]}</p></div><span className="environment-badge">모의 현장</span></div>
          <RegistryBanner view={view} opsError={opsError} />
          <div className={`area-content area-${current.id}`}>
            {!current.ready && <p>이 영역은 다음 단계에서 엽니다.</p>}
            <div hidden={current.id !== 'robots'}>
              <RobotsArea
                view={view}
                adapters={adapters}
                profiles={profiles}
                opsError={opsError}
                session={session}
                onChanged={() => setTick((value) => value + 1)}
                onOpenOperations={() => setArea('operations')}
              />
            </div>
            {current.id === 'site' && (
              <>
                <SiteArea
                  view={settings}
                  opsError={opsError}
                  session={session}
                  onChanged={() => setTick((value) => value + 1)}
                />
              </>
            )}
            {current.id === 'overview' && <OverviewArea session={session} />}
            {current.id === 'stations' && <StationWorkspace key={`${session.mode}/${session.user}`} session={session} />}
              {current.id === 'requests' && <WorkRequests session={session} />}
            {current.id === 'validation' && <><ManufacturingValidation session={session} /><FaultPanel robots={view} session={session} onChanged={() => setTick((value) => value + 1)} /></>}
            {current.id === 'missions' && (
              <MissionsArea session={session} onChanged={() => setTick((value) => value + 1)} />
            )}
            {current.id === 'operations' && (
              <OperationsArea session={session} onChanged={() => setTick((value) => value + 1)} />
            )}
            {current.id === 'history' && <HistoryArea records={records} opsError={opsError} />}
          </div>
        </main>
      </div>
    </div>
  )
}
