import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 화면 설계서의 대표 스크린숏 21장(docs/screens/img/). 실제 스택 앞에서 e2e/lifecycle.spec.ts·station.spec.ts 의 흐름을 따라
 * 한 번 돌며 찍는다. 기체·어댑터·프로파일 준비는 station.spec 처럼 운영 서비스 API 로 하고, 화면에 보이는 상태는 화면 조작으로 만든다.
 * 그 밖의 상태는 화면 설계서 본문이 글로 설명한다.
 *
 * 순서가 중요하다. 공유 자원은 InspectAsset 에 필수 자원을 불변 등록하므로 그 앞의 InspectAsset 작업 요청이 모두 끝난 뒤에 두고,
 * registry 불통(런처 종료)은 맨 끝이다. 실행 호스트 재기동의 복원 보고는 실제로 만들기 어려워 page.route 로 실행 목록 응답만 바꿔
 * 찍고 파일 이름 끝에 `-mock` 을 붙인다.
 *
 * 찍기 전에 그 화면에 보여야 할 문구를 단언한다(보이는 글자 기준). 틀린 상태면 찍지 않고 실패한다. 그 문구가 INDEX.md 의 «단언 문구» 다.
 */

const OUT = fileURLToPath(new URL('../../docs/screens/img/', import.meta.url))
const ENGINEER = { 'X-Ops-Mode': 'engineer', 'X-Ops-User': 'local' }
const OPERATOR = { 'X-Ops-Mode': 'operator', 'X-Ops-User': 'local' }
const MES = { Authorization: 'Bearer picasso-e2e-mock-mes-only' }
const STYLE = '.skip-link { visibility:hidden!important; }'
const DESKTOP = { width: 1440, height: 900 }
const MOBILE = { width: 390, height: 844 }

type Mode = '엔지니어' | '운영자'

interface Meta {
  area: string
  mode: Mode
  state: string
  /** 만든 방법. 없으면 실제 스택. */
  how?: string
  /** 찍기 전에 화면에 보인다고 단언하는 문구. */
  strings: string[]
}

interface Entry extends Meta {
  file: string
  how: string
  shape: string
}

const entries: Entry[] = []

/**
 * 문구를 단언하고 찍는다. `target` 이 'page' 면 전체 페이지(fullPage), 로케이터면 그 구역만 찍는다. 단언은 보이는 글자(innerText)로
 * 하므로 감춘 영역(로봇 영역은 늘 마운트돼 있다)의 글자로 통과하지 않는다.
 */
async function shot(page: Page, target: Locator | 'page', file: string, meta: Meta) {
  const scope = target === 'page' ? page.locator('main') : target
  for (const text of meta.strings) await expect(scope).toContainText(text, { useInnerText: true })
  await page.evaluate(async () => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
    window.scrollTo(0, 0)
    await document.fonts.ready
  })
  const where = path.join(OUT, file)
  if (target === 'page') await fullPageShot(page, where)
  else {
    await target.scrollIntoViewIfNeeded()
    await target.screenshot({ path: where, animations: 'disabled', style: STYLE })
  }
  const viewport = page.viewportSize()!
  const shape = `${viewport.width}px · ${target === 'page' ? '전체 페이지' : '구역'}`
  expect(statSync(where).size).toBeGreaterThan(0)
  entries.push({ ...meta, file, how: meta.how ?? '실제 스택', shape })
}

/**
 * 전체 페이지 촬영. 사이드바가 화면 높이(100vh)에 고정돼 있어 fullPage 로만 찍으면 첫 화면 높이 아래가 비므로, 찍기 직전에 화면 높이를
 * 문서 높이로 늘리고(폭은 그대로) 한 프레임 기다린 뒤 찍고 되돌린다.
 */
async function fullPageShot(page: Page, where: string) {
  const original = page.viewportSize()!
  const frame = () => page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  let height = await page.evaluate(() => document.documentElement.scrollHeight)
  for (let i = 0; i < 3; i += 1) {
    await page.setViewportSize({ width: original.width, height })
    await frame()
    const next = await page.evaluate(() => document.documentElement.scrollHeight)
    if (next === height) break
    height = next
  }
  await page.screenshot({ path: where, fullPage: true, animations: 'disabled', style: STYLE })
  await page.setViewportSize(original)
  await frame()
}

async function mobile(page: Page) {
  await page.setViewportSize(MOBILE)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}

const radio = (page: Page, name: Mode) => page.getByRole('radio', { name, exact: true }).check()
const go = (page: Page, name: string) =>
  page.getByRole('navigation', { name: '영역' }).getByRole('button', { name, exact: true }).click()

/**
 * INDEX.md 에는 실행마다 달라지는 값(작업 지시 id, UUID, 실행 호스트 인스턴스, 앞 인스턴스의 실행 id, 시각)을 자리표시로 바꿔 적는다.
 * 단언은 실제 값으로 하고, 목록은 다시 찍어도 바뀌지 않게 한다.
 */
const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
const stable = (text: string) =>
  text
    .replace(new RegExp(`JO-${UUID}`, 'g'), '<작업 지시 id>')
    .replace(new RegExp(`mw-${UUID}`, 'g'), '<실행 호스트 인스턴스>')
    .replace(new RegExp(UUID, 'g'), '<UUID>')
    .replace(/이전 exec-\d+/g, '이전 <실행 id>')
    .replace(/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}(:?\d{2})?)?/g, '<시각>')

function writeIndex() {
  const cell = (text: string) => stable(text).replaceAll('|', '\\|').replaceAll('\n', ' ')
  const rows = [...entries].sort((a, b) => a.file.localeCompare(b.file))
  const lines = [
    '# 화면 스크린숏 목록',
    '',
    '`ui/screens/capture.spec.ts` 가 `npx playwright test -c playwright.screens.config.ts` 한 번으로 찍고 이 목록을 쓴다. 손으로 고치지 않는다.',
    '',
    `- 대표 ${rows.length}장. 그 밖의 상태는 화면 설계서 본문에 글로 적는다`,
    '- 1440×900(모바일은 390×844), 배율 1, 밝은 색 구성',
    '- 방법: 실제 스택 = 로컬 전체 스택(Postgres, 런처, 실행 호스트, 운영 서비스, Mock MES 콜백, host-link 프록시). 대역 = page.route 로 운영 서비스 응답을 바꿈(파일 이름 끝 `-mock`)',
    '- 단언 문구: 찍기 전에 화면에 보인다고 단언한 문구',
    '',
    '| 파일 | 영역 | 모드 | 상태 | 크기·범위 | 방법 | 단언 문구 |',
    '|---|---|---|---|---|---|---|',
    ...rows.map(
      (e) =>
        `| \`${e.file}\` | ${e.area} | ${e.mode} | ${cell(e.state)} | ${e.shape} | ${cell(e.how)} | ${e.strings.map((s) => `«${cell(s)}»`).join(', ')} |`,
    ),
    '',
  ]
  writeFileSync(path.join(OUT, 'INDEX.md'), lines.join('\n'), 'utf8')
}

test('화면 설계서 대표 스크린숏을 찍는다', async ({ page }) => {
  mkdirSync(OUT, { recursive: true })
  for (const name of readdirSync(OUT)) if (/^\d{2}-.+\.png$/.test(name)) rmSync(path.join(OUT, name))

  async function get<T = any>(url: string, headers: Record<string, string> = OPERATOR): Promise<T> {
    const response = await page.request.get(url, { headers })
    expect(response.status(), `${url}: ${await response.text()}`).toBe(200)
    return response.json() as Promise<T>
  }
  async function post(url: string, body: unknown, headers: Record<string, string> = ENGINEER) {
    const response = await page.request.post(url, { headers, data: body })
    expect(response.status(), `${url}: ${await response.text()}`).toBe(200)
    return response.json()
  }
  const signal = (name: string, value: string) => post(`/api/cell/signals/${name}`, { value }, OPERATOR)
  const physical = async (receiptId: string) =>
    (await get(`/api/work-requests/${receiptId}`)).latestResult?.snapshot?.jobResponse?.physicalState ?? null
  const waitDone = (receiptId: string) => expect.poll(() => physical(receiptId), { timeout: 150_000 }).toBe('PHYSICALLY_DONE')
  const stationRun = async (id: string) => (await get(`/api/stations/runs/${id}`)).run

  await page.goto('/')

  // ── 준비(API): 기체 둘, 어댑터 빌드, 프로파일 리비전 둘, 바인딩, 명칭 기록. humanoid-01 만 시운전 완료가 된다 ──────
  await post('/api/robots', { robotId: 'humanoid-01', serialNumber: 'HA-0001' })
  await post('/api/robots', { robotId: 'quadruped-01', serialNumber: 'QB-0001' })
  await post('/api/adapters', { vendor: 'acme', name: 'fleet' })
  const adapterId = (await get('/api/adapters')).adapters.find((row: { name: string }) => row.name === 'fleet').adapterId
  await post(`/api/adapters/${adapterId}/versions`, { version: '1.0.0', contractSemver: '0.9.0' })
  const build = (await get('/api/adapters')).adapters.find((row: { adapterId: number }) => row.adapterId === adapterId).versions[0]
    .adapterVersionId
  for (const [robotId, model] of [['humanoid-01', 'humanoid-a'], ['quadruped-01', 'quadruped-b']] as const) {
    const profile = JSON.parse(readFileSync(fileURLToPath(new URL(`../../picasso/profile/profiles/${model}.json`, import.meta.url)), 'utf8'))
    await post('/api/profile-revisions', profile)
    type Row = { revision: { model: string; profileRevisionId: number; status: string } }
    const ids = (await get('/api/profiles')).revisions.filter((row: Row) => row.revision.model === model).map((row: Row) => row.revision.profileRevisionId)
    const revision = Math.max(...ids)
    await post(`/api/profile-revisions/${revision}/test-requests`, undefined)
    await expect
      .poll(async () => (await get('/api/profiles')).revisions.find((row: Row) => row.revision.profileRevisionId === revision).revision.status)
      .toBe('TESTED')
    await post(`/api/profile-revisions/${revision}/activation`, undefined)
    await post(`/api/robots/${robotId}/binding`, { adapterVersionId: build, profileRevisionId: revision })
    await post(`/api/robots/${robotId}/site-names`, undefined)
  }
  await expect
    .poll(async () => (await get('/api/robots')).robots.find((row: { robot: { robotId: string } }) => row.robot.robotId === 'humanoid-01').commissioning.state)
    .toBe('COMPLETE')
  await page.reload()

  // ── 15 현장 설정: 연결 기준 시간을 늘려 버전 2 ─────────────────────────────────────────────
  await go(page, '현장 설정')
  const settings = page.getByRole('region', { name: '현장 설정', exact: true })
  const change = settings.getByRole('form', { name: '현장 설정 변경' })
  await change.getByLabel('연결 기준 시간(초)').fill('120')
  await change.getByLabel('변경 사유').fill('연결 기준 늘림')
  await change.getByRole('button', { name: '변경', exact: true }).click()
  await expect(page.getByText('연결 기준 시간 120초로 변경: 반영됨', { exact: true })).toBeVisible()
  await expect(settings.getByText('실행 호스트 반영: 버전 2', { exact: true })).toBeVisible({ timeout: 15_000 })
  await shot(page, 'page', '15-site-settings.png', {
    area: '현장 설정',
    mode: '엔지니어',
    state: '버전 2 반영, 변경 폼과 버전 이력',
    strings: ['연결 기준 시간 120초로 변경: 반영됨', '실행 호스트 반영: 버전 2', '연결 기준 늘림'],
  })

  // ── 01·02 로봇 ───────────────────────────────────────────────────────────────────────
  await go(page, '로봇')
  await page.getByRole('button', { name: '도입 준비', exact: true }).click()
  const onboarding = page.getByRole('region', { name: '로봇 도입 준비' })
  await onboarding.getByLabel('준비를 이어갈 기체').selectOption('quadruped-01')
  await expect(onboarding.getByText(/다음 조치: 아래 상세의 명칭 상태/)).toBeVisible()
  await shot(page, 'page', '01-robots-overview.png', {
    area: '로봇',
    mode: '엔지니어',
    state: '도입 준비 탭, 기체 둘 선언, quadruped-01 준비 현황',
    strings: ['연결 준비부터 첫 작업까지', 'quadruped-01 준비 현황', '다음 조치: 아래 상세의 명칭 상태', 'humanoid-01'],
  })
  await page.getByRole('button', { name: '기체', exact: true }).click()
  await page.getByRole('button', { name: 'humanoid-01', exact: true }).click()
  const humanoid = page.getByRole('region', { name: 'humanoid-01 상세' })
  await expect(humanoid.getByRole('heading', { name: '시운전: 완료' })).toBeVisible()
  await shot(page, page.getByRole('region', { name: '상세', exact: true }), '02-robots-detail-commissioned.png', {
    area: '로봇',
    mode: '엔지니어',
    state: 'humanoid-01 바인딩·명칭 기록 뒤 시운전 완료',
    strings: ['시운전: 완료', '막힘 없음', '기준 120초, 현장 설정 버전 2'],
  })

  // ── 03·04 임무: 목록, 실행 정의 초안 검증·모의 실행 ────────────────────────────────────────
  await go(page, '임무')
  const missionNav = page.getByRole('navigation', { name: '임무 관리' })
  const catalog = page.getByRole('region', { name: '지원 임무 목록' })
  await expect(catalog).toContainText('PrepareSequencedRack')
  await shot(page, 'page', '03-missions-overview.png', {
    area: '임무',
    mode: '엔지니어',
    state: '임무 목록 패널(지원 임무 카드)',
    strings: ['지원 임무', '실행 정의 편집 지원', '코드 정의 · 읽기 전용', 'PrepareSequencedRack', 'InspectAsset'],
  })
  await missionNav.getByRole('button', { name: '실행 정의', exact: true }).click()
  const editor = page.getByRole('region', { name: '임무 편집' })
  const missionNotice = editor.getByRole('status', { name: '임무 조작 결과' })
  await editor.getByRole('button', { name: '운영자 보류 대기 템플릿 불러오기' }).click()
  await editor.getByRole('button', { name: '초안 저장', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 저장: 초안 1 저장됨')
  await editor.getByRole('button', { name: '검증', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 1 검증: 통과')
  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
  await expect(missionNotice).toContainText('초안 1 모의 실행: 통과')
  await shot(page, 'page', '04-missions-mock-run-passed.png', {
    area: '임무',
    mode: '엔지니어',
    state: '실행 정의 패널, 초안 1 검증 통과 뒤 모의 실행 통과(활성화 전)',
    strings: ['초안 1 모의 실행: 통과', 'PrepareSequencedRack'],
  })
  await editor.getByLabel('활성화 사유').fill('운영자 보류 대기 도입')
  await editor.getByRole('button', { name: '활성화', exact: true }).click()
  await expect(missionNotice).toHaveText('초안 1 활성화: 버전 1 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')

  // ── 05 임무 › 호출 연결: asset-check 저장·공개·배포 ───────────────────────────────────────
  await missionNav.getByRole('button', { name: '호출 연결', exact: true }).click()
  const interfaces = page.getByRole('region', { name: '버전별 호출 계약' })
  await interfaces.getByRole('button', { name: '호출 계약 만들기' }).click()
  const interfaceForm = interfaces.getByRole('region', { name: '호출 계약 작성' })
  await interfaceForm.getByLabel('호출 식별자', { exact: true }).fill('asset-check')
  await interfaceForm.getByLabel('계약 이름', { exact: true }).fill('MES 자산 점검')
  await interfaceForm.getByLabel('대상 임무', { exact: true }).selectOption('InspectAsset')
  await interfaceForm.getByRole('button', { name: '매핑 검증·미리보기' }).click()
  await expect(interfaceForm.getByLabel('계약 매핑 미리보기')).toContainText('JO-PREVIEW')
  await interfaceForm.getByRole('button', { name: '계약 초안 저장' }).click()
  const savedInterface = interfaces.getByRole('region', { name: '선택한 호출 계약' })
  await savedInterface.getByLabel('공개 변경 사유').fill('대상 목록 매핑 검증 완료')
  await savedInterface.getByRole('button', { name: '호출 계약 공개' }).click()
  await expect(interfaces.getByRole('status', { name: '호출 계약 처리 결과' })).toHaveText('asset-check v1: 공개')
  const deploymentPanel = savedInterface.getByRole('region', { name: '실행 배포' })
  await deploymentPanel.getByLabel('배포 판단 사유').fill('초기 코드 정의 명시적 고정')
  await deploymentPanel.getByRole('button', { name: '새 실행 배포 적용' }).click()
  await expect(deploymentPanel).toContainText('실행 배포 · v2')
  await deploymentPanel.getByText('불변 배포 이력', { exact: true }).click()
  await expect(deploymentPanel).toContainText('초기 코드 정의 명시적 고정')
  await shot(page, savedInterface, '05-missions-interface-deployed.png', {
    area: '임무',
    mode: '엔지니어',
    state: '호출 계약 asset-check v1 공개, 저장된 매핑, 실행 배포 v2와 배포 이력',
    strings: ['MES 자산 점검 · v1', '대상 임무: InspectAsset', '실행 배포 · v2', '초기 코드 정의 명시적 고정'],
  })

  // ── 12 운영: 수동 작업 요청과 실행 목록 ─────────────────────────────────────────────────
  await radio(page, '운영자')
  await go(page, '운영')
  const order = page.getByRole('form', { name: '작업 지시 폼' })
  await order.getByLabel('임무').selectOption('InspectAsset')
  await order.getByLabel('대상 1 id').fill('T1')
  await order.getByLabel('대상 1 장소').fill('bay-7')
  const eligibility = page.getByRole('table', { name: '기체별 배정 가능' })
  await expect(eligibility.getByRole('row', { name: /^humanoid-01 / }).getByRole('cell', { name: '가능', exact: true })).toHaveText('가능')
  const manualPosted = page.waitForResponse((r) => r.url().endsWith('/api/work-requests/manual') && r.request().method() === 'POST')
  await order.getByRole('button', { name: '작업 요청 내기' }).click()
  const manual = await (await manualPosted).json()
  await expect(page.getByRole('status', { name: '제출 결과' })).toContainText('InspectAsset 작업 지시: 배정됨')
  const executions = page.getByRole('table', { name: '실행 목록' })
  const manualRun = executions.getByRole('row').filter({ hasText: manual.receipt.jobOrderId })
  await expect(manualRun).toContainText('코드 정의')
  await shot(page, 'page', '12-operations-executions.png', {
    area: '운영',
    mode: '운영자',
    state: '수동 작업 요청(InspectAsset) 배정 직후, 수동 작업 요청 추적과 실행 목록',
    strings: ['InspectAsset 작업 지시: 배정됨', '작업 요청 기록 저장됨', manual.receipt.jobOrderId, '코드 정의'],
  })
  await expect(manualRun).toContainText('PHYSICALLY_DONE', { timeout: 120_000 })
  await waitDone(manual.receipt.receiptId)

  // 호출 계약으로 한 건(운영자, API). 그 뒤 배포 v3 을 적용해 다음 외부 주문과 배포가 갈리게 한다(18 비교).
  const invoked = await page.request.post('/api/mission-interfaces/asset-check/1/invoke', {
    headers: OPERATOR,
    data: { requestId: randomUUID(), expectedDeploymentVersion: 2, input: { assets: [{ id: 'T2', location: 'bay-7' }] } },
  })
  expect(invoked.status(), await invoked.text()).toBe(202)
  const contractReceipt = (await invoked.json()).receipt as { receiptId: string; state: string }
  expect(contractReceipt.state).toBe('ACCEPTED')
  await waitDone(contractReceipt.receiptId)
  await post('/api/mission-interfaces/asset-check/1/deploy', { missionVersion: 'CODE', reason: '외부 주문용 배포 갱신', expectedDeploymentVersion: 2 })

  // 외부 주문(Mock MES): MES-E2E-204 수락, MES-E2E-BAD 거부. 결과 v1 전달(첫 시도 503) 뒤 수신 확인.
  const upstream = { externalOrderId: 'MES-E2E-204', interfaceId: 'asset-check', interfaceVersion: 1, input: { assets: [{ id: 'T1', location: 'bay-7' }] } }
  const received = await page.request.post('/integration/v1/job-orders', { headers: MES, data: upstream })
  expect(received.status()).toBe(202)
  const mesReceipt = (await received.json()).receipt as { receiptId: string; state: string }
  expect(mesReceipt.state).toBe('ACCEPTED')
  const rejected = await page.request.post('/integration/v1/job-orders', { headers: MES, data: { ...upstream, externalOrderId: 'MES-E2E-BAD', input: {} } })
  expect((await rejected.json()).receipt.state).toBe('REJECTED')
  const resultBase = `/integration/v1/job-orders/${mesReceipt.receiptId}/results`
  await expect.poll(async () => (await page.request.get(`${resultBase}/1`, { headers: MES })).status(), { timeout: 150_000 }).toBe(200)
  await expect
    .poll(
      async () => {
        const rows = await (await page.request.get('http://127.0.0.1:8786/received')).json()
        return rows.some((row: { body: { receiptId: string }; attempts: number }) => row.body.receiptId === mesReceipt.receiptId && row.attempts >= 2)
      },
      { timeout: 60_000 },
    )
    .toBe(true)
  const latest = await (await page.request.get(`${resultBase}/latest`, { headers: MES })).json()
  expect((await page.request.post(`${resultBase}/${latest.version}/ack`, { headers: MES, data: { resultHash: latest.resultHash } })).status()).toBe(200)

  // ── 07·08·21 작업 요청 ────────────────────────────────────────────────────────────────
  await go(page, '작업 요청')
  const requests = page.getByRole('region', { name: '상위 작업 요청' })
  await requests.getByRole('button', { name: '작업 요청 다시 조회' }).click()
  const requestTable = requests.getByRole('table', { name: '작업 요청 목록' })
  await expect(requestTable).toContainText('MES-E2E-BAD')
  await shot(page, 'page', '07-requests-list.png', {
    area: '작업 요청',
    mode: '운영자',
    state: '외부 주문 둘(수락·거부), 운영자 수동, 호출 계약 요청이 섞인 목록',
    strings: ['검색 결과 4건', 'MES-E2E-204', 'MES-E2E-BAD', '운영자 수동', '호출 계약 · 운영자', '거부'],
  })
  await requests.getByRole('button', { name: 'MES-E2E-204 작업 요청 보기', exact: true }).click()
  const detail = requests.getByRole('article', { name: '작업 요청 상세' })
  await expect(detail.getByRole('region', { name: '외부 결과 수신 확인' })).toContainText('v1 수신 확인됨')
  await expect(detail.getByLabel('결과 전달 이력')).toContainText('DELIVERED')
  const detailStrings = ['MES-E2E-204 · 호스트 수락', 'v1 수신 확인됨', 'DELIVERED', '요청 시 고정된 배포', 'PHYSICALLY_DONE']
  await shot(page, detail, '08-requests-detail-result-ack.png', {
    area: '작업 요청',
    mode: '운영자',
    state: '외부 주문 상세, 결과 v1 저장·전달(시도 2회)·수신 확인',
    strings: detailStrings,
  })
  await mobile(page)
  await shot(page, detail, '21-requests-detail-mobile.png', {
    area: '작업 요청',
    mode: '운영자',
    state: '08 과 같은 상세, 390px',
    strings: detailStrings,
  })
  await page.setViewportSize(DESKTOP)

  // ── 06·20 운영 개요 ──────────────────────────────────────────────────────────────────
  await go(page, '운영 개요')
  const overview = page.getByRole('region', { name: '운영 개요' })
  const summary = await get('/api/work-requests/summary')
  await expect(overview.getByRole('link', { name: `총 작업 요청 ${summary.total}`, exact: true })).toBeVisible()
  await expect(overview.getByRole('region', { name: '결과 자동 수집' })).toContainText('수집 중')
  const overviewStrings = ['총 작업 요청','작업 요청 거부', '수집 중', '미해결 인시던트', 'MES-E2E-204']
  await shot(page, 'page', '06-overview.png', {
    area: '운영 개요',
    mode: '운영자',
    state: `작업 요청 ${summary.total}건 뒤 지표 카드, 호스트 실행 관측, 결과 자동 수집, 최근 작업 요청`,
    strings: overviewStrings,
  })
  await mobile(page)
  await shot(page, overview, '20-overview-mobile.png', {
    area: '운영 개요',
    mode: '운영자',
    state: '06 과 같은 상태, 390px',
    strings: overviewStrings,
  })
  await page.setViewportSize(DESKTOP)

  // ── 13 운영: 운영자 보류(설비 대기 기한 20초) ──────────────────────────────────────────────
  await go(page, '운영')
  const signals = page.getByRole('table', { name: '셀 대역 신호' })
  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
  await page.getByRole('button', { name: '새 작업 작성', exact: true }).click()
  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
  await order.getByLabel('RACK-204.S02').check()
  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
  await order.getByRole('button', { name: '작업 요청 내기' }).click()
  const holdRun = executions.getByRole('row').filter({ hasText: 'rack-arrival equipment_wait' })
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING')
  const holdJobOrder = (await holdRun.getByRole('cell').nth(1).textContent())!.trim()
  const incidentSection = page.getByRole('region', { name: '인시던트', exact: true })
  const held = page.getByRole('table', { name: '인시던트 목록' }).getByRole('row').filter({ hasText: '보류 중' })
  await expect(held).toContainText('SIGNAL_DEADLINE')
  await held.getByRole('button', { name: /상세 보기$/ }).click()
  const incidentDetail = page.getByRole('region', { name: '인시던트 상세' })
  const decision = incidentDetail.getByRole('form', { name: '운영자 판단' })
  await expect(decision).toBeVisible()
  await decision.getByLabel('판단 사유').fill('랙 재배치 뒤 재작업')
  await shot(page, incidentSection, '13-operations-incident-hold.png', {
    area: '운영',
    mode: '운영자',
    state: '설비 대기 기한 초과로 보류 중인 인시던트 상세와 보류 해소 폼(사유 입력, 보내기 전)',
    strings: ['SIGNAL_DEADLINE', '보류 중', '보류 해소 방식(완료 확인 또는 재작업)을 결정합니다. 결정자는 local입니다.', '사람의 판단 없음. 아래 값은 모두 관측입니다'],
  })
  await decision.getByRole('button', { name: '재작업' }).click()
  await expect(page.getByRole('status', { name: '판단 결과' })).toHaveText(/\/rack-arrival 재작업: 판단이 섰습니다\(incident-\d+\)$/)
  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING', { timeout: 10_000 })
  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
  await expect(holdRun).toContainText('PHYSICALLY_DONE', { timeout: 150_000 })

  // ── 17·18 검증 도구 ──────────────────────────────────────────────────────────────────
  await radio(page, '엔지니어')
  await go(page, '검증 도구')
  const faultForm = page.getByRole('form', { name: '장애 주입 폼' })
  const faultNotice = page.getByRole('status', { name: '장애 주입 결과' })
  await faultForm.getByRole('combobox', { name: /^기체/ }).selectOption('quadruped-01')
  await faultForm.getByRole('combobox', { name: /^장애 종류/ }).selectOption('CONNECTION')
  await faultForm.getByRole('combobox', { name: /^연결 상태/ }).selectOption('OFFLINE')
  await faultForm.getByRole('textbox', { name: /^장애 주입 사유/ }).fill('연결 끊김 시연')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(faultNotice).toHaveText('quadruped-01 연결 상태 OFFLINE: 받아들임(연결 상태 OFFLINE)')
  await shot(page, page.getByRole('region', { name: '장애 주입', exact: true }), '17-validation-fault.png', {
    area: '검증 도구',
    mode: '엔지니어',
    state: 'quadruped-01 연결 상태 OFFLINE 주입 받아들임',
    strings: ['quadruped-01 연결 상태 OFFLINE: 받아들임(연결 상태 OFFLINE)', '장애 넣기'],
  })
  await faultForm.getByRole('combobox', { name: /^연결 상태/ }).selectOption('ONLINE')
  await faultForm.getByRole('textbox', { name: /^장애 주입 사유/ }).fill('연결 복구')
  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
  await expect(faultNotice).toHaveText(/^quadruped-01 연결 상태 ONLINE: 받아들임/)

  const comparison = page.getByRole('region', { name: '제조 시나리오 검증' })
  await comparison.getByLabel('작업 요청 A ID').fill(contractReceipt.receiptId)
  await comparison.getByLabel('작업 요청 B ID').fill(mesReceipt.receiptId)
  await comparison.getByRole('button', { name: '두 작업 요청 비교하기' }).click()
  await expect(comparison.getByRole('article', { name: '작업 요청 B 근거' })).toContainText('PHYSICALLY_DONE')
  await shot(page, comparison, '18-validation-comparison.png', {
    area: '검증 도구',
    mode: '엔지니어',
    state: '호출 계약 요청(배포 v2)과 외부 주문 MES-E2E-204(배포 v3) 비교',
    strings: ['계약 또는 실행 배포가 다릅니다.', '호출 계약과 실행 근거 비교', 'MES-E2E-204', 'PHYSICALLY_DONE'],
  })

  // ── 09 작업 요청: 전송 결과 불명(host-link 가 수락 응답을 잃음) ─────────────────────────────────
  expect((await page.request.post('http://127.0.0.1:8790/cut-after-submit')).ok()).toBe(true)
  const uncertainReply = await page.request.post('/integration/v1/job-orders', {
    headers: MES,
    data: { externalOrderId: 'MES-RESTART-1', interfaceId: 'asset-check', interfaceVersion: 1, input: { assets: [{ id: 'T3', location: 'bay-7' }] } },
  })
  expect(uncertainReply.status()).toBe(202)
  const uncertain = (await uncertainReply.json()).receipt as { receiptId: string; state: string }
  expect(uncertain.state).toBe('IN_DOUBT')
  await page.goto(`/#requests?receipt=${uncertain.receiptId}`)
  await radio(page, '운영자')
  const inDoubt = page.getByRole('article', { name: '작업 요청 상세' })
  await expect(inDoubt).toContainText('MES-RESTART-1 · 전송 결과 불명')
  await shot(page, inDoubt, '09-requests-in-doubt.png', {
    area: '작업 요청',
    mode: '운영자',
    state: '외부 주문 MES-RESTART-1 전송 결과 불명(호스트 연결 단절 중), 현장 확인 기록 폼',
    how: '실제 스택: host-link 프록시가 실행 호스트의 수락 응답 뒤 연결을 끊음(cut-after-submit)',
    strings: ['MES-RESTART-1 · 전송 결과 불명', '새 제출은 차단됩니다', '현장 확인 기록 저장'],
  })
  const review = page.getByRole('region', { name: '현장 확인 기록' })
  await review.getByLabel('현장 관측').selectOption('IN_PROGRESS')
  await review.getByLabel('확인 근거·담당자·인계 내용').fill('로컬 PoC 담당자 확인: 실제 호스트 수락, 연결 단절 중 진행')
  await review.getByRole('button', { name: '현장 확인 기록 저장', exact: true }).click()
  await expect(review.getByRole('status')).toContainText('현장 확인 기록 저장됨')
  expect((await page.request.post('http://127.0.0.1:8790/connect')).ok()).toBe(true)
  await expect.poll(async () => (await get(`/api/work-requests/${uncertain.receiptId}`)).receipt.state, { timeout: 150_000 }).toBe('ACCEPTED')
  await waitDone(uncertain.receiptId)

  // ── 10·11 공유 자원: InspectAsset 에 필수 자원, 단계 기한 60초 ──────────────────────────────
  await radio(page, '엔지니어')
  await go(page, '공유 자원')
  const workspace = page.getByRole('region', { name: '공유 스테이션 운영' })
  await workspace.getByText('공유 스테이션 등록', { exact: true }).click()
  await workspace.getByLabel('스테이션 ID', { exact: true }).fill('inspection-1')
  await workspace.getByLabel('스테이션 이름', { exact: true }).fill('공유 검사 스테이션')
  await workspace.getByLabel('단계 기한 초', { exact: true }).fill('60')
  await workspace.getByRole('button', { name: '스테이션 등록', exact: true }).click()
  await expect(workspace.getByRole('table', { name: '스테이션 점유' })).toContainText('공유 검사 스테이션')
  await workspace.getByText('임무 자원 요구 등록', { exact: true }).click()
  await workspace.getByLabel('자원이 필요한 임무').selectOption('InspectAsset')
  await workspace.getByRole('combobox', { name: '필수 스테이션', exact: true }).selectOption('inspection-1')
  await workspace.getByLabel('자원 요구 등록 사유').fill('모든 검사 호출의 공유 자원 조정')
  await workspace.getByRole('button', { name: '필수 자원 등록', exact: true }).click()
  await expect(workspace.getByRole('region', { name: '임무 필수 자원' })).toContainText('InspectAsset → inspection-1')
  await signal('inspection_ready', 'false')
  await signal('inspection_complete', 'false')
  await radio(page, '운영자')
  await workspace.getByLabel('사용할 스테이션').selectOption('inspection-1')
  await workspace.getByLabel('대상 호출 계약').selectOption('asset-check/1')
  await workspace.getByLabel('준비 조건이 충족되면 실제 로봇 작업을 지시합니다').check()
  await workspace.getByRole('button', { name: '스테이션 작업 요청', exact: true }).click()
  const runDetail = workspace.getByRole('article', { name: '스테이션 작업 근거' })
  await expect(runDetail).toBeVisible()
  const a = (await get('/api/stations')).runs[0].runId as string
  await expect.poll(async () => (await stationRun(a)).state).toBe('WAITING_READY')
  const queued = await page.request.post('/api/mission-interfaces/asset-check/1/invoke', {
    headers: OPERATOR,
    data: { requestId: randomUUID(), expectedDeploymentVersion: 3, input: { assets: [{ id: 'T4', location: 'bay-7' }] } },
  })
  expect(queued.status(), await queued.text()).toBe(202)
  const b = (await queued.json()).receipt.receiptId as string
  await signal('inspection_ready', 'true')
  await expect.poll(async () => (await stationRun(a)).state).toBe('RUNNING')
  expect((await stationRun(b)).state).toBe('QUEUED')
  await workspace.getByRole('button', { name: '자원 상태 새로고침' }).click()
  const runTable = workspace.getByRole('table', { name: '스테이션 작업' })
  await expect(runTable).toContainText('실행·설비 완료 대기')
  await expect(runTable).toContainText('자원 대기')
  await workspace.getByRole('button', { name: '점유 작업 보기' }).click()
  await expect(runDetail.getByRole('status')).toContainText('실행·설비 완료 대기')
  await shot(page, 'page', '10-stations-queue.png', {
    area: '공유 자원',
    mode: '운영자',
    state: 'inspection-1 점유 작업 실행 중, 다음 작업 요청 자원 대기',
    strings: ['공유 검사 스테이션', 'InspectAsset → inspection-1', '실행·설비 완료 대기', '자원 대기', '점유 작업 보기'],
  })
  // 완료 신호를 켜지 않으므로 RUNNING 단계가 기한 60초를 넘겨 현장 확인 필요가 된다.
  await expect.poll(async () => (await stationRun(a)).state, { timeout: 150_000 }).toBe('REVIEW_REQUIRED')
  await runDetail.getByRole('button', { name: '선택 작업 다시 조회' }).click()
  await expect(runDetail.getByRole('status')).toContainText('현장 확인 필요')
  await runDetail.getByLabel('운영자 판단 근거').fill('설비 완료 신호 미수신, 현장 확인 중')
  await runDetail.getByLabel('현장 작업과 자원 해제 가능 여부를 확인했습니다').check()
  await shot(page, runDetail, '11-stations-review-required.png', {
    area: '공유 자원',
    mode: '운영자',
    state: '단계 기한 초과로 현장 확인 필요, 판단 근거 입력·확인 체크(보내기 전)',
    strings: ['현장 확인 필요 · 작업 시간 초과', '현장 확인 후 자원 해제', '재실행 없이 관측 재개'],
  })

  // ── 16 이력 ──────────────────────────────────────────────────────────────────────────
  await go(page, '이력')
  await shot(page, 'page', '16-history.png', {
    area: '이력',
    mode: '운영자',
    state: '흐름을 돈 뒤 조작 기록',
    strings: ['조작 기록', '운영자/local', '엔지니어/local', '랙 재배치 뒤 재작업', '연결 기준 늘림'],
  })

  // ── 14 운영: 실행 호스트 재기동 뒤 복원 보고(대역) ─────────────────────────────────────────
  type Execution = { executionId: string; jobOrderId: string; robotId: string; units: Record<string, unknown>[] } & Record<string, unknown>
  const real = await get<{ instanceId: string; executions: Execution[] }>('/api/executions')
  const before = real.executions.find((row) => row.jobOrderId === holdJobOrder)!
  const at = new Date().toISOString()
  const restarted = {
    instanceId: `mw-${randomUUID()}`,
    pumpedAt: at,
    executions: [
      {
        ...before,
        executionId: 'exec-1',
        physicalState: 'RUNNING',
        units: before.units.map((unit, index) => ({ ...unit, state: index === 0 ? 'RUNNING' : 'PENDING', reached: 'E0' })),
        jobResponse: null,
        restoredFrom: { instanceId: real.instanceId, executionId: before.executionId },
      },
    ],
    restore: {
      at,
      rows: [
        {
          jobOrderId: holdJobOrder,
          robotId: before.robotId,
          previousInstanceId: real.instanceId,
          previousExecutionId: before.executionId,
          result: 'RESTORED',
          executionId: 'exec-1',
          reason: null,
        },
      ],
    },
  }
  await page.route('**/api/executions', (route) => route.fulfill({ json: restarted }))
  await go(page, '운영')
  const executionSection = page.getByRole('region', { name: '실행', exact: true })
  await expect(executionSection.getByRole('region', { name: '재기동 복원 보고' })).toBeVisible()
  await shot(page, executionSection, '14-operations-restart-restored-mock.png', {
    area: '운영',
    mode: '운영자',
    state: '실행 호스트 재기동 뒤 복원 보고와 «이전 exec-k» 행',
    how: '대역: GET /api/executions 를 재기동 뒤 모양(restore, restoredFrom)으로. 바탕은 같은 실행의 실제 PrepareSequencedRack 실행',
    strings: [
      '재기동: 이전 인스턴스의 실행 1건을 다시 지었습니다',
      `${holdJobOrder}(${before.robotId}): 이전 ${before.executionId} → exec-1`,
      `exec-1 (이전 ${before.executionId})`,
    ],
  })
  await page.unrouteAll({ behavior: 'wait' })

  // ── 19 registry 불통(실제): 런처를 끈다. 맨 끝 ─────────────────────────────────────────────
  await radio(page, '엔지니어')
  await go(page, '로봇')
  await expect(page.getByRole('button', { name: 'humanoid-01', exact: true })).toBeVisible()
  process.kill(Number(readFileSync(fileURLToPath(new URL('../../build/site.pid', import.meta.url)), 'utf8')))
  await expect(page.getByRole('alert').filter({ hasText: '모름: registry 가 답하지 않습니다' })).toBeVisible()
  await expect(page.getByRole('region', { name: '기체 목록' }).getByText(/직전 값입니다/)).toBeVisible()
  await shot(page, 'page', '19-robots-registry-down.png', {
    area: '로봇',
    mode: '엔지니어',
    state: 'registry 불통, 전체 상태 모름과 기체 목록 직전 값',
    how: '실제 스택: 런처(registry·mimic 프로세스)를 끔',
    strings: ['모름: registry 가 답하지 않습니다', '직전 값입니다', 'humanoid-01', 'quadruped-01'],
  })

  writeIndex()
  expect(entries.map((e) => e.file).sort()).toEqual(
    [
      '01-robots-overview.png', '02-robots-detail-commissioned.png', '03-missions-overview.png', '04-missions-mock-run-passed.png',
      '05-missions-interface-deployed.png', '06-overview.png', '07-requests-list.png', '08-requests-detail-result-ack.png',
      '09-requests-in-doubt.png', '10-stations-queue.png', '11-stations-review-required.png', '12-operations-executions.png',
      '13-operations-incident-hold.png', '14-operations-restart-restored-mock.png', '15-site-settings.png', '16-history.png',
      '17-validation-fault.png', '18-validation-comparison.png', '19-robots-registry-down.png', '20-overview-mobile.png',
      '21-requests-detail-mobile.png',
    ],
  )
})
