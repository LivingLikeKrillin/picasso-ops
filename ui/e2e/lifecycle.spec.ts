import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * S1b 완료 판정의 화면 쪽(스펙 §3·§10). 선언 → 보고(CONFIRMED) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록,
 * 그리고 registry 를 멈추면 화면 전체 상태가 «모름» 이고 목록이 직전 값으로 남는 것(스펙 §10 마지막 문단).
 * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
 *
 * 이어서 S1c 의 화면 쪽(스펙 §3). 제품 선언 → 빌드 선언 → 인스턴스 등록 → 인스턴스 목록에 UNTESTED.
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
  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()

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
  await expect(detail.getByText('막힘 없음', { exact: true })).toBeVisible()

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
})
