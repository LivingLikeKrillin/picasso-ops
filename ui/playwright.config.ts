import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { loadEnv } from 'vite'

// 포트는 루트 .env 하나에서 읽는다(스펙 §4·§6). 작업 디렉터리와 상관없이 이 파일 기준으로 찾는다.
const env = loadEnv('', fileURLToPath(new URL('..', import.meta.url)), ['OPS_', 'REGISTRY_'])
if (!env.OPS_PORT || !env.REGISTRY_PORT) throw new Error('루트 .env 에 OPS_PORT·REGISTRY_PORT 가 없다')

/** Linux 에서는 SIGTERM 으로 끝내 각 스크립트가 정리하게 한다. Windows 는 신호 없이 트리째 끈다. */
const gracefulShutdown = { signal: 'SIGTERM' as const, timeout: 15_000 }

/**
 * 실행 중인 전체 스택 앞에서 생애주기를 화면으로 한 번 돌린다(스펙 §10). 런처는 실제 1초에 가상 1초를 밀므로
 * 보고를 기다리는 단계마다 최대 60초를 기다린다.
 *
 * 스택은 Postgres, 런처, 운영 서비스, 화면 순으로 띄우고 각각 준비 확인을 거친다(스펙 §10). 프로세스마다
 * webServer 항목을 따로 두어, 끝날 때 Playwright 가 프로세스 트리째 끈다. 배포본은 먼저 만들어 둔다:
 * `./gradlew :site:installDist :ops-service:installDist`
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 300_000,
  expect: { timeout: 60_000 },
  workers: 1,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalTeardown: './e2e/teardown.ts',
  use: { baseURL: 'http://127.0.0.1:4173', trace: 'retain-on-failure' },
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
