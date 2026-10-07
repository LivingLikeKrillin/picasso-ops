import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

/**
 * 스택 스크립트가 강제로 끝나 정리하지 못했을 때를 위해 compose 를 한 번 더 내린다. 이미 내려가 있으면 아무 일도 없다.
 */
export default function teardown() {
  const root = fileURLToPath(new URL('../..', import.meta.url))
  try {
    execFileSync('docker', ['compose', '-f', 'site/compose.yaml', '--env-file', '.env', 'down', '-v'], {
      cwd: root,
      stdio: 'ignore',
    })
  } catch {
    // docker 가 없으면 스택도 뜨지 않았다.
  }
}
