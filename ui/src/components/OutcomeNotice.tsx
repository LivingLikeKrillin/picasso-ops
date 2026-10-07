import type { Sent } from '../api'
import { FindingCard } from './FindingCard'

interface Props {
  /** 어느 대상의 어느 조작인지. 예: `humanoid-01 퇴역`, `acme/fleet 1.0.0 빌드 선언` */
  what: string
  sent: Sent
  onSelect: (robotId: string) => void
}

/**
 * 마지막 조작의 결과. 응답 없음은 거절과 다르다(스펙 §9). 재조회로 확인되기 전에는 성공으로도 실패로도 보이지 않는다.
 * 운영 서비스는 응답 없음 뒤 한 번 다시 읽고 답하므로, 확인 결과가 없으면 그 재조회도 실패한 것이다.
 */
export function OutcomeNotice({ what, sent, onSelect }: Props) {
  if (sent.kind === 'refused') {
    return (
      <p role="status">
        {what}: 보내지 않음. {sent.refusal.detail}
      </p>
    )
  }
  if (sent.kind === 'unknown') {
    return (
      <p role="status">
        {what}: 결과 모름({sent.cause}). 목록을 다시 읽어 확인하십시오
      </p>
    )
  }
  const { outcome } = sent
  if (outcome.result === 'SUCCEEDED') return <p role="status">{what}: 반영됨</p>
  if (outcome.unauthorized) {
    return <p role="status">{what}: registry 가 운영자 토큰을 거절했습니다. 상단의 전체 상태를 보십시오</p>
  }
  if (outcome.result === 'REJECTED' && outcome.rejection !== null) {
    return (
      <div role="status">
        <p>{what}: 거절됨</p>
        <FindingCard finding={outcome.rejection} onSelect={onSelect} />
      </div>
    )
  }
  switch (outcome.confirmation) {
    case 'CONFIRMED_APPLIED':
      return <p role="status">{what}: 응답은 없었으나 다시 읽어 보니 반영됨</p>
    case 'CONFIRMED_NOT_APPLIED':
      return <p role="status">{what}: 응답 없음. 다시 읽어 보니 반영 안 됨. 다시 하려면 새로 요청하십시오</p>
    default:
      return (
        <p role="status">
          {what}: 반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 목록에서 확인하십시오
        </p>
      )
  }
}
