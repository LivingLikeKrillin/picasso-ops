import { declareAdapter, declareBuild, registerInstance } from '../api'
import type { Adapter, AdapterBuild, AdapterListView, Sent, Session } from '../api'
import { BuildForm, InstanceForm, ProductForm } from './AdapterForms'

interface Props {
  view: AdapterListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  busy: boolean
  /** 조작을 보낸다. 결과 알림과 목록 다시 읽기는 부르는 쪽이 한다. */
  run: (what: string, operation: () => Promise<Sent>) => void
}

/**
 * 어댑터 인스턴스와 제품·빌드(스펙 §8 의 로봇·연결 영역 왼쪽 목록). 등록은 엔지니어 모드에서 한다.
 * 적합성은 registry 값 그대로 보인다. S1 은 적합성을 기록하지 않으므로 모두 `UNTESTED` 다(스펙 §5).
 */
export function AdaptersSection({ view, opsError, session, busy, run }: Props) {
  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 칸이 없다. 없는 칸도 모름이다.
  const known = view !== null && view.adapters != null && view.instances != null ? view : null
  const adapters = known?.adapters ?? []

  return (
    <section aria-label="어댑터">
      <h2>어댑터</h2>
      {known !== null ? (
        <AdapterLists view={known} opsError={opsError} />
      ) : (
        <p>모름: 어댑터 목록을 아직 읽지 못했습니다</p>
      )}
      {session.mode === 'engineer' ? (
        <>
          <ProductForm
            busy={busy}
            onDeclare={(vendor, name) =>
              run(`${vendor}/${name} 제품 선언`, () => declareAdapter(session, vendor, name))
            }
          />
          <BuildForm
            adapters={adapters}
            busy={busy}
            onDeclare={(adapter, version, contractSemver) =>
              run(`${adapter.vendor}/${adapter.name} ${version} 빌드 선언`, () =>
                declareBuild(session, adapter.adapterId, version, contractSemver),
              )
            }
          />
          <InstanceForm
            adapters={adapters}
            busy={busy}
            onRegister={(instanceId, adapterVersionId, fleetEndpoint) =>
              run(`${instanceId} 인스턴스 등록`, () =>
                registerInstance(session, instanceId, adapterVersionId, fleetEndpoint),
              )
            }
          />
        </>
      ) : (
        <p>어댑터 등록은 엔지니어 모드에서 합니다</p>
      )}
    </section>
  )
}

function AdapterLists({ view, opsError }: { view: AdapterListView; opsError: string | null }) {
  // 읽은 시각이 확인 시각과 다르거나 운영 서비스에 닿지 않으면 직전 값이다(스펙 §9).
  const stale = view.asOf !== view.checkedAt || opsError !== null
  const instances = view.instances ?? []
  const builds = (view.adapters ?? []).flatMap<{ adapter: Adapter; build: AdapterBuild | null }>((adapter) =>
    adapter.versions.length === 0
      ? [{ adapter, build: null }]
      : adapter.versions.map((build) => ({ adapter, build })),
  )
  return (
    <>
      {stale && <p className="stale">직전 값입니다 ({view.asOf} 기준)</p>}
      <h3>인스턴스</h3>
      {instances.length === 0 ? (
        <p>등록된 인스턴스가 없습니다</p>
      ) : (
        <table aria-label="인스턴스 목록">
          <thead>
            <tr>
              <th>instance_id</th>
              <th>빌드</th>
              <th>계약 semver</th>
              <th>적합성</th>
              <th>플릿 주소</th>
              <th>발견 기체</th>
            </tr>
          </thead>
          <tbody>
            {instances.map((instance) => (
              <tr key={instance.instanceId}>
                <td>{instance.instanceId}</td>
                <td>
                  {instance.adapter} {instance.version}
                </td>
                <td>{instance.contractSemver}</td>
                <td>{instance.conformance}</td>
                <td>{instance.fleetEndpoint ?? '직결'}</td>
                <td>{instance.discoveredRobots}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h3>제품·빌드</h3>
      {builds.length === 0 ? (
        <p>등록된 제품이 없습니다</p>
      ) : (
        <table aria-label="제품·빌드 목록">
          <thead>
            <tr>
              <th>제품</th>
              <th>버전</th>
              <th>계약 semver</th>
              <th>적합성</th>
            </tr>
          </thead>
          <tbody>
            {builds.map(({ adapter, build }) => (
              <tr key={build === null ? `p${adapter.adapterId}` : `b${build.adapterVersionId}`}>
                <td>
                  {adapter.vendor}/{adapter.name}
                </td>
                <td>{build?.version ?? '빌드 없음'}</td>
                <td>{build?.contractSemver ?? ''}</td>
                <td>{build?.conformance ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
