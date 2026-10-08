import { useEffect, useState } from 'react'
import { fetchAdapters, fetchOperations, fetchProfiles, fetchRobots, fetchSiteSettings } from './api'
import type { AdapterListView, OperationRecord, ProfileListView, RobotListView, Session, SiteSettingsView } from './api'
import { AREAS } from './areas'
import type { AreaId } from './areas'
import { HistoryArea } from './components/HistoryArea'
import { MissionsArea } from './components/MissionsArea'
import { ModeSwitch } from './components/ModeSwitch'
import { OperationsArea } from './components/OperationsArea'
import { RegistryBanner } from './components/RegistryBanner'
import { RobotsArea } from './components/RobotsArea'
import { SiteArea } from './components/SiteArea'
import { POLL_MS } from './poll'

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

export default function App() {
  const [session, setSession] = useState<Session>({ mode: 'engineer', user: 'local' })
  const [area, setArea] = useState<AreaId>('robots')
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
    <>
      <header>
        <h1>picasso-ops</h1>
        <nav aria-label="영역">
          {AREAS.map((candidate) => (
            <button
              key={candidate.id}
              aria-current={candidate.id === area ? 'page' : undefined}
              onClick={() => setArea(candidate.id)}
            >
              {candidate.label}
              {!candidate.ready && <small> 다음 단계</small>}
            </button>
          ))}
        </nav>
        <ModeSwitch session={session} onChange={setSession} />
      </header>
      <RegistryBanner view={view} opsError={opsError} />
      <main>
        {!current.ready && <p>이 영역은 다음 단계에서 엽니다.</p>}
        {current.id === 'robots' && (
          <RobotsArea
            view={view}
            adapters={adapters}
            profiles={profiles}
            opsError={opsError}
            session={session}
            onChanged={() => setTick((value) => value + 1)}
          />
        )}
        {current.id === 'site' && (
          <SiteArea
            view={settings}
            opsError={opsError}
            session={session}
            onChanged={() => setTick((value) => value + 1)}
          />
        )}
        {current.id === 'missions' && (
          <MissionsArea session={session} onChanged={() => setTick((value) => value + 1)} />
        )}
        {current.id === 'operations' && (
          <OperationsArea session={session} onChanged={() => setTick((value) => value + 1)} />
        )}
        {current.id === 'history' && <HistoryArea records={records} opsError={opsError} />}
      </main>
    </>
  )
}
