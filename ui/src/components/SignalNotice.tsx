import type { Delivered, SignalWriteOutcome } from '../api'
import { kindLabel, rejectionText } from '../labels'

interface Props {
  /** 어느 신호를 어떻게 썼는지. 예: `rack_present 켜기` */
  what: string
  sent: Delivered<SignalWriteOutcome>
}

/**
 * 신호 조작의 결과(S3b JSON 계약 §10.8). 현장의 거부(안전 신호, 틀린 값, 모르는 신호)는 200 본문의 `rejection` 이다. 응답 없음은
 * 운영 서비스가 셀 대역을 다시 읽어 값으로 대조한 결과를 보인다. 확인되기 전에는 성공으로도 실패로도 보이지 않는다.
 */
export function SignalNotice({ what, sent }: Props) {
  return (
    <p role="status" aria-label="신호 조작 결과">
      {what}: {describe(sent)}
    </p>
  )
}

function describe(sent: Delivered<SignalWriteOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 셀 대역 신호 표에서 확인하십시오`
  const { result, confirmation, signal, rejection } = sent.outcome
  if (result === 'SUCCEEDED') return `반영됨(값 ${signal?.value ?? sent.outcome.value})`
  if (result === 'REJECTED' && rejection !== null) {
    return rejectionText('현장이 거부함', rejection)
  }
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 그 값임'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 그 값이 아님. 다시 하려면 새로 누르십시오'
    default:
      return '반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 셀 대역 신호 표에서 확인하십시오'
  }
}
