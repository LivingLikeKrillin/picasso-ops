import { useState } from 'react'
import type { AdapterListView, ProfileListView, RobotListView, RobotView } from '../api'
import { FindingCard } from './FindingCard'

type Path = 'existing' | 'model' | 'adapter'
interface Props {
  view: RobotListView | null
  adapters: AdapterListView | null
  profiles: ProfileListView | null
  opsError: string | null
  current: RobotView | null
  onManage: (tab: 'fleet' | 'onboarding' | 'adapters' | 'profiles') => void
  onSelect: (id: string) => void
  onOpenOperations?: () => void
}

/** 서버 관측을 표시한다. 카탈로그 개수로 기체 호환성이나 통신 상태를 추정하지 않는다. */
export function OnboardingGuide({ view, adapters, profiles, opsError, current, onManage, onSelect, onOpenOperations }: Props) {
  const [path, setPath] = useState<Path>('existing')
  const stale = opsError !== null || (view != null && view.robotsAsOf !== view.checkedAt)
  const adapterStale = opsError !== null || (adapters != null && adapters.asOf !== adapters.checkedAt)
  const profileStale = opsError !== null || (profiles != null && profiles.asOf !== profiles.checkedAt)
  const commissioning = current?.commissioning
  const retired = current?.robot.status === 'RETIRED'
  const status = (ok: boolean | undefined) => stale ? '직전 값 · 재확인 필요' : ok == null ? '모름' : ok ? '확인됨' : '준비 필요'
  return (
    <section className="onboarding-guide" aria-label="로봇 도입 준비">
      <p className="eyebrow">ROBOT ONBOARDING</p>
      <h2>연결 준비부터 첫 작업까지</h2>
      <p>도입 상황을 고르고, 필요한 준비를 진행하세요. 현장 작업이 끝나면 기체를 다시 선택해 이어갈 수 있습니다.</p>
      <fieldset className="journey-paths">
        <legend>어떤 로봇을 추가하나요?</legend>
        {([
          ['existing', '기존 유형의 기체 추가', '사용 중인 어댑터와 활성 프로파일을 재사용합니다.'],
          ['model', '새 모델 도입', '기존 어댑터의 지원 범위를 확인하고 모델 프로파일을 시험합니다.'],
          ['adapter', '새 제조사 · 통신 방식 도입', '어댑터 구현·배포 후 제품, 빌드, 인스턴스를 등록합니다.'],
        ] as const).map(([id, title, detail]) => (
          <label key={id} className={path === id ? 'path-card selected' : 'path-card'}>
            <input type="radio" name="onboarding-path" checked={path === id} onChange={() => setPath(id)} />
            <span><strong>{title}</strong><small>{detail}</small></span>
          </label>
        ))}
      </fieldset>
      <div className="journey-prerequisites">
        <section className="card" aria-label="어댑터 준비 안내">
          <h3>01 · 어댑터 준비</h3>
          <p>{path === 'adapter' ? '엔지니어가 통신·제어 어댑터를 구현하고 배포한 뒤 제품 → 빌드 → 현장 인스턴스 순서로 등록하세요. 등록 폼이 어댑터 코드를 생성하거나 배포하지는 않습니다.' : '기존 어댑터가 해당 모델과 프로토콜을 지원하는지 확인하세요. 등록된 빌드와 현장 인스턴스를 함께 확인합니다.'}</p>
          <p className={adapterStale ? 'stale' : undefined}>{adapters?.adapters == null || adapters.instances == null ? '모름: 어댑터 목록 미확인' : `${adapterStale ? '직전 값 · ' : ''}등록 제품 ${adapters.adapters.length}개 · 현장 인스턴스 ${adapters.instances.length}개`}</p>
          <p>등록 여부만으로 통신 정상이나 모델 호환성을 보장하지 않습니다.</p>
          <button type="button" onClick={() => onManage('adapters')}>어댑터 확인·등록</button>
        </section>
        <section className="card" aria-label="프로파일 준비 안내">
          <h3>02 · 모델 프로파일 검증</h3>
          <p>{path === 'existing' ? '재사용할 모델의 활성 리비전을 확인하세요.' : '스킬·제약·현장 참조를 정의한 JSON을 등록하고 시험 요청 → 결과 확인 → 활성화 순서로 진행하세요.'} 활성화할 수 있는지는 서버가 판단합니다.</p>
          <p className={profileStale ? 'stale' : undefined}>{profiles?.revisions == null ? '모름: 프로파일 목록 미확인' : `${profileStale ? '직전 값 · ' : ''}활성 리비전 ${profiles.revisions.filter(row => row.revision.status === 'ACTIVE').length}개`}</p>
          <p>활성 리비전 수는 카탈로그 정보입니다. 선택 기체와의 호환성은 별도로 확인하세요.</p>
          <button type="button" onClick={() => onManage('profiles')}>프로파일 시험·활성화</button>
        </section>
      </div>
      <section className="card" aria-label="기체 준비 현황">
        <h3>03 · 기체 등록과 현장 준비</h3>
        <p>관측 기준: {view?.robotsAsOf ?? '아직 읽지 못함'}</p>
        <label>준비를 이어갈 기체
          <select value={current?.robot.robotId ?? ''} onChange={event => onSelect(event.target.value)}>
            <option value="">아래에서 새 기체를 등록하거나 기존 기체를 선택하세요</option>
            {(view?.robots ?? []).map(row => <option key={row.robot.robotId} value={row.robot.robotId}>{row.robot.displayName ?? row.robot.robotId} · {row.robot.robotId}</option>)}
          </select>
        </label>
        {current == null ? <p>기체를 선택하면 서버가 확인한 준비 상태와 다음 조치를 표시합니다.</p> : (
          <>
            <h4>{current.robot.robotId} 준비 현황{retired ? ' · 퇴역한 기체' : ''}</h4>
            <ol className="readiness-steps">
              <li><strong>기체 확인</strong><span>{status(commissioning?.ledgerConfirmed)}</span><p>현장 담당: 로봇·어댑터 기동과 최초 보고를 확인합니다.</p></li>
              <li><strong>어댑터 · 프로파일 연결</strong><span>{status(commissioning?.bound)}</span><p>엔지니어: 아래 상세의 바인딩에서 빌드와 활성 리비전을 연결합니다.</p></li>
              <li><strong>현장 명칭 준비</strong><span>{status(commissioning?.siteNamesReady)}</span><p>현장 담당이 지도·웨이포인트를 티칭하고 엔지니어가 사실을 기록합니다. 기체 응답과 사람의 기록은 아래 상세에서 따로 확인합니다.</p></li>
            </ol>
            {retired ? <p className="stale">퇴역 기체입니다. 운영자 모드의 상세에서 복귀 여부를 판단하세요.</p> : commissioning == null ? <p>시운전 판정을 아직 받지 못했습니다.</p> : stale ? <p className="stale">직전 관측입니다. 최신 조회 전에는 현재 준비 완료로 판단하지 마세요.</p> : commissioning.state === 'COMPLETE' ? <p>시운전 완료가 확인되었습니다. 운영에서 실제 임무의 배정 가능 여부를 확인하세요.</p> : <p>다음 조치: {!commissioning.ledgerConfirmed ? '현장에서 기체·어댑터의 최초 보고를 확인하세요.' : !commissioning.bound ? '아래 상세에서 빌드와 활성 프로파일을 바인딩하세요.' : '아래 상세의 명칭 상태와 현장 티칭 결과를 확인하세요.'}</p>}
            <a className="detail-jump" href="#robot-detail">기체 연결·현장 명칭 설정으로 이동</a>
            {current.blockers.map(finding => <FindingCard key={finding.kind} finding={finding} />)}
          </>
        )}
      </section>
      <section className="card" aria-label="첫 작업 검증 안내">
        <h3>04 · 임무별 배정 가능 여부 확인</h3>
        <p>시운전 완료와 임무 배정 가능은 별도 판정입니다. 운영에서 임무를 선택해 스킬·연결·현장 조건과 막힘을 확인한 뒤 작업을 요청하세요.</p>
        {onOpenOperations && <button type="button" onClick={onOpenOperations}>운영에서 배정 조건 확인</button>}
      </section>
    </section>
  )
}
