import type { CommissioningState, Connection, Owner, TestRequestState } from './api'

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
  ADAPTER_BAD_REQUEST: '제품·빌드 형식 오류',
  UNKNOWN_ADAPTER: '없는 제품',
  VERSION_CONFLICT: '같은 버전에 다른 계약값',
  INSTANCE_BAD_REQUEST: '인스턴스 본문 오류',
  UNBOUND: '바인딩 없음',
  SITE_NAMES_UNREGISTERED: '명칭 기록 없음',
  SITE_NAMES_UNANSWERED: '기체가 명칭에 답하지 않음',
  SITE_NAMES_CONTRADICTED: '기체가 아는 명칭 없음',
  PROFILE_UNREADABLE: '읽을 수 없는 문서',
  REVISION_NOT_MONOTONIC: '리비전 번호가 오르지 않음',
  UNKNOWN_REVISION: '없는 리비전',
  REVISION_NOT_TESTABLE: '시험할 수 없는 상태',
  ACTIVATION_REFUSED: '활성화 조건 미달',
  UNKNOWN_BUILD: '없는 빌드',
  REVISION_NOT_ACTIVE: '활성 리비전이 아님',
  ROBOT_RETIRED: '퇴역한 기체',
  CONTRACT_TOO_OLD: '빌드의 계약이 낮음',
  NO_ACTIVE_BINDING: '활성 바인딩 없음',
  NOTHING_TO_REGISTER: '등록할 명칭 없음',
  UNCLASSIFIED: '분류되지 않은 거부',
  SETTINGS_VERSION_CONFLICT: '현장 설정 버전 충돌',
}

/** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
export const COMMISSIONING_LABEL: Record<CommissioningState, string> = {
  COMPLETE: '완료',
  INCOMPLETE: '미완',
  RETIRED: '퇴역',
}

/** 소프트웨어 대조(P2·S1d 스펙 §9). 시운전을 막지 않고 보이기만 한다. 모르는 값은 그대로 보인다. */
export const SOFTWARE_LABEL: Record<string, string> = {
  MATCH: '일치',
  MISMATCH: '불일치',
  UNREPORTED: '보고 없음',
}

/** 시험 요청 상태(P2·S1d 스펙 §9). */
export const TEST_REQUEST_LABEL: Record<TestRequestState, string> = {
  NONE: '요청 없음',
  WAITING: '대기',
  RUNNING: '실행 중',
  EXPIRED: '만료',
  DONE: '끝남',
}

export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind
