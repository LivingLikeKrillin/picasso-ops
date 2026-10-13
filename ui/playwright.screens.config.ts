import { defineConfig } from '@playwright/test'
import base from './playwright.config'

/**
 * 화면 설계서의 대표 스크린숏을 찍는 설정. 스택은 `playwright.config.ts` 와 같다(Mock MES 콜백, Postgres, 런처, 재기동 감독이 붙은
 * 실행 호스트, host-link 프록시, `--mock-mes --restartable --host-proxy` 운영 서비스, 화면). 시험 디렉터리만 `./screens` 다.
 * CI 의 `npx playwright test` 는 `./e2e` 만 돌므로 이것을 돌지 않는다.
 * 실행: `npx playwright test -c playwright.screens.config.ts`. 배포본은 먼저 만들어 둔다:
 * `./gradlew :site:installDist :mission-host:installDist :ops-service:installDist`
 *
 * 화면 크기는 1440×900, 배율 1, 밝은 색 구성으로 고정한다. 결과는 `docs/screens/img/` 에 쌓인다.
 */
export default defineConfig({
  ...base,
  testDir: './screens',
  testMatch: '**/capture.spec.ts',
  timeout: 1_500_000,
  reporter: [['list']],
  outputDir: './test-results/screens',
  use: {
    ...base.use,
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    colorScheme: 'light',
    locale: 'ko-KR',
    trace: 'retain-on-failure',
  },
})
