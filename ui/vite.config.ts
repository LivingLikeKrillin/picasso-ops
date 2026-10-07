/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// 화면은 운영 서비스만 부른다(스펙 §4). registry 주소와 토큰을 모른다.
// 포트는 루트 .env 하나에서 읽는다(스펙 §4·§6). 여기에 기본값을 두면 두 번째 출처가 된다.
export default defineConfig(({ mode }) => {
  const opsPort = loadEnv(mode, '..', 'OPS_').OPS_PORT
  if (!opsPort) throw new Error('루트 .env 에 OPS_PORT 가 없다')
  const proxy = { '/api': `http://127.0.0.1:${opsPort}` }
  return {
    plugins: [react()],
    server: { proxy },
    preview: { proxy },
    test: {
      environment: 'jsdom',
      // e2e/ 는 Playwright 의 몫이다(실행 중인 전체 스택 앞에서 돈다).
      include: ['src/**/*.test.{ts,tsx}'],
      setupFiles: ['./src/setupTests.ts'],
    },
  }
})
