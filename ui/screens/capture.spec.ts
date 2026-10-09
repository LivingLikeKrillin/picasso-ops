import { expect, test } from '@playwright/test'
import type { Locator, Page, Route } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 화면 설계서의 스크린숏(docs/screens/img/). 실제 스택 앞에서 e2e/lifecycle.spec.ts 의 흐름을 따라 영역마다 빈 상태, 채운 상태,
 * 거부·발견 카드, 조작 뒤 알림을 찍고, 두 모드가 다른 곳은 둘 다 찍는다. 화면 크기는 설정(playwright.screens.config.ts)이 정한다.
 *
 * 실제로 만들기 어려운 상태는 page.route 로 운영 서비스 응답을 바꿔 찍고 파일 이름 끝에 `-mock` 을 붙인다.
 * - 실행 호스트 재기동(복원 보고, «이전 exec-k», 이전 인스턴스 인시던트, RESTART_DUPLICATE): S4b JSON 계약 H1·H3·H4·H6 의 모양으로,
 *   같은 실행에서 앞서 읽은 실제 응답을 바탕으로 짓는다.
 * - 실행 호스트 불통: 호스트를 거치는 읽기를 503 `HOST_SILENT` 로 바꾼다(S3a·S4a·S4b 계약의 운영 서비스 절).
 * - 운영 서비스 불통: /api 요청을 끊는다.
 * registry 불통은 e2e 처럼 런처를 실제로 꺼서 찍는다(맨 끝).
 *
 * 찍을 때마다 그 화면에 보여야 할 문구를 먼저 단언한다. 그 문구가 INDEX.md 의 «보이는 주요 문구» 칸이 된다.
 */

const OUT = fileURLToPath(new URL('../../docs/screens/img/', import.meta.url))
const OPERATOR_HEADERS = { 'X-Ops-Mode': 'operator', 'X-Ops-User': 'local' }
const ENGINEER_HEADERS = { 'X-Ops-Mode': 'engineer', 'X-Ops-User': 'local' }

type ModeName = '엔지니어' | '운영자' | '공통'

interface Meta {
  area: string
  section: string
  mode: ModeName
  state: string
  /** 만든 방법. 없으면 실제 스택이다. */
  how?: string
  /** 찍기 전에 단언하는 문구. */
  strings: string[]
}

interface Entry extends Meta {
  file: string
  how: string
  bytes: number
}

const entries: Entry[] = []
let sequence = 0

const isPage = (target: Page | Locator): target is Page => 'goto' in target

/** 문구를 단언하고 찍는다. 페이지면 전체 페이지, 로케이터면 그 구역만 찍는다. */
async function shot(target: Page | Locator, slug: string, meta: Meta) {
  const scope = isPage(target) ? target.locator('body') : target
  for (const text of meta.strings) await expect(scope).toContainText(text)
  sequence += 1
  const file = `${String(sequence).padStart(2, '0')}-${slug}.png`
  const where = path.join(OUT, file)
  if (isPage(target)) await target.screenshot({ path: where, fullPage: true, animations: 'disabled' })
  else {
    await target.scrollIntoViewIfNeeded()
    await target.screenshot({ path: where, animations: 'disabled' })
  }
  entries.push({ ...meta, file, how: meta.how ?? '실제 스택', bytes: statSync(where).size })
}

/** 보이는 글자의 첫 줄. 실제 응답에 따라 달라지는 알림 문구를 INDEX 에 적을 때 쓴다. */
async function firstLine(locator: Locator): Promise<string> {
  return (await locator.innerText()).split('\n')[0].trim()
}

const radio = (page: Page, name: '엔지니어' | '운영자') => page.getByRole('radio', { name, exact: true }).check()
const go = (page: Page, name: string) =>
  page.getByRole('navigation', { name: '영역' }).getByRole('button', { name, exact: true }).click()

function writeIndex() {
  const escape = (text: string) => text.replaceAll('|', '\\|').replaceAll('\n', ' ')
  const total = entries.reduce((sum, entry) => sum + entry.bytes, 0)
  const mocked = entries.filter((entry) => entry.file.endsWith('-mock.png')).length
  const lines = [
    '# 화면 스크린숏 목록',
    '',
    '`ui/screens/capture.spec.ts` 가 `npx playwright test -c playwright.screens.config.ts` 한 번으로 찍고 이 목록을 쓴다. 손으로 고치지 않는다.',
    '',
    `- 화면 크기 1440×900, 배율 1, 밝은 색 구성. 파일 ${entries.length}개, 합계 ${(total / 1024).toFixed(0)} KiB, 그 가운데 경로 대역(\`-mock\`) ${mocked}개`,
    '- 번호는 찍은 순서이고 흐름(빈 상태 → 기체·어댑터·프로파일 → 현장 설정·장애 주입 → 작업 지시 → 임무 → 인시던트·판단 → 송신 기록 → 대역 상태 → registry 불통)을 따른다',
    '- 전체 페이지는 `전체`, 구역만 찍은 것은 그 구역(region) 이름이다',
    '- `-mock` 은 page.route 로 운영 서비스 응답을 바꿔 찍은 것이다. 바꾼 엔드포인트와 바탕을 «만든 방법» 에 적는다',
    '- «보이는 주요 문구» 는 찍기 전에 화면에 있다고 단언한 문구다',
    '',
    '| 파일 | 영역 | 구역 | 모드 | 상태 | 만든 방법 | 보이는 주요 문구 |',
    '|---|---|---|---|---|---|---|',
    ...entries.map(
      (entry) =>
        `| \`${entry.file}\` | ${entry.area} | ${entry.section} | ${entry.mode} | ${escape(entry.state)} | ${escape(entry.how)} | ${entry.strings.map((text) => `«${escape(text)}»`).join(', ')} |`,
    ),
    '',
  ]
  writeFileSync(path.join(OUT, 'INDEX.md'), lines.join('\n'), 'utf8')
}

test('화면 설계서 스크린숏을 영역·구역·상태마다 찍는다', async ({ page }) => {
  mkdirSync(OUT, { recursive: true })
  for (const name of readdirSync(OUT)) {
    if (name.endsWith('.png')) rmSync(path.join(OUT, name))
  }

  await page.goto('/')
  await expect(page.getByText(/^registry 응답 확인/)).toBeVisible()

  // ── 빈 상태 ──────────────────────────────────────────────────────────────
  await expect(page.getByText('선언된 기체가 없습니다', { exact: true })).toBeVisible()
  await shot(page, 'robots-overview-empty', {
    area: '로봇·연결',
    section: '전체',
    mode: '엔지니어',
    state: '빈 상태(기체·어댑터·리비전 없음)',
    strings: ['registry 응답 확인', '선언된 기체가 없습니다', '등록된 인스턴스가 없습니다', '제출된 리비전이 없습니다', '기체를 고르면 원장 상태와 연결이 여기에 보입니다.'],
  })
  await radio(page, '운영자')
  await shot(page, 'robots-overview-empty-operator', {
    area: '로봇·연결',
    section: '전체',
    mode: '운영자',
    state: '빈 상태, 운영자 모드(선언·등록 폼 없음)',
    strings: ['선언은 엔지니어 모드에서 합니다', '어댑터 등록은 엔지니어 모드에서 합니다', '프로파일 관리는 엔지니어 모드에서 합니다'],
  })
  await radio(page, '엔지니어')

  await go(page, '현장·자원')
  const settings = page.getByRole('region', { name: '현장 설정' })
  await expect(settings.getByText('실행 호스트 반영: 버전 1', { exact: true })).toBeVisible({ timeout: 15_000 })
  await shot(page, 'site-overview-initial', {
    area: '현장·자원',
    section: '전체',
    mode: '엔지니어',
    state: '처음 상태(현장 설정 버전 1, 기체 없음)',
    strings: ['현재 버전', '실행 호스트 반영: 버전 1', '결과 판정 값', '정체 표시', '장애를 넣을 기체가 없습니다'],
  })

  await go(page, '임무·정책')
  await expect(page.getByText('활성화한 버전이 없습니다. 코드 정의로 돕니다', { exact: true })).toBeVisible()
  await shot(page, 'missions-overview-initial', {
    area: '임무·정책',
    section: '전체',
    mode: '엔지니어',
    state: '처음 상태(코드 정의, 버전·초안 없음)',
    strings: ['PrepareSequencedRack', '코드 정의', '활성화한 버전이 없습니다. 코드 정의로 돕니다', '저장한 초안이 없습니다', '데이터 정의 템플릿 불러오기'],
  })

  await radio(page, '운영자')
  await go(page, '운영')
  await expect(page.getByText('실행이 없습니다', { exact: true })).toBeVisible()
  await expect(page.getByText('송신 기록이 없습니다', { exact: true })).toBeVisible()
  await shot(page, 'operations-overview-empty', {
    area: '운영',
    section: '전체',
    mode: '운영자',
    state: '빈 상태(실행·인시던트·송신 기록 없음)',
    strings: ['폼을 채우면 기체별 배정 가능을 봅니다', '실행이 없습니다', '인시던트가 없습니다', '이전 인스턴스의 인시던트가 없습니다', '송신 기록이 없습니다', 'rack_present'],
  })

  await go(page, '이력')
  await shot(page, 'history-overview-empty', {
    area: '이력',
    section: '전체',
    mode: '공통',
    state: '빈 상태',
    strings: ['조작 기록이 없습니다'],
  })

  // ── 로봇·연결: 선언, 퇴역·복귀 ─────────────────────────────────────────────
  await radio(page, '엔지니어')
  await go(page, '로봇·연결')
  const detailArea = page.getByRole('region', { name: '상세', exact: true })
  const humanoid = page.getByRole('region', { name: 'humanoid-01 상세' })
  const declare = page.getByRole('form', { name: '기체 선언' })
  await declare.getByLabel('robot_id').fill('humanoid-01')
  await declare.getByLabel('일련번호').fill('HA-0001')
  await declare.getByRole('button', { name: '선언' }).click()
  await expect(page.getByText('humanoid-01 선언: 반영됨', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(humanoid.getByText('CONFIRMED', { exact: true })).toBeVisible()
  await shot(detailArea, 'robots-detail-unbound', {
    area: '로봇·연결',
    section: '상세',
    mode: '엔지니어',
    state: '선언 직후 알림, 바인딩 없음 막힘 카드, 시운전 미완',
    strings: ['humanoid-01 선언: 반영됨', 'CONFIRMED', '바인딩 없음', '활성 바인딩 없음', '시운전: 미완', '퇴역과 복귀는 운영자 모드에서 합니다'],
  })

  await radio(page, '운영자')
  await humanoid.getByLabel('퇴역 사유').fill('정비')
  await humanoid.getByRole('button', { name: '퇴역' }).click()
  await expect(humanoid.getByText('퇴역 뒤 보고', { exact: true })).toBeVisible()
  await shot(detailArea, 'robots-detail-retired-blocked', {
    area: '로봇·연결',
    section: '상세',
    mode: '운영자',
    state: '퇴역 뒤 보고 막힘 카드, 복귀 버튼',
    strings: ['humanoid-01 퇴역: 반영됨', 'RETIRED', '퇴역 뒤 보고', '운영자(화면 안): 현장에서 기체를 내리거나 복귀', '복귀'],
  })
  await humanoid.getByRole('button', { name: '복귀' }).click()
  await expect(humanoid.getByText('CONFIRMED', { exact: true })).toBeVisible()

  // ── 어댑터 ──────────────────────────────────────────────────────────────
  await radio(page, '엔지니어')
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
  // 같은 버전을 다른 계약값으로 다시 선언하면 registry 가 거절한다.
  await build.getByLabel('제품').selectOption('acme/fleet')
  await build.getByLabel('버전').fill('1.0.0')
  await build.getByLabel('계약 semver').fill('0.8.0')
  await build.getByRole('button', { name: '빌드 선언' }).click()
  await expect(detailArea.getByText('acme/fleet 1.0.0 빌드 선언: 거절됨', { exact: true })).toBeVisible()
  await shot(detailArea, 'robots-notice-build-rejected', {
    area: '로봇·연결',
    section: '상세',
    mode: '엔지니어',
    state: '조작 거절 알림과 발견 카드(같은 빌드 버전에 다른 계약값)',
    strings: ['acme/fleet 1.0.0 빌드 선언: 거절됨', '관측값과 기대값', '해결 담당'],
  })
  const instance = page.getByRole('form', { name: '인스턴스 등록' })
  await instance.getByLabel('instance_id').fill('fleet-gw-01')
  await instance.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
  await instance.getByRole('button', { name: '인스턴스 등록' }).click()
  await expect(page.getByText('fleet-gw-01 인스턴스 등록: 반영됨', { exact: true })).toBeVisible()
  await shot(page.getByRole('region', { name: '어댑터' }), 'robots-adapters-populated', {
    area: '로봇·연결',
    section: '어댑터',
    mode: '엔지니어',
    state: '제품·빌드·인스턴스 등록 뒤',
    strings: ['fleet-gw-01', 'UNTESTED', 'acme/fleet', '1.0.0'],
  })

  // ── 프로파일 ─────────────────────────────────────────────────────────────
  await declare.getByLabel('robot_id').fill('quadruped-01')
  await declare.getByLabel('일련번호').fill('QB-0001')
  await declare.getByRole('button', { name: '선언' }).click()
  await expect(page.getByText('quadruped-01 선언: 반영됨', { exact: true })).toBeVisible()

  const profiles = page.getByRole('region', { name: '프로파일' })
  const revisions = profiles.getByRole('table', { name: '리비전 목록' })
  for (const [model, revision] of [['humanoid-a', 2], ['quadruped-b', 1]] as const) {
    const submit = profiles.getByRole('form', { name: '리비전 제출' })
    await submit
      .getByLabel('프로파일 문서')
      .setInputFiles(fileURLToPath(new URL(`../../picasso/profile/profiles/${model}.json`, import.meta.url)))
    await submit.getByRole('button', { name: '제출' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 제출: 반영됨`, { exact: true })).toBeVisible()
    const row = revisions.getByRole('row', { name: new RegExp(`picasso-ref/${model} ${revision}`) })
    if (model === 'humanoid-a') {
      // 시험 전에 활성화하면 거절된다.
      await row.getByRole('button', { name: '활성화' }).click()
      await expect(detailArea.getByText(`picasso-ref/${model}#${revision} 활성화: 거절됨`, { exact: true })).toBeVisible()
      await shot(page, 'robots-overview-activation-rejected', {
        area: '로봇·연결',
        section: '전체',
        mode: '엔지니어',
        state: '시험 전 활성화 거절 알림과 발견 카드, 리비전 VALIDATED',
        strings: [`picasso-ref/${model}#${revision} 활성화: 거절됨`, '관측값과 기대값', '요청 없음'],
      })
    }
    await row.getByRole('button', { name: '시험 요청' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 시험 요청: 반영됨`, { exact: true })).toBeVisible()
    await expect(row).toContainText('TESTED')
    await expect(row).toContainText('PASS (site-runner)')
    await row.getByRole('button', { name: '활성화' }).click()
    await expect(page.getByText(`picasso-ref/${model}#${revision} 활성화: 반영됨`, { exact: true })).toBeVisible()
    await expect(row).toContainText('ACTIVE')
  }
  await shot(profiles, 'robots-profiles-populated', {
    area: '로봇·연결',
    section: '프로파일',
    mode: '엔지니어',
    state: '리비전 둘 시험 통과·활성화 뒤',
    strings: ['ACTIVE', 'PASS (site-runner)', 'picasso-ref/humanoid-a', 'picasso-ref/quadruped-b'],
  })

  // ── 바인딩과 명칭 기록 ────────────────────────────────────────────────────
  for (const [robotId, model, revision] of [['humanoid-01', 'humanoid-a', 2], ['quadruped-01', 'quadruped-b', 1]] as const) {
    await page.getByRole('button', { name: robotId, exact: true }).click()
    const robot = page.getByRole('region', { name: `${robotId} 상세` })
    const bind = robot.getByRole('form', { name: '바인딩' })
    await bind.getByLabel('빌드').selectOption('acme/fleet 1.0.0')
    await bind.getByLabel('리비전').selectOption(`picasso-ref/${model}#${revision}`)
    await bind.getByRole('button', { name: '바인딩' }).click()
    await expect(page.getByText(`${robotId} 바인딩: 반영됨`, { exact: true })).toBeVisible()
    await expect(robot.getByText('명칭 기록 없음', { exact: true })).toBeVisible()
    if (robotId === 'humanoid-01') {
      await shot(detailArea, 'robots-detail-bound-unnamed', {
        area: '로봇·연결',
        section: '상세',
        mode: '엔지니어',
        state: '바인딩 뒤 명칭 기록 없음 막힘',
        strings: ['humanoid-01 바인딩: 반영됨', '명칭 기록 없음', '명칭 등록 기록', '요구 키'],
      })
    }
    await robot.getByRole('button', { name: '명칭 등록 기록' }).click()
    await expect(page.getByText(`${robotId} 명칭 기록: 반영됨`, { exact: true })).toBeVisible()
  }
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(humanoid.getByRole('heading', { name: '시운전: 완료' })).toBeVisible()
  await shot(detailArea, 'robots-detail-commissioned', {
    area: '로봇·연결',
    section: '상세',
    mode: '엔지니어',
    state: '시운전 완료, 막힘 없음',
    strings: ['시운전: 완료', '막힘 없음', 'acme/fleet 1.0.0', 'picasso-ref/humanoid-a#2', '[v] 활성 바인딩'],
  })
  await page.getByRole('button', { name: 'quadruped-01', exact: true }).click()
  const quadruped = page.getByRole('region', { name: 'quadruped-01 상세' })
  await expect(quadruped.getByText('기체가 아는 명칭 없음', { exact: true })).toBeVisible()
  await shot(detailArea, 'robots-detail-names-contradicted', {
    area: '로봇·연결',
    section: '상세',
    mode: '엔지니어',
    state: '시운전 미완, 기체가 아는 명칭 없음 막힘 카드',
    strings: ['시운전: 미완', '기체가 아는 명칭 없음', '현장(화면 밖): 현장에서 명칭 티칭을 다시'],
  })
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await shot(page, 'robots-overview-populated', {
    area: '로봇·연결',
    section: '전체',
    mode: '엔지니어',
    state: '기체 둘·어댑터·리비전을 채운 뒤',
    strings: ['humanoid-01', 'quadruped-01', 'fleet-gw-01', '시운전: 완료'],
  })
  await radio(page, '운영자')
  await shot(page, 'robots-overview-populated-operator', {
    area: '로봇·연결',
    section: '전체',
    mode: '운영자',
    state: '채운 상태, 운영자 모드(퇴역 폼, 등록 폼 없음)',
    strings: ['선언은 엔지니어 모드에서 합니다', '퇴역 사유', '프로파일 관리는 엔지니어 모드에서 합니다'],
  })
  await radio(page, '엔지니어')

  // ── 현장·자원: 현장 설정 ───────────────────────────────────────────────────
  await go(page, '현장·자원')
  const change = settings.getByRole('form', { name: '현장 설정 변경' })
  await change.getByLabel('연결 기준 시간(초)').fill('0')
  await change.getByLabel('변경 사유').fill('범위 시험')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(change.getByRole('alert')).toContainText('연결 기준 시간은')
  await shot(change, 'site-settings-out-of-range', {
    area: '현장·자원',
    section: '현장 설정 변경',
    mode: '엔지니어',
    state: '범위 밖 값이라 보내지 않음',
    strings: ['연결 기준 시간은', '초의 정수여야 합니다', '허용 범위'],
  })
  await change.getByLabel('연결 기준 시간(초)').fill('120')
  await change.getByLabel('변경 사유').fill('')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(change.getByRole('alert')).toHaveText('변경 사유를 넣으십시오')
  await shot(change, 'site-settings-reason-missing', {
    area: '현장·자원',
    section: '현장 설정 변경',
    mode: '엔지니어',
    state: '사유 없음이라 보내지 않음',
    strings: ['변경 사유를 넣으십시오'],
  })
  await change.getByLabel('변경 사유').fill('연결 기준 늘림')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(page.getByText('연결 기준 시간 120초로 변경: 반영됨', { exact: true })).toBeVisible()
  await expect(settings.getByText('실행 호스트 반영: 버전 2', { exact: true })).toBeVisible({ timeout: 15_000 })
  await change.getByLabel('stallWindow(초)').fill('600')
  await change.getByLabel('변경 사유').fill('정체 표시 늦춤')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(page.getByText('stallWindow 600초로 변경: 반영됨', { exact: true })).toBeVisible()
  await expect(settings.getByText('실행 호스트 반영: 버전 3', { exact: true })).toBeVisible({ timeout: 15_000 })
  await shot(page, 'site-overview-changed', {
    area: '현장·자원',
    section: '전체',
    mode: '엔지니어',
    state: '변경 반영 알림, 버전 3, 버전 이력 세 줄',
    strings: ['stallWindow 600초로 변경: 반영됨', '실행 호스트 반영: 버전 3', '연결 기준 늘림', '정체 표시 늦춤'],
  })

  // 고치기 시작한 사이 다른 엔지니어가 먼저 바꾸면 기준 버전이 낡아 거절된다.
  await change.getByLabel('stallWindow(초)').fill('300')
  const current = (await (await page.request.get('/api/site-settings', { headers: ENGINEER_HEADERS })).json()) as {
    current: Record<string, number>
  }
  const other = await page.request.put('/api/site-settings', {
    headers: { 'X-Ops-Mode': 'engineer', 'X-Ops-User': 'kim', 'Content-Type': 'application/json' },
    data: {
      baseVersion: current.current.version,
      connectionThresholdSeconds: current.current.connectionThresholdSeconds,
      evidenceBeforeSeconds: current.current.evidenceBeforeSeconds,
      evidenceAfterSeconds: current.current.evidenceAfterSeconds,
      inDoubtGraceSeconds: current.current.inDoubtGraceSeconds,
      stallWindowSeconds: current.current.stallWindowSeconds,
      reason: '다른 화면에서 먼저 기록',
    },
  })
  expect(other.ok()).toBe(true)
  await change.getByLabel('변경 사유').fill('정체 표시 되돌림')
  await change.getByRole('button', { name: '변경' }).click()
  await expect(settings.getByText('stallWindow 300초로 변경: 거절됨', { exact: true })).toBeVisible()
  await shot(settings, 'site-settings-version-conflict', {
    area: '현장·자원',
    section: '현장 설정',
    mode: '엔지니어',
    state: '기준 버전이 낡아 거절된 알림과 발견 카드',
    strings: ['stallWindow 300초로 변경: 거절됨', '관측값과 기대값', '다른 화면에서 먼저 기록'],
  })
  await expect(settings.getByText('실행 호스트 반영: 버전 4', { exact: true })).toBeVisible({ timeout: 15_000 })

  // ── 현장·자원: 장애 주입(진행 중 태스크 없음) ────────────────────────────────
  const faultPanel = page.getByRole('region', { name: '장애 주입' })
  const faultForm = page.getByRole('form', { name: '장애 주입 폼' })
  await faultForm.getByLabel('기체').selectOption('humanoid-01')
  await faultForm.getByLabel('장애 종류').selectOption('SKILL_EXECUTION_FAILED')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(faultForm.getByRole('alert')).toHaveText('장애 주입 사유를 넣으십시오')
  await shot(faultPanel, 'site-fault-reason-missing', {
    area: '현장·자원',
    section: '장애 주입',
    mode: '엔지니어',
    state: '사유 없음이라 보내지 않음',
    strings: ['장애 주입 사유를 넣으십시오', '스킬 실패(진행 중 태스크)'],
  })
  await faultForm.getByLabel('장애 주입 사유').fill('태스크 없이 시도')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  const faultNotice = page.getByRole('status', { name: '장애 주입 결과' })
  await expect(faultNotice).toContainText('humanoid-01 스킬 실패: 현장이 거부함')
  await shot(faultPanel, 'site-fault-rejected', {
    area: '현장·자원',
    section: '장애 주입',
    mode: '엔지니어',
    state: '진행 중 태스크가 없어 현장이 거부',
    strings: ['humanoid-01 스킬 실패: 현장이 거부함', '진행 중 태스크 없음'],
  })
  await faultForm.getByLabel('기체').selectOption('quadruped-01')
  await faultForm.getByLabel('장애 종류').selectOption('CONNECTION')
  await faultForm.locator('label', { hasText: /^연결 상태/ }).locator('select').selectOption('OFFLINE')
  await faultForm.getByLabel('장애 주입 사유').fill('연결 끊김 시연')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(faultNotice).toContainText('quadruped-01 연결 상태 OFFLINE:')
  await shot(faultPanel, 'site-fault-connection-accepted', {
    area: '현장·자원',
    section: '장애 주입',
    mode: '엔지니어',
    state: '연결 상태 장애 받아들임(연결 상태 칸이 더 보임)',
    strings: [await firstLine(faultNotice), '연결 상태'],
  })
  await faultForm.locator('label', { hasText: /^연결 상태/ }).locator('select').selectOption('ONLINE')
  await faultForm.getByLabel('장애 주입 사유').fill('연결 복구')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(faultNotice).toContainText('quadruped-01 연결 상태 ONLINE:')
  await radio(page, '운영자')
  await shot(page, 'site-overview-operator', {
    area: '현장·자원',
    section: '전체',
    mode: '운영자',
    state: '운영자 모드(변경·장애 주입 폼 없음)',
    strings: ['현장 설정 변경은 엔지니어 모드에서 합니다', '장애 주입은 엔지니어 모드에서 합니다', '실행 호스트 반영: 버전 4'],
  })

  // ── 운영: 작업 지시와 배정 가능 ──────────────────────────────────────────────
  await go(page, '운영')
  const orderSection = page.getByRole('region', { name: '작업 지시', exact: true })
  const eligibilitySection = page.getByRole('region', { name: '배정 가능', exact: true })
  const executionSection = page.getByRole('region', { name: '실행', exact: true })
  const order = page.getByRole('form', { name: '작업 지시 폼' })
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  await expect(order.getByRole('alert')).toHaveText('점검 대상마다 대상 id 와 장소 이름을 넣으십시오')
  await shot(page.locator('main > .split').first(), 'operations-order-incomplete', {
    area: '운영',
    section: '작업 지시·배정 가능',
    mode: '운영자',
    state: '덜 채운 폼이라 보내지 않음, 배정 가능 묻지 않음',
    strings: ['점검 대상마다 대상 id 와 장소 이름을 넣으십시오', '폼을 채우면 기체별 배정 가능을 봅니다'],
  })
  await order.getByLabel('임무').selectOption('InspectAsset')
  await order.getByLabel('대상 1 id').fill('T1')
  await order.getByLabel('대상 1 장소').fill('bay-7')
  const eligibility = page.getByRole('table', { name: '기체별 배정 가능' })
  await expect(eligibility.getByRole('row', { name: /^humanoid-01 / }).getByRole('cell').nth(5)).toHaveText('가능')
  await shot(eligibilitySection, 'operations-eligibility-table', {
    area: '운영',
    section: '배정 가능',
    mode: '운영자',
    state: '기체별 배정 가능 판정(가능 하나, 불가 하나)',
    strings: ['판정 시각', '가능', '불가', '시운전이 끝나지 않았다'],
  })
  await radio(page, '엔지니어')
  await shot(page.locator('main > .split').first(), 'operations-order-engineer', {
    area: '운영',
    section: '작업 지시·배정 가능',
    mode: '엔지니어',
    state: '엔지니어 모드(입력·판정은 보이고 제출 버튼 없음)',
    strings: ['작업 지시는 운영자 모드에서 냅니다', '판정 시각'],
  })
  await radio(page, '운영자')
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  const notice = page.getByRole('status', { name: '제출 결과' })
  await expect(notice).toContainText('InspectAsset 작업 지시: 배정됨')
  await shot(orderSection, 'operations-order-assigned', {
    area: '운영',
    section: '작업 지시',
    mode: '운영자',
    state: '제출 결과 배정됨',
    strings: ['InspectAsset 작업 지시: 배정됨', '작업 지시 id', '실행 id', '배정된 기체'],
  })
  // 도는 실행이 있는 동안 하나 더 내면 배정하지 못한다.
  await order.getByLabel('대상 1 id').fill('T2')
  await order.getByLabel('대상 1 장소').fill('dock-3')
  await expect(eligibility.getByRole('row', { name: /^humanoid-01 / })).toContainText('도는 실행이 있다')
  await shot(eligibilitySection, 'operations-eligibility-busy', {
    area: '운영',
    section: '배정 가능',
    mode: '운영자',
    state: '도는 실행이 있어 모두 불가',
    strings: ['도는 실행이 있다', '불가'],
  })
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  await expect(notice).not.toContainText('배정됨')
  await shot(orderSection, 'operations-order-unassigned', {
    area: '운영',
    section: '작업 지시',
    mode: '운영자',
    state: '배정 가능한 기체가 없어 배정하지 못함',
    strings: [await firstLine(notice)],
  })
  const executions = page.getByRole('table', { name: '실행 목록' })
  const run = executions.getByRole('row').filter({ hasText: 'InspectAsset' }).first()
  await expect(run).toContainText('코드 정의')
  await expect(run).toContainText(': RUNNING')
  await shot(executionSection, 'operations-executions-running', {
    area: '운영',
    section: '실행',
    mode: '운영자',
    state: '코드 정의 임무 실행 중',
    strings: ['실행 호스트 인스턴스', '마지막 pump', 'InspectAsset', '코드 정의', ': RUNNING'],
  })
  await expect(run).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })

  // ── 임무·정책 ────────────────────────────────────────────────────────────
  await radio(page, '엔지니어')
  await go(page, '임무·정책')
  const editor = page.getByRole('region', { name: '임무 편집' })
  const missionNotice = editor.getByRole('status', { name: '임무 조작 결과' })
  const textarea = editor.getByLabel('임무 정의 JSON')
  await editor.getByRole('button', { name: '데이터 정의 템플릿 불러오기' }).click()
  const template = await textarea.inputValue()
  expect(template).toContain('"pick_place"')
  await textarea.fill(template.replaceAll('"pick_place"', '"weld_seam"'))
  await editor.getByRole('button', { name: '초안 저장' }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 1 저장됨')
  await editor.getByRole('button', { name: '검증', exact: true }).click()
  await expect(missionNotice).toContainText('초안 1 검증: 거부됨')
  await shot(editor, 'missions-validate-refused', {
    area: '임무·정책',
    section: '임무 편집',
    mode: '엔지니어',
    state: '현장에 없는 스킬로 고친 정의의 검증 거부와 발견 카드',
    strings: ['초안 1 검증: 거부됨', '관측값과 기대값', '해결 담당'],
  })
  await editor.getByRole('button', { name: '데이터 정의 템플릿 불러오기' }).click()
  await editor.getByRole('button', { name: '초안 저장' }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 2 저장됨')
  await editor.getByRole('button', { name: '검증', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 2 검증: 통과')
  await editor.getByLabel('활성화 사유').fill('데이터 정의로 옮김')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toContainText('초안 2 활성화: 막힘')
  await shot(editor, 'missions-activate-mock-run-required', {
    area: '임무·정책',
    section: '임무 편집',
    mode: '엔지니어',
    state: '모의 실행 없이 활성화해 막힘',
    strings: ['초안 2 활성화: 막힘. 이 초안에 통과한 모의 실행이 없습니다. 모의 실행을 먼저 하십시오', '대상: 초안 2, 마지막 모의 실행 없음'],
  })
  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
  await expect(missionNotice).toContainText('초안 2 모의 실행: 통과')
  await shot(editor, 'missions-mock-run-passed', {
    area: '임무·정책',
    section: '임무 편집',
    mode: '엔지니어',
    state: '모의 실행 통과 보고(표본 작업 지시, 단위 표)',
    strings: ['초안 2 모의 실행: 통과', '표본 작업 지시', '가상 경과', '물리 상태'],
  })
  await editor.getByLabel('활성화 사유').fill('')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(editor.getByRole('alert')).toHaveText('활성화 사유를 넣으십시오')
  await shot(editor, 'missions-activate-reason-missing', {
    area: '임무·정책',
    section: '임무 편집',
    mode: '엔지니어',
    state: '사유 없음이라 보내지 않음',
    strings: ['활성화 사유를 넣으십시오'],
  })
  await editor.getByLabel('활성화 사유').fill('데이터 정의로 옮김')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 2 활성화: 버전 1 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')
  const missionVersions = page.getByRole('table', { name: '임무 버전 이력' })
  await expect(missionVersions.getByRole('row', { name: /^버전 1 \(활성\) 초안 2 local 데이터 정의로 옮김 / })).toBeVisible()
  await shot(page, 'missions-overview-active', {
    area: '임무·정책',
    section: '전체',
    mode: '엔지니어',
    state: '활성화 뒤(버전 1 활성, 초안 둘)',
    strings: ['초안 2 활성화: 버전 1 활성화됨. 다음 작업 지시부터 이 버전을 씁니다', '버전 1 (활성)', '활성: 버전 1, 편집기와 같음'],
  })
  const drafts = page.getByRole('region', { name: '초안', exact: true })
  await drafts.getByRole('row', { name: /^초안 2 / }).locator('summary').click()
  await shot(drafts, 'missions-drafts-mock-run-detail', {
    area: '임무·정책',
    section: '초안',
    mode: '엔지니어',
    state: '초안 목록, 마지막 모의 실행 펼침',
    strings: ['초안 1', '초안 2', '통과', '초안 2 열기'],
  })
  await radio(page, '운영자')
  await shot(page, 'missions-overview-operator', {
    area: '임무·정책',
    section: '전체',
    mode: '운영자',
    state: '운영자 모드(편집기 없음, 활성 정의 읽기만)',
    strings: ['임무 편집은 엔지니어 모드에서 합니다', '버전 1 (활성)'],
  })

  // ── 운영: 셀 대역 신호 ────────────────────────────────────────────────────
  await go(page, '운영')
  const cellSection = page.getByRole('region', { name: '셀 대역', exact: true })
  const signals = page.getByRole('table', { name: '셀 대역 신호' })
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN true / })).toBeVisible()
  await shot(cellSection, 'operations-cell-signal-on', {
    area: '운영',
    section: '셀 대역',
    mode: '운영자',
    state: '신호 켜기 반영 알림',
    strings: ['rack_present 켜기: 반영됨(값 true)', '셀 대역: 슬롯은 기체가 보고한 배치로 채웁니다', '제시 자리', '슬롯'],
  })

  // ── 장애 주입 → GRASP_FAILED 인시던트 ─────────────────────────────────────
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S01').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  const rackRuns = executions.getByRole('row').filter({ hasText: 'PrepareSequencedRack' })
  const graspRun = rackRuns.filter({ hasText: 'RACK-204.S01 pick_place' })
  await expect(graspRun).toContainText('RACK-204.S01 pick_place: RUNNING')
  await radio(page, '엔지니어')
  await go(page, '현장·자원')
  await faultForm.getByLabel('기체').selectOption('humanoid-01')
  await faultForm.getByLabel('장애 종류').selectOption('SKILL_EXECUTION_FAILED')
  await faultForm.getByLabel('장애 주입 사유').fill('스킬 실패 시연')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(faultNotice).toHaveText(/^humanoid-01 스킬 실패: 받아들임\(태스크 .+#RACK-204\.S01, RETRIABLE\)$/)
  await shot(faultPanel, 'site-fault-skill-accepted', {
    area: '현장·자원',
    section: '장애 주입',
    mode: '엔지니어',
    state: '진행 중 태스크에 스킬 실패 받아들임',
    strings: [await firstLine(faultNotice)],
  })
  await radio(page, '운영자')
  await go(page, '운영')
  const incidentSection = page.getByRole('region', { name: '인시던트', exact: true })
  const incidentTable = page.getByRole('table', { name: '인시던트 목록' })
  const grasp = incidentTable.getByRole('row').filter({ hasText: 'GRASP_FAILED' })
  await expect(grasp.getByRole('cell').nth(9)).toHaveText('판단 대상 아님')
  await expect(graspRun).toContainText('RACK-204.S01 pick_place: FAILED')
  await grasp.getByRole('button', { name: /상세 보기$/ }).click()
  const incidentDetail = page.getByRole('region', { name: '인시던트 상세' })
  await expect(incidentDetail).toContainText('사람의 판단 없음. 아래 값은 모두 관측입니다')
  await shot(incidentSection, 'operations-incidents-failed', {
    area: '운영',
    section: '인시던트',
    mode: '운영자',
    state: '실패 인시던트(판단 대상 아님)와 상세(관측, 결함, 근거 윈도우)',
    strings: ['GRASP_FAILED', '판단 대상 아님', '관측(봉인 때 기록)', '결함', '근거 윈도우', '버전 4', '버전 1'],
  })
  await shot(executionSection, 'operations-executions-failed', {
    area: '운영',
    section: '실행',
    mode: '운영자',
    state: '데이터 정의 버전 1 실행의 단위 실패',
    strings: ['RACK-204.S01 pick_place: FAILED', '버전 1', 'PHYSICALLY_DONE'],
  })

  // ── 운영자 보류와 판단 ─────────────────────────────────────────────────────
  await radio(page, '엔지니어')
  await go(page, '임무·정책')
  await editor.getByRole('button', { name: '운영자 보류 대기 템플릿 불러오기' }).click()
  await editor.getByRole('button', { name: '초안 저장' }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 3 저장됨')
  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
  await expect(missionNotice).toContainText('초안 3 모의 실행: 통과')
  await editor.getByLabel('활성화 사유').fill('운영자 보류 대기 도입')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 3 활성화: 버전 2 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')

  await radio(page, '운영자')
  await go(page, '운영')
  await signals.getByRole('button', { name: 'rack_present 끄기' }).click()
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S02').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  await order.getByRole('button', { name: '작업 지시 내기' }).click()
  const holdRun = rackRuns.filter({ hasText: 'rack-arrival equipment_wait' })
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING')
  const held = incidentTable.getByRole('row').filter({ hasText: '보류 중' })
  await expect(held).toContainText('SIGNAL_DEADLINE')
  await expect(held.getByRole('cell').nth(9)).toHaveText('미해결')
  await shot(incidentSection, 'operations-incidents-held', {
    area: '운영',
    section: '인시던트',
    mode: '운영자',
    state: '보류 중 인시던트(미해결) 줄 강조',
    strings: ['SIGNAL_DEADLINE', '보류 중', '미해결', 'GRASP_FAILED'],
  })
  await shot(executionSection, 'operations-executions-held', {
    area: '운영',
    section: '실행',
    mode: '운영자',
    state: '설비 대기 기한이 지나 운영자 보류에 선 실행',
    strings: ['OPERATOR_HOLD', 'rack-arrival equipment_wait: OPERATOR_HOLD', '운영자 개입 필요', '버전 2'],
  })
  await held.getByRole('button', { name: /상세 보기$/ }).click()
  await expect(incidentDetail).toContainText('사람의 판단 없음. 아래 값은 모두 관측입니다')
  const decision = incidentDetail.getByRole('form', { name: '운영자 판단' })
  await expect(decision).toBeVisible()
  await shot(incidentDetail, 'operations-incident-detail-held', {
    area: '운영',
    section: '인시던트 상세',
    mode: '운영자',
    state: '보류 중 상세와 운영자 판단 폼',
    strings: ['보류를 판단합니다. 판단자는 local 입니다', '판단 사유', '완료 확인', '재작업', 'SIGNAL_DEADLINE'],
  })
  await radio(page, '엔지니어')
  await expect(incidentDetail.getByText('보류 중입니다. 운영자 판단은 운영자 모드에서 합니다', { exact: true })).toBeVisible()
  await shot(incidentDetail, 'operations-incident-detail-held-engineer', {
    area: '운영',
    section: '인시던트 상세',
    mode: '엔지니어',
    state: '보류 중 상세, 엔지니어 모드라 판단 폼 없음',
    strings: ['보류 중입니다. 운영자 판단은 운영자 모드에서 합니다'],
  })
  await radio(page, '운영자')
  await decision.getByRole('button', { name: '재작업' }).click()
  await expect(decision.getByRole('alert')).toHaveText('판단 사유를 넣으십시오')
  await shot(decision, 'operations-incident-decision-reason-missing', {
    area: '운영',
    section: '운영자 판단',
    mode: '운영자',
    state: '사유 없음이라 보내지 않음',
    strings: ['판단 사유를 넣으십시오'],
  })
  await decision.getByLabel('판단 사유').fill('랙 재배치 뒤 재작업')
  await decision.getByRole('button', { name: '재작업' }).click()
  const decisionNotice = page.getByRole('status', { name: '판단 결과' })
  await expect(decisionNotice).toHaveText(/\/rack-arrival 재작업: 판단이 섰습니다\(incident-\d+\)$/)
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING', { timeout: 10_000 })
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(holdRun).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
  const asserted = incidentDetail.getByRole('region', { name: '사람의 판단' })
  await expect(asserted).toContainText('결정 재작업(REWORK)')
  const deadline = incidentTable.getByRole('row').filter({ hasText: 'SIGNAL_DEADLINE' })
  await expect(deadline.getByRole('cell').nth(9)).toHaveText('판단됨')
  await shot(incidentSection, 'operations-incident-decided', {
    area: '운영',
    section: '인시던트',
    mode: '운영자',
    state: '판단 결과 알림, 판단됨, 사람의 판단 구역',
    strings: [await firstLine(decisionNotice), '판단됨', '사람이 판단함: local, ', '결정 재작업(REWORK)'],
  })

  // ── 운영: 작업 응답 송신 기록 ──────────────────────────────────────────────
  const holdJobOrder = (await holdRun.getByRole('cell').nth(1).textContent())!.trim()
  const responseLog = page.getByRole('region', { name: '작업 응답 송신 기록' })
  await responseLog.getByLabel('송신 기록의 작업 지시').selectOption(holdJobOrder)
  const sentRows = responseLog.getByRole('table', { name: '송신 기록 목록' }).getByRole('row').filter({ hasText: holdJobOrder })
  await expect(sentRows.filter({ hasText: 'PHYSICALLY_DONE' }).getByRole('cell').nth(9)).toHaveText('송신')
  await shot(responseLog, 'operations-job-responses-filtered', {
    area: '운영',
    section: '작업 응답 송신 기록',
    mode: '운영자',
    state: '보류 작업 지시 하나로 고름',
    strings: ['그 가운데 재기동 중복 0건', holdJobOrder, '송신', '필요'],
  })
  await responseLog.getByLabel('송신 기록의 작업 지시').selectOption('')
  await expect(responseLog.getByLabel('송신 기록의 작업 지시')).toHaveValue('')
  await shot(responseLog, 'operations-job-responses-all', {
    area: '운영',
    section: '작업 응답 송신 기록',
    mode: '운영자',
    state: '전체 작업 지시',
    strings: ['송신 기록', '그 가운데 재기동 중복 0건', '송신'],
  })
  await shot(page, 'operations-overview-operator', {
    area: '운영',
    section: '전체',
    mode: '운영자',
    state: '흐름을 돈 뒤 운영 영역 전체',
    strings: ['작업 지시 내기', '실행 호스트 인스턴스', '판단됨', 'rack_present'],
  })
  await radio(page, '엔지니어')
  await shot(page, 'operations-overview-engineer', {
    area: '운영',
    section: '전체',
    mode: '엔지니어',
    state: '흐름을 돈 뒤 운영 영역 전체, 엔지니어 모드',
    strings: ['작업 지시는 운영자 모드에서 냅니다'],
  })

  await go(page, '이력')
  await shot(page, 'history-overview-populated', {
    area: '이력',
    section: '전체',
    mode: '공통',
    state: '흐름을 돈 뒤 조작 기록',
    strings: ['운영자/local', '엔지니어/local', '엔지니어/kim', '정비', '랙 재배치 뒤 재작업', 'REJECTED'],
  })

  // ── 경로 대역: 실행 호스트 재기동 ───────────────────────────────────────────
  // 같은 실행에서 읽은 실제 응답을 바탕으로 S4b JSON 계약 H1·H3·H4·H6 의 모양을 짓는다.
  const realExecutions = (await (await page.request.get('/api/executions', { headers: OPERATOR_HEADERS })).json()) as {
    instanceId: string
    pumpedAt: string
    executions: Record<string, unknown>[]
  }
  const realIncidents = (await (await page.request.get('/api/incidents', { headers: OPERATOR_HEADERS })).json()) as {
    instanceId: string
    total: number
    incidents: Record<string, unknown>[]
  }
  const realResponses = (await (await page.request.get('/api/job-responses', { headers: OPERATOR_HEADERS })).json()) as {
    instanceId: string
    total: number
    responses: Record<string, unknown>[]
  }
  const oldInstance = realExecutions.instanceId
  const newInstance = `mw-${randomUUID()}`
  const at = new Date().toISOString()
  const holdExecution = realExecutions.executions.find((execution) => execution.jobOrderId === holdJobOrder)!
  const inspectExecution = realExecutions.executions.find((execution) => execution.workMasterId === 'InspectAsset')!
  const graspExecution = realExecutions.executions.find(
    (execution) => execution.workMasterId === 'PrepareSequencedRack' && execution.jobOrderId !== holdJobOrder,
  )!
  const holdUnits = holdExecution.units as Record<string, unknown>[]
  const restartedExecutions = {
    instanceId: newInstance,
    pumpedAt: at,
    executions: [
      {
        ...holdExecution,
        executionId: 'exec-1',
        physicalState: 'RUNNING',
        units: holdUnits.map((unit, index) => ({ ...unit, state: index === 0 ? 'RUNNING' : 'PENDING', reached: 'E0' })),
        jobResponse: null,
        restoredFrom: { instanceId: oldInstance, executionId: holdExecution.executionId },
      },
    ],
    restore: {
      at,
      rows: [
        {
          jobOrderId: holdJobOrder,
          robotId: 'humanoid-01',
          previousInstanceId: oldInstance,
          previousExecutionId: holdExecution.executionId,
          result: 'RESTORED',
          executionId: 'exec-1',
          reason: null,
        },
        {
          jobOrderId: inspectExecution.jobOrderId,
          robotId: 'quadruped-01',
          previousInstanceId: oldInstance,
          previousExecutionId: inspectExecution.executionId,
          result: 'DEFERRED',
          executionId: null,
          reason: '기체 스냅숏을 못 읽어 다시 짓지 않는다: robot=quadruped-01, 재작업 횟수와 지난 설비 대기를 정할 근거가 없다',
        },
        {
          jobOrderId: graspExecution.jobOrderId,
          robotId: 'humanoid-01',
          previousInstanceId: oldInstance,
          previousExecutionId: graspExecution.executionId,
          result: 'GAVE_UP',
          executionId: null,
          reason: '임무 버전 행이 없다: PrepareSequencedRack 버전 1',
        },
      ],
    },
  }
  const deadlineRow = realIncidents.incidents.find((row) => row.failureClass === 'SIGNAL_DEADLINE')!
  const restartedIncidents = {
    instanceId: newInstance,
    total: 1,
    incidents: [
      {
        ...deadlineRow,
        incidentId: 'incident-1',
        executionId: 'exec-1',
        at,
        resolution: null,
        unresolved: true,
        held: true,
        confirmedWithoutEvidence: false,
      },
    ],
    earlierTotal: realIncidents.incidents.length,
    earlier: realIncidents.incidents.map((row) => ({ instanceId: oldInstance, ...row, held: false })),
  }
  const earlierDetail = (await (
    await page.request.get(
      `/api/incidents/${encodeURIComponent(String(deadlineRow.incidentId))}?instanceId=${encodeURIComponent(oldInstance)}`,
      { headers: OPERATOR_HEADERS },
    )
  ).json()) as Record<string, unknown>
  const heldResponse =
    realResponses.responses.find((row) => row.jobOrderId === holdJobOrder && row.operatorRequired === true) ??
    realResponses.responses.find((row) => row.jobOrderId === holdJobOrder)!
  const duplicate: Record<string, unknown> = {
    ...heldResponse,
    instanceId: newInstance,
    jobResponseId: 'resp-1',
    executionId: 'exec-1',
    disposition: 'RESTART_DUPLICATE',
    recordedAt: at,
  }
  const restartedResponses = (jobOrderId: string | null) => {
    const rows = [duplicate, ...realResponses.responses].filter(
      (row) => jobOrderId === null || row.jobOrderId === jobOrderId,
    )
    return { instanceId: newInstance, total: rows.length, responses: rows }
  }
  const restartMock = 'GET /api/executions·/api/incidents·/api/incidents/{id}·/api/job-responses, POST /api/job-orders/eligibility'

  await page.route('**/api/executions', (route) => route.fulfill({ json: restartedExecutions }))
  await page.route('**/api/incidents', (route) => route.fulfill({ json: restartedIncidents }))
  await page.route(/\/api\/incidents\/[^/?]+\?instanceId=/, (route) => {
    const url = new URL(route.request().url())
    if (url.searchParams.get('instanceId') === oldInstance) {
      return route.fulfill({
        json: { ...earlierDetail, instanceId: oldInstance, held: false, unitState: null },
      })
    }
    return route.fulfill({
      json: {
        ...earlierDetail,
        instanceId: newInstance,
        incidentId: 'incident-1',
        executionId: 'exec-1',
        held: true,
        unitState: 'RUNNING',
        resolution: null,
        confirmedWithoutEvidence: false,
      },
    })
  })
  await page.route(/\/api\/job-responses(\?.*)?$/, (route) => {
    const jobOrderId = new URL(route.request().url()).searchParams.get('jobOrderId')
    return route.fulfill({ json: restartedResponses(jobOrderId) })
  })
  await page.route('**/api/job-orders/eligibility', async (route: Route) => {
    const response = await route.fetch()
    const body = (await response.json()) as { robots: Record<string, unknown>[] | null }
    const robots = (body.robots ?? []).map((row) =>
      row.robotId === 'quadruped-01' || row.robotId === 'humanoid-01'
        ? {
            ...row,
            host: row.host === null ? null : { ...(row.host as Record<string, unknown>), runningExecutionId: row.robotId === 'humanoid-01' ? 'exec-1' : null },
            eligible: false,
            reasons:
              row.robotId === 'humanoid-01'
                ? ['도는 실행이 있다: exec-1']
                : [...(row.reasons as string[]), `복원 못 한 실행이 있다: ${inspectExecution.jobOrderId}`],
          }
        : row,
    )
    await route.fulfill({ response, json: { ...body, robots } })
  })

  await page.reload()
  await radio(page, '운영자')
  await go(page, '운영')
  const restoreReport = executionSection.getByRole('region', { name: '재기동 복원 보고' })
  await expect(restoreReport).toContainText('재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다')
  await shot(executionSection, 'operations-executions-restored-mock', {
    area: '운영',
    section: '실행',
    mode: '운영자',
    state: '재기동 복원 보고(다시 지음 1, 미룸 1, 포기 1)와 «이전 exec-k» 행',
    how: `경로 대역: GET /api/executions 를 S4b 계약 H1 모양(restore, restoredFrom)으로. 바탕은 같은 실행의 실제 실행 목록, 미룸·포기 행과 사유는 계약 문장으로 지음`,
    strings: [
      '재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다',
      `${holdJobOrder}(humanoid-01): 이전 ${String(holdExecution.executionId)} → exec-1`,
      '미룬 실행 1건. 실행 호스트가 다시 시도하며 그동안 그 기체는 배정에서 빠집니다',
      '포기한 실행 1건. 다시 짓지 않습니다. 기체의 남은 태스크를 운영자가 확인하십시오',
      `exec-1 (이전 ${String(holdExecution.executionId)})`,
    ],
  })
  await expect(incidentSection.getByRole('region', { name: '이전 인스턴스' })).toContainText('재기동 앞 인스턴스의 인시던트')
  await incidentSection
    .getByRole('table', { name: '이전 인스턴스 인시던트 목록' })
    .getByRole('button', { name: `${oldInstance} ${String(deadlineRow.incidentId)} 상세 보기` })
    .click()
  await expect(incidentDetail).toContainText('이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다')
  await shot(incidentSection, 'operations-incidents-earlier-mock', {
    area: '운영',
    section: '인시던트',
    mode: '운영자',
    state: '재기동 뒤 새 인스턴스의 보류와 이전 인스턴스 표, 이전 인스턴스 사본 상세(판단 폼 없음)',
    how: `경로 대역: GET /api/incidents 를 S4b 계약 H3 모양(earlierTotal, earlier)으로, GET /api/incidents/{id}?instanceId= 를 H4 사본 모양으로. 바탕은 같은 실행의 실제 인시던트 목록과 상세`,
    strings: [
      '이전 인스턴스',
      `재기동 앞 인스턴스의 인시던트 ${realIncidents.incidents.length}건 가운데 최신 ${realIncidents.incidents.length}건. 읽기 전용이며 여기서 판단하지 않습니다`,
      `${String(deadlineRow.incidentId)} 상세(이전 인스턴스 ${oldInstance})`,
      '이전 인스턴스의 사본입니다. 읽기 전용이며 판단하지 않습니다',
      '모름(실행 없음)',
    ],
  })
  await shot(responseLog, 'operations-job-responses-duplicate-mock', {
    area: '운영',
    section: '작업 응답 송신 기록',
    mode: '운영자',
    state: '새 인스턴스의 첫 보류 응답이 재기동 중복',
    how: '경로 대역: GET /api/job-responses 를 S4b 계약 H6 모양으로. 바탕은 같은 실행의 실제 송신 기록, 맨 위 RESTART_DUPLICATE 행을 더함',
    strings: ['재기동 중복(송신 안 함)', '그 가운데 재기동 중복 1건', newInstance],
  })
  await order.getByLabel('대상 1 id').fill('T3')
  await order.getByLabel('대상 1 장소').fill('bay-7')
  await expect(eligibility).toContainText('복원 못 한 실행이 있다')
  await shot(eligibilitySection, 'operations-eligibility-restore-blocked-mock', {
    area: '운영',
    section: '배정 가능',
    mode: '운영자',
    state: '복원 못 한 실행이 있는 기체를 배정에서 뺌',
    how: '경로 대역: POST /api/job-orders/eligibility 의 실제 응답에 S4b 계약 H2 이유 문장을 더함',
    strings: [`복원 못 한 실행이 있다: ${String(inspectExecution.jobOrderId)}`, '도는 실행이 있다: exec-1'],
  })
  await shot(page, 'operations-overview-restarted-mock', {
    area: '운영',
    section: '전체',
    mode: '운영자',
    state: '실행 호스트 재기동 뒤 운영 영역 전체',
    how: `경로 대역: ${restartMock}`,
    strings: ['재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다', '재기동 중복(송신 안 함)', '보류 중'],
  })
  await page.unrouteAll({ behavior: 'wait' })

  // ── 경로 대역: 실행 호스트 불통 ─────────────────────────────────────────────
  await page.reload()
  await radio(page, '운영자')
  await go(page, '운영')
  await order.getByLabel('대상 1 id').fill('T3')
  await order.getByLabel('대상 1 장소').fill('bay-7')
  await expect(eligibility).toBeVisible()
  await expect(executions).toBeVisible()
  await expect(responseLog.getByRole('table', { name: '송신 기록 목록' })).toBeVisible()
  const hostSilent = { error: 'HOST_SILENT', detail: '실행 호스트가 답하지 않는다: java.net.ConnectException' }
  const silent = (route: Route) => route.fulfill({ status: 503, json: hostSilent })
  await page.route('**/api/executions', silent)
  await page.route('**/api/cell', silent)
  await page.route('**/api/incidents', silent)
  await page.route(/\/api\/incidents\/.+/, silent)
  await page.route(/\/api\/job-responses(\?.*)?$/, silent)
  await page.route(/\/api\/missions\/.+/, silent)
  await page.route('**/api/job-orders/eligibility', silent)
  await page.route('**/api/site-settings', async (route) => {
    const response = await route.fetch()
    await route.fulfill({ response, json: { ...(await response.json()), hostTimings: null } })
  })
  const hostDownMock =
    '경로 대역: GET /api/executions·/api/cell·/api/incidents·/api/job-responses·/api/missions/*, POST /api/job-orders/eligibility 를 503 HOST_SILENT 로(S3a·S4a·S4b 계약). 앞서 읽은 실제 값이 직전 값으로 남음'
  await expect(executionSection.getByText(/^직전 값입니다\. 실행 호스트 불통/)).toBeVisible()
  await expect(responseLog.getByText(/^직전 값입니다\. 실행 호스트 불통/)).toBeVisible()
  await expect(eligibilitySection.getByText(/^직전 값입니다/)).toBeVisible()
  await shot(page, 'operations-overview-host-down-mock', {
    area: '운영',
    section: '전체',
    mode: '운영자',
    state: '실행 호스트 불통, 구역마다 직전 값',
    how: hostDownMock,
    strings: ['직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: java.net.ConnectException', '직전 값입니다 (운영 서비스 응답 503)', 'registry 응답 확인'],
  })
  await go(page, '임무·정책')
  await expect(page.getByText(/^모름: 임무 버전을 아직 읽지 못했습니다/)).toBeVisible()
  await shot(page, 'missions-overview-host-down-mock', {
    area: '임무·정책',
    section: '전체',
    mode: '운영자',
    state: '실행 호스트 불통에 영역을 처음 열어 모름',
    how: hostDownMock,
    strings: ['모름: 임무 버전을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: java.net.ConnectException)', '모름: 활성 버전을 아직 읽지 못했습니다'],
  })
  await go(page, '현장·자원')
  await expect(settings.getByText('실행 호스트 반영: 모름', { exact: true })).toBeVisible()
  await shot(settings, 'site-settings-host-down-mock', {
    area: '현장·자원',
    section: '현장 설정',
    mode: '운영자',
    state: '실행 호스트 반영 모름',
    how: '경로 대역: GET /api/site-settings 의 실제 응답에서 hostTimings 를 null 로(S3c 계약 §3, 호스트 불통)',
    strings: ['실행 호스트 반영: 모름', '현재 버전'],
  })
  await page.unrouteAll({ behavior: 'wait' })

  // ── 경로 대역: 운영 서비스 불통 ─────────────────────────────────────────────
  await page.reload()
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  await expect(humanoid.getByRole('heading', { name: '시운전: 완료' })).toBeVisible()
  await page.route('**/api/**', (route) => route.abort('connectionrefused'))
  await expect(page.getByRole('alert').filter({ hasText: '모름: 운영 서비스에 닿지 않습니다' })).toBeVisible()
  await shot(page, 'robots-overview-ops-unreachable-mock', {
    area: '로봇·연결',
    section: '전체',
    mode: '엔지니어',
    state: '운영 서비스 불통, 전체 상태 모름과 직전 값',
    how: '경로 대역: /api/** 요청을 끊음(connection refused). 앞서 읽은 실제 값이 직전 값으로 남음',
    strings: ['모름: 운영 서비스에 닿지 않습니다', '직전 값입니다'],
  })
  await page.unrouteAll({ behavior: 'wait' })
  await expect(page.getByText(/^registry 응답 확인/)).toBeVisible()

  // ── registry 불통(실제): 런처를 끈다 ─────────────────────────────────────────
  const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
  process.kill(Number(readFileSync(pidFile, 'utf8')))
  await expect(page.getByRole('alert')).toContainText('모름: registry 가 답하지 않습니다')
  await expect(page.getByRole('region', { name: '기체 목록' }).getByText(/직전 값입니다/)).toBeVisible()
  await shot(page, 'robots-overview-registry-down', {
    area: '로봇·연결',
    section: '전체',
    mode: '엔지니어',
    state: 'registry 불통, 전체 상태 모름과 목록마다 직전 값',
    how: '실제 스택: 런처(registry·mimic 프로세스)를 끔',
    strings: ['모름: registry 가 답하지 않습니다. 해결 담당 엔지니어, registry 상태 확인', '직전 값입니다', 'humanoid-01', 'fleet-gw-01'],
  })
  await radio(page, '운영자')
  await go(page, '운영')
  await order.getByLabel('대상 1 id').fill('T4')
  await order.getByLabel('대상 1 장소').fill('bay-7')
  await expect(eligibility).toBeVisible()
  await shot(eligibilitySection, 'operations-eligibility-registry-down', {
    area: '운영',
    section: '배정 가능',
    mode: '운영자',
    state: 'registry 불통 중의 배정 가능 판정',
    how: '실제 스택: 런처(registry·mimic 프로세스)를 끔',
    strings: [await firstLine(eligibilitySection.locator('p').last()), '불가'],
  })

  writeIndex()
  for (const entry of entries) {
    expect.soft(entry.bytes, `${entry.file} 는 1 MB 아래여야 한다`).toBeLessThan(1024 * 1024)
  }
})
