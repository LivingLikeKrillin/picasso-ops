import type { FaultDetail } from '../api'

/** 결함 원문 하나(S4a JSON 계약 §4). 인시던트 상세와 실행 구역의 결함 감수 폼이 같이 쓴다. */
export function FaultCard({ fault, label }: { fault: FaultDetail; label: string }) {
  return (
    <dl aria-label={label}>
      <dt>분류</dt>
      <dd>
        {fault.failureClass}({fault.errorType})
      </dd>
      <dt>조치 힌트</dt>
      <dd>{fault.errorHint === '' ? '없음' : fault.errorHint}</dd>
      {fault.vendorDetail !== '' && (
        <>
          <dt>벤더 원문</dt>
          <dd>{fault.vendorDetail}</dd>
        </>
      )}
      <dt>참조</dt>
      <dd>{fault.references.map((ref) => `${ref.key}=${ref.value}`).join(', ') || '없음'}</dd>
      <dt>지금 태스크 계속</dt>
      <dd>{fault.canContinueCurrentTask ? '가능' : '불가'}</dd>
      <dt>새 태스크 받기</dt>
      <dd>{fault.canAcceptNewTask ? '가능' : '불가'}</dd>
      <dt>유지</dt>
      <dd>
        {fault.activeUntilKind}
        {fault.activeUntilTime !== '' && ` ${fault.activeUntilTime}`}
      </dd>
    </dl>
  )
}
