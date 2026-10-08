import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * S1b 완료 판정의 화면 쪽(스펙 §3·§10). 선언 → 보고(CONFIRMED) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록,
 * 그리고 registry 를 멈추면 화면 전체 상태가 «모름» 이고 목록이 직전 값으로 남는 것(스펙 §10 마지막 문단).
 * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
 *
 * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
 * 이어서 S1d 의 화면 쪽(P2·S1d 스펙 §3·§11). 개정판 제출(파일 고르기) → 시험 요청 → 현장 실행기가 TESTED → 활성화 →
 * 바인딩 → 명칭 기록 → «시운전 완료»(humanoid-01). quadruped-01 은 명칭을 티칭하지 않아 «기체가 아는 명칭 없음» 으로 막힌다.
 * 이어서 S2 의 화면 쪽(S2 스펙 §3). 현장·자원 영역에서 연결 기준 시간을 바꾸면 버전 2 와 이력 행이 보이고, 운영자 모드는
 * 바꾸지 못하며, 기체 상세가 버전 2 의 기준으로 판정한다.
 * 이어서 S3a 의 화면 쪽(S3a 스펙 §3). 운영자 모드로 «운영» 영역에서 InspectAsset 작업 지시를 내면 시운전을 마친 humanoid-01 에
 * 배정되고 실행 목록에 «코드 정의» 행이 보인다. 실행 호스트는 실제 시각을 쓰고 런처가 가상 시계를 실제 시각까지 따라잡게 민다.
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

  // 프로파일: 개정판 둘을 제출하고 시험을 요청하면 현장 실행기가 TESTED 로 올린다. 그 뒤 활성화.
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
