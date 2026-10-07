import type { Connection, Owner } from './api'

/** 화면에 보이는 이름. 값은 운영 서비스의 열거형 그대로 받고, 이름만 여기서 붙인다. */
export const CONNECTION_LABEL: Record<Connection, string> = {
  FRESH: '신선',
  STALE: '오래됨',
  NO_REPORT: '보고 없음',
}

export const OWNER_LABEL: Record<Owner, string> = {
  SITE: '현장',
  OPERATOR: '운영자',
  ENGINEER: '엔지니어',
  NONE: '없음',
}

/** 막힘(스펙 §7.4 상태 막힘 4종)과 조작 거절(대응표)의 이름. 모르는 종류는 값 그대로 보인다. */
export const KIND_LABEL: Record<string, string> = {
  AWAITING_FIRST_REPORT: '첫 보고 대기',
  REPORT_STALE: '보고 오래됨',
  REPORTING_AFTER_RETIREMENT: '퇴역 뒤 보고',
  UNREGISTERED_ROW: '출처 없는 행',
  BAD_REQUEST: '본문 오류',
  WRONG_DOOR: '이미 다른 문으로 들어온 기체',
  RETIRED_ALREADY: '이미 퇴역한 기체',
  RETIRE_BAD_REQUEST: '퇴역 요청 오류',
  UNKNOWN_ROBOT: '모르는 기체',
  UNCLASSIFIED: '분류되지 않은 거절',
}

export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind
