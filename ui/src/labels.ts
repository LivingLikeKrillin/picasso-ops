import type {
  CommissioningState,
  Connection,
  HostSubmitResult,
  MockRunFailure,
  MockRunView,
  Owner,
  SkillFit,
  TestRequestState,
} from './api'

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
  JOB_ORDER_BAD_REQUEST: '작업 지시 폼 오류',
  UNKNOWN_WORK_MASTER: '받지 않는 임무',
  UNIT_ID_CONFLICT: '단위 id 겹침',
  NO_ELIGIBLE_ROBOT: '배정 가능한 기체 없음',
  MODE_NOT_ALLOWED: '이 모드에서 할 수 없는 조작',
  ACTOR_REQUIRED: '행위자 없음',
  // 임무 정의 거부 9종(S3b JSON 계약 §3.1). 호스트는 바닥 소유를 쥐지 않아 FLOOR_UNOWNED 를 내지 않지만 이름은 둔다.
  UNREADABLE: '읽을 수 없는 정의',
  DUPLICATE_NODE_ID: '노드 id 겹침',
  DEADLINE_INVALID: '대기 기한 오류',
  SIGNAL_NOT_IN_SPEC: '신호 사양에 없는 신호',
  // 임무 거부(기대 값이 신호 종류에 맞지 않음)와 현장의 신호 쓰기 거부(BOOLEAN 에 틀린 값)가 같은 이름을 쓴다.
  SIGNAL_VALUE_INVALID: '신호 종류에 맞지 않는 값',
  FLOOR_UNOWNED: '바닥 소유 없음',
  SKILL_NOT_IN_CONTRACT: '계약에 없는 스킬',
  SKILL_NOT_ON_SITE: '현장에 없는 스킬',
  SAFETY_SIGNAL_WAIT: '안전 신호 대기',
  // 임무·신호 조작의 사전 거부와 호스트·현장의 4xx(S3b JSON 계약 §10).
  MISSION_BAD_REQUEST: '임무 요청 오류',
  REASON_REQUIRED: '사유 없음',
  DRAFT_NOT_FOUND: '없는 초안',
  REQUEST_ID_REUSED: '요청 id 재사용',
  HOST_SILENT: '실행 호스트 불통',
  SIGNAL_BAD_REQUEST: '신호 값 형식 오류',
  UNKNOWN_SIGNAL: '모르는 신호',
  SAFETY_SIGNAL_READ_ONLY: '안전 신호는 쓸 수 없음',
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

/** 실행 호스트의 스킬 적합(S3a 스펙 §9.1). `UNKNOWN` 은 기체 케이퍼빌리티를 못 물어본 것이다. */
export const SKILL_FIT_LABEL: Record<SkillFit, string> = {
  FIT: '적합',
  MISSING: '모자람',
  UNKNOWN: '모름',
}

/** 작업 지시 제출의 호스트 결과(S3a JSON 계약 §4). */
export const SUBMIT_RESULT_LABEL: Record<HostSubmitResult, string> = {
  ACCEPTED: '배정됨',
  IDEMPOTENT: '같은 작업 지시의 기존 실행',
  REJECTED: '거부됨',
  UNASSIGNED: '미배정',
}

/** 모의 실행 실패의 하위 범주(S3b JSON 계약 §4.5). */
export const MOCK_RUN_FAILURE_LABEL: Record<MockRunFailure, string> = {
  DEFINITION: '정의(같은 신호에 다른 기대 값)',
  SUBMISSION_REJECTED: '표본 작업 지시 거부',
  NOT_SETTLED: '정착하지 않음',
  WALL_CLOCK_LIMIT: '실제 시간 상한 초과',
  EXECUTION_FAILED: '실행 실패',
}

/** 모의 실행의 통과 여부 한 줄. 실패면 하위 범주를 붙인다(S3b JSON 계약 §4.5). */
export function mockRunVerdict(run: MockRunView): string {
  if (run.passed) return '통과'
  const { failure } = run.result
  return failure === null ? '실패' : `실패(${MOCK_RUN_FAILURE_LABEL[failure] ?? failure})`
}

/** 시작용 정의의 화면 이름(S3b JSON 계약 §4.2). 모르는 id 는 호스트가 준 제목을 쓴다. */
export const TEMPLATE_LABEL: Record<string, string> = {
  DATA_V1: '데이터 정의 템플릿',
  ARRIVAL_WAIT: '랙 도착 대기 템플릿',
}
