// compose 의 Postgres 를 띄우고 끝날 때까지 기다린다. Playwright 의 webServer 가 부른다.
// 준비 신호는 `up --wait`(healthcheck)가 끝난 뒤 찍는 줄이다. 포트가 열리는 것은 initdb 뒤 재기동보다 먼저라
// 준비 신호가 되지 못한다. 띄우기 전에 한 번 내린다. 앞선 실행이 기동 실패나 강제 종료로 남긴 컨테이너가
// 다음 실행을 막지 않게 한다. 전용 프로젝트 picasso-ops-e2e만 정리하며 평소 site 프로젝트는 건드리지 않는다.
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../..', import.meta.url))
const compose = (...args) =>
  execFileSync('docker', ['compose', '-p', 'picasso-ops-e2e', '-f', 'site/compose.yaml', '--env-file', '.env', ...args], {
    cwd: root,
    stdio: ['ignore', 'ignore', 'inherit'],
  })

compose('down', '-v')
compose('up', '-d', '--wait')
console.log('postgres ready')

// SIGTERM 으로 끝나면(Linux 의 gracefulShutdown) 여기서 내린다. 강제 종료(Windows)면 globalTeardown 이 내린다.
const down = () => {
  compose('down', '-v')
  process.exit(0)
}
process.on('SIGINT', down)
process.on('SIGTERM', down)
setInterval(() => {}, 1 << 30)
