/** 운영 서비스를 다시 읽는 주기. 다섯 조회와 «운영» 영역의 실행 호스트 읽기가 같은 주기를 쓴다. */
export const POLL_MS = 5000

/** 작업 지시 폼이 바뀐 뒤 배정 가능을 묻기까지 기다리는 시간. 그 사이 다시 바뀌면 처음부터 다시 기다린다. */
export const ELIGIBILITY_DEBOUNCE_MS = 300

/**
 * 신호 조작 뒤 셀 대역을 한 번 더 읽기까지 기다리는 시간. 바뀐 값은 실행 호스트의 다음 pump(250ms 주기)부터 보이므로 그보다 길게
 * 둔다(S3b JSON 계약 §5).
 */
export const SIGNAL_SETTLE_MS = 500
