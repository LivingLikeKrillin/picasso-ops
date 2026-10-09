import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { loadEnv } from 'vite'

// 포트는 루트 .env 하나에서 읽는다(스펙 §4·§6). 작업 디렉터리와 상관없이 이 파일 기준으로 찾는다.
const env = loadEnv('', fileURLToPath(new URL('..', import.meta.url)), ['OPS_', 'REGISTRY_', 'HOST_'])
if (!env.OPS_PORT || !env.REGISTRY_PORT || !env.HOST_PORT) {
  throw new Error('루트 .env 에 OPS_PORT·REGISTRY_PORT·HOST_PORT 가 없다')
}

/** Linux 에서는 SIGTERM 으로 끝내 각 스크립트가 정리하게 한다. Windows 는 신호 없이 트리째 끈다. */
const gracefulShutdown = { signal: 'SIGTERM' as const, timeout: 15_000 }

/**
 * 화면 설계서의 스크린숏을 찍는 설정. 스택은 `playwright.config.ts` 와 같고(Postgres, 런처, 실행 호스트, 운영 서비스, 화면)
 * 시험 디렉터리만 `./screens` 다. CI 의 기본 `npx playwright test` 는 `./e2e` 만 돌므로 이것을 돌지 않는다.
 * 실행: `npx playwright test -c playwright.screens.config.ts`. 배포본은 먼저 만들어 둔다:
 * `./gradlew :site:installDist :mission-host:installDist :ops-service:installDist`
 *
 * 화면 크기는 1440×900, 배율 1, 밝은 색 구성으로 고정한다. 결과는 `docs/screens/img/` 에 쌓인다.
 */
export default defineConfig({
  testDir: './screens',
  timeout: 900_000,
  expect: { timeout: 60_000 },
  workers: 1,
  reporter: [['list']],
  outputDir: './test-results/screens',
  globalTeardown: './e2e/teardown.ts',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    colorScheme: 'light',
    locale: 'ko-KR',
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      command: 'node e2e/postgres.mjs',
      wait: { stdout: /postgres ready/ },
      stdout: 'pipe',
      timeout: 120_000,
      reuseExistingServer: false,
      gracefulShutdown,
    },
    {
      command: 'node e2e/run-dist.mjs site',
      url: `http://127.0.0.1:${env.REGISTRY_PORT}/diag/robots`,
      stdout: 'pipe',
      timeout: 120_000,
      reuseExistingServer: false,
      gracefulShutdown,
    },
    {
      command: 'node e2e/run-dist.mjs mission-host',
      url: `http://127.0.0.1:${env.HOST_PORT}/host/executions`,
      stdout: 'pipe',
      timeout: 120_000,
      reuseExistingServer: false,
      gracefulShutdown,
    },
    {
      command: 'node e2e/run-dist.mjs ops-service',
      url: `http://127.0.0.1:${env.OPS_PORT}/api/robots`,
      stdout: 'pipe',
      timeout: 120_000,
      reuseExistingServer: false,
      gracefulShutdown,
    },
    {
      command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
      url: 'http://127.0.0.1:4173',
      timeout: 120_000,
      reuseExistingServer: false,
      gracefulShutdown,
    },
  ],
})
