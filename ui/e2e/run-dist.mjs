// 배포본 하나(site 또는 ops-service)를 루트 .env 를 환경 변수로 넣어 띄운다. Playwright 의 webServer 가 부르고,
// 끝낼 때 프로세스 트리째 끈다. 배포본이 먼저 있어야 한다: ./gradlew :site:installDist :ops-service:installDist
//
// 시작 스크립트(bin/)를 거치지 않고 java 를 바로 띄운다. Windows 의 .bat 은 클래스패스를 한 줄로 펼쳐 cmd 의
// 줄 길이 한도를 넘는다(실측). 클래스패스 와일드카드(lib/*)는 그 한도에 걸리지 않고 셸도 필요 없다.
// 실행 JDK 는 21 이상이어야 한다(Gradle 이 JDK 21 툴체인으로 컴파일한다).
//
// java 의 PID 를 build/<이름>.pid 에 적는다. 화면 시험이 registry 를 멈출 때 이것으로 런처를 끈다.
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 각 모듈 build.gradle.kts 의 application.mainClass 와 같다. 어긋나면 기동이 바로 실패한다.
const MAIN = {
  site: 'dev.picasso.ops.site.SiteLauncherKt',
  'ops-service': 'dev.picasso.ops.service.OpsApplicationKt',
}

const root = fileURLToPath(new URL('../..', import.meta.url))
const name = process.argv[2]
if (!(name in MAIN)) throw new Error(`site 또는 ops-service 만 띄운다: ${name}`)

const env = { ...process.env }
for (const raw of readFileSync(path.join(root, '.env'), 'utf8').split(/\r?\n/)) {
  const line = raw.trim()
  if (line === '' || line.startsWith('#')) continue
  const at = line.indexOf('=')
  if (at < 0) throw new Error(`.env 줄에 '=' 가 없다: ${line}`)
  env[line.slice(0, at).trim()] = line.slice(at + 1).trim()
}
// 적재 토큰은 site 만 쥔다(스펙 §4). 셸에서 상속된 값도 지운다.
if (name !== 'site') delete env.PICASSO_INGEST_TOKEN

const lib = path.join(root, name, 'build', 'install', name, 'lib')
if (!existsSync(lib)) throw new Error(`배포본이 없다: ${lib} (installDist 먼저)`)
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java'

const child = spawn(
  java,
  ['-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', path.join(lib, '*'), MAIN[name]],
  { cwd: root, env, stdio: 'inherit' },
)
child.on('error', (error) => {
  console.error(`java 를 띄우지 못했다(${java}): ${error.message}`)
  process.exit(1)
})
mkdirSync(path.join(root, 'build'), { recursive: true })
writeFileSync(path.join(root, 'build', `${name}.pid`), String(child.pid))
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal))
child.on('exit', (code) => process.exit(code ?? 1))
