import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * S1b 완료 판정의 화면 쪽(스펙 §3·§10). 선언 → 보고(CONFIRMED) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록,
 * 그리고 registry 를 멈추면 화면 전체 상태가 «모름» 이고 목록이 직전 값으로 남는 것(스펙 §10 마지막 문단).
 * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
 *
 * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
 * 이어서 S1d 의 화면 쪽(P2·S1d 스펙 §3·§11). 리비전 제출(파일 고르기) → 시험 요청 → 현장 실행기가 TESTED → 활성화 →
 * 바인딩 → 명칭 기록 → «시운전 완료»(humanoid-01). quadruped-01 은 명칭을 티칭하지 않아 «기체가 아는 명칭 없음» 으로 막힌다.
 * 이어서 S2 의 화면 쪽(S2 스펙 §3). 현장·자원 영역에서 연결 기준 시간을 바꾸면 버전 2 와 이력 행이 보이고, 운영자 모드는
 * 바꾸지 못하며, 기체 상세가 버전 2 의 기준으로 판정한다.
 * 이어서 S3a 의 화면 쪽(S3a 스펙 §3). 운영자 모드로 «운영» 영역에서 InspectAsset 작업 지시를 내면 시운전을 마친 humanoid-01 에
 * 배정되고 실행 목록에 «코드 정의» 행이 보인다. 실행 호스트는 실제 시각을 쓰고 런처가 가상 시계를 실제 시각까지 따라잡게 민다.
 * 이어서 S3b 의 화면 쪽(S3b 스펙 §3). 엔지니어 모드로 «임무·정책» 영역에서 데이터 정의 템플릿을 불러와 초안 저장 → 검증 → 모의
 * 실행 → 활성화(사유)하면 버전 이력에 «버전 1 (활성)» 이 보인다. 운영 영역의 셀 대역 신호 표에서 rack_present 를 켜면 신호 조작
 * 결과와 신호 값이 보인다.
 * S4b 의 화면 쪽(S4b 스펙 T11)은 운영자 보류 단계 뒤에 송신 기록 구역이 그 작업 지시의 송신 행을 보이는 것 하나다.
 * S3c 의 화면 쪽(S3c 스펙 §3)은 S2 단계 뒤다. 엔지니어 모드로 stallWindow 를 바꾸면 버전 3 이력 행이 보이고 «실행 호스트 반영»
 * 이 버전 3 이 된다.
 * registry 를 멈추는 것은 맨 끝이다. 그 뒤로는 조작이 registry 에 닿지 않는다.
 *
 * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
 * CLAIMED 가 화면에 보이는 시간이 정해지지 않는다. CLAIMED 는 가상 시계를 직접 미는 통합 시험이 본다.
 */
test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 registry 를 멈추면 모름을 본다', async ({ page }) => {
  await page.goto('/')
  const detail = page.getByRole('region', { name: 'humanoid-01 상세' })

  // 엔지니어 모드(기본)에서 선언
  const declare = page.getByRole('form', { name: '기체 선언' })
  await declare.getByLabel('robot_id').fill('humanoid-01')
  await declare.getByLabel('일련번호').fill('HA-0001')
  await declare.getByRole('button', { name: '선언' }).click()
  await expect(page.getByText('humanoid-01 선언: 반영됨', { exact: true })).toBeVisible()

  // 보고를 기다려 CONFIRMED 를 본다
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await expect(detail.getByText('바인딩 없음', { exact: true })).toBeVisible()

  // 운영자 모드에서 사유를 넣어 퇴역
  await page.getByLabel('운영자').check()
  await detail.getByLabel('퇴역 사유').fill('정비')
  await detail.getByRole('button', { name: '퇴역' }).click()
  await expect(detail.getByText('RETIRED', { exact: true })).toBeVisible()

  // 퇴역 뒤에도 기체가 보고하므로 막힘이 뜬다
  await expect(detail.getByText('퇴역 뒤 보고', { exact: true })).toBeVisible()
  await expect(detail.getByText('운영자(화면 안): 현장에서 기체를 내리거나 복귀')).toBeVisible()

  // 복귀
  await detail.getByRole('button', { name: '복귀' }).click()
  await expect(detail.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await expect(detail.getByText('바인딩 없음', { exact: true })).toBeVisible()

  // 조작 기록
  await page.getByRole('button', { name: '이력' }).click()
  await expect(page.getByRole('cell', { name: '운영자/local' })).toHaveCount(2)
  await expect(page.getByRole('cell', { name: '엔지니어/local' })).toHaveCount(1)
  await expect(page.getByRole('cell', { name: '정비' })).toBeVisible()

  // 어댑터: 엔지니어 모드에서 제품 → 빌드 → 인스턴스
  await page.getByRole('button', { name: '로봇·연결' }).click()
  await page.getByLabel('엔지니어').check()
  const product = page.getByRole('form', { name: '제품 선언' })
  await product.getByLabel('vendor').fill('acme')
  await product.getByLabel('name').fill('fleet')
  await product.getByRole('button', { name: '제품 선언' }).click()
  await expect(page.getByText('acme/fleet 제품 선언: 반영됨', { exact: true })).toBeVisible()

  const build = page.getByRole('form', { name: '빌드 선언' })
  await build.getByLabel('제품').selectOption('acme/fleet')
  await build.getByLabel('버전').fill('1.0.0')
  await build.getByLabel('계약 semver').fill('0.9.0')
  await build.getByRole('button', { name: '빌드 선언' }).click()
  await expect(page.getByText('acme/fleet 1.0.0 빌드 선언: 반영됨', { exact: true })).toBeVisible()

  const instance = page.getByRole('form', { name: '인스턴스 등록' })
  await instance.getByLabel('instance_id').fill('fleet-gw-01')
  await instance.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
  await instance.getByRole('button', { name: '인스턴스 등록' }).click()
  await expect(page.getByText('fleet-gw-01 인스턴스 등록: 반영됨', { exact: true })).toBeVisible()
  const instances = page.getByRole('table', { name: '인스턴스 목록' })
  await expect(instances.getByRole('row', { name: /fleet-gw-01/ })).toContainText('UNTESTED')

  // 프로파일: 리비전 둘을 제출하고 시험을 요청하면 현장 실행기가 TESTED 로 올린다. 그 뒤 활성화.
  const declareQuadruped = page.getByRole('form', { name: '기체 선언' })
  await declareQuadruped.getByLabel('robot_id').fill('quadruped-01')
  await declareQuadruped.getByLabel('일련번호').fill('QB-0001')
  await declareQuadruped.getByRole('button', { name: '선언' }).click()
  await expect(page.getByText('quadruped-01 선언: 반영됨', { exact: true })).toBeVisible()

  const profiles = page.getByRole('region', { name: '프로파일' })
  const revisions = profiles.getByRole('table', { name: '리비전 목록' })
  for (const [model, revision] of [['humanoid-a', 2], ['quadruped-b', 1]] as const) {
    const submit = profiles.getByRole('form', { name: '리비전 제출' })
    await submit.getByLabel('프로파일 문서').setInputFiles(fileURLToPath(new URL(`../../picasso/profile/profiles/${model}.json`, import.meta.url)))
    await submit.getByRole('button', { name: '제출' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 제출: 반영됨`, { exact: true })).toBeVisible()
    const row = revisions.getByRole('row', { name: new RegExp(`picasso-ref/${model} ${revision}`) })
    await row.getByRole('button', { name: '시험 요청' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 시험 요청: 반영됨`, { exact: true })).toBeVisible()
    await expect(row).toContainText('TESTED')
    await expect(row).toContainText('PASS (site-runner)')
    await row.getByRole('button', { name: '활성화' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 활성화: 반영됨`, { exact: true })).toBeVisible()
    await expect(row).toContainText('ACTIVE')
  }

  // 바인딩과 명칭 기록. humanoid-01 은 현장에서 명칭을 티칭했으므로 기록하면 시운전 완료다.
  for (const [robotId, model, revision] of [['humanoid-01', 'humanoid-a', 2], ['quadruped-01', 'quadruped-b', 1]] as const) {
    await page.getByRole('button', { name: robotId, exact: true }).click()
    const robot = page.getByRole('region', { name: `${robotId} 상세` })
    const bind = robot.getByRole('form', { name: '바인딩' })
    await bind.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
    await bind.getByLabel('리비전').selectOption(`picasso-ref/${model}#${revision}`)
    await bind.getByRole('button', { name: '바인딩' }).click()
    await expect(page.getByText(`${robotId} 바인딩: 반영됨`, { exact: true })).toBeVisible()
    await expect(robot.getByText('명칭 기록 없음', { exact: true })).toBeVisible()
    await robot.getByRole('button', { name: '명칭 등록 기록' }).click()
    await expect(page.getByText(`${robotId} 명칭 기록: 반영됨`, { exact: true })).toBeVisible()
  }
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(detail.getByRole('heading', { name: '시운전: 완료' })).toBeVisible()
  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()

  // quadruped-01 은 명칭을 티칭하지 않았다. 사람은 기록했는데 기체가 아는 명칭이 없다.
  await page.getByRole('button', { name: 'quadruped-01', exact: true }).click()
  const quadruped = page.getByRole('region', { name: 'quadruped-01 상세' })
  await expect(quadruped.getByRole('heading', { name: '시운전: 미완' })).toBeVisible()
  await expect(quadruped.getByText('기체가 아는 명칭 없음', { exact: true })).toBeVisible()
  await expect(quadruped.getByText(/현장\(화면 밖\): 현장에서 명칭 티칭을 다시/)).toBeVisible()

  // 현장 설정(S2 스펙 §3). 엔지니어 모드에서 연결 기준 시간을 바꾸면 새 버전과 이력 행이 보이고, 기체 상세가 그 버전으로
  // 판정한다. 런처가 30초마다 보고하므로 120초면 기체는 계속 신선하다.
  await page.getByRole('button', { name: '현장·자원' }).click()
  const settings = page.getByRole('region', { name: '현장 설정' })
  const change = settings.getByRole('form', { name: '현장 설정 변경' })
  await change.getByLabel('연결 기준 시간(초)').fill('120')
  await change.getByLabel('변경 사유').fill('연결 기준 늘림')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(page.getByText('연결 기준 시간 120초로 변경: 반영됨', { exact: true })).toBeVisible()
  const versions = settings.getByRole('table', { name: '현장 설정 버전 이력' })
  await expect(versions.getByRole('row', { name: /^2 120초 local 엔지니어 연결 기준 늘림/ })).toBeVisible()
  await page.getByLabel('운영자').check()
  await expect(settings.getByText('현장 설정 변경은 엔지니어 모드에서 합니다', { exact: true })).toBeVisible()
  await page.getByLabel('엔지니어').check()
  await page.getByRole('button', { name: '로봇·연결' }).click()
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(detail.getByText('기준 120초, 현장 설정 버전 2', { exact: true })).toBeVisible()

  // 현장 시간값(S3c 스펙 §3). 엔지니어 모드에서 stallWindow 를 바꾸면 버전 3 과 시간값 열이 붙은 이력 행이 보이고, 실행 호스트가
  // ops 의 현재 버전 뷰를 읽어 적용한 버전이 따로 보인다. 실행 호스트는 운영 서비스보다 먼저 떠 미적용으로 시작하고 운영 서비스가
  // 마이그레이션을 마친 뒤 적용하므로 여기서는 이미 버전 2 다. 화면은 5초마다 다시 읽고 호스트는 1초마다 읽는다.
  const hostApplied = { timeout: 15_000 }
  await page.getByRole('button', { name: '현장·자원' }).click()
  await expect(settings.getByText('실행 호스트 반영: 버전 2', { exact: true })).toBeVisible(hostApplied)
  await change.getByLabel('stallWindow(초)').fill('600')
  await change.getByLabel('변경 사유').fill('정체 표시 늦춤')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(page.getByText('stallWindow 600초로 변경: 반영됨', { exact: true })).toBeVisible()
  await expect(versions.getByRole('row', { name: /^3 120초 local 엔지니어 정체 표시 늦춤 .* 30초 15초 60초 600초$/ })).toBeVisible()
  await expect(versions.getByRole('row', { name: /^2 120초 local 엔지니어 연결 기준 늘림 .* 30초 15초 60초 300초$/ })).toBeVisible()
  await expect(settings.getByText('실행 호스트 반영: 버전 3', { exact: true })).toBeVisible(hostApplied)

  // 운영(S3a 스펙 §3). 시운전 완료는 humanoid-01 하나다. quadruped-01 은 명칭 막힘으로 시운전 미완이라 배정 불가다.
  await page.getByLabel('운영자').check()
  await page.getByRole('button', { name: '운영', exact: true }).click()
  const order = page.getByRole('form', { name: '작업 지시 폼' })
  await order.getByLabel('임무').selectOption('InspectAsset')
  await order.getByLabel('대상 1 id').fill('T1')
  await order.getByLabel('대상 1 장소').fill('bay-7')
  const eligibility = page.getByRole('table', { name: '기체별 배정 가능' })
  await expect(eligibility.getByRole('row', { name: /^humanoid-01 / }).getByRole('cell').nth(5)).toHaveText('가능')
  const quadrupedRow = eligibility.getByRole('row', { name: /^quadruped-01 / })
  await expect(quadrupedRow.getByRole('cell').nth(5)).toHaveText('불가')
  await expect(quadrupedRow).toContainText('시운전이 끝나지 않았다')
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  const notice = page.getByRole('status', { name: '제출 결과' })
  await expect(notice).toContainText('InspectAsset 작업 지시: 배정됨')
  await expect(notice.locator('dt', { hasText: '배정된 기체' }).locator('xpath=following-sibling::dd[1]')).toHaveText('humanoid-01')
  const executions = page.getByRole('table', { name: '실행 목록' })
  const run = executions.getByRole('row').filter({ hasText: 'InspectAsset' })
  await expect(run).toContainText('코드 정의')
  // 이동 20초와 점검 12초(±10%)를 실제 시간으로 돈다.
  await expect(run).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })

  // 임무·정책(S3b 스펙 §3). 엔지니어 모드로 데이터 정의를 초안 저장 → 검증 → 모의 실행 → 활성화한다. 시운전 완료 기체는
  // humanoid-01 하나이고 pick_place 를 가지므로 검증을 지난다. 모의 실행은 실행 호스트 안의 별도 mimic 으로 1~3초 돈다.
  await page.getByLabel('엔지니어').check()
  await page.getByRole('button', { name: '임무·정책' }).click()
  const editor = page.getByRole('region', { name: '임무 편집' })
  const missionNotice = editor.getByRole('status', { name: '임무 조작 결과' })
  await editor.getByRole('button', { name: '데이터 정의 템플릿 불러오기' }).click()
  await editor.getByRole('button', { name: '초안 저장' }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 1 저장됨')
  await editor.getByRole('button', { name: '검증', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 1 검증: 통과')
  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
  await expect(missionNotice).toContainText('초안 1 모의 실행: 통과')
  await editor.getByLabel('활성화 사유').fill('데이터 정의로 옮김')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 1 활성화: 버전 1 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')
  const missionVersions = page.getByRole('table', { name: '임무 버전 이력' })
  await expect(missionVersions.getByRole('row', { name: /^버전 1 \(활성\) 초안 1 local 데이터 정의로 옮김 / })).toBeVisible()

  // 셀 대역 신호(S3b 스펙 §8). 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드에서도 한다.
  await page.getByLabel('운영자').check()
  await page.getByRole('button', { name: '운영', exact: true }).click()
  const signals = page.getByRole('table', { name: '셀 대역 신호' })
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN true / })).toBeVisible()

  // 장애 주입(S4a 스펙 §3). 운영자가 데이터 정의 버전 1 로 슬롯 하나짜리 PrepareSequencedRack 을 내고, pick_place 가 도는 동안
  // 엔지니어가 현장·자원 영역에서 스킬 실패를 넣는다. 슬롯 S01 은 아직 채운 적이 없어 근거가 없으므로 단위가 FAILED 이고
  // GRASP_FAILED 인시던트가 선다. 현장 기체의 pick_place 는 이것이 처음이고 강제한 태스크는 자연 실패 추첨을 하지 않는다.
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S01').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  const rackRuns = executions.getByRole('row').filter({ hasText: 'PrepareSequencedRack' })
  const graspRun = rackRuns.filter({ hasText: 'RACK-204.S01 pick_place' })
  // 작업 지시 직후의 태스크는 ACCEPTED 라 현장이 거부한다. RUNNING 이 보인 뒤에 넣는다. pick_place 는 45초 ±10% 다.
  await expect(graspRun).toContainText('RACK-204.S01 pick_place: RUNNING')
  await page.getByLabel('엔지니어').check()
  await page.getByRole('button', { name: '현장·자원' }).click()
  const faultForm = page.getByRole('form', { name: '장애 주입 폼' })
  await faultForm.getByLabel('기체').selectOption('humanoid-01')
  await faultForm.getByLabel('장애 종류').selectOption('SKILL_EXECUTION_FAILED')
  await faultForm.getByLabel('장애 주입 사유').fill('스킬 실패 시연')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(page.getByRole('status', { name: '장애 주입 결과' })).toHaveText(
    /^humanoid-01 스킬 실패: 받아들임\(태스크 .+#RACK-204\.S01, RETRIABLE\)$/,
  )
  await page.getByRole('button', { name: '운영', exact: true }).click()
  const incidentTable = page.getByRole('table', { name: '인시던트 목록' })
  const grasp = incidentTable.getByRole('row').filter({ hasText: 'GRASP_FAILED' })
  // 현장 설정은 버전 3(stallWindow 변경), 임무는 데이터 정의 버전 1 이다.
  await expect(grasp).toContainText('humanoid-01')
  await expect(grasp.getByRole('cell').nth(7)).toHaveText('버전 3')
  await expect(grasp.getByRole('cell').nth(8)).toHaveText('버전 1')
  await expect(grasp.getByRole('cell').nth(9)).toHaveText('판단 대상 아님')
  await expect(graspRun).toContainText('RACK-204.S01 pick_place: FAILED')

  // 운영자 보류(S4a 스펙 §3). 활성화한 보류 버전은 DB 에 남으므로 맨 끝에 둔다. 런처는 1:1 실시간이고 시계를 밀 수단이 없어
  // 기한 20초를 실제 시간으로 기다린다. 재작업 뒤 대기는 새 기한 20초로 다시 돌므로 다시 돈 것을 본 즉시 신호를 켠다.
  await page.getByRole('button', { name: '임무·정책' }).click()
  await editor.getByRole('button', { name: '운영자 보류 대기 템플릿 불러오기' }).click()
  await editor.getByRole('button', { name: '초안 저장' }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 2 저장됨')
  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
  await expect(missionNotice).toContainText('초안 2 모의 실행: 통과')
  await editor.getByLabel('활성화 사유').fill('운영자 보류 대기 도입')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 2 활성화: 버전 2 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')

  // 셀 대역 신호는 스스로 돌아가지 않는다. 앞에서 켠 rack_present 를 끄지 않으면 대기가 곧바로 끝난다.
  await page.getByLabel('운영자').check()
  await page.getByRole('button', { name: '운영', exact: true }).click()
  await signals.getByRole('button', { name: 'rack_present 끄기' }).click()
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
  // 영역을 옮기면 작업 지시 폼이 처음 값(InspectAsset)으로 돌아온다.
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S02').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  const holdRun = rackRuns.filter({ hasText: 'rack-arrival equipment_wait' })
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING')
  const held = incidentTable.getByRole('row').filter({ hasText: '보류 중' })
  await expect(held).toContainText('SIGNAL_DEADLINE')
  await expect(held.getByRole('cell').nth(8)).toHaveText('버전 2')
  await expect(held.getByRole('cell').nth(9)).toHaveText('미해결')
  await held.getByRole('button', { name: /상세 보기$/ }).click()
  const incidentDetail = page.getByRole('region', { name: '인시던트 상세' })
  await expect(incidentDetail).toContainText('사람의 판단 없음. 아래 값은 모두 관측입니다')
  // 판단 버튼은 운영자 모드에서만 보인다.
  await page.getByLabel('엔지니어').check()
  await expect(incidentDetail.getByText('보류 중입니다. 운영자 판단은 운영자 모드에서 합니다', { exact: true })).toBeVisible()
  await expect(incidentDetail.getByRole('button', { name: '재작업' })).toHaveCount(0)
  await page.getByLabel('운영자').check()
  const decision = incidentDetail.getByRole('form', { name: '운영자 판단' })
  await decision.getByLabel('판단 사유').fill('랙 재배치 뒤 재작업')
  await decision.getByRole('button', { name: '재작업' }).click()
  await expect(page.getByRole('status', { name: '판단 결과' })).toHaveText(/\/rack-arrival 재작업: 판단이 섰습니다\(incident-\d+\)$/)
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING', { timeout: 10_000 })
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
  await expect(holdRun).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
  const asserted = incidentDetail.getByRole('region', { name: '사람의 판단' })
  await expect(asserted).toContainText('사람이 판단함: local, ')
  await expect(asserted).toContainText('결정 재작업(REWORK)')
  const deadline = incidentTable.getByRole('row').filter({ hasText: 'SIGNAL_DEADLINE' })
  await expect(deadline).toHaveCount(1)
  await expect(deadline.getByRole('cell').nth(9)).toHaveText('판단됨')
  await expect(deadline.getByRole('cell').nth(10)).toHaveText('-')

  // 작업 응답 송신 기록(S4b 스펙 T10·T11). 보류 작업 지시가 낸 응답이 송신 행으로 남는다. 재기동하지 않았으므로 재기동 중복은
  // 없다. 재기동 단계는 통합 시험(RestartRecoveryTest)이 본다.
  const holdJobOrder = (await holdRun.getByRole('cell').nth(1).textContent())!.trim()
  const responseLog = page.getByRole('region', { name: '작업 응답 송신 기록' })
  await responseLog.getByLabel('송신 기록의 작업 지시').selectOption(holdJobOrder)
  const sentRows = responseLog.getByRole('table', { name: '송신 기록 목록' }).getByRole('row').filter({ hasText: holdJobOrder })
  // 처분 칸(열째)은 글자 그대로 송신이다. 재기동 중복의 표시(재기동 중복(송신 안 함))도 송신을 품으므로 포함 검사로는 못 가른다.
  await expect(sentRows.filter({ hasText: 'PHYSICALLY_DONE' }).getByRole('cell').nth(9)).toHaveText('송신')
  await expect(sentRows.filter({ hasText: '재기동 중복' })).toHaveCount(0)
  await expect(responseLog).toContainText('그 가운데 재기동 중복 0건')

  // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
  const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
  process.kill(Number(readFileSync(pidFile, 'utf8')))
  await page.getByRole('button', { name: '로봇·연결' }).click()
  await expect(page.getByRole('alert')).toContainText('모름: registry 가 답하지 않습니다')
  await expect(page.getByRole('button', { name: 'humanoid-01', exact: true })).toBeVisible()
  // 기체 목록과 어댑터 목록이 각자 직전 값으로 남는다.
  await expect(page.getByRole('region', { name: '기체 목록' }).getByText(/직전 값입니다/)).toBeVisible()
  const adapters = page.getByRole('region', { name: '어댑터' })
  await expect(adapters.getByText(/직전 값입니다/)).toBeVisible()
  await expect(adapters.getByRole('row', { name: /fleet-gw-01/ })).toBeVisible()
  await expect(profiles.getByText(/직전 값입니다/)).toBeVisible()
})
