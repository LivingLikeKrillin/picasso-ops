import { useEffect, useState } from 'react'
import { fetchAdapters, fetchOperations, fetchRobots } from './api'
import type { AdapterListView, OperationRecord, RobotListView, Session } from './api'
import { AREAS } from './areas'
import type { AreaId } from './areas'
import { HistoryArea } from './components/HistoryArea'
import { ModeSwitch } from './components/ModeSwitch'
import { RegistryBanner } from './components/RegistryBanner'
import { RobotsArea } from './components/RobotsArea'

const POLL_MS = 5000

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

export default function App() {
  const [session, setSession] = useState<Session>({ mode: 'engineer', user: 'local' })
  const [area, setArea] = useState<AreaId>('robots')
  const [view, setView] = useState<RobotListView | null>(null)
  const [adapters, setAdapters] = useState<AdapterListView | null>(null)
  const [records, setRecords] = useState<OperationRecord[] | null>(null)
  const [opsError, setOpsError] = useState<string | null>(null)
  // 조작이 끝나면 하나 올린다. 목록을 주기(5초)를 기다리지 않고 다시 읽는다.
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    // 실패해도 직전 값을 지우지 않는다. 대신 opsError 로 직전 값임을 표시한다(스펙 §9).
    const load = () => {
      Promise.all([fetchRobots(session), fetchAdapters(session), fetchOperations(session)])
        .then(([nextView, nextAdapters, nextRecords]) => {
          if (!alive) return
          setView(nextView)
          setAdapters(nextAdapters)
          setRecords(nextRecords)
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
            opsError={opsError}
            session={session}
            onChanged={() => setTick((value) => value + 1)}
          />
        )}
        {current.id === 'history' && <HistoryArea records={records} opsError={opsError} />}
      </main>
    </>
  )
}
