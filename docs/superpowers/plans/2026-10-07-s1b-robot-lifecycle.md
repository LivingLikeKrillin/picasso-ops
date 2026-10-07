# S1b 로봇 생애주기 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** S1a 골격 위에 기체 생애주기를 화면에서 돌린다. 운영 서비스가 기체 선언·퇴역·복귀를 registry 에 보내고 조작 기록에 남기며, 기체마다 연결 칸과 상태 막힘 4종을 계산하고, registry 의 거절을 대응표로 옮긴다. 화면은 목록과 상세에서 이것을 보이고 조작한다. 완료 판정은 스펙 §3 의 S1b 행이다. 통합 시험과 Playwright 에서 선언(`CLAIMED`) → 보고(`CONFIRMED`) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록 확인이 돌고, 막힘·거절 판정 단위 시험이 결함 주입을 잡는다.

**Architecture:** 운영 서비스에 판정 모델 3개(`Finding`, `Blockers`, `Rejections`), 조작 서비스 `RobotOperations`, 조작 API `RobotOperationsController` 를 더하고 `RegistryClient` 에 조작 3가지와 관문 안 토큰 확인을 더한다. 기체 목록 서비스는 목록을 읽을 때마다 관문 안의 `GET /operations/adapters` 로 토큰을 함께 확인하고, 기체마다 연결 칸과 막힘을 붙인다. 화면은 상세 패널, 선언 폼, 퇴역·복귀, 결과 알림을 더한다. 통합 시험은 공용 기동 픽스처 `E2eStack` 위에 생애주기 시험을 얹고, Playwright 는 스택의 프로세스를 하나씩 띄워 화면으로 같은 흐름을 돈다.

**Tech Stack:** S1a 와 같다(Kotlin 2.4.20, Spring Boot 3.4.0 BOM, Gradle 9.7.1, PostgreSQL 16, Testcontainers, JUnit5, React 19, Vite 8, TypeScript 6, vitest 5). 더하는 것은 `@playwright/test` 1.63.0(chromium) 하나다.

**근거 스펙:** `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` §3(S1b 행), §7.1~§7.4, §8, §9, §10. S1a 계획 `docs/superpowers/plans/2026-10-07-s1a-skeleton.md` 끝의 «S1b 로 넘긴 것».

**결정(2026-10-07 사용자):** 운영자 토큰 불일치(401)는 목록을 읽을 때 관문 안의 `GET /operations/adapters` 를 함께 불러 판정한다. 목록 읽기 `/diag/robots` 는 관문 밖이라, 이것이 없으면 조작에서 받은 401 이 다음 폴링에 `OK` 로 덮인다.

**스펙과 다른 결정(이 계획이 정함):**
1. **모드를 운영 서비스도 집행한다.** 스펙 §8 은 «등록은 엔지니어 모드, 퇴역·복귀는 운영자 모드» 라고만 적었다. 화면이 버튼을 감추는 것에 더해, 운영 서비스가 모드가 맞지 않는 조작을 403 으로 막는다.
2. **registry 에 보내기 전에 막은 요청은 조작 기록에 남기지 않는다.** 행위자 헤더 없음(400), 모드 불일치(403), 사이트 불일치(400, 스펙 §9 의 사전 거절), JSON 아닌 본문(415), 본문이 없거나 JSON 으로 읽히지 않음(스프링 기본 400)이다. 조작 기록은 registry 에 닿은 조작의 기록이다. 사이트 대조는 선언에만 있다. 퇴역·복귀는 기체 id 로만 부르며 registry 는 사이트를 대조하지 않는다(스펙 §6 ④).
3. **조작 API 는 registry 가 답한 결과를 거절까지 포함해 200 과 결과 본문으로 돌려준다.** 이 API 의 호출은 성공했고 registry 의 판단은 본문에 있다. 400·403·415 는 위 2번의 사전 거절에만 쓴다.
4. **대응표에 없는 registry 응답은 `UNCLASSIFIED`(엔지니어, 화면 밖)로 둔다.** 아는 종류로 접지 않는다(스펙 §7.4 의 «409 를 하나로 다루지 않는다» 와 같은 이유). 종류 값은 열거형 표기(`WRONG_DOOR`, `RETIRED_ALREADY`, `BAD_REQUEST`, `RETIRE_BAD_REQUEST`, `UNKNOWN_ROBOT`)이며, 스펙의 `WrongDoor`·`RetiredAlready` 와 표기만 다르다. 거절은 `UNCLASSIFIED` 만 화면 밖이다.
5. **막힘의 판정 시각은 목록을 읽은 시각이다.** registry 가 침묵해 직전 목록을 보일 때 판정도 그 목록과 같은 시점의 것이 된다. 막힘 칸의 «마지막 확인 시각» 도 그 시각이다.
6. **«바로 갈 링크» 칸은 기체 id(`target`)로 둔다.** 링크 모양은 화면이 정한다(상세를 여는 버튼).
7. **화면의 «직전 값» 표시는 목록을 읽은 시각이 확인 시각과 다를 때다.** S1a 는 registry 상태가 `OK` 가 아닐 때였다. 토큰 불일치는 목록을 새로 읽었으므로 직전 값이 아니다.
8. **Playwright 는 스택을 프로세스마다 따로 띄운다.** Postgres, 런처, 운영 서비스, 화면을 각각 `webServer` 로 두어 Playwright 가 프로세스 트리째 끈다. 런처와 운영 서비스는 시작 스크립트 대신 `java -cp lib/*` 로 띄운다. 한 bash 스크립트에 묶었을 때 Windows 에서 java 2개가 남았고, Windows 의 `.bat` 시작 스크립트는 cmd 의 줄 길이 한도를 넘었다(스크래치 실측). Postgres 의 준비 확인은 포트가 아니라 `up --wait` 뒤에 찍는 줄이다. 기동 실패면 Postgres 컨테이너가 남으므로(실측) Postgres 스크립트가 띄우기 전에 한 번 내린다.
9. **응답 없음 뒤 재조회는 1초 뒤 한 번이다.** 시간 초과 직후에는 registry 가 아직 커밋 중일 수 있어 바로 읽으면 «반영 안 됨» 을 잘못 남길 수 있다. 재조회도 실패하면 확인 행을 붙이지 않는다. 운영 서비스는 재조회까지 마치고 답하므로, 화면은 이때 «확인 중» 이 아니라 «다시 읽지도 못해 확인하지 못했다, 목록에서 확인하라» 를 보인다. 확인 행의 `registry_response` 에는 재조회에서 본 그 기체의 출처와 원장 상태를 남긴다.
10. **선언의 «반영됨» 은 그 기체가 선언으로 들어와 퇴역하지 않은 것이다.** 스펙 §9 의 «기체 목록에 그 `robot_id` 가 있음» 보다 좁다. 목록은 퇴역 기체와 발견된 기체도 담는데, registry 는 그 둘의 선언을 409 로 거절한다.
11. **화면의 조작 결과에 «결과 모름» 이 있다.** 운영 서비스의 응답이 비정상(프록시 오류, 500)이거나 연결이 끊기면 registry 까지 갔는지 모르므로 «보내지 못함» 으로 단정하지 않는다(스펙 §9). 알림은 어느 기체의 어느 조작인지 함께 보인다. 거절 알림의 막힘 카드에만 «바로 가기» 가 있고, 상세 안의 막힘 카드에는 없다(자기 자신을 가리킨다). 선언 폼에는 스펙 §8 에 없는 «표시 이름» 입력이 있다(registry 선언 본문의 `display_name`).
12. **Playwright 는 선언 직후의 `CLAIMED` 를 단언하지 않는다.** 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수 있어 `CLAIMED` 가 보이는 시간이 정해지지 않는다. `CLAIMED` 는 가상 시계를 직접 미는 통합 시험이 본다. 스펙 §10 이 S1b 의 Playwright 에 넘긴 «registry 를 멈추면 모름» 의 화면 표시는, 시험이 런처의 PID(`build/site.pid`)로 런처를 꺼서 본다.

**S1a 에서 넘긴 것의 처리:** 조작 API 의 교차 출처 방어(헤더 없으면 400, `application/json` 만)와 동시 읽기 역전 방지, registry 주소의 기동 검사, e2e 공용 픽스처는 이 계획이 짓는다. 운영 서비스 `Clock` 교체는 짓지 않는다. 연결 기준의 경계는 단위 시험이 시계를 직접 넣어 보고(스펙 §10), 통합 시험은 `REPORT_STALE` 을 보지 않는다. mimic gRPC 포트 노출은 S1b 에 소비자가 없어 그대로 둔다.

**스크래치에서 미리 확인한 것(2026-10-07, S1a 브랜치 `3a51f29` 위, Docker 26.1.4):** 이 계획의 코드는 같은 내용으로 스크래치 빌드에서 돌렸고, 계획의 코드 블록은 그 파일에서 기계로 옮겼다. Kotlin 시험 78개(site 12, ops-service 53, e2e 13)와 vitest 22개가 통과했다. Playwright 생애주기 시험 1개가 Windows 에서 통과했다(52초, 스택 기동 포함 1.4분). 끝난 뒤 남은 프로세스와 컨테이너는 0개였다. 각 작업의 결함 주입은 적힌 실패 이름 그대로 잡혔다(서로 다른 주입으로 Kotlin 13건, 화면 6건, Playwright 1건). 기동 실패 주입에서는 프로세스는 남지 않고 Postgres 컨테이너만 남았으며, 다음 실행이 그것을 내리고 통과했다. 계획 검토는 청크별 3명이 한 번, 재검토 1명이 한 번 보았고 지적을 모두 반영했다. Playwright 의 결함 주입과 통과는 마지막 코드로 다시 실측했다.
- 덧붙여 드러난 것: registry 는 퇴역한 기체의 생존 보고도 받아 `lastReportedAt` 을 갱신한다. 그래서 퇴역 뒤 31초를 밀면 `reportingAfterRetirement` 가 참이 된다.
- 덧붙여 드러난 것: registry 는 선언·퇴역·복귀를 모두 `audit_log` 에 남긴다(`ROBOT_DECLARED`·`ROBOT_RETIRED`·`ROBOT_REINSTATED`). 행위자는 운영 서비스가 보낸 `X-Actor` 그대로다.
- 덧붙여 드러난 것: Windows 의 파이썬 `subprocess` 에서 `bash` 는 WSL 의 bash 를 잡는다. 결함 주입을 스크립트로 돌릴 때 Gradle 이 돌지 않고 «실패 0» 이 나왔다. 이 계획의 결함 주입은 손으로 하나씩 넣고, XML 에서 실패 이름을 읽는다.

**작업 위치 규칙(필수):**
- picasso-ops 체크아웃(`C:\Users\Eisen\Desktop\Labs\[projects] picasso-ops`)의 브랜치 `feat/s1b-lifecycle` 에서 일한다(Task 0 이 기준을 정한다). picasso 메인 체크아웃은 건드리지 않는다.
- 서브모듈 `picasso/` 안의 파일을 고치지 않는다(스펙 §4).
- `./gradlew --stop` 금지. 같은 작업 트리에서 Gradle 을 동시에 두 번 돌리지 않는다.
- `git add -A` 금지. 파일을 이름으로 더한다. `--no-verify` 금지.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. vitest 는 출력의 `Tests` 줄, Playwright 는 `N passed`·`N failed` 줄과 실패 이름으로 한다.
- 결함 주입은 하나씩 넣는다. 넣기 전에 파일을 복사해 두고, 확인 뒤 복원하고 `git diff`(또는 `cmp`)로 원래대로인지 본다. 빌드 스크립트만 바꾼 주입은 시험 입력이 그대로라 UP-TO-DATE 로 초록이 나오므로 `--rerun` 을 붙인다.
- KDoc(`/** */`) 안에 사선 바로 뒤에 별표를 쓰지 않는다. 중첩 주석이 열려 컴파일이 깨진다. 이 계획의 코드는 이미 피했으니 그대로 옮긴다.
- 새 파일은 LF 다(`.gitattributes` 가 집행한다).
- 작업마다 임시 커밋을 남기고 Task 9 에서 하나로 합친다. 커밋은 형식 훅 때문에 반드시 이 꼴로 한다(`-m` 을 두 번 쓰면 훅이 거절한다).

```bash
git commit -F - <<'EOF'
chore(s1b): <제목>

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- 마지막 커밋·PR·문서 문장은 사용자 지시에 따라 Fable 과 Codex 에 같은 브리프로 초안을 받아 취합한다(Gemini 한도 소진 중). 형식: 제목 `type(scope): 명사구`, 불릿 명사형, «~다» 종결 금지, em-dash·en-dash·겹화살괄호·낫표 금지.

**전제:** 이 계획 파일은 브랜치 `feat/s1b-lifecycle` 의 첫 커밋이다(계획을 쓸 때 `origin/feat/s1a-skeleton` 위에 만들었다). 계획 커밋은 Task 9 에서도 따로 남는다.

**XML 확인**(모듈 이름을 바꿔 쓴다):

```bash
for f in ops-service/build/test-results/test/*.xml; do grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"; done
for f in ops-service/build/test-results/test/*.xml; do grep -B1 "<failure" "$f" | grep -o 'testcase name="[^"]*"'; done
```

첫 줄은 모음마다 개수, 둘째 줄은 실패한 시험 이름이다.

---

## Chunk 1: 판정 모델과 registry 조작

### Task 0: 브랜치와 기준선

**Files:** 없음(환경)

- [ ] **Step 1: 브랜치와 기준 확인**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops"
git switch feat/s1b-lifecycle
git status -sb
git log --oneline -2
git fetch -q origin
gh pr view 1 --repo LivingLikeKrillin/picasso-ops --json state -q .state
git submodule status
```
Expected: 작업 트리 변경 없음, 맨 위 커밋이 이 계획의 `docs(plans)` 커밋, 그 아래가 S1a 의 `3a51f29`, 서브모듈 `6b1a255`. PR #1 이 `OPEN` 이면 그대로 진행하고 기준 브랜치는 `origin/feat/s1a-skeleton` 이다. `MERGED` 면 `git rebase --onto origin/main 3a51f29 feat/s1b-lifecycle` 로 계획 커밋을 main 위로 옮기고 기준 브랜치는 `origin/main` 이다(충돌이 나면 멈추고 보고한다). 계획 커밋의 해시와 기준 브랜치를 보고에 적는다(Task 9 가 쓴다).

- [ ] **Step 2: 기준선 시험**

```bash
./gradlew build --console=plain -q
cd ui && npm ci --no-audit --no-fund && npm test && cd ..
```
Expected: XML 확인에서 site 12, ops-service 23, e2e 3, 실패 0. vitest `9 passed`. 아니면 멈추고 보고한다(기준선 실패를 새 변경과 섞지 않는다).

### Task 1: 판정 모델

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt`
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/Rejections.kt`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/RejectionsTest.kt`

막힘과 거절은 같은 5칸(스펙 §7.4)으로 보이므로 한 모델 `Finding` 을 쓴다. 상태 막힘 4종과 연결 칸은 `Blockers`, 조작 거절 대응표는 `Rejections` 가 정한다. 둘 다 registry 에서 읽은 값만으로 판정한다.

- [ ] **Step 1: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.Blockers
import dev.picasso.ops.service.robots.Connection
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 상태 막힘 4종과 연결 칸(스펙 §7.3·§7.4). 종류마다 경계를 넣는다. */
class BlockersTest {

    private val at = Instant.parse("2026-10-07T00:10:00Z")
    private val threshold = Duration.ofSeconds(90)

    private fun robot(
        status: String,
        lastReportedAt: Instant? = null,
        retiredAt: Instant? = null,
        reportingAfterRetirement: Boolean = false,
    ) = RegistryRobot(
        robotId = "r1",
        siteId = "site-01",
        status = status,
        lastReportedAt = lastReportedAt?.toString(),
        retiredAt = retiredAt?.toString(),
        reportingAfterRetirement = reportingAfterRetirement,
    )

    private fun kinds(robot: RegistryRobot) = Blockers.of(robot, at, threshold).map { it.kind }

    @Test
    fun `연결 칸은 보고 없음, 기준 시간 안, 기준 시간 넘김으로 갈린다`() {
        assertEquals(Connection.NO_REPORT, Blockers.connection(robot("CLAIMED"), at, threshold))
        assertEquals(Connection.FRESH, Blockers.connection(robot("CONFIRMED", at.minusSeconds(90)), at, threshold))
        assertEquals(Connection.STALE, Blockers.connection(robot("CONFIRMED", at.minusSeconds(91)), at, threshold))
    }

    @Test
    fun `선언했는데 보고가 0회면 첫 보고 대기이고 현장이 푼다`() {
        val finding = Blockers.of(robot("CLAIMED"), at, threshold).single()
        assertEquals(Blockers.AWAITING_FIRST_REPORT, finding.kind)
        assertEquals(Owner.SITE, finding.owner)
        assertEquals(false, finding.inScreen)
        assertEquals(at, finding.checkedAt)
        assertEquals("r1", finding.target)
    }

    @Test
    fun `확인된 기체의 보고가 기준 시간을 넘기면 오래됨이고 직전은 아니다`() {
        assertEquals(listOf(), kinds(robot("CONFIRMED", at.minusSeconds(90))))
        assertEquals(listOf(Blockers.REPORT_STALE), kinds(robot("CONFIRMED", at.minusSeconds(91))))
    }

    @Test
    fun `퇴역 뒤 보고는 운영자가 화면 안에서 푼다`() {
        val finding = Blockers.of(
            robot("RETIRED", at.minusSeconds(5), retiredAt = at.minusSeconds(60), reportingAfterRetirement = true),
            at,
            threshold,
        ).single()
        assertEquals(Blockers.REPORTING_AFTER_RETIREMENT, finding.kind)
        assertEquals(Owner.OPERATOR, finding.owner)
        assertEquals(true, finding.inScreen)
    }

    @Test
    fun `퇴역 뒤 보고가 없는 퇴역 기체는 막힘이 없다`() {
        assertEquals(listOf(), kinds(robot("RETIRED", at.minusSeconds(300), retiredAt = at.minusSeconds(60))))
    }

    @Test
    fun `어느 문으로도 안 들어온 행은 엔지니어가 조사한다`() {
        val finding = Blockers.of(robot("UNREGISTERED"), at, threshold).single()
        assertEquals(Blockers.UNREGISTERED_ROW, finding.kind)
        assertEquals(Owner.ENGINEER, finding.owner)
    }

    @Test
    fun `발견된 기체와 신선한 확인 기체는 막힘이 없다`() {
        assertEquals(listOf(), kinds(robot("DISCOVERED")))
        assertEquals(listOf(), kinds(robot("CONFIRMED", at)))
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/RejectionsTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.operations.Rejections
import dev.picasso.ops.service.operations.RobotOp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 조작 거절 대응표(스펙 §7.4)의 행마다 응답 코드와 본문을 넣는다. */
class RejectionsTest {

    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val json = ObjectMapper()

    private data class Row(val op: RobotOp, val status: Int, val body: String, val kind: String, val owner: Owner)

    private val table = listOf(
        Row(RobotOp.DECLARE, 400, """{"error":"serial_number 가 비었다"}""", Rejections.BAD_REQUEST, Owner.ENGINEER),
        Row(RobotOp.DECLARE, 409, """{"error":"다른 문","origin":"DISCOVERED"}""", Rejections.WRONG_DOOR, Owner.NONE),
        Row(RobotOp.DECLARE, 409, """{"error":"퇴역","status":"RETIRED"}""", Rejections.RETIRED_ALREADY, Owner.OPERATOR),
        Row(RobotOp.RETIRE, 400, """{"error":"사유가 없다"}""", Rejections.RETIRE_BAD_REQUEST, Owner.OPERATOR),
        Row(RobotOp.RETIRE, 404, """{"error":"모르는 기체다"}""", Rejections.UNKNOWN_ROBOT, Owner.ENGINEER),
        Row(RobotOp.REINSTATE, 404, """{"error":"모르는 기체다"}""", Rejections.UNKNOWN_ROBOT, Owner.ENGINEER),
    )

    @Test
    fun `대응표의 행마다 종류와 해결 담당이 맞다`() {
        table.forEach { row ->
            val finding = Rejections.of(row.op, row.status, json.readTree(row.body), at, "r1")
            assertEquals(row.kind to row.owner, finding.kind to finding.owner, "$row")
            assertEquals(at, finding.checkedAt)
            assertEquals("r1", finding.target)
        }
    }

    @Test
    fun `409 는 본문으로 가르고 관측값에 가른 본문을 싣는다`() {
        val wrongDoor = Rejections.of(RobotOp.DECLARE, 409, json.readTree("""{"origin":"DISCOVERED"}"""), at, "r1")
        val retired = Rejections.of(RobotOp.DECLARE, 409, json.readTree("""{"status":"RETIRED"}"""), at, "r1")
        assertEquals("HTTP 409, origin=DISCOVERED", wrongDoor.observed)
        assertEquals("HTTP 409, status=RETIRED", retired.observed)
    }

    @Test
    fun `표에 없는 응답은 아는 종류로 접지 않고 화면 밖 조사로 둔다`() {
        val unclassified = Rejections.of(RobotOp.DECLARE, 409, json.readTree("{}"), at, "r1")
        assertEquals(Rejections.UNCLASSIFIED to false, unclassified.kind to unclassified.inScreen)
        assertEquals(Rejections.UNCLASSIFIED, Rejections.of(RobotOp.DECLARE, 404, null, at, "r1").kind)
        assertEquals(Rejections.UNCLASSIFIED, Rejections.of(RobotOp.REINSTATE, 400, null, at, "r1").kind)
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*BlockersTest' --tests '*RejectionsTest' --console=plain`
Expected: `compileTestKotlin` 실패(`Blockers`·`Rejections`·`Finding` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt`:

```kotlin
package dev.picasso.ops.service.finding

import java.time.Instant

/** 누가 푸는가(스펙 §7.4). */
enum class Owner { SITE, OPERATOR, ENGINEER, NONE }

/**
 * 막힘이나 거절 한 건. 종류를 값으로 둔다. 사유 문장만 주면 읽는 쪽이 문자열을 대조하게 된다(스펙 §7.4).
 *
 * 화면에 내는 칸 5개와 맞춘다. 종류는 [kind], 관측값과 기대값은 [observed]·[expected], 마지막 확인 시각은
 * [checkedAt], 해결 담당은 [owner]·[inScreen], 바로 갈 링크는 [target] 이다. 링크 모양은 화면이 정한다.
 *
 * @param checkedAt 이 판정이 기댄 값을 registry 에서 읽은 시각
 * @param inScreen 화면 안에서 풀 수 있는가. 거짓이면 현장 등 화면 밖에서 풀린다
 * @param target 링크가 가리킬 기체 id. 없으면 널
 */
data class Finding(
    val kind: String,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val owner: Owner,
    val inScreen: Boolean,
    val action: String,
    val target: String?,
)
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt`:

```kotlin
package dev.picasso.ops.service.robots

import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.registry.RegistryRobot
import java.time.Duration
import java.time.Instant

/** 연결 칸(스펙 §7.3). 원장 상태와 합치지 않는다. */
enum class Connection { FRESH, STALE, NO_REPORT }

/**
 * 기체별 상태 막힘 4종과 연결 칸(스펙 §7.3·§7.4). 모두 registry 에서 읽은 값과 그 시각만으로 정한다.
 *
 * [at] 은 목록을 읽은 시각이다. 지금 시각이 아니라 읽은 시각으로 판정해야, registry 가 침묵해 직전 목록을
 * 보일 때 판정도 그 목록과 같은 시점의 것이 된다.
 */
object Blockers {

    const val AWAITING_FIRST_REPORT = "AWAITING_FIRST_REPORT"
    const val REPORT_STALE = "REPORT_STALE"
    const val REPORTING_AFTER_RETIREMENT = "REPORTING_AFTER_RETIREMENT"
    const val UNREGISTERED_ROW = "UNREGISTERED_ROW"

    /** 마지막 보고가 [threshold] 보다 오래면 오래됨이다. 정확히 [threshold] 이면 아직 신선하다. */
    fun connection(robot: RegistryRobot, at: Instant, threshold: Duration): Connection {
        val last = robot.lastReportedAt?.let(Instant::parse) ?: return Connection.NO_REPORT
        return if (Duration.between(last, at) > threshold) Connection.STALE else Connection.FRESH
    }

    fun of(robot: RegistryRobot, at: Instant, threshold: Duration): List<Finding> = buildList {
        fun add(kind: String, observed: String, expected: String, owner: Owner, inScreen: Boolean, action: String) =
            add(Finding(kind, observed, expected, at, owner, inScreen, action, robot.robotId))

        if (robot.status == "CLAIMED" && robot.lastReportedAt == null) {
            add(AWAITING_FIRST_REPORT, "보고 0회", "생존 보고 1회 이상", Owner.SITE, false, "기체·어댑터 기동과 사이트 id 확인")
        }
        if (robot.status == "CONFIRMED" && connection(robot, at, threshold) == Connection.STALE) {
            add(
                REPORT_STALE, "마지막 보고 ${robot.lastReportedAt}", "${threshold.seconds}초 안의 보고",
                Owner.SITE, false, "연결 확인",
            )
        }
        if (robot.reportingAfterRetirement) {
            add(
                REPORTING_AFTER_RETIREMENT, "퇴역 ${robot.retiredAt}, 마지막 보고 ${robot.lastReportedAt}", "퇴역 뒤 보고 없음",
                Owner.OPERATOR, true, "현장에서 기체를 내리거나 복귀",
            )
        }
        if (robot.status == "UNREGISTERED") {
            add(UNREGISTERED_ROW, "출처 없음", "선언 또는 발견으로 들어온 행", Owner.ENGINEER, false, "조사")
        }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/operations/Rejections.kt`:

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.finding.Owner
import java.time.Instant

/** 기체 조작 3가지(스펙 §7.2). */
enum class RobotOp { DECLARE, RETIRE, REINSTATE }

/**
 * registry 가 응답한 거절(400/404/409)을 종류로 옮기는 대응표(스펙 §7.4). 409 는 본문으로 가르며 하나로 다루지 않는다.
 *
 * 401 과 5xx 는 여기 오지 않는다. 401 은 화면 전체 상태이고, 5xx 는 응답 없음과 같이 다룬다(스펙 §7.4).
 * 표에 없는 응답은 [UNCLASSIFIED] 로 두어 엔지니어가 본다. 아는 종류로 접지 않는다.
 *
 * 거절은 대부분 화면 안에서 풀린다(고쳐서 다시, 복귀, 목록 새로 읽기). [UNCLASSIFIED] 만 registry 응답을
 * 조사해야 하므로 화면 밖이다. [WRONG_DOOR] 는 할 일이 없지만 그 사실을 화면이 알린다.
 */
object Rejections {

    const val BAD_REQUEST = "BAD_REQUEST"
    const val WRONG_DOOR = "WRONG_DOOR"
    const val RETIRED_ALREADY = "RETIRED_ALREADY"
    const val RETIRE_BAD_REQUEST = "RETIRE_BAD_REQUEST"
    const val UNKNOWN_ROBOT = "UNKNOWN_ROBOT"
    const val UNCLASSIFIED = "UNCLASSIFIED"

    fun of(op: RobotOp, status: Int, body: JsonNode?, checkedAt: Instant, robotId: String): Finding {
        val error = body?.get("error")?.asText()
        val origin = body?.get("origin")?.asText()
        val ledger = body?.get("status")?.asText()
        val expected = if (op == RobotOp.DECLARE) "201 또는 200" else "200"

        fun finding(kind: String, observed: String, owner: Owner, action: String, inScreen: Boolean = true) =
            Finding(kind, observed, expected, checkedAt, owner, inScreen, action, robotId)

        val detail = listOfNotNull("HTTP $status", origin?.let { "origin=$it" }, ledger?.let { "status=$it" }, error)
            .joinToString(", ")
        return when {
            op == RobotOp.DECLARE && status == 400 -> finding(BAD_REQUEST, detail, Owner.ENGINEER, "고쳐서 다시")
            op == RobotOp.DECLARE && status == 409 && origin != null ->
                finding(WRONG_DOOR, detail, Owner.NONE, "고칠 것 없음(이미 다른 문으로 들어온 기체)")
            op == RobotOp.DECLARE && status == 409 && ledger == "RETIRED" ->
                finding(RETIRED_ALREADY, detail, Owner.OPERATOR, "복귀")
            op == RobotOp.RETIRE && status == 400 -> finding(RETIRE_BAD_REQUEST, detail, Owner.OPERATOR, "사유를 넣어 다시")
            op != RobotOp.DECLARE && status == 404 -> finding(UNKNOWN_ROBOT, detail, Owner.ENGINEER, "목록 새로 읽고 조사")
            else -> finding(UNCLASSIFIED, detail, Owner.ENGINEER, "registry 응답 조사", inScreen = false)
        }
    }
}
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: Step 2 와 같다.
Expected: XML 에서 `BlockersTest` 7개, `RejectionsTest` 3개, 실패 0.

- [ ] **Step 5: 결함 주입 3건(하나씩)**

① `Blockers.connection` 의 `> threshold` 를 `>= threshold` 로 바꾼다. Expected 실패 이름: `연결 칸은 보고 없음, 기준 시간 안, 기준 시간 넘김으로 갈린다()`, `확인된 기체의 보고가 기준 시간을 넘기면 오래됨이고 직전은 아니다()`.
② `Rejections.of` 의 `op == RobotOp.DECLARE && status == 409 && origin != null ->` 에서 `&& origin != null` 을 지운다(409 를 본문으로 가르지 않는다). Expected 실패 이름: `대응표의 행마다 종류와 해결 담당이 맞다()`, `표에 없는 응답은 아는 종류로 접지 않고 화면 밖 조사로 둔다()`.
③ `Blockers.of` 의 `REPORTING_AFTER_RETIREMENT` 의 `Owner.OPERATOR, true,` 를 `Owner.SITE, true,` 로 바꾼다(담당을 바꾼다). Expected 실패 이름: `퇴역 뒤 보고는 운영자가 화면 안에서 푼다()`.
각각 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/finding/Finding.kt ops-service/src/main/kotlin/dev/picasso/ops/service/robots/Blockers.kt ops-service/src/main/kotlin/dev/picasso/ops/service/operations/Rejections.kt ops-service/src/test/kotlin/dev/picasso/ops/service/BlockersTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RejectionsTest.kt
git commit -F - <<'EOF'
chore(s1b): 막힘과 거절 판정 모델

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: registry 조작과 토큰 확인

**Files:**
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt`(파일 전체를 아래로 바꾼다)
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryWritesTest.kt`

조작 3가지(`RobotWrites`)와 관문 안 토큰 확인(`TokenProbe`)을 더한다. 조작은 응답이 오면 코드와 본문을 그대로 넘기고(`RegistryWrite.Answered`), 오지 않으면 `NoResponse` 다. 분류는 부르는 쪽이 대응표로 한다. 형식이 틀린 registry 주소는 기동에서 거절한다. 토큰 속성 이름은 `token` 으로 바꾼다(새 메서드 `operatorToken()` 과 겹치지 않게).

- [ ] **Step 1: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryWritesTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryClient
import dev.picasso.ops.service.registry.RegistryWrite
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 조작과 토큰 확인이 registry 에 보내는 모양(스펙 §7.1·§7.2). registry 는 JDK HttpServer 대역이다. */
class RegistryWritesTest {

    private class Seen(val method: String, val path: String, val headers: Map<String, String?>, val body: String)

    private var server: HttpServer? = null
    @Volatile private var seen: Seen? = null
    private val json = ObjectMapper()

    private fun serve(status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            val headers = listOf("Authorization", "X-Actor", "Content-Type")
                .associateWith { exchange.requestHeaders.getFirst(it) }
            seen = Seen(
                exchange.requestMethod,
                exchange.requestURI.rawPath,
                headers,
                exchange.requestBody.readBytes().decodeToString(),
            )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `선언은 운영자 토큰과 X-Actor 를 싣고 registry 의 본문 모양으로 보낸다`() {
        val write = RegistryClient(serve(201, """{"robot":"r1","status":"CLAIMED"}"""), "op-t")
            .declare("site-01", "r1", "SN-1", null, "engineer/lee")
        assertEquals(RegistryWrite.Answered(201, """{"robot":"r1","status":"CLAIMED"}"""), write)
        val request = seen!!
        assertEquals("POST", request.method)
        assertEquals("/operations/robots", request.path)
        assertEquals("Bearer op-t", request.headers["Authorization"])
        assertEquals("engineer/lee", request.headers["X-Actor"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals(
            json.readTree("""{"robot_id":"r1","site":"site-01","serial_number":"SN-1"}"""),
            json.readTree(request.body),
        )
    }

    @Test
    fun `퇴역은 사유를 싣고 복귀는 같은 경로에 DELETE 를 보낸다`() {
        val url = serve(200, "{}")
        val client = RegistryClient(url, "op-t")
        client.retire("r 1", "정비", "operator/kim")
        assertEquals("POST" to "/operations/robots/r%201/retirement", seen!!.method to seen!!.path)
        assertEquals(json.readTree("""{"reason":"정비"}"""), json.readTree(seen!!.body))
        client.reinstate("r 1", "operator/kim")
        assertEquals("DELETE" to "/operations/robots/r%201/retirement", seen!!.method to seen!!.path)
    }

    @Test
    fun `거절도 응답이므로 코드와 본문을 그대로 넘긴다`() {
        val write = RegistryClient(serve(409, """{"status":"RETIRED"}"""), "op-t").retire("r1", "정비", "operator/kim")
        assertEquals(RegistryWrite.Answered(409, """{"status":"RETIRED"}"""), write)
    }

    @Test
    fun `닿지 않는 registry 는 응답 없음이다`() {
        val url = serve(200, "{}")
        server!!.stop(0)
        server = null
        assertIs<RegistryWrite.NoResponse>(RegistryClient(url, "op-t").reinstate("r1", "operator/kim"))
    }

    @Test
    fun `토큰 확인은 관문 안의 읽기를 부르고 401 을 토큰 불일치로 본다`() {
        assertEquals(RegistryCall.Unauthorized, RegistryClient(serve(401, ""), "op-t").operatorToken())
        assertEquals("GET" to "/operations/adapters", seen!!.method to seen!!.path)
        stop()
        assertEquals(RegistryCall.Ok(Unit), RegistryClient(serve(200, "[]"), "op-t").operatorToken())
    }

    @Test
    fun `형식이 틀린 registry 주소는 기동에서 거절한다`() {
        assertFailsWith<IllegalArgumentException> { RegistryClient("127.0.0.1:8781", "op-t") }
        assertFailsWith<IllegalArgumentException> { RegistryClient("", "op-t") }
        assertEquals("http://127.0.0.1:8781", RegistryClient.checkBaseUrl("http://127.0.0.1:8781/"))
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*RegistryWritesTest' --console=plain`
Expected: `compileTestKotlin` 실패(`declare`·`operatorToken`·`checkBaseUrl`·`RegistryWrite` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt`(전체):

```kotlin
package dev.picasso.ops.service.registry

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * registry 호출 한 번의 결과. «없음» 과 «모름» 을 접지 않는다(스펙 §9).
 *
 * 읽기에서는 401 이 아닌 실패를 모두 [Silent] 로 둔다. 연결 실패·시간 초과·5xx·해석 불가 어느 것이든
 * 값을 모른다는 점이 같다. 조작은 [RegistryWrite] 로 따로 돌려준다.
 *
 * [Unauthorized] 는 운영자 토큰 관문(`/operations` 이하) 안의 호출에서만 나온다. 목록 읽기 `/diag/robots` 는
 * 관문 밖이라 토큰을 [TokenProbe] 가 관문 안의 읽기로 따로 확인한다.
 */
sealed interface RegistryCall<out T> {
    data class Ok<T>(val value: T) : RegistryCall<T>

    data class Silent(val cause: String) : RegistryCall<Nothing>

    /** 운영 서비스의 운영자 토큰이 registry 와 맞지 않는다(스펙 §7.4). */
    data object Unauthorized : RegistryCall<Nothing>
}

/** registry `GET /diag/robots` 의 한 줄. 운영 서비스가 쓰는 칸만 읽고 나머지는 버린다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RegistryRobot(
    val robotId: String,
    val siteId: String,
    val serialNumber: String? = null,
    val displayName: String? = null,
    val origin: String? = null,
    val registeredAt: String? = null,
    val registeredBy: String? = null,
    val lastReportedAt: String? = null,
    val status: String,
    val retiredAt: String? = null,
    val retiredBy: String? = null,
    val retiredReason: String? = null,
    val reportingAfterRetirement: Boolean = false,
)

/** 기체 목록의 출처. 시험이 registry 없이 대신 끼운다. */
fun interface RobotSource {
    fun robots(siteId: String): RegistryCall<List<RegistryRobot>>
}

/** 운영자 토큰 확인. 목록 읽기는 관문 밖이라 401 이 오지 않으므로 관문 안의 읽기를 따로 부른다. */
fun interface TokenProbe {
    fun operatorToken(): RegistryCall<Unit>
}

/**
 * 조작 한 번의 결과. 응답이 오면 코드와 본문을 그대로 넘기고, 분류는 부르는 쪽이 대응표로 한다(스펙 §7.4).
 * 응답이 오지 않으면 반영 여부를 모르는 것이며, 거절과 섞지 않는다(스펙 §9).
 */
sealed interface RegistryWrite {
    data class Answered(val status: Int, val body: String) : RegistryWrite

    data class NoResponse(val cause: String) : RegistryWrite
}

/** 기체 조작 3가지(스펙 §7.2). 시험이 registry 없이 대신 끼운다. */
interface RobotWrites {
    fun declare(siteId: String, robotId: String, serialNumber: String, displayName: String?, actor: String): RegistryWrite

    fun retire(robotId: String, reason: String, actor: String): RegistryWrite

    fun reinstate(robotId: String, actor: String): RegistryWrite
}

/** registry REST 클라이언트. 운영 서비스만 운영자 토큰을 쥔다(스펙 §4). DB 에 직결하지 않는다. */
class RegistryClient(
    baseUrl: String,
    private val token: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) : RobotSource, TokenProbe, RobotWrites, AutoCloseable {

    private val base = checkBaseUrl(baseUrl)

    /** JDK 21 의 HttpClient 는 닫아야 셀렉터 스레드가 끝난다. 스프링이 빈을 내릴 때 부른다. */
    override fun close() = http.close()

    /** 퇴역 기체를 늘 포함한다. 기본값(`retired=false`)은 퇴역 뒤 보고와 복귀를 감춘다(스펙 §7.2). */
    override fun robots(siteId: String): RegistryCall<List<RegistryRobot>> {
        val site = URLEncoder.encode(siteId, StandardCharsets.UTF_8)
        return get("/diag/robots?retired=true&site=$site") { json.readValue<List<RegistryRobot>?>(it) }
    }

    /** 관문 안의 읽기 하나로 토큰을 본다. 읽은 값은 쓰지 않는다. */
    override fun operatorToken(): RegistryCall<Unit> = get("/operations/adapters") { }

    override fun declare(
        siteId: String,
        robotId: String,
        serialNumber: String,
        displayName: String?,
        actor: String,
    ): RegistryWrite {
        val body = json.createObjectNode()
            .put("robot_id", robotId)
            .put("site", siteId)
            .put("serial_number", serialNumber)
        if (displayName != null) body.put("display_name", displayName)
        return send("POST", "/operations/robots", actor, json.writeValueAsString(body))
    }

    override fun retire(robotId: String, reason: String, actor: String): RegistryWrite =
        send(
            "POST", "/operations/robots/${segment(robotId)}/retirement", actor,
            json.writeValueAsString(json.createObjectNode().put("reason", reason)),
        )

    override fun reinstate(robotId: String, actor: String): RegistryWrite =
        send("DELETE", "/operations/robots/${segment(robotId)}/retirement", actor, null)

    /** [read] 가 널을 내면(본문 `null`) 값을 모르는 것이다. `OK` 인데 목록이 널인 보기를 만들지 않는다. */
    private fun <T : Any> get(path: String, read: (String) -> T?): RegistryCall<T> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return RegistryCall.Silent("응답 없음: ${e.javaClass.simpleName}")
        }
        return when (val status = response.statusCode()) {
            in 200..299 -> try {
                read(response.body())?.let { RegistryCall.Ok(it) } ?: RegistryCall.Silent("본문이 null 이다")
            } catch (e: JacksonException) {
                RegistryCall.Silent("본문 해석 실패: ${e.originalMessage}")
            }
            401 -> RegistryCall.Unauthorized
            else -> RegistryCall.Silent("HTTP $status")
        }
    }

    private fun send(method: String, path: String, actor: String, body: String?): RegistryWrite {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token")
            .header("X-Actor", actor)
            .header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            RegistryWrite.Answered(response.statusCode(), response.body())
        } catch (e: IOException) {
            RegistryWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
        }
    }

    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(3)

        /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 500 이 되어 화면에는 운영 서비스 불통으로 보인다. */
        fun checkBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "registry 주소가 http(s)://호스트[:포트] 꼴이 아니다: '$baseUrl'"
            }
            return trimmed
        }
    }
}
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test --console=plain`
Expected: XML 에서 `RegistryWritesTest` 6개, 기존 `RegistryClientTest` 6개를 포함해 모듈 전체 실패 0. 기존 `OpsApplication` 은 바꾸지 않았으므로 그대로 컴파일된다.

- [ ] **Step 5: 결함 주입 2건(하나씩)**

① `send` 의 `.header("X-Actor", actor)` 줄을 지운다. Expected 실패 이름: `선언은 운영자 토큰과 X-Actor 를 싣고 registry 의 본문 모양으로 보낸다()`.
② `private val base = checkBaseUrl(baseUrl)` 를 `private val base = baseUrl.trimEnd('/')` 로 바꾼다. Expected 실패 이름: `형식이 틀린 registry 주소는 기동에서 거절한다()`.
각각 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/registry/RegistryClient.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RegistryWritesTest.kt
git commit -F - <<'EOF'
chore(s1b): registry 조작과 토큰 확인

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 2: 운영 서비스의 조작과 목록

### Task 3: 조작 서비스

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/operations/RobotOperations.kt`
- Test: `ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt`

조작 한 번을 registry 에 보내고 대응표로 분류해 조작 기록에 남긴다. 응답이 없거나 5xx 면 «응답 없음» 을 남기고 1초 뒤 목록을 다시 읽어 반영 여부를 같은 요청 id 의 새 행으로 붙인다(스펙 §9, 머리말 결정 9). 시험은 지연을 0 으로 넣는다. 선언의 «반영됨» 은 머리말 결정 10 이다. 401 은 거절 행으로 남기되 대응표가 아니라 화면 전체 상태로 넘긴다.

- [ ] **Step 1: 실패하는 시험 쓰기**

`ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt`:

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.Rejections
import dev.picasso.ops.service.operations.RobotOperations
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.registry.RobotWrites
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 조작 한 번이 조작 기록에 남는 모양과 응답 없음 뒤 재조회(스펙 §7.1·§9). registry 는 대역이다. */
class RobotOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val at = Instant.parse("2026-10-07T00:00:00Z")
    private val kim = Actor(Mode.OPERATOR, "kim")

    private var answer: RegistryWrite = RegistryWrite.Answered(200, """{"robot":"r1","status":"RETIRED"}""")
    private var list: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(emptyList())
    private val sent = mutableListOf<String>()

    private val writes = object : RobotWrites {
        override fun declare(siteId: String, robotId: String, serialNumber: String, displayName: String?, actor: String) =
            answer.also { sent += "declare $siteId $robotId $serialNumber $actor" }

        override fun retire(robotId: String, reason: String, actor: String) =
            answer.also { sent += "retire $robotId $reason $actor" }

        override fun reinstate(robotId: String, actor: String) = answer.also { sent += "reinstate $robotId $actor" }
    }

    private val operations =
        RobotOperations(writes, { list }, log, "site-01", Clock.fixed(at, ZoneOffset.UTC), requeryDelay = Duration.ZERO)

    private fun robot(status: String, origin: String = "DECLARED") =
        RegistryRobot(robotId = "r1", siteId = "site-01", status = status, origin = origin)

    private val lee = Actor(Mode.ENGINEER, "lee")

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    @Test
    fun `성공한 조작은 사유와 행위자와 registry 응답과 함께 한 행으로 남는다`() {
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(listOf("retire r1 정비 operator/kim"), sent)
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals("robot r1", row.target)
        assertEquals("정비", row.reason)
        assertEquals(OperationResult.SUCCEEDED, row.result)
        assertTrue(row.registryResponse!!.contains("\"status\": 200"), row.registryResponse)
    }

    @Test
    fun `선언은 운영 서비스의 사이트로 보낸다`() {
        answer = RegistryWrite.Answered(201, """{"robot":"r1","status":"CLAIMED"}""")
        operations.declare(Actor(Mode.ENGINEER, "lee"), "r1", "SN-1", null)
        assertEquals(listOf("declare site-01 r1 SN-1 engineer/lee"), sent)
    }

    @Test
    fun `거절은 대응표로 옮기고 거절 행으로 남긴다`() {
        answer = RegistryWrite.Answered(409, """{"error":"퇴역한 기체다","status":"RETIRED"}""")
        val outcome = operations.declare(Actor(Mode.ENGINEER, "lee"), "r1", "SN-1", null)
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(Rejections.RETIRED_ALREADY, outcome.rejection!!.kind)
        assertEquals(at, outcome.rejection.checkedAt)
        assertEquals(OperationResult.REJECTED, log.list().single().result)
    }

    @Test
    fun `401 은 거절 행으로 남기고 대응표가 아니라 전체 상태로 넘긴다`() {
        answer = RegistryWrite.Answered(401, "")
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(true, outcome.unauthorized)
        assertNull(outcome.rejection)
        assertEquals(OperationResult.REJECTED, log.list().single().result)
    }

    @Test
    fun `응답이 없으면 응답 없음을 남기고 다시 읽어 반영됨을 같은 요청 id 로 붙인다`() {
        answer = RegistryWrite.NoResponse("응답 없음: HttpTimeoutException")
        list = RegistryCall.Ok(listOf(robot("RETIRED")))
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        // 확인 행에는 재조회에서 본 원장 상태가 남는다.
        assertTrue(rows.first().registryResponse!!.contains("\"status\": \"RETIRED\""), rows.first().registryResponse)
    }

    @Test
    fun `선언의 반영됨은 선언으로 들어와 퇴역하지 않은 기체다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Ok(listOf(robot("CLAIMED")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
        list = RegistryCall.Ok(emptyList())
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
    }

    @Test
    fun `목록에 있어도 퇴역했거나 발견된 기체면 선언이 반영된 것이 아니다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Ok(listOf(robot("RETIRED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
        list = RegistryCall.Ok(listOf(robot("DISCOVERED", origin = "DISCOVERED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.declare(lee, "r1", "SN-1", null).confirmation)
    }

    @Test
    fun `5xx 는 응답 없음과 같이 다루고 반영 안 됨을 붙인다`() {
        answer = RegistryWrite.Answered(503, "")
        list = RegistryCall.Ok(listOf(robot("CONFIRMED")))
        val outcome = operations.retire(kim, "r1", "정비")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.rejection)
    }

    @Test
    fun `복귀의 반영됨은 퇴역이 아닌 상태다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Ok(listOf(robot("CLAIMED")))
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.reinstate(kim, "r1").confirmation)
        list = RegistryCall.Ok(listOf(robot("RETIRED")))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, operations.reinstate(kim, "r1").confirmation)
    }

    @Test
    fun `재조회도 실패하면 확인 행을 붙이지 않고 모름으로 둔다`() {
        answer = RegistryWrite.NoResponse("응답 없음")
        list = RegistryCall.Silent("응답 없음")
        val outcome = operations.declare(Actor(Mode.ENGINEER, "lee"), "r1", "SN-1", null)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*RobotOperationsTest' --console=plain`
Expected: `compileTestKotlin` 실패(`RobotOperations` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/operations/RobotOperations.kt`:

```kotlin
package dev.picasso.ops.service.operations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistryWrite
import dev.picasso.ops.service.registry.RobotSource
import dev.picasso.ops.service.registry.RobotWrites
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 조작 한 번의 답. 화면은 이것으로 결과를 보인다.
 *
 * @param result 처음 남긴 행의 결과. 성공, 거절, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이며, 그때 화면은 반영되었을 수 있으나 확인하지 못했다고 보인다
 * @param rejection registry 가 400/404/409 로 답했을 때의 대응표 판정(스펙 §7.4)
 * @param unauthorized registry 가 401 로 답했다. 조작별이 아니라 화면 전체 상태로 보인다(스펙 §7.4)
 */
data class OperationOutcome(
    val requestId: UUID,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val rejection: Finding?,
    val unauthorized: Boolean,
    val registryStatus: Int?,
)

/**
 * 기체 선언·퇴역·복귀(스펙 §7.2). 모든 조작을 조작 기록에 남긴다(스펙 §7.1).
 *
 * 응답 없음(연결 실패·시간 초과·5xx)은 거절과 다르다. 요청이 닿았는지 모르므로 «응답 없음» 을 남기고 목록을
 * 다시 읽어, 반영 여부를 같은 요청 id 의 새 행으로 붙인다(스펙 §9). 재시도는 하지 않는다. 재시도는 새 요청 id 를
 * 받는 새 조작이며 사람이 정한다.
 *
 * 재조회는 [requeryDelay] 뒤 한 번이다. 시간 초과 직후에는 registry 가 아직 커밋 중일 수 있어 바로 읽으면
 * «반영 안 됨» 을 잘못 남길 수 있다. 재조회도 실패하면 확인 행을 붙이지 않고, 화면이 목록에서 확인하게 한다.
 */
class RobotOperations(
    private val writes: RobotWrites,
    private val reads: RobotSource,
    private val log: OperationLog,
    private val siteId: String,
    private val clock: Clock,
    private val requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = ObjectMapper(),
) {

    fun declare(actor: Actor, robotId: String, serialNumber: String, displayName: String?): OperationOutcome {
        val request = json.createObjectNode()
            .put("op", RobotOp.DECLARE.name)
            .put("robot_id", robotId)
            .put("site", siteId)
            .put("serial_number", serialNumber)
            .put("display_name", displayName)
        // 선언이 반영된 기체는 선언으로 들어와 퇴역하지 않은 기체다. 목록에는 퇴역 기체와 발견된 기체도 있으므로
        // 그 기체가 목록에 있다는 것만으로는 이 선언이 반영됐다고 할 수 없다(registry 는 그 둘을 409 로 거절한다).
        val declared = { robots: List<RegistryRobot> ->
            robots.firstOrNull { it.robotId == robotId }?.let { it.origin == "DECLARED" && it.status != "RETIRED" } == true
        }
        return run(RobotOp.DECLARE, actor, robotId, request, reason = null, applied = declared) {
            writes.declare(siteId, robotId, serialNumber, displayName, actor.header())
        }
    }

    fun retire(actor: Actor, robotId: String, reason: String): OperationOutcome {
        val request = json.createObjectNode().put("op", RobotOp.RETIRE.name).put("robot_id", robotId).put("reason", reason)
        return run(RobotOp.RETIRE, actor, robotId, request, reason, applied = { robots -> statusOf(robots, robotId) == "RETIRED" }) {
            writes.retire(robotId, reason, actor.header())
        }
    }

    fun reinstate(actor: Actor, robotId: String): OperationOutcome {
        val request = json.createObjectNode().put("op", RobotOp.REINSTATE.name).put("robot_id", robotId)
        return run(
            RobotOp.REINSTATE, actor, robotId, request, reason = null,
            applied = { robots -> statusOf(robots, robotId).let { it != null && it != "RETIRED" } },
        ) {
            writes.reinstate(robotId, actor.header())
        }
    }

    private fun run(
        op: RobotOp,
        actor: Actor,
        robotId: String,
        request: ObjectNode,
        reason: String?,
        applied: (List<RegistryRobot>) -> Boolean,
        call: () -> RegistryWrite,
    ): OperationOutcome {
        val id = UUID.randomUUID()
        val target = "robot $robotId"
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String?) =
            log.append(id, actor, target, requestJson, reason, result, response)

        val write = call()
        if (write is RegistryWrite.NoResponse) {
            record(OperationResult.NO_RESPONSE, json.writeValueAsString(json.createObjectNode().put("cause", write.cause)))
            return confirm(id, robotId, applied, ::record, status = null)
        }
        write as RegistryWrite.Answered
        val response = responseJson(write)
        return when {
            write.status in 200..299 -> {
                record(OperationResult.SUCCEEDED, response)
                OperationOutcome(id, OperationResult.SUCCEEDED, null, null, false, write.status)
            }
            write.status == 401 -> {
                record(OperationResult.REJECTED, response)
                OperationOutcome(id, OperationResult.REJECTED, null, null, true, write.status)
            }
            // registry 가 응답했지만 반영 여부를 알 수 없다. 응답 없음과 같이 다룬다(스펙 §7.4).
            write.status >= 500 -> {
                record(OperationResult.NO_RESPONSE, response)
                confirm(id, robotId, applied, ::record, write.status)
            }
            else -> {
                record(OperationResult.REJECTED, response)
                val rejection = Rejections.of(op, write.status, parse(write.body), clock.instant(), robotId)
                OperationOutcome(id, OperationResult.REJECTED, null, rejection, false, write.status)
            }
        }
    }

    /**
     * 응답 없음 뒤 재조회(스펙 §9). 목록을 못 읽으면 행을 붙이지 않고 확인 결과를 널로 둔다.
     * 확인 행의 `registry_response` 에는 재조회에서 본 그 기체의 출처와 원장 상태를 남겨, 그 행만 읽어도 판정 근거가 보이게 한다.
     */
    private fun confirm(
        id: UUID,
        robotId: String,
        applied: (List<RegistryRobot>) -> Boolean,
        record: (OperationResult, String?) -> Unit,
        status: Int?,
    ): OperationOutcome {
        if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
        val robots = reads.robots(siteId)
        if (robots !is RegistryCall.Ok) return OperationOutcome(id, OperationResult.NO_RESPONSE, null, null, false, status)
        val confirmation = if (applied(robots.value)) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
        val seen = robots.value.firstOrNull { it.robotId == robotId }
        val observed = json.createObjectNode()
        if (seen == null) {
            observed.putNull("observed")
        } else {
            observed.putObject("observed").put("origin", seen.origin).put("status", seen.status)
        }
        record(confirmation, json.writeValueAsString(observed))
        return OperationOutcome(id, OperationResult.NO_RESPONSE, confirmation, null, false, status)
    }

    private fun statusOf(robots: List<RegistryRobot>, robotId: String): String? = robots.firstOrNull { it.robotId == robotId }?.status

    private fun parse(body: String): JsonNode? = runCatching { json.readTree(body) }.getOrNull()?.takeIf { it.isObject }

    /** 조작 기록의 `registry_response` 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다. */
    private fun responseJson(write: RegistryWrite.Answered): String {
        val node = json.createObjectNode().put("status", write.status)
        val body = parse(write.body)
        if (body != null) node.set<JsonNode>("body", body) else node.put("body", write.body)
        return json.writeValueAsString(node)
    }
}
```

- [ ] **Step 4: 시험이 통과하는지 확인**

Run: Step 2 와 같다.
Expected: XML 에서 `RobotOperationsTest` 10개, 실패 0.

- [ ] **Step 5: 결함 주입 2건(하나씩)**

① 재조회 결과를 버린다. `confirm` 의 `if (robots !is RegistryCall.Ok) return OperationOutcome(id, OperationResult.NO_RESPONSE, null, null, false, status)` 줄의 조건을 `if (true)` 로 바꾼다(재조회는 부르지만 결과를 쓰지 않는다. 호출 자체를 지우면 아래 코드가 컴파일되지 않는다). Expected 실패 이름: `응답이 없으면 응답 없음을 남기고 다시 읽어 반영됨을 같은 요청 id 로 붙인다()`, `5xx 는 응답 없음과 같이 다루고 반영 안 됨을 붙인다()`, `복귀의 반영됨은 퇴역이 아닌 상태다()`, `선언의 반영됨은 선언으로 들어와 퇴역하지 않은 기체다()`, `목록에 있어도 퇴역했거나 발견된 기체면 선언이 반영된 것이 아니다()`.
② 선언의 반영 판정을 스펙 §9 문구 그대로로 넓힌다. `robots.firstOrNull { it.robotId == robotId }?.let { it.origin == "DECLARED" && it.status != "RETIRED" } == true` 를 `robots.any { it.robotId == robotId }` 로 바꾼다. Expected 실패 이름: `목록에 있어도 퇴역했거나 발견된 기체면 선언이 반영된 것이 아니다()`.
각각 `./gradlew :ops-service:test --tests '*RobotOperationsTest' --console=plain` 으로 돌리고, 되돌리고 초록을 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/operations/RobotOperations.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotOperationsTest.kt
git commit -F - <<'EOF'
chore(s1b): 조작 서비스와 응답 없음 뒤 재조회

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 4: 목록 서비스, 조작 API, 배선

**Files:**
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt`(전체)
- Modify: `ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt`(전체)
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/web/RobotOperationsController.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`(전체)
- Modify: `ops-service/src/main/resources/ops-service.properties`(전체)
- Modify(두 줄): `e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt`

목록 서비스의 생성자가 바뀌므로(`TokenProbe`, 기준 시간) 배선과 한 작업으로 묶는다. 목록 응답의 기체 모양이 바뀌므로 S1a 의 `SkeletonTest` 가 읽는 자리 두 곳도 이 작업에서 고친다(통째로 바꾸는 것은 Task 5). 화면은 Task 6 전까지 목록 칸을 비워 그리며, 이는 Task 6 이 고친다. 목록 응답의 기체는 `RobotView`(registry 값, 연결 칸, 막힘)다. 늦게 끝난 옛 읽기가 더 새 목록을 덮지 않는다. 조작 API 는 registry 에 보내기 전에 행위자 헤더, 모드, 사이트를 본다.

- [ ] **Step 1: 시험 고치기(실패하게)**

`ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt`(전체):

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.robots.Blockers
import dev.picasso.ops.service.robots.Connection
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RobotListServiceTest {

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val t1 = Instant.parse("2026-10-07T00:00:00Z")
    private val t2 = Instant.parse("2026-10-07T00:00:05Z")
    private val clock = MovableClock(t1)
    private val r1 = RegistryRobot(robotId = "r1", siteId = "site-01", status = "CLAIMED")
    private var next: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(emptyList())
    private var token: RegistryCall<Unit> = RegistryCall.Ok(Unit)
    private var askedSite: String? = null
    private val service = RobotListService(
        { site -> askedSite = site; next },
        { token },
        "site-01",
        clock,
        Duration.ofSeconds(90),
    )

    @Test
    fun `registry 가 답하면 목록과 읽은 시각을 낸다`() {
        next = RegistryCall.Ok(listOf(r1))
        val view = service.read()
        assertEquals(RegistryState.OK, view.registry)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
        assertEquals(t1, view.robotsAsOf)
        assertEquals("site-01", askedSite)
    }

    @Test
    fun `기체마다 읽은 시각 기준의 연결 칸과 막힘을 붙인다`() {
        next = RegistryCall.Ok(listOf(r1))
        val robot = service.read().robots!!.single()
        assertEquals(Connection.NO_REPORT, robot.connection)
        assertEquals(listOf(Blockers.AWAITING_FIRST_REPORT), robot.blockers.map { it.kind })
        assertEquals(t1, robot.blockers.single().checkedAt)
    }

    @Test
    fun `한 번도 못 읽었으면 목록은 없음이 아니라 모름이다`() {
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertNull(view.robots)
        assertNull(view.robotsAsOf)
    }

    @Test
    fun `registry 가 침묵하면 직전 목록과 그 시각을 유지한다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        clock.now = t2
        next = RegistryCall.Silent("응답 없음")
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_SILENT, view.registry)
        assertEquals(t2, view.checkedAt)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
        assertEquals(t1, view.robotsAsOf)
    }

    @Test
    fun `직전 값이 빈 목록이면 침묵 뒤에도 없음을 유지한다`() {
        next = RegistryCall.Ok(emptyList())
        service.read()
        next = RegistryCall.Silent("응답 없음")
        assertEquals(emptyList(), service.read().robots)
    }

    @Test
    fun `토큰 불일치는 전체 상태로 가고 직전 목록을 유지한다`() {
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        next = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
    }

    @Test
    fun `목록은 읽혀도 관문 안 확인이 401 이면 토큰 불일치이고 목록은 새 값이다`() {
        next = RegistryCall.Ok(listOf(r1))
        token = RegistryCall.Unauthorized
        val view = service.read()
        assertEquals(RegistryState.REGISTRY_UNAUTHORIZED, view.registry)
        assertEquals(view.checkedAt, view.robotsAsOf)
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
    }

    @Test
    fun `관문 안 확인이 답하지 않는 것은 토큰 불일치가 아니다`() {
        next = RegistryCall.Ok(listOf(r1))
        token = RegistryCall.Silent("HTTP 503")
        assertEquals(RegistryState.OK, service.read().registry)
    }

    @Test
    fun `늦게 끝난 옛 읽기는 더 새 목록을 덮지 않는다`() {
        clock.now = t2
        next = RegistryCall.Ok(listOf(r1))
        service.read()
        clock.now = t1
        next = RegistryCall.Ok(emptyList())
        val view = service.read()
        assertEquals(listOf(r1), view.robots!!.map { it.robot })
        assertEquals(t2, view.robotsAsOf)
        // 확인 시각도 남긴 값의 시각으로 맞춘다. 화면은 두 시각이 다르면 직전 값으로 보인다.
        assertEquals(t2, view.checkedAt)
    }
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `./gradlew :ops-service:test --tests '*RobotListServiceTest' --console=plain`
Expected: `compileTestKotlin` 실패(`RobotListService` 생성자 인자 수, `RobotView` 의 `.robot`·`.connection`·`.blockers` 없음).

- [ ] **Step 3: 구현**

`ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt`(전체):

```kotlin
package dev.picasso.ops.service.robots

import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RobotSource
import dev.picasso.ops.service.registry.TokenProbe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** 화면 전체 상태(스펙 §7.4). 기체별이 아니라 전역이다. */
enum class RegistryState { OK, REGISTRY_SILENT, REGISTRY_UNAUTHORIZED }

/** 기체 한 대. 원장 상태는 [robot] 의 `status` 그대로이고, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3·§7.4). */
data class RobotView(
    val robot: RegistryRobot,
    val connection: Connection,
    val blockers: List<Finding>,
)

/**
 * `GET /api/robots` 의 답.
 *
 * @param robots 널이면 «모름»(한 번도 읽지 못했다). 빈 목록은 «없음». 둘을 접지 않는다(스펙 §9).
 * @param robotsAsOf [robots] 를 registry 에서 읽은 시각. [checkedAt] 과 다르면 직전 값이다.
 */
data class RobotListView(
    val registry: RegistryState,
    val checkedAt: Instant,
    val robots: List<RobotView>?,
    val robotsAsOf: Instant?,
)

/**
 * 기체 목록. registry 가 답하지 않으면 목록을 비우지 않고 직전 값과 시각을 보인다(스펙 §9).
 *
 * 목록 읽기(`/diag/robots`)는 운영자 토큰 관문 밖이라 401 이 오지 않는다. 그래서 목록을 읽을 때마다
 * [token] 으로 관문 안의 읽기를 함께 불러, 토큰 불일치가 다음 폴링에 `OK` 로 덮이지 않게 한다.
 *
 * @param threshold 연결 칸의 기준 시간(스펙 §7.3). S1 에서는 설정값이다
 */
class RobotListService(
    private val source: RobotSource,
    private val token: TokenProbe,
    private val siteId: String,
    private val clock: Clock,
    private val threshold: Duration,
) {
    private data class Known(val robots: List<RegistryRobot>, val at: Instant)

    private val last = AtomicReference<Known?>(null)

    fun read(): RobotListView {
        val now = clock.instant()
        return when (val call = source.robots(siteId)) {
            is RegistryCall.Ok -> {
                // 늦게 끝난 옛 읽기가 더 새 값을 덮지 않게 한다. 폴링과 조작 뒤 다시 읽기가 겹칠 수 있다.
                val known = last.updateAndGet { prev ->
                    if (prev != null && prev.at.isAfter(now)) prev else Known(call.value, now)
                }!!
                val state = when (token.operatorToken()) {
                    RegistryCall.Unauthorized -> RegistryState.REGISTRY_UNAUTHORIZED
                    else -> RegistryState.OK
                }
                // 더 새 값을 남겼으면 확인 시각도 그 시각으로 맞춘다. 화면은 두 시각이 다르면 직전 값으로 보인다.
                view(state, maxOf(now, known.at), known)
            }
            is RegistryCall.Silent -> view(RegistryState.REGISTRY_SILENT, now, last.get())
            RegistryCall.Unauthorized -> view(RegistryState.REGISTRY_UNAUTHORIZED, now, last.get())
        }
    }

    private fun view(state: RegistryState, now: Instant, known: Known?): RobotListView =
        RobotListView(
            registry = state,
            checkedAt = now,
            robots = known?.robots?.map { robot ->
                RobotView(robot, Blockers.connection(robot, known.at, threshold), Blockers.of(robot, known.at, threshold))
            },
            robotsAsOf = known?.at,
        )
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/RobotOperationsController.kt`:

```kotlin
package dev.picasso.ops.service.web

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.operations.OperationOutcome
import dev.picasso.ops.service.operations.RobotOperations
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `POST /api/robots` 의 본문. [site] 는 화면이 싣지 않는다. 실려 오면 운영 서비스의 사이트와 대조한다(스펙 §9). */
data class DeclareBody(
    val robotId: String = "",
    val serialNumber: String = "",
    val displayName: String? = null,
    val site: String? = null,
)

data class RetireBody(val reason: String = "")

/** registry 에 보내기 전에 막은 요청의 답. 조작 기록에 남기지 않는다. registry 에 닿지 않은 요청은 조작이 아니다. */
data class PreRejection(val error: String, val detail: String)

/**
 * 기체 조작 API(스펙 §7.2). 등록은 엔지니어 모드, 퇴역·복귀는 운영자 모드에서 한다(스펙 §8).
 *
 * registry 를 부르기 전에 3가지를 막는다. 행위자 헤더가 없거나 틀리면 400, 모드가 맞지 않으면 403, 사이트가
 * 운영 서비스의 `SITE_ID` 와 다르면 400 이다. 쓰기 본문은 `application/json` 만 받는다. 이 저장소에 인증은 없으며,
 * 커스텀 헤더와 JSON 본문 요구가 다른 출처의 페이지가 이 API 를 부르지 못하게 하는 유일한 방어다(브라우저가
 * 사전 요청을 보내고, 스프링은 CORS 설정이 없으면 그것을 거절한다). 폼이나 `text/plain` 을 받는 쓰기를 더하면 이
 * 방어가 사라진다. DELETE 는 본문이 없어 커스텀 헤더만으로 막히고, GET 은 계속 부작용이 없어야 한다.
 * `server.address=127.0.0.1` 은 다른 기계를 막지만 DNS 재바인딩은 막지 못한다(Host 검사가 없다). 인증을 생략한
 * PoC 의 한계다.
 *
 * 사이트 대조는 선언에만 있다. 퇴역·복귀는 기체 id 로만 부르며, registry 는 사이트를 대조하지 않는다(스펙 §6 ④).
 * 본문이 없거나 JSON 으로 읽히지 않는 요청은 스프링이 400 으로 막는다. 이것도 registry 에 닿지 않아 기록되지 않는다.
 *
 * registry 가 답한 결과(거절 포함)는 200 과 [OperationOutcome] 으로 돌려준다. 이 API 의 호출은 성공했고,
 * registry 의 판단은 본문에 있다.
 */
@RestController
@RequestMapping("/api/robots")
class RobotOperationsController(
    private val operations: RobotOperations,
    private val siteId: SiteId,
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun declare(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: DeclareBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        if (body.site != null && body.site != siteId.value) {
            return@guarded reject(HttpStatus.BAD_REQUEST, "SITE_MISMATCH", "사이트가 ${siteId.value} 가 아니다: ${body.site}")
        }
        ResponseEntity.ok(operations.declare(actor, body.robotId, body.serialNumber, body.displayName))
    }

    @PostMapping("/{robotId}/retirement", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun retire(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody body: RetireBody,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        ResponseEntity.ok(operations.retire(actor, robotId, body.reason))
    }

    @DeleteMapping("/{robotId}/retirement")
    fun reinstate(
        @PathVariable robotId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        ResponseEntity.ok(operations.reinstate(actor, robotId))
    }

    private inline fun guarded(
        mode: String?,
        user: String?,
        required: Mode,
        action: (Actor) -> ResponseEntity<Any>,
    ): ResponseEntity<Any> {
        val actor = Actor.fromHeaders(mode, user)
            ?: return reject(HttpStatus.BAD_REQUEST, "ACTOR_REQUIRED", "${Actor.MODE_HEADER}·${Actor.USER_HEADER} 헤더가 없거나 틀리다")
        if (actor.mode != required) {
            return reject(HttpStatus.FORBIDDEN, "MODE_NOT_ALLOWED", "이 조작은 ${required.wire} 모드에서 한다")
        }
        return action(actor)
    }

    private fun reject(status: HttpStatus, error: String, detail: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(PreRejection(error, detail))
}

/** 운영 서비스의 사이트 id(`.env` 의 `SITE_ID`). 기체 선언과 목록이 이 값 하나를 쓴다(스펙 §6 ④). */
data class SiteId(val value: String)
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`(전체):

```kotlin
package dev.picasso.ops.service

import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.operations.RobotOperations
import dev.picasso.ops.service.registry.RegistryClient
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.store.OpsSchemaMigrated
import dev.picasso.ops.service.web.SiteId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/** 운영 서비스(스펙 §7). Flyway 자동설정을 끄는 이유는 [OpsSchema] 에 있다. */
@SpringBootApplication(exclude = [FlywayAutoConfiguration::class])
open class OpsApplication {

    @Bean
    open fun clock(): Clock = Clock.systemUTC()

    @Bean
    open fun opsSchemaMigrated(dataSource: DataSource): OpsSchemaMigrated =
        OpsSchemaMigrated(OpsSchema.flyway(dataSource).migrate().migrationsExecuted)

    @Bean
    open fun siteId(@Value("\${ops.site-id}") value: String): SiteId {
        require(value.isNotBlank()) { "ops.site-id(SITE_ID)가 비었다" }
        return SiteId(value)
    }

    /** 주소 형식은 [RegistryClient.checkBaseUrl] 이 기동에서 본다. */
    @Bean
    open fun registryClient(
        @Value("\${ops.registry.url}") url: String,
        @Value("\${ops.registry.operator-token}") token: String,
    ): RegistryClient {
        // registry 는 빈 토큰을 전부 401 로 다룬다. 설정 실수를 401 이 아니라 기동에서 드러낸다.
        require(token.isNotBlank()) { "운영자 토큰(ops.registry.operator-token)이 비었다" }
        return RegistryClient(url, token)
    }

    /** [threshold] 는 연결 칸의 기준 시간이다(스펙 §7.3). S2 에서 데이터로 옮긴다. */
    @Bean
    open fun robotList(
        registry: RegistryClient,
        siteId: SiteId,
        clock: Clock,
        @Value("\${ops.connection.threshold}") threshold: Duration,
    ): RobotListService = RobotListService(registry, registry, siteId.value, clock, threshold)

    @Bean
    open fun robotOperations(
        registry: RegistryClient,
        log: OperationLog,
        siteId: SiteId,
        clock: Clock,
    ): RobotOperations = RobotOperations(registry, registry, log, siteId.value, clock)

    /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
    @Bean
    open fun operationLog(
        jdbc: JdbcClient,
        @Suppress("UNUSED_PARAMETER") migrated: OpsSchemaMigrated,
    ): OperationLog = OperationLog(jdbc)

    companion object {
        /** 설정 파일 이름을 `ops-service` 로 둔다. 같은 JVM 의 registry `application.properties` 와 가리지 않게(스펙 §7.1). */
        fun builder(): SpringApplicationBuilder =
            SpringApplicationBuilder(OpsApplication::class.java)
                .properties("spring.config.name=ops-service")
    }
}

fun main(args: Array<String>) {
    OpsApplication.builder().run(*args)
}
```

`ops-service/src/main/resources/ops-service.properties`(전체):

```properties
# 운영 서비스 설정. 파일 이름이 ops-service 인 것은 registry 의 application.properties 와 가리지 않기 위해서다(스펙 §7.1).
spring.application.name=picasso-ops-service
spring.threads.virtual.enabled=true

# 값은 루트 .env 가 준다(스펙 §4). 기본값을 적지 않는다: 빠지면 기동에서 멈춘다.
server.port=${OPS_PORT}
# 이 기계 밖에 열지 않는다. 화면은 Vite 프록시로 붙는다.
server.address=127.0.0.1
spring.datasource.url=${PICASSO_DB_URL}
spring.datasource.username=${PICASSO_DB_USER}
spring.datasource.password=${PICASSO_DB_PASSWORD}
ops.site-id=${SITE_ID}
ops.registry.url=http://127.0.0.1:${REGISTRY_PORT}
ops.registry.operator-token=${PICASSO_OPERATOR_TOKEN}

# 연결 칸의 기준 시간(스펙 §7.3). 프로파일 보고 주기 상한 30초의 3배다. 런처의 시간 진행 비율(1:1)이 바뀌면 다시 정한다.
ops.connection.threshold=90s
```

- [ ] **Step 4: S1a 통합 시험 두 줄 고치기**

`e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt` 의 `it["robotId"].asText()` 두 곳(`before` 와 `after` 의 목록)을 `it["robot"]["robotId"].asText()` 로 바꾼다. 다른 줄은 그대로다.

- [ ] **Step 5: 시험이 통과하는지 확인**

Run: `./gradlew :ops-service:test :e2e:test --console=plain` 그리고 `./gradlew :ops-service:checkNoPicassoOnMain --console=plain`
Expected: XML 에서 `RobotListServiceTest` 9개, ops-service 모듈 전체 53개(Actor 4, Blockers 7, EnvBoundary 1, OperationLog 7, RegistryClient 6, RegistryWrites 6, Rejections 3, RobotListService 9, RobotOperations 10), e2e `SkeletonTest` 3개, 실패 0. 검사 성공. 조작 API 는 Task 5 의 e2e 가 본다.

- [ ] **Step 6: 결함 주입 2건(하나씩)**

① `read()` 의 `RegistryCall.Unauthorized -> RegistryState.REGISTRY_UNAUTHORIZED` 줄을 지운다(관문 안 확인을 무시한다). Expected 실패 이름: `목록은 읽혀도 관문 안 확인이 401 이면 토큰 불일치이고 목록은 새 값이다()`.
② `if (prev != null && prev.at.isAfter(now)) prev else Known(call.value, now)` 를 `Known(call.value, now)` 로 바꾼다. Expected 실패 이름: `늦게 끝난 옛 읽기는 더 새 목록을 덮지 않는다()`.
각각 `./gradlew :ops-service:test --tests '*RobotListServiceTest' --console=plain` 으로 돌리고, 되돌리고 초록을 본다.

- [ ] **Step 7: 임시 커밋**

```bash
git add ops-service/src/main/kotlin/dev/picasso/ops/service/robots/RobotListService.kt ops-service/src/test/kotlin/dev/picasso/ops/service/RobotListServiceTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/RobotOperationsController.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/resources/ops-service.properties e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt
git commit -F - <<'EOF'
chore(s1b): 목록 서비스와 조작 API

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 3: 통합 시험

### Task 5: 통합 시험(S1b 완료 판정)

**Files:**
- Create: `e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt`
- Modify: `e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt`(전체)
- Test: `e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt`

S1a 의 `SkeletonTest` 에 있던 기동과 정리를 공용 픽스처 `E2eStack` 으로 뺀다. 시험 클래스마다 새로 띄우고 닫는다(`SkeletonTest` 가 registry 를 멈추므로 공유하지 않는다). `SkeletonTest` 는 registry 직접 선언 대신 운영 서비스의 선언 API 를 쓴다. `LifecycleTest` 는 순서가 있는 시험 10개다. 조작 기록 시험은 대상 칸과 registry 감사 기록의 행위자까지 보고, 토큰이 틀린 운영 서비스 시험은 조작의 401 도 본다.

- [ ] **Step 1: 시험 쓰기**

이 시험들은 이미 지은 코드를 한데 묶어 보므로 처음부터 통과할 수 있다. Step 3 의 결함 주입이 이 시험들이 실제로 보는지의 증거다.

`e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.OpsApplication
import dev.picasso.ops.site.DbConfig
import dev.picasso.ops.site.RobotRoster
import dev.picasso.ops.site.Site
import dev.picasso.ops.site.SiteConfig
import dev.picasso.registry.PostgresSupport
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * 통합 시험 한 벌. 한 JVM 에 Postgres(registry testFixtures)·registry·mimic·운영 서비스를 띄운다(스펙 §10).
 *
 * 시험 클래스마다 새로 띄우고 닫는다. 클래스 사이에 registry 를 멈추는 시험이 있어 공유하지 않는다.
 * DB 는 띄울 때마다 비운다. `PostgresSupport.reset()` 은 public 만 지우므로 ops 스키마는 따로 지운다.
 */
class E2eStack private constructor(
    val site: Site,
    private val ops: ConfigurableApplicationContext,
    val opsUrl: String,
    val siteId: String,
) : AutoCloseable {

    /** 운영 서비스 응답 한 건. 본문이 비면 널이다. */
    data class Reply(val status: Int, val body: JsonNode?)

    fun get(path: String): JsonNode = read(opsUrl + path)

    /** 화면처럼 모드·사용자 헤더를 싣고 이 스택의 운영 서비스를 부른다. [mode] 가 널이면 헤더를 싣지 않는다. */
    fun send(
        method: String,
        path: String,
        mode: String?,
        user: String = "kim",
        body: String? = null,
        contentType: String = "application/json",
    ): Reply = send(opsUrl, method, path, mode, user, body, contentType)

    /** 같은 registry 에 운영자 토큰만 다른 운영 서비스를 하나 더 띄운다. 닫는 것은 부르는 쪽이다. */
    fun opsWithToken(token: String): Pair<ConfigurableApplicationContext, String> {
        val context = startOps(site.registryUrl, token, siteId)
        return context to "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"
    }

    override fun close() {
        try {
            ops.close()
        } finally {
            site.close()
        }
    }

    companion object {
        const val OPERATOR_TOKEN = "e2e-operator"
        val root: Path = Path.of("..").toAbsolutePath().normalize()
        private val http = HttpClient.newHttpClient()
        private val json = ObjectMapper()

        fun start(): E2eStack {
            val siteId = checkNotNull(System.getenv("SITE_ID")) { "SITE_ID 가 없다(루트 .env)" }
            PostgresSupport.reset()
            PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
            val site = Site.start(
                SiteConfig(
                    root = root,
                    siteId = siteId,
                    db = db(),
                    registryPort = 0,
                    operatorToken = OPERATOR_TOKEN,
                    ingestToken = "e2e-ingest",
                    roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
                ),
            )
            val ops = try {
                startOps(site.registryUrl, OPERATOR_TOKEN, siteId)
            } catch (e: Exception) {
                site.close()
                throw e
            }
            return E2eStack(site, ops, "http://127.0.0.1:${(ops as WebServerApplicationContext).webServer.port}", siteId)
        }

        /** [baseUrl] 의 운영 서비스를 화면처럼 부른다. */
        fun send(
            baseUrl: String,
            method: String,
            path: String,
            mode: String?,
            user: String = "kim",
            body: String? = null,
            contentType: String = "application/json",
        ): Reply {
            val headers = buildMap {
                if (mode != null) {
                    put("X-Ops-Mode", mode)
                    put("X-Ops-User", user)
                }
                if (body != null) put("Content-Type", contentType)
            }
            return call(method, baseUrl + path, headers, body)
        }

        /** 운영 서비스 하나를 GET 으로 읽는다. 200 이 아니면 시험을 멈춘다. */
        fun read(url: String): JsonNode {
            val reply = call("GET", url, emptyMap(), null)
            check(reply.status == 200) { "GET $url → ${reply.status} ${reply.body}" }
            return reply.body!!
        }

        private fun db() = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)

        private fun startOps(registryUrl: String, token: String, siteId: String): ConfigurableApplicationContext {
            val db = db()
            return OpsApplication.builder().run(
                "--server.port=0",
                "--spring.datasource.url=${db.url}",
                "--spring.datasource.username=${db.user}",
                "--spring.datasource.password=${db.password}",
                "--ops.registry.url=$registryUrl",
                "--ops.registry.operator-token=$token",
                "--ops.site-id=$siteId",
            )
        }

        private fun call(method: String, url: String, headers: Map<String, String>, body: String?): Reply {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            headers.forEach { (name, value) -> builder.header(name, value) }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val parsed = response.body().takeIf { it.isNotBlank() }?.let { runCatching { json.readTree(it) }.getOrNull() }
            return Reply(response.statusCode(), parsed)
        }
    }
}
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt`(전체):

```kotlin
package dev.picasso.ops.e2e

import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * S1a 완료 판정(스펙 §3). 전체를 한 JVM 에 띄우고 운영 서비스의 기체 목록이 빈 목록을 돌려준다.
 * registry 를 멈추면 화면 전체 상태가 «모름» 이 된다. 순서가 있다: 마지막 시험이 registry 를 멈춘다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkeletonTest {

    companion object {
        private lateinit var stack: E2eStack

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    @Test
    @Order(1)
    fun `운영 서비스의 기체 목록 조회가 빈 목록을 돌려준다`() {
        // 선언 전 mimic 의 보고는 registry 가 거절하고 남기지 않는다(스펙 §6). 보고가 흘러도 목록은 비어 있다.
        repeat(2) { stack.site.advance(Duration.ofSeconds(31)) }
        val view = stack.get("/api/robots")
        assertEquals("OK", view["registry"].asText())
        assertEquals(0, view["robots"].size(), view.toString())
        assertFalse(view["robots"].isNull)
        assertFalse(view["robotsAsOf"].isNull)
    }

    @Test
    @Order(2)
    fun `ops 스키마는 운영 서비스가 올리고 조작 기록은 비어 있다`() {
        // Flyway 가 스키마를 만들 때 남기는 표식 행은 version 이 널이라 뺀다.
        val versions = PostgresSupport.queryAll(
            "SELECT version FROM ops.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
        ) { it.getString(1) }
        assertEquals(listOf("1"), versions)
        assertEquals(0, stack.get("/api/operations").size())
    }

    @Test
    @Order(3)
    fun `registry 를 멈추면 전체 상태가 모름이고 직전 목록을 지우지 않는다`() {
        val declared = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"e2e-held-01","serialNumber":"E2E-0001"}""")
        assertEquals("SUCCEEDED", declared.body!!["result"].asText(), declared.toString())
        val before = stack.get("/api/robots")
        assertEquals(listOf("e2e-held-01"), before["robots"].map { it["robot"]["robotId"].asText() })

        stack.site.stopRegistry()

        val after = stack.get("/api/robots")
        assertEquals("REGISTRY_SILENT", after["registry"].asText())
        assertEquals(listOf("e2e-held-01"), after["robots"].map { it["robot"]["robotId"].asText() })
        assertEquals(before["robotsAsOf"].asText(), after["robotsAsOf"].asText())
    }
}
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * S1b 완료 판정(스펙 §3). 운영 서비스 API 로 선언(`CLAIMED`) → 보고(`CONFIRMED`) → 퇴역 → 퇴역 뒤 보고 감지 →
 * 복귀 → 조작 기록 확인. 순서가 있다. 앞 시험의 기체 상태를 뒤 시험이 이어받는다.
 *
 * 기체는 `site/robots.json` 의 `humanoid-01` 이다. mimic 이 떠 있으므로 시간을 밀면 보고가 나간다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LifecycleTest {

    companion object {
        private const val ROBOT = "humanoid-01"
        private lateinit var stack: E2eStack

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    private fun robot(): JsonNode = stack.get("/api/robots")["robots"].single { it["robot"]["robotId"].asText() == ROBOT }

    private fun blockers(robot: JsonNode) = robot["blockers"].map { it["kind"].asText() }

    /** 프로파일의 상태 발행 주기 상한 30초를 넘겨 민다. 상태 발행이 곧 생존 보고다. */
    private fun report() = stack.site.advance(Duration.ofSeconds(31))

    @Test
    @Order(1)
    fun `엔지니어가 선언하면 CLAIMED 이고 첫 보고를 기다린다`() {
        val reply = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"$ROBOT","serialNumber":"HA-0001"}""")
        assertEquals(200, reply.status)
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        val robot = robot()
        assertEquals("CLAIMED", robot["robot"]["status"].asText())
        assertEquals("NO_REPORT", robot["connection"].asText())
        assertEquals(listOf("AWAITING_FIRST_REPORT"), blockers(robot))
    }

    @Test
    @Order(2)
    fun `보고가 오면 CONFIRMED 이고 막힘이 없다`() {
        report()
        val robot = robot()
        assertEquals("CONFIRMED", robot["robot"]["status"].asText())
        assertEquals("FRESH", robot["connection"].asText())
        assertEquals(listOf(), blockers(robot))
    }

    @Test
    @Order(3)
    fun `운영자가 사유와 함께 퇴역시키면 RETIRED 이다`() {
        val reply = stack.send("POST", "/api/robots/$ROBOT/retirement", "operator", body = """{"reason":"정비"}""")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        assertEquals("RETIRED", robot()["robot"]["status"].asText())
    }

    @Test
    @Order(4)
    fun `퇴역 뒤에도 보고가 오면 운영자가 풀 막힘으로 보인다`() {
        report()
        val robot = robot()
        assertEquals(true, robot["robot"]["reportingAfterRetirement"].asBoolean())
        val blocker = robot["blockers"].single()
        assertEquals("REPORTING_AFTER_RETIREMENT", blocker["kind"].asText())
        assertEquals("OPERATOR", blocker["owner"].asText())
        assertEquals(true, blocker["inScreen"].asBoolean())
    }

    @Test
    @Order(5)
    fun `퇴역한 기체를 다시 선언하면 복귀하라는 거절이다`() {
        val reply = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"$ROBOT","serialNumber":"HA-0001"}""")
        assertEquals("REJECTED", reply.body!!["result"].asText())
        assertEquals("RETIRED_ALREADY", reply.body["rejection"]["kind"].asText())
        assertEquals("OPERATOR", reply.body["rejection"]["owner"].asText())
    }

    @Test
    @Order(6)
    fun `복귀하면 퇴역이 풀리고 막힘이 사라진다`() {
        val reply = stack.send("DELETE", "/api/robots/$ROBOT/retirement", "operator")
        assertEquals("SUCCEEDED", reply.body!!["result"].asText())
        val robot = robot()
        assertEquals("CONFIRMED", robot["robot"]["status"].asText())
        assertEquals(listOf(), blockers(robot))
    }

    @Test
    @Order(7)
    fun `조작 기록에 4건이 대상과 행위자와 사유와 함께 남고 registry 도 같은 행위자를 적었다`() {
        val records = stack.get("/api/operations").map {
            listOf(it["target"].asText(), it["mode"].asText(), it["user"].asText(), it["result"].asText(), it["reason"].asText(""))
        }
        assertEquals(
            listOf(
                listOf("robot $ROBOT", "OPERATOR", "kim", "SUCCEEDED", ""),
                listOf("robot $ROBOT", "ENGINEER", "kim", "REJECTED", ""),
                listOf("robot $ROBOT", "OPERATOR", "kim", "SUCCEEDED", "정비"),
                listOf("robot $ROBOT", "ENGINEER", "kim", "SUCCEEDED", ""),
            ),
            records,
        )
        // registry 감사 기록의 행위자가 운영 서비스의 X-Actor 와 같다(스펙 §7.1). 거절된 재선언은 감사 기록에 없다.
        assertEquals("engineer/kim", robot()["robot"]["registeredBy"].asText())
        val audit = PostgresSupport.queryAll(
            "SELECT operation, actor FROM audit_log WHERE subject = '$ROBOT' ORDER BY audit_id",
        ) { it.getString(1) to it.getString(2) }
        assertEquals(
            listOf("ROBOT_DECLARED" to "engineer/kim", "ROBOT_RETIRED" to "operator/kim", "ROBOT_REINSTATED" to "operator/kim"),
            audit,
        )
    }

    @Test
    @Order(8)
    fun `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다`() {
        val before = stack.get("/api/operations").size()
        val body = """{"robotId":"pre-01","serialNumber":"PRE-0001"}"""

        assertEquals(400, stack.send("POST", "/api/robots", mode = null, body = body).status)
        assertEquals(403, stack.send("POST", "/api/robots", "operator", body = body).status)
        val mismatch = stack.send("POST", "/api/robots", "engineer", body = """{"robotId":"pre-01","serialNumber":"PRE-0001","site":"other-site"}""")
        assertEquals(400 to "SITE_MISMATCH", mismatch.status to mismatch.body!!["error"].asText())
        assertEquals(415, stack.send("POST", "/api/robots", "engineer", body = body, contentType = "text/plain").status)

        assertEquals(before, stack.get("/api/operations").size())
        assertFalse(stack.get("/api/robots")["robots"].any { it["robot"]["robotId"].asText() == "pre-01" })
    }

    @Test
    @Order(9)
    fun `모르는 기체의 퇴역은 registry 의 404 를 대응표로 옮긴다`() {
        val reply = stack.send("POST", "/api/robots/no-such-robot/retirement", "operator", body = """{"reason":"정비"}""")
        assertEquals("REJECTED", reply.body!!["result"].asText())
        assertEquals("UNKNOWN_ROBOT", reply.body["rejection"]["kind"].asText())
    }

    @Test
    @Order(10)
    fun `운영자 토큰이 틀린 운영 서비스는 목록을 읽어도 화면 전체 상태가 토큰 불일치이고 조작도 401 이다`() {
        val (context, url) = stack.opsWithToken("wrong-token")
        context.use {
            val view = E2eStack.read("$url/api/robots")
            assertEquals("REGISTRY_UNAUTHORIZED", view["registry"].asText())
            // 목록 자체는 관문 밖에서 새로 읽었다. 직전 값이 아니다.
            assertEquals(view["checkedAt"].asText(), view["robotsAsOf"].asText())

            val reply = E2eStack.send(url, "POST", "/api/robots", "engineer", body = """{"robotId":"tok-01","serialNumber":"TOK-0001"}""")
            assertEquals("REJECTED", reply.body!!["result"].asText())
            assertEquals(true, reply.body["unauthorized"].asBoolean())
            assertFalse(stack.get("/api/robots")["robots"].any { it["robot"]["robotId"].asText() == "tok-01" })
        }
    }
}
```

- [ ] **Step 2: 시험이 통과하는지 확인**

Run: `./gradlew :e2e:test --console=plain`
Expected: XML(`e2e/...`)에서 `LifecycleTest` 10개, `SkeletonTest` 3개, 실패 0.

- [ ] **Step 3: 결함 주입 5건(하나씩)**

앞의 둘은 S1b 완료 흐름(선언부터 조작 기록까지)을 겨눈다.
① `RegistryClient.robots` 의 `retired=true` 를 `retired=false` 로 바꾼다(스펙 §7.2 의 고정 질의값). Expected 실패 이름(e2e): `운영자가 사유와 함께 퇴역시키면 RETIRED 이다()`, `퇴역 뒤에도 보고가 오면 운영자가 풀 막힘으로 보인다()`. ops-service 의 `퇴역 기체를 포함해 사이트로 묻고 칸을 읽는다()` 도 빨갛다.
② `Blockers.of` 의 `if (robot.reportingAfterRetirement) {` 를 `if (false) {` 로 바꾼다. Expected 실패 이름(e2e): `퇴역 뒤에도 보고가 오면 운영자가 풀 막힘으로 보인다()`. ops-service 의 `퇴역 뒤 보고는 운영자가 화면 안에서 푼다()` 도 빨갛다.
③ Task 4 Step 6 ① 과 같은 주입(관문 안 확인 무시). Expected 실패 이름(e2e): `운영자 토큰이 틀린 운영 서비스는 목록을 읽어도 화면 전체 상태가 토큰 불일치이고 조작도 401 이다()`. ops-service 의 `목록은 읽혀도 관문 안 확인이 401 이면 토큰 불일치이고 목록은 새 값이다()` 도 빨갛다.
④ `RobotOperationsController.declare` 의 `if (body.site != null && body.site != siteId.value) {` 를 `if (false) {` 로 바꾼다. Expected 실패 이름: `registry 에 보내기 전에 막는 요청은 조작 기록에 남지 않는다()`.
⑤ `guarded` 의 `if (actor.mode != required) {` 를 `if (false) {` 로 바꾼다. Expected 실패 이름: 같은 시험.
각각 `./gradlew :ops-service:test :e2e:test --continue --console=plain` 으로 돌리고, 되돌리고 초록을 본다.

- [ ] **Step 4: 임시 커밋**

```bash
git add e2e/src/test/kotlin/dev/picasso/ops/e2e/E2eStack.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/SkeletonTest.kt e2e/src/test/kotlin/dev/picasso/ops/e2e/LifecycleTest.kt
git commit -F - <<'EOF'
chore(s1b): 생애주기 통합 시험

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 4: 화면

### Task 6: 화면

**Files:**
- Modify(전체): `ui/src/api.ts`, `ui/src/components/RobotsArea.tsx`, `ui/src/styles.css`, `ui/vite.config.ts`
- Modify(일부): `ui/src/App.tsx`(세 곳), `ui/src/App.test.tsx`(기체 픽스처만)
- Create: `ui/src/labels.ts`, `ui/src/components/FindingCard.tsx`, `ui/src/components/OutcomeNotice.tsx`, `ui/src/components/DeclareForm.tsx`, `ui/src/components/RobotDetail.tsx`
- Test: `ui/src/testing/fakeOps.ts`, `ui/src/components/RobotsArea.test.tsx`

목록의 기체는 `RobotView` 다. 기체를 고르면 상세에 원장 상태와 연결(합치지 않는다, 스펙 §7.3), 막힘 5칸, 모드에 맞는 조작이 보인다. 선언 폼은 엔지니어 모드에서만, 퇴역·복귀는 운영자 모드에서만 보인다(다른 모드에서는 어느 모드에서 하는지 적는다). 퇴역 사유가 비거나 공백뿐이면 보내지 않는다. 조작 결과는 성공, 거절(5칸), 토큰 불일치, 응답 없음(재조회 결과), 사전 거절, 결과 모름으로 갈라, 어느 기체의 어느 조작인지와 함께 보인다(머리말 결정 11). 조작이 끝나면 목록을 바로 다시 읽는다. 화면 밖 작업(지도·웨이포인트 티칭, mimic 기동)을 한 줄로 적는다(스펙 §8). `vite.config.ts` 는 vitest 가 `e2e/`(Playwright 몫)를 집지 않게 `src` 만 본다.

- [ ] **Step 1: 시험 쓰기와 고치기(실패하게)**

`ui/src/testing/fakeOps.ts`:

```ts
import { vi } from 'vitest'
import type { Finding, OperationOutcome, RobotListView, RobotView } from '../api'

/** 운영 서비스 대역이 받은 요청 한 건. */
export interface Call {
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
}

/** 시험이 고칠 수 있는 대역의 상태. 조작마다 [answer] 를 돌려준다. */
export interface FakeOps {
  calls: Call[]
  view: RobotListView
  answer: { status: number; body: unknown }
}

export function robotView(robotId: string, status: string, blockers: Finding[] = []): RobotView {
  return {
    robot: {
      robotId,
      siteId: 'site-01',
      serialNumber: 'SN',
      displayName: null,
      status,
      lastReportedAt: status === 'CLAIMED' ? null : 't0',
      retiredAt: status === 'RETIRED' ? 't0' : null,
      retiredReason: status === 'RETIRED' ? '정비' : null,
      reportingAfterRetirement: false,
    },
    connection: status === 'CLAIMED' ? 'NO_REPORT' : 'FRESH',
    blockers,
  }
}

export function outcome(partial: Partial<OperationOutcome>): OperationOutcome {
  return {
    requestId: 'r-1',
    result: 'SUCCEEDED',
    confirmation: null,
    rejection: null,
    unauthorized: false,
    registryStatus: 200,
    ...partial,
  }
}

/** 브라우저처럼 ISO-8859-1 밖의 문자가 헤더에 있으면 보내기 전에 던지는 fetch 대역을 끼운다. */
export function installFakeOps(view: RobotListView): FakeOps {
  const fake: FakeOps = { calls: [], view, answer: { status: 200, body: outcome({}) } }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      for (const value of Object.values(headers)) {
        if ([...value].some((ch) => ch.charCodeAt(0) > 0xff)) {
          throw new TypeError("Failed to execute 'fetch': String contains non ISO-8859-1 code point.")
        }
      }
      const method = init?.method ?? 'GET'
      fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
      if (method === 'GET') {
        const body = url === '/api/robots' ? fake.view : []
        return new Response(JSON.stringify(body), { status: 200 })
      }
      return new Response(JSON.stringify(fake.answer.body), { status: fake.answer.status })
    }),
  )
  return fake
}
```

`ui/src/components/RobotsArea.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { Finding } from '../api'
import { installFakeOps, outcome, robotView } from '../testing/fakeOps'

const awaiting: Finding = {
  kind: 'AWAITING_FIRST_REPORT',
  observed: '보고 0회',
  expected: '생존 보고 1회 이상',
  checkedAt: 't1',
  owner: 'SITE',
  inScreen: false,
  action: '기체·어댑터 기동과 사이트 id 확인',
  target: 'humanoid-01',
}

/** 운영 서비스의 `Rejections.of` 가 내는 모양 그대로다. */
const retiredAlready: Finding = {
  kind: 'RETIRED_ALREADY',
  observed: 'HTTP 409, status=RETIRED, 퇴역한 기체다',
  expected: '201 또는 200',
  checkedAt: 't2',
  owner: 'OPERATOR',
  inScreen: true,
  action: '복귀',
  target: 'humanoid-01',
}

const view = (robots = [robotView('humanoid-01', 'CLAIMED', [awaiting]), robotView('quadruped-01', 'RETIRED')]) => ({
  registry: 'OK' as const,
  checkedAt: 't1',
  robots,
  robotsAsOf: 't1',
})

/** 상세의 칸 하나. 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

async function choose(mode: '엔지니어' | '운영자', robotId?: string) {
  render(<App />)
  await userEvent.click(screen.getByLabelText(mode))
  if (robotId) await userEvent.click(await screen.findByRole('button', { name: robotId }))
}

async function declare(robotId: string, serial: string) {
  const form = await screen.findByRole('form', { name: '기체 선언' })
  await userEvent.type(within(form).getByLabelText('robot_id'), robotId)
  await userEvent.type(within(form).getByLabelText('일련번호'), serial)
  await userEvent.click(within(form).getByRole('button', { name: '선언' }))
}

describe('로봇·연결 영역', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('기체를 고르면 상세에 원장 상태와 연결이 따로 보이고 막힘이 5칸으로 보인다', async () => {
    installFakeOps(view())
    await choose('엔지니어', 'humanoid-01')
    const detail = screen.getByRole('region', { name: 'humanoid-01 상세' })
    expect(field(detail, '원장 상태')).toBe('CLAIMED')
    expect(field(detail, '연결')).toBe('보고 없음')
    expect(field(detail, '종류')).toBe('첫 보고 대기')
    expect(field(detail, '관측값과 기대값')).toBe('보고 0회 / 기대: 생존 보고 1회 이상')
    expect(field(detail, '마지막 확인')).toBe('t1')
    expect(field(detail, '해결 담당')).toBe('현장(화면 밖): 기체·어댑터 기동과 사이트 id 확인')
    // 이미 이 기체의 상세 안이므로 자기 자신을 가리키는 링크는 없다.
    expect(within(detail).queryByRole('button', { name: 'humanoid-01 상세' })).not.toBeInTheDocument()
  })

  it('화면 밖 작업을 화면이 명시한다', async () => {
    installFakeOps(view())
    render(<App />)
    expect(await screen.findByText(/화면 밖 작업: 로봇 내부 지도와 웨이포인트 티칭, mimic 기동/)).toBeInTheDocument()
  })

  it('엔지니어 모드에서만 선언 폼이 보이고 사이트 없이 JSON 으로 선언을 보낸다', async () => {
    const fake = installFakeOps(view([]))
    render(<App />)
    await declare('humanoid-01', 'HA-0001')
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots')
    expect(post.body).toEqual({ robotId: 'humanoid-01', serialNumber: 'HA-0001', displayName: null })
    expect(post.headers['X-Ops-Mode']).toBe('engineer')
    expect(post.headers['Content-Type']).toBe('application/json')
    expect(await screen.findByText('humanoid-01 선언: 반영됨')).toBeInTheDocument()

    await userEvent.click(screen.getByLabelText('운영자'))
    expect(screen.queryByRole('form', { name: '기체 선언' })).not.toBeInTheDocument()
    expect(screen.getByText('선언은 엔지니어 모드에서 합니다')).toBeInTheDocument()
  })

  it('퇴역은 운영자 모드에서 사유 없이는 보낼 수 없다', async () => {
    const fake = installFakeOps(view())
    await choose('운영자', 'humanoid-01')
    const form = screen.getByRole('form', { name: '퇴역' })
    const button = within(form).getByRole('button', { name: '퇴역' })
    expect(button).toBeDisabled()
    await userEvent.type(within(form).getByLabelText('퇴역 사유'), '   ')
    expect(button).toBeDisabled()
    await userEvent.clear(within(form).getByLabelText('퇴역 사유'))
    await userEvent.type(within(form).getByLabelText('퇴역 사유'), '정비')
    await userEvent.click(button)
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'POST')).toBe(true))
    const post = fake.calls.find((call) => call.method === 'POST')!
    expect(post.url).toBe('/api/robots/humanoid-01/retirement')
    expect(post.body).toEqual({ reason: '정비' })
    expect(post.headers['X-Ops-Mode']).toBe('operator')
    expect(post.headers['Content-Type']).toBe('application/json')
  })

  it('엔지니어 모드에서는 퇴역 폼과 복귀 버튼이 없다', async () => {
    installFakeOps(view())
    await choose('엔지니어', 'humanoid-01')
    expect(screen.queryByRole('form', { name: '퇴역' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'quadruped-01' }))
    expect(screen.queryByRole('button', { name: '복귀' })).not.toBeInTheDocument()
    expect(screen.getByText('퇴역과 복귀는 운영자 모드에서 합니다')).toBeInTheDocument()
  })

  it('퇴역한 기체는 운영자 모드에서 복귀를 보낸다', async () => {
    const fake = installFakeOps(view())
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'DELETE')).toBe(true))
    expect(fake.calls.find((call) => call.method === 'DELETE')!.url).toBe('/api/robots/quadruped-01/retirement')
    expect(await screen.findByText('quadruped-01 복귀: 반영됨')).toBeInTheDocument()
  })

  it('거절은 관측값과 기대값, 해결 담당과 후속 행동으로 보인다', async () => {
    const fake = installFakeOps(view([]))
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: retiredAlready }) }
    render(<App />)
    await declare('humanoid-01', 'HA-0001')
    expect(await screen.findByText('humanoid-01 선언: 거절됨')).toBeInTheDocument()
    expect(screen.getByText('이미 퇴역한 기체')).toBeInTheDocument()
    expect(screen.getByText('HTTP 409, status=RETIRED, 퇴역한 기체다 / 기대: 201 또는 200')).toBeInTheDocument()
    expect(screen.getByText('운영자(화면 안): 복귀')).toBeInTheDocument()
  })

  it('거절 알림의 바로 가기를 누르면 그 기체의 상세가 열린다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 200, body: outcome({ result: 'REJECTED', registryStatus: 409, rejection: retiredAlready }) }
    render(<App />)
    await declare('humanoid-01', 'HA-0001')
    expect(screen.queryByRole('region', { name: 'humanoid-01 상세' })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: 'humanoid-01 상세' }))
    expect(screen.getByRole('region', { name: 'humanoid-01 상세' })).toBeInTheDocument()
  })

  it('응답이 없고 다시 읽어 보니 반영 안 됐으면 그렇게 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = {
      status: 200,
      body: outcome({ result: 'NO_RESPONSE', confirmation: 'CONFIRMED_NOT_APPLIED', registryStatus: null }),
    }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(await screen.findByText(/quadruped-01 복귀: 응답 없음. 다시 읽어 보니 반영 안 됨/)).toBeInTheDocument()
  })

  it('응답이 없고 재조회도 못 했으면 확인하지 못했다고 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 200, body: outcome({ result: 'NO_RESPONSE', confirmation: null, registryStatus: null }) }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(
      await screen.findByText(/quadruped-01 복귀: 반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다/),
    ).toBeInTheDocument()
  })

  it('운영 서비스의 응답이 비정상이면 보내지 못했다고 단정하지 않고 결과 모름으로 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 502, body: {} }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(await screen.findByText(/quadruped-01 복귀: 결과 모름\(운영 서비스 응답 502\)/)).toBeInTheDocument()
  })

  it('운영 서비스가 먼저 막은 요청은 보내지 않음으로 보인다', async () => {
    const fake = installFakeOps(view())
    fake.answer = { status: 403, body: { error: 'MODE_NOT_ALLOWED', detail: '이 조작은 operator 모드에서 한다' } }
    await choose('운영자', 'quadruped-01')
    await userEvent.click(screen.getByRole('button', { name: '복귀' }))
    expect(await screen.findByText('quadruped-01 복귀: 보내지 않음. 이 조작은 operator 모드에서 한다')).toBeInTheDocument()
  })

  it('토큰 불일치여도 새로 읽은 목록은 직전 값으로 표시하지 않는다', async () => {
    installFakeOps({ ...view(), registry: 'REGISTRY_UNAUTHORIZED' })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('운영자 토큰 설정 확인')
    expect(await screen.findByRole('button', { name: 'humanoid-01' })).toBeInTheDocument()
    expect(screen.queryByText(/직전 값입니다/)).not.toBeInTheDocument()
  })
})
```

`ui/src/App.test.tsx` 는 기체 픽스처만 `RobotView` 모양으로 바꾼다. 다른 줄은 그대로다. 파일 위쪽의 다음 원문을

```tsx
import type { OperationRecord, RobotListView } from './api'

const robot = {
  robotId: 'humanoid-01',
  siteId: 'site-01',
  serialNumber: 'HA-0001',
  status: 'CLAIMED',
  lastReportedAt: null,
  retiredAt: null,
  reportingAfterRetirement: false,
}
```

다음으로 바꾼다.

```tsx
import type { OperationRecord, RobotListView, RobotView } from './api'

const robot: RobotView = {
  robot: {
    robotId: 'humanoid-01',
    siteId: 'site-01',
    serialNumber: 'HA-0001',
    displayName: null,
    status: 'CLAIMED',
    lastReportedAt: null,
    retiredAt: null,
    retiredReason: null,
    reportingAfterRetirement: false,
  },
  connection: 'NO_REPORT',
  blockers: [],
}
```

- [ ] **Step 2: 시험이 실패하는지 확인**

Run: `cd ui && npx tsc -b; npm test; cd ..`
Expected: `tsc -b` 의 형 오류(`RobotView`·`Finding` 없음). vitest 는 형을 보지 않으므로 import 에서 멈추지 않고, 새 시험 다수와 S1a 시험 일부가 실행 중에 실패한다.

- [ ] **Step 3: 구현**

`ui/vite.config.ts`(전체):

```ts
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
```

`ui/src/api.ts`(전체):

```ts
export type Mode = 'engineer' | 'operator'
export type RegistryState = 'OK' | 'REGISTRY_SILENT' | 'REGISTRY_UNAUTHORIZED'
export type Connection = 'FRESH' | 'STALE' | 'NO_REPORT'
export type Owner = 'SITE' | 'OPERATOR' | 'ENGINEER' | 'NONE'

export interface Robot {
  robotId: string
  siteId: string
  serialNumber: string | null
  displayName: string | null
  status: string
  lastReportedAt: string | null
  retiredAt: string | null
  retiredReason: string | null
  reportingAfterRetirement: boolean
}

/** 막힘이나 거절 한 건. 화면에 내는 칸 5개와 맞춘다(스펙 §7.4). */
export interface Finding {
  kind: string
  observed: string
  expected: string
  checkedAt: string
  owner: Owner
  inScreen: boolean
  action: string
  target: string | null
}

/** 기체 한 대. 원장 상태는 registry 값 그대로, 연결과 막힘은 운영 서비스가 계산한다(스펙 §7.3). */
export interface RobotView {
  robot: Robot
  connection: Connection
  blockers: Finding[]
}

/** 운영 서비스의 `GET /api/robots`. `robots` 가 null 이면 모름, 빈 배열이면 없음이다(스펙 §9). */
export interface RobotListView {
  registry: RegistryState
  checkedAt: string
  robots: RobotView[] | null
  robotsAsOf: string | null
}

export interface OperationRecord {
  requestId: string
  mode: 'ENGINEER' | 'OPERATOR'
  user: string
  target: string
  reason: string | null
  result: string
  recordedAt: string
}

/** 조작 한 번의 답. registry 의 판단은 여기에 있다(스펙 §7.4·§9). */
export interface OperationOutcome {
  requestId: string
  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
  rejection: Finding | null
  unauthorized: boolean
  registryStatus: number | null
}

/** 운영 서비스가 registry 에 보내기 전에 막은 요청의 답. */
export interface PreRejection {
  error: string
  detail: string
}

/**
 * 조작을 보낸 결과. registry 에 닿았으면 `outcome`, 운영 서비스가 먼저 막았으면 `refused` 다.
 * 그 밖의 실패(연결 끊김, 프록시 오류, 운영 서비스 500)는 `unknown` 이다. 요청이 registry 까지 갔는지 모르므로
 * 보내지 못했다고 단정하지 않는다(스펙 §9).
 */
export type Sent =
  | { kind: 'outcome'; outcome: OperationOutcome }
  | { kind: 'refused'; refusal: PreRejection }
  | { kind: 'unknown'; cause: string }

/** 사용자 이름 규칙. 운영 서비스의 `Actor` 와 같다. 헤더는 ASCII 만 실을 수 있고 `/` 는 모드와 사용자를 가르는 자리다. */
export const USER_PATTERN = /^[A-Za-z0-9._-]{1,64}$/

/** 인증 없이 화면이 싣는 모드와 사용자(스펙 §7.1·§8). */
export interface Session {
  mode: Mode
  user: string
}

const actorHeaders = (session: Session) => ({ 'X-Ops-Mode': session.mode, 'X-Ops-User': session.user })

async function getJson<T>(path: string, session: Session): Promise<T> {
  const response = await fetch(path, { headers: actorHeaders(session) })
  if (!response.ok) throw new Error(`운영 서비스 응답 ${response.status}`)
  return (await response.json()) as T
}

async function send(method: string, path: string, session: Session, body?: unknown): Promise<Sent> {
  try {
    const response = await fetch(path, {
      method,
      headers:
        body === undefined
          ? actorHeaders(session)
          : { ...actorHeaders(session), 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (response.ok) return { kind: 'outcome', outcome: (await response.json()) as OperationOutcome }
    if (response.status === 400 || response.status === 403) {
      return { kind: 'refused', refusal: (await response.json()) as PreRejection }
    }
    return { kind: 'unknown', cause: `운영 서비스 응답 ${response.status}` }
  } catch (error: unknown) {
    return { kind: 'unknown', cause: error instanceof Error ? error.message : String(error) }
  }
}

const retirementPath = (robotId: string) => `/api/robots/${encodeURIComponent(robotId)}/retirement`

export const fetchRobots = (session: Session) => getJson<RobotListView>('/api/robots', session)
export const fetchOperations = (session: Session) =>
  getJson<OperationRecord[]>('/api/operations', session)
export const declareRobot = (
  session: Session,
  robotId: string,
  serialNumber: string,
  displayName: string | null,
) => send('POST', '/api/robots', session, { robotId, serialNumber, displayName })
export const retireRobot = (session: Session, robotId: string, reason: string) =>
  send('POST', retirementPath(robotId), session, { reason })
export const reinstateRobot = (session: Session, robotId: string) =>
  send('DELETE', retirementPath(robotId), session)
```

`ui/src/labels.ts`:

```ts
import type { Connection, Owner } from './api'

/** 화면에 보이는 이름. 값은 운영 서비스의 열거형 그대로 받고, 이름만 여기서 붙인다. */
export const CONNECTION_LABEL: Record<Connection, string> = {
  FRESH: '신선',
  STALE: '오래됨',
  NO_REPORT: '보고 없음',
}

export const OWNER_LABEL: Record<Owner, string> = {
  SITE: '현장',
  OPERATOR: '운영자',
  ENGINEER: '엔지니어',
  NONE: '없음',
}

/** 막힘(스펙 §7.4 상태 막힘 4종)과 조작 거절(대응표)의 이름. 모르는 종류는 값 그대로 보인다. */
export const KIND_LABEL: Record<string, string> = {
  AWAITING_FIRST_REPORT: '첫 보고 대기',
  REPORT_STALE: '보고 오래됨',
  REPORTING_AFTER_RETIREMENT: '퇴역 뒤 보고',
  UNREGISTERED_ROW: '출처 없는 행',
  BAD_REQUEST: '본문 오류',
  WRONG_DOOR: '이미 다른 문으로 들어온 기체',
  RETIRED_ALREADY: '이미 퇴역한 기체',
  RETIRE_BAD_REQUEST: '퇴역 요청 오류',
  UNKNOWN_ROBOT: '모르는 기체',
  UNCLASSIFIED: '분류되지 않은 거절',
}

export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind
```

`ui/src/components/FindingCard.tsx`:

```tsx
import type { Finding } from '../api'
import { OWNER_LABEL, kindLabel } from '../labels'

interface Props {
  finding: Finding
  /** 링크를 누르면 그 기체의 상세를 연다. 없으면 링크를 그리지 않는다(이미 그 기체의 상세 안일 때). */
  onSelect?: (robotId: string) => void
}

/** 막힘이나 거절 한 건을 5칸으로 보인다(스펙 §7.4): 종류, 관측값과 기대값, 마지막 확인 시각, 해결 담당, 바로 갈 링크. */
export function FindingCard({ finding, onSelect }: Props) {
  return (
    <dl className="finding">
      <dt>종류</dt>
      <dd>{kindLabel(finding.kind)}</dd>
      <dt>관측값과 기대값</dt>
      <dd>
        {finding.observed} / 기대: {finding.expected}
      </dd>
      <dt>마지막 확인</dt>
      <dd>{finding.checkedAt}</dd>
      <dt>해결 담당</dt>
      <dd>
        {OWNER_LABEL[finding.owner]}({finding.inScreen ? '화면 안' : '화면 밖'}): {finding.action}
      </dd>
      {finding.target !== null && onSelect && (
        <>
          <dt>바로 가기</dt>
          <dd>
            <button type="button" className="link" onClick={() => onSelect(finding.target!)}>
              {finding.target} 상세
            </button>
          </dd>
        </>
      )}
    </dl>
  )
}
```

`ui/src/components/OutcomeNotice.tsx`:

```tsx
import type { Sent } from '../api'
import { FindingCard } from './FindingCard'

interface Props {
  /** 어느 기체의 어느 조작인지. 예: `humanoid-01 퇴역` */
  what: string
  sent: Sent
  onSelect: (robotId: string) => void
}

/**
 * 마지막 조작의 결과. 응답 없음은 거절과 다르다(스펙 §9). 재조회로 확인되기 전에는 성공으로도 실패로도 보이지 않는다.
 * 운영 서비스는 응답 없음 뒤 한 번 다시 읽고 답하므로, 확인 결과가 없으면 그 재조회도 실패한 것이다.
 */
export function OutcomeNotice({ what, sent, onSelect }: Props) {
  if (sent.kind === 'refused') {
    return (
      <p role="status">
        {what}: 보내지 않음. {sent.refusal.detail}
      </p>
    )
  }
  if (sent.kind === 'unknown') {
    return (
      <p role="status">
        {what}: 결과 모름({sent.cause}). 목록을 다시 읽어 확인하십시오
      </p>
    )
  }
  const { outcome } = sent
  if (outcome.result === 'SUCCEEDED') return <p role="status">{what}: 반영됨</p>
  if (outcome.unauthorized) {
    return <p role="status">{what}: registry 가 운영자 토큰을 거절했습니다. 상단의 전체 상태를 보십시오</p>
  }
  if (outcome.result === 'REJECTED' && outcome.rejection !== null) {
    return (
      <div role="status">
        <p>{what}: 거절됨</p>
        <FindingCard finding={outcome.rejection} onSelect={onSelect} />
      </div>
    )
  }
  switch (outcome.confirmation) {
    case 'CONFIRMED_APPLIED':
      return <p role="status">{what}: 응답은 없었으나 다시 읽어 보니 반영됨</p>
    case 'CONFIRMED_NOT_APPLIED':
      return <p role="status">{what}: 응답 없음. 다시 읽어 보니 반영 안 됨. 다시 하려면 새로 요청하십시오</p>
    default:
      return (
        <p role="status">
          {what}: 반영되었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 목록에서 확인하십시오
        </p>
      )
  }
}
```

`ui/src/components/DeclareForm.tsx`:

```tsx
import { useState } from 'react'
import type { FormEvent } from 'react'

interface Props {
  onDeclare: (robotId: string, serialNumber: string, displayName: string | null) => void
  busy: boolean
}

/** 기체 선언(엔지니어 모드). 사이트는 운영 서비스가 `SITE_ID` 로 채우므로 입력란이 없다(스펙 §9). */
export function DeclareForm({ onDeclare, busy }: Props) {
  const [robotId, setRobotId] = useState('')
  const [serialNumber, setSerialNumber] = useState('')
  const [displayName, setDisplayName] = useState('')
  const ready = robotId.trim() !== '' && serialNumber.trim() !== ''

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!ready) return
    onDeclare(robotId.trim(), serialNumber.trim(), displayName.trim() === '' ? null : displayName.trim())
  }

  return (
    <form aria-label="기체 선언" onSubmit={submit}>
      <label>
        robot_id
        <input value={robotId} onChange={(event) => setRobotId(event.target.value)} />
      </label>
      <label>
        일련번호
        <input value={serialNumber} onChange={(event) => setSerialNumber(event.target.value)} />
      </label>
      <label>
        표시 이름
        <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
      </label>
      <button type="submit" disabled={!ready || busy}>
        선언
      </button>
    </form>
  )
}
```

`ui/src/components/RobotDetail.tsx`:

```tsx
import { useState } from 'react'
import type { Mode, RobotView } from '../api'
import { CONNECTION_LABEL } from '../labels'
import { FindingCard } from './FindingCard'

interface Props {
  view: RobotView
  mode: Mode
  busy: boolean
  onRetire: (reason: string) => void
  onReinstate: () => void
}

/**
 * 기체 상세. 상태 2칸(원장 상태, 연결)을 합치지 않고 따로 보인다(스펙 §7.3). 퇴역·복귀는 운영자 모드에서 한다(스펙 §8).
 * 퇴역 사유는 필수다. 사유가 비면 요청을 보내지 않는다.
 */
export function RobotDetail({ view, mode, busy, onRetire, onReinstate }: Props) {
  const [reason, setReason] = useState('')
  const { robot } = view
  const retired = robot.status === 'RETIRED'
  return (
    <section aria-label={`${robot.robotId} 상세`}>
      <h3>{robot.robotId}</h3>
      <dl>
        <dt>원장 상태</dt>
        <dd>{robot.status}</dd>
        <dt>연결</dt>
        <dd>{CONNECTION_LABEL[view.connection]}</dd>
        <dt>마지막 보고</dt>
        <dd>{robot.lastReportedAt ?? '보고 없음'}</dd>
        {retired && (
          <>
            <dt>퇴역</dt>
            <dd>
              {robot.retiredAt} ({robot.retiredReason})
            </dd>
          </>
        )}
      </dl>
      <h4>막힘</h4>
      {view.blockers.length === 0 ? (
        <p>막힘 없음</p>
      ) : (
        view.blockers.map((finding) => <FindingCard key={finding.kind} finding={finding} />)
      )}
      {mode !== 'operator' ? (
        <p>퇴역과 복귀는 운영자 모드에서 합니다</p>
      ) : retired ? (
        <button type="button" disabled={busy} onClick={onReinstate}>
          복귀
        </button>
      ) : (
        <form
          aria-label="퇴역"
          onSubmit={(event) => {
            event.preventDefault()
            if (reason.trim() !== '') onRetire(reason.trim())
          }}
        >
          <label>
            퇴역 사유
            <input value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
          <button type="submit" disabled={busy || reason.trim() === ''}>
            퇴역
          </button>
        </form>
      )}
    </section>
  )
}
```

`ui/src/components/RobotsArea.tsx`(전체):

```tsx
import { useState } from 'react'
import { declareRobot, reinstateRobot, retireRobot } from '../api'
import type { RobotListView, Sent, Session } from '../api'
import { CONNECTION_LABEL } from '../labels'
import { DeclareForm } from './DeclareForm'
import { OutcomeNotice } from './OutcomeNotice'
import { RobotDetail } from './RobotDetail'

interface Props {
  view: RobotListView | null
  /** 운영 서비스에 닿지 않으면 [view] 는 직전 값이다. */
  opsError: string | null
  session: Session
  /** 조작이 끝나면 부른다. 목록을 다시 읽는다. */
  onChanged: () => void
}

/** 로봇·연결 영역. 왼쪽 목록과 오른쪽 상세(스펙 §8, 결정 6). */
export function RobotsArea({ view, opsError, session, onChanged }: Props) {
  const [selected, setSelected] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Sent } | null>(null)

  const run = (what: string, operation: () => Promise<Sent>) => {
    setBusy(true)
    operation()
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        onChanged()
      })
  }

  const current = view?.robots?.find((candidate) => candidate.robot.robotId === selected) ?? null

  return (
    <div className="split">
      <section aria-label="기체 목록">
        <h2>기체</h2>
        {session.mode === 'engineer' ? (
          <DeclareForm
            busy={busy}
            onDeclare={(robotId, serial, displayName) =>
              run(`${robotId} 선언`, () => declareRobot(session, robotId, serial, displayName))
            }
          />
        ) : (
          <p>선언은 엔지니어 모드에서 합니다</p>
        )}
        <RobotList view={view} opsError={opsError} selected={selected} onSelect={setSelected} />
        <p className="offscreen">
          화면 밖 작업: 로봇 내부 지도와 웨이포인트 티칭, mimic 기동(site/ 런처). 화면은 완료를 대신 체크하지 않습니다
        </p>
      </section>
      <section aria-label="상세">
        <h2>상세</h2>
        {last !== null && <OutcomeNotice what={last.what} sent={last.sent} onSelect={setSelected} />}
        {current === null ? (
          <p>
            {selected === null
              ? '기체를 고르면 원장 상태와 연결이 여기에 보입니다.'
              : `목록에 없는 기체입니다: ${selected}`}
          </p>
        ) : (
          <RobotDetail
            key={current.robot.robotId}
            view={current}
            mode={session.mode}
            busy={busy}
            onRetire={(reason) =>
              run(`${current.robot.robotId} 퇴역`, () => retireRobot(session, current.robot.robotId, reason))
            }
            onReinstate={() =>
              run(`${current.robot.robotId} 복귀`, () => reinstateRobot(session, current.robot.robotId))
            }
          />
        )}
      </section>
    </div>
  )
}

interface ListProps {
  view: RobotListView | null
  opsError: string | null
  selected: string | null
  onSelect: (robotId: string) => void
}

function RobotList({ view, opsError, selected, onSelect }: ListProps) {
  if (view === null || view.robots === null) {
    return <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
  }
  // 목록을 읽은 시각이 확인 시각과 다르거나 운영 서비스에 닿지 않으면 직전 값이다(스펙 §9).
  // 토큰 불일치는 목록을 새로 읽었으므로 직전 값이 아니다.
  const stale = view.robotsAsOf !== view.checkedAt || opsError !== null
  return (
    <>
      {stale && <p className="stale">직전 값입니다 ({view.robotsAsOf} 기준)</p>}
      {view.robots.length === 0 ? (
        <p>선언된 기체가 없습니다</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>robot_id</th>
              <th>원장 상태</th>
              <th>연결</th>
              <th>막힘</th>
            </tr>
          </thead>
          <tbody>
            {view.robots.map(({ robot, connection, blockers }) => (
              <tr key={robot.robotId} className={robot.robotId === selected ? 'selected' : undefined}>
                <td>
                  <button type="button" className="link" onClick={() => onSelect(robot.robotId)}>
                    {robot.robotId}
                  </button>
                </td>
                <td>{robot.status}</td>
                <td>{CONNECTION_LABEL[connection]}</td>
                <td>{blockers.length}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
```

`ui/src/App.tsx` 는 세 곳만 바꾼다. 다른 줄은 그대로다.

1) 원문

```tsx
  const [opsError, setOpsError] = useState<string | null>(null)
```

새 글

```tsx
  const [opsError, setOpsError] = useState<string | null>(null)
  // 조작이 끝나면 하나 올린다. 목록을 주기(5초)를 기다리지 않고 다시 읽는다.
  const [tick, setTick] = useState(0)
```

2) 원문

```tsx
  }, [session])
```

새 글

```tsx
  }, [session, tick])
```

3) 원문

```tsx
        {current.id === 'robots' && <RobotsArea view={view} opsError={opsError} />}
```

새 글

```tsx
        {current.id === 'robots' && (
          <RobotsArea
            view={view}
            opsError={opsError}
            session={session}
            onChanged={() => setTick((value) => value + 1)}
          />
        )}
```

`ui/src/styles.css`(전체):

```css
body { font-family: system-ui, sans-serif; margin: 0 16px; }
header { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
nav button[aria-current='page'] { font-weight: 700; }
.banner { padding: 8px; margin: 8px 0; border: 1px solid #999; }
.banner.unknown { border-color: #b23a1d; }
.split { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.stale { color: #6f6f6f; }
.invalid { color: #b23a1d; }
.finding { border-left: 3px solid #b23a1d; padding-left: 8px; }
.finding dt { font-weight: 700; }
.link { background: none; border: none; color: #2b3f6b; text-decoration: underline; cursor: pointer; padding: 0; }
.offscreen { color: #6f6f6f; }
tr.selected { background: #e8ebf3; }
```

- [ ] **Step 4: 시험과 빌드가 통과하는지 확인**

Run: `cd ui && npm test && npm run build; cd ..`
Expected: `Tests  22 passed (22)`(S1a 9개, 새 13개), 빌드 성공.

- [ ] **Step 5: 결함 주입 6건(하나씩)**

① `RobotDetail.tsx` 의 `disabled={busy || reason.trim() === ''}` 를 `disabled={busy}` 로 바꾼다. Expected 실패: `퇴역은 운영자 모드에서 사유 없이는 보낼 수 없다`.
② `RobotsArea.tsx` 의 `const stale = view.robotsAsOf !== view.checkedAt || opsError !== null` 을 `const stale = view.registry !== 'OK' || opsError !== null` 로 바꾼다. Expected 실패: `토큰 불일치여도 새로 읽은 목록은 직전 값으로 표시하지 않는다`.
③ `RobotsArea.tsx` 의 `{session.mode === 'engineer' ? (` 를 `{true ? (` 로 바꾼다. Expected 실패: `엔지니어 모드에서만 선언 폼이 보이고 사이트 없이 JSON 으로 선언을 보낸다`.
④ `RobotsArea.tsx` 의 `sent={last.sent} onSelect={setSelected} />` 를 `sent={last.sent} onSelect={() => {}} />` 로 바꾼다. Expected 실패: `거절 알림의 바로 가기를 누르면 그 기체의 상세가 열린다`.
⑤ `RobotsArea.tsx` 의 안내 문장 `화면 밖 작업: 로봇 내부 지도와 웨이포인트 티칭, mimic 기동(site/ 런처). 화면은 완료를 대신 체크하지 않습니다` 를 지운다. Expected 실패: `화면 밖 작업을 화면이 명시한다`.
⑥ `api.ts` 의 ``return { kind: 'unknown', cause: `운영 서비스 응답 ${response.status}` }`` 를 `return { kind: 'refused', refusal: { error: 'SEND_FAILED', detail: '보내지 못함' } }` 로 바꾼다. Expected 실패: `운영 서비스의 응답이 비정상이면 보내지 못했다고 단정하지 않고 결과 모름으로 보인다`.
각각 `1 failed | 21 passed` 인지 보고, 되돌리고 `22 passed` 를 본다.

- [ ] **Step 6: 임시 커밋**

```bash
git add ui/vite.config.ts ui/src/api.ts ui/src/labels.ts ui/src/components/FindingCard.tsx ui/src/components/OutcomeNotice.tsx ui/src/components/DeclareForm.tsx ui/src/components/RobotDetail.tsx ui/src/components/RobotsArea.tsx ui/src/App.tsx ui/src/styles.css ui/src/App.test.tsx ui/src/testing/fakeOps.ts ui/src/components/RobotsArea.test.tsx
git commit -F - <<'EOF'
chore(s1b): 로봇 상세와 조작 화면

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

---

## Chunk 5: Playwright, CI, 마무리

### Task 7: Playwright

**Files:**
- Modify: `ui/package.json`, `ui/package-lock.json`(`npm install` 이 고친다), `ui/.gitignore`(전체)
- Create: `ui/playwright.config.ts`, `ui/e2e/run-dist.mjs`, `ui/e2e/postgres.mjs`, `ui/e2e/teardown.ts`
- Test: `ui/e2e/lifecycle.spec.ts`

스택(Postgres, 런처, 운영 서비스, 화면)을 `webServer` 항목 4개로 하나씩 띄우고 각각 준비 확인을 거친다(스펙 §10). Playwright 가 끝날 때 프로세스 트리째 끈다(Linux 는 SIGTERM). 런처와 운영 서비스는 `java -cp lib/*` 로 바로 띄우며, 실행 JDK 는 21 이상이어야 한다(머리말 결정 8). 둘의 로그는 Playwright 출력에 `[WebServer]` 로 나온다. 포트는 루트 `.env` 에서 읽는다. 런처가 1:1 로 시간을 미므로 보고를 기다리는 단계마다 최대 60초다. 시험 끝에 런처를 꺼서 «registry 를 멈추면 모름» 을 본다(머리말 결정 12).

- [ ] **Step 1: 설치**

```bash
cd ui
npm install -D --no-audit --no-fund @playwright/test@^1.63.0
npx playwright install chromium
cd ..
```
Expected: `ui/package.json` 의 `devDependencies` 에 `"@playwright/test": "^1.63.0"`.

- [ ] **Step 2: 설정과 기동 스크립트 쓰기**

`ui/.gitignore`(전체):

```
# Logs
logs
*.log
npm-debug.log*
yarn-debug.log*
yarn-error.log*
pnpm-debug.log*
lerna-debug.log*

node_modules
dist
dist-ssr
*.local

# Editor directories and files
.vscode/*
!.vscode/extensions.json
.idea
.DS_Store
*.suo
*.ntvs*
*.njsproj
*.sln
*.sw?

# Playwright
playwright-report
test-results
```

`ui/playwright.config.ts`:

```ts
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
```

`ui/e2e/run-dist.mjs`:

```js
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
```

`ui/e2e/postgres.mjs`:

```js
// compose 의 Postgres 를 띄우고 끝날 때까지 기다린다. Playwright 의 webServer 가 부른다.
// 준비 신호는 `up --wait`(healthcheck)가 끝난 뒤 찍는 줄이다. 포트가 열리는 것은 initdb 뒤 재기동보다 먼저라
// 준비 신호가 되지 못한다. 띄우기 전에 한 번 내린다. 앞선 실행이 기동 실패나 강제 종료로 남긴 컨테이너가
// 다음 실행을 막지 않게 하려는 것이며, 손으로 띄워 둔 같은 compose 프로젝트(site)도 함께 내린다.
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../..', import.meta.url))
const compose = (...args) =>
  execFileSync('docker', ['compose', '-f', 'site/compose.yaml', '--env-file', '.env', ...args], {
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
```

`ui/e2e/teardown.ts`:

```ts
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
```

- [ ] **Step 3: 시험 쓰기**

`ui/e2e/lifecycle.spec.ts`:

```ts
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * S1b 완료 판정의 화면 쪽(스펙 §3·§10). 선언 → 보고(CONFIRMED) → 퇴역 → 퇴역 뒤 보고 감지 → 복귀 → 조작 기록,
 * 그리고 registry 를 멈추면 화면 전체 상태가 «모름» 이고 목록이 직전 값으로 남는 것(스펙 §10 마지막 문단).
 * 기체는 site/robots.json 의 humanoid-01 이다. 런처가 mimic 을 띄워 두었으므로 선언하면 보고가 붙는다.
 *
 * 선언 직후의 CLAIMED 는 여기서 단언하지 않는다. 실시간 1:1 시계에서는 다음 보고가 1초 안에 올 수도 있어
 * CLAIMED 가 화면에 보이는 시간이 정해지지 않는다. CLAIMED 는 가상 시계를 직접 미는 통합 시험이 본다.
 */
test('화면에서 기체 생애주기를 한 번 돌고 registry 를 멈추면 모름을 본다', async ({ page }) => {
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

  // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
  const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
  process.kill(Number(readFileSync(pidFile, 'utf8')))
  await page.getByRole('button', { name: '로봇·연결' }).click()
  await expect(page.getByRole('alert')).toContainText('모름: registry 가 답하지 않습니다')
  await expect(page.getByRole('button', { name: 'humanoid-01', exact: true })).toBeVisible()
  await expect(page.getByText(/직전 값입니다/)).toBeVisible()
})
```

- [ ] **Step 4: 돌리기**

```bash
./gradlew :site:installDist :ops-service:installDist --console=plain -q
cd ui && npx playwright test; cd ..
docker ps -a --format '{{.Names}}' | grep site- ; echo containers-checked
```
Expected: `1 passed`(약 1.5분), 마지막 줄 앞에 `site-` 컨테이너 없음. 이어서 남은 프로세스가 없는지 본다(Windows PowerShell). 출력이 `0` 이어야 한다.

```powershell
(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='node.exe'" | Where-Object { $_.CommandLine -match 'SiteLauncherKt|OpsApplicationKt|run-dist|postgres.mjs|vite' } | Measure-Object).Count
```

- [ ] **Step 5: 결함 주입과 기동 실패 확인**

① `lifecycle.spec.ts` 가 실제로 화면을 보는지 한 번 확인한다. `ui/src/labels.ts` 의 `REPORTING_AFTER_RETIREMENT: '퇴역 뒤 보고',` 를 `REPORTING_AFTER_RETIREMENT: '퇴역 후 보고',` 로 바꾼다. 이 주입은 형 검사에 걸리지 않고 vitest 도 이 문자열을 보지 않으므로 `npx tsc -b` 와 `npm test` 는 그대로 초록이어야 한다(기동 실패가 아니라 시험 실패를 보려는 것이다). 이어서 Step 4 를 다시 돈다. Expected: `1 failed`(`퇴역 뒤 보고` 를 60초 안에 못 찾는다), 끝난 뒤 컨테이너와 프로세스는 남지 않는다. 되돌리고 Playwright 를 다시 돌려 `1 passed` 를 본다.
② 기동 실패 때 남는 것을 본다. `ui/e2e/run-dist.mjs` 의 `'dev.picasso.ops.service.OpsApplicationKt'` 를 `'dev.picasso.ops.service.NoSuchMainKt'` 로 바꾸고 `npx playwright test` 를 돈다. Expected: `Process from config.webServer was not able to start`, 남은 java·node 프로세스 0, `site-postgres-1` 컨테이너 1개가 남는다(실측과 같다). 되돌리고 Step 4 를 다시 돌면 `1 passed` 이고 컨테이너가 남지 않는다(`postgres.mjs` 가 띄우기 전에 내린다).

- [ ] **Step 6: 임시 커밋**

```bash
git add ui/package.json ui/package-lock.json ui/.gitignore ui/playwright.config.ts ui/e2e/run-dist.mjs ui/e2e/postgres.mjs ui/e2e/teardown.ts ui/e2e/lifecycle.spec.ts
git status --short ui
git commit -F - <<'EOF'
chore(s1b): Playwright 생애주기 시험

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: 커밋 전 `git status --short ui` 에 `playwright-report`·`test-results` 가 없다.

### Task 8: CI 와 README

**Files:**
- Modify: `.github/workflows/ci.yml`(전체)
- Modify: `README.md`(컨트롤러가 한다)

- [ ] **Step 1: CI 에 Playwright job 더하기**

`.github/workflows/ci.yml`(전체):

```yaml
name: ci

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

# Gradle 과 npm 을 따로 돌린다(스펙 §10). job 이 둘이라 한쪽이 실패해도 다른 쪽이 돈다.
jobs:
  gradle:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      # Testcontainers 는 러너의 Docker 를 쓴다.
      - name: Gradle build
        run: ./gradlew build --console=plain
      # 판정은 종료 코드가 아니라 XML 의 실패 시험 이름으로 한다. 실패해도 올린다.
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: '**/build/test-results/test/*.xml'

  ui:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: ui/package-lock.json
      - name: UI test and build
        working-directory: ui
        run: |
          npm ci
          npm test
          npm run build

  # 실행 중인 전체 스택 앞에서 화면으로 생애주기를 한 번 돈다(스펙 §10). 스택은 Playwright 가 띄우고 끈다.
  playwright:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Build distributions
        run: ./gradlew :site:installDist :ops-service:installDist --console=plain
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: ui/package-lock.json
      - name: Playwright
        working-directory: ui
        run: |
          npm ci
          npx playwright install --with-deps chromium
          npx playwright test
      - name: Upload Playwright report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report
          path: ui/playwright-report
```

- [ ] **Step 2: README 고치기(컨트롤러가 한다)**

문장은 Fable·Codex 초안 취합이다. 더할 사실:
- 지금 단계는 S1b(로봇 생애주기). 화면에서 엔지니어 모드는 선언, 운영자 모드는 퇴역·복귀.
- 화면 시험: 먼저 `./gradlew :site:installDist :ops-service:installDist` 와 `cd ui && npx playwright install chromium`, 그다음 `cd ui && npx playwright test`. Playwright 가 Postgres·런처·운영 서비스·화면을 띄우고 끈다. 1:1 시계라 약 1~2분.
- 실행 JDK 는 21 이상이어야 한다(Playwright 와 `smoke.sh` 가 배포본을 바로 띄운다). 선행 도구의 «JDK 17 이상» 줄을 고친다.
- 손으로 띄운 compose 스택이 있으면 화면 시험이 시작할 때 그것을 볼륨째 내린다(같은 compose 프로젝트, 데이터가 지워진다). 남은 컨테이너를 걷는 명령: `docker compose -f site/compose.yaml --env-file .env down -v`.

- [ ] **Step 3: 임시 커밋**

```bash
git add .github/workflows/ci.yml README.md
git commit -F - <<'EOF'
chore(s1b): CI 의 Playwright job 과 README

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 9: 전체 확인, 스펙 정정, PR

**Files:**
- Modify: `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md`
- Modify: `docs/superpowers/plans/2026-10-07-s1b-robot-lifecycle.md`(끝에 «실행 결과» 절)

- [ ] **Step 1: 전체 빌드와 시험**

Run: `./gradlew build :site:installDist :ops-service:installDist --console=plain` 그리고 세 모듈 XML 확인. 이어서 `cd ui && npm ci && npm test && npm run build && npx playwright test; cd ..`
Expected: site 12, ops-service 53, e2e 13, 실패 0. vitest `22 passed`. Playwright `1 passed`. 배포본을 다시 만드는 것은 Task 7 뒤 코드가 바뀌었을 때 Playwright 가 낡은 배포본으로 돌지 않게 하려는 것이다.

- [ ] **Step 2: 커밋된 트리만으로 도는지 확인**

Run: `git status --short` 가 비었는지 본다. 이어서 짧은 경로에 새로 클론해 커밋된 파일만으로 돌린다(빠뜨린 `git add` 를 잡는다).

```bash
D=C:/Users/Eisen/AppData/Local/Temp/s1b-clone
rm -rf "$D"
git clone -q --recurse-submodules -b feat/s1b-lifecycle "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" "$D"
(cd "$D" && ./gradlew build :site:installDist :ops-service:installDist --console=plain -q && cd ui && npm ci && npm test && npx playwright test)
rm -rf "$D"
```
Expected: 모두 성공(Playwright `1 passed`). 커밋된 파일만으로 CI 와 가장 가까운 확인이다.

- [ ] **Step 3: 스펙 정정(컨트롤러가 한다)**

문장은 Fable·Codex 초안 취합이다. 고칠 사실(머리말 결정 1~12 와 대응):
- §7.2: 목록을 읽을 때마다 관문 안 `GET /operations/adapters` 로 운영자 토큰을 함께 확인한다(2026-10-07 결정).
- §7.3: 연결 칸과 막힘의 판정 시각은 목록을 읽은 시각이다(결정 5).
- §7.4: 대응표에 없는 응답은 `UNCLASSIFIED`(엔지니어, 화면 밖), 종류 값의 표기, 거절은 `UNCLASSIFIED` 만 화면 밖이라는 것(대응표에 화면 안/밖 열을 더한다)(결정 4). «바로 갈 링크» 칸은 기체 id 이고 모양은 화면이 정한다(결정 6). `REGISTRY_UNAUTHORIZED` 는 목록 읽기의 토큰 확인에서도 나온다. «토큰 불일치는 S1b 의 첫 조작에서 드러난다» 는 문장을 «목록을 읽을 때 드러난다» 로 고친다.
- §8: 모드를 운영 서비스도 집행한다(모드 불일치 403, 결정 1). 다른 모드에서는 어느 모드에서 하는지 적는다. «직전 값» 표시는 목록을 읽은 시각이 확인 시각과 다를 때다(결정 7). 선언 폼의 «표시 이름».
- §9: registry 에 보내기 전에 막은 요청(행위자 헤더 없음 400, 모드 403, 사이트 400, JSON 아닌 본문 415, 본문 해석 불가 400)은 조작 기록에 남지 않고, 사이트 대조는 선언에만 있다(결정 2). 조작 API 는 registry 가 답한 결과를 거절까지 포함해 200 과 결과 본문으로 돌려준다(결정 3). 재조회는 1초 뒤 한 번이고 실패하면 확인 행을 붙이지 않으며, 그때 화면 문구(결정 9). 선언의 «반영됨» 판정(결정 10). 화면의 «결과 모름», 알림에 어느 기체의 어느 조작인지 보이는 것, 거절 알림의 막힘 카드에만 바로 가기가 있는 것(결정 11).
- §10: Playwright 는 스택을 프로세스마다 `webServer` 로 띄우고 끈다. 런처와 운영 서비스는 `java -cp lib/*` 로 띄운다. Postgres 준비 확인과 기동 실패 때의 정리(결정 8). Playwright 가 `CLAIMED` 를 단언하지 않는 이유와 registry 정지 방법(결정 12). CI 는 job 3개(`gradle`·`ui`·`playwright`)를 돌린다.
- §11: 사실 행 둘을 더한다. registry 는 퇴역 기체의 생존 보고도 받아 `lastReportedAt` 을 갱신한다(`registry/.../ingest/LivenessService.kt`). 선언·퇴역·복귀는 `audit_log` 에 남는다(`registry/.../binding/RobotRegistration.kt`). 서브모듈 포인터를 옮길 때 `GET /operations/adapters` 가 여전히 관문 안에 있는지 다시 확인한다는 것도 적는다(없어지면 토큰 확인이 조용히 꺼진다).

- [ ] **Step 4: 이 계획 끝에 «실행 결과» 절 덧붙이기**

S1a 계획과 같은 모양이다. 담을 것: 기준 브랜치와 계획 커밋(Task 0 의 결과), 계획과 달라진 점, 모듈별 시험 수와 실패 0, 결함 주입 결과, Playwright 결과와 정리 확인, 깨끗한 클론 결과. CI 는 «PR 뒤 확인 대기».

- [ ] **Step 5: 임시 커밋을 하나로 합치기**

커밋 문장은 Fable·Codex 초안 취합이다. 기준은 계획 커밋이다. 계획 커밋은 따로 남고, 그 위의 임시 커밋만 하나로 합친다.

```bash
git add docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md docs/superpowers/plans/2026-10-07-s1b-robot-lifecycle.md
git commit -F - <<'EOF'
chore(s1b): 스펙 정정과 실행 결과

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
BASE=<Task 0 에서 적은 계획 커밋 해시>
T=$(git rev-parse 'HEAD^{tree}')
git log --oneline "$BASE"..HEAD
git reset --soft "$BASE"
git commit -F - <<'EOF'
<취합한 메시지>
EOF
git log --oneline "$BASE"..HEAD
test "$(git rev-parse 'HEAD^{tree}')" = "$T" && echo TREE-SAME
git status --short
```
Expected: 첫 `git log` 에 임시 커밋 9개(최종 검토 반영 커밋이 있으면 그만큼 더), 둘째 `git log` 에 1개, `TREE-SAME`, `git status --short` 출력 없음. `git log --oneline -3` 이 합친 커밋, 계획 커밋, S1a 커밋(또는 main) 순이다.

- [ ] **Step 6: 푸시와 PR(사용자 승인 뒤)**

PR 을 만들기 직전에 `gh pr view 1 --repo LivingLikeKrillin/picasso-ops --json state -q .state` 를 다시 본다(Task 0 의 결과를 다시 쓰지 않는다). `OPEN` 이면 base 를 `feat/s1a-skeleton` 으로 두고, #1 이 머지된 뒤 base 를 main 으로 바꾼다(스쿼시 머지 금지, 쌓은 PR 이 깨진다). 그 사이 S1a 브랜치에 새 커밋이 들어왔으면 이 브랜치를 그 위로 옮긴 뒤 올린다. `MERGED` 인데 Task 0 이 S1a 위에서 시작했으면 계획 커밋부터 main 위로 옮긴다(`git rebase --onto origin/main 3a51f29`). PR 본문은 Fable·Codex 초안 취합이며 형식 훅이 `--body-file -` 만 읽는다.

```bash
git push -u origin feat/s1b-lifecycle
gh pr create --repo LivingLikeKrillin/picasso-ops --base <기준 브랜치> --head feat/s1b-lifecycle --title "<제목>" --body-file - <<'EOF'
<본문>
EOF
```

- [ ] **Step 7: CI 결과 한 번 읽기**

CI 를 폴링하지 않는다. PR 을 만든 뒤 앱의 PR 도구로 연결하고 CI 상태를 한 번 읽는다(Auto-fix 가 켜져 있으면 실패 때 앱이 알린다). Linux 의 Playwright 는 로컬에서 돈 적이 없으므로 첫 CI 가 첫 실측이다.
- 세 job 이 모두 초록이면 job 로그에서 `1 passed` 와 Gradle 시험 XML(아티팩트 `test-results`)을 확인하고, «실행 결과» 의 «CI 대기» 를 바꾸는 후속 커밋을 남긴다. S1b 완료 보고는 이 뒤에 한다(스펙 §10 «Playwright 는 CI 에서도 돌립니다»).
- 빨가면 `playwright` job 은 아티팩트 `playwright-report` 와 로그의 `[WebServer]` 줄(런처·운영 서비스 로그)을, `gradle` job 은 `test-results` 의 XML 실패 이름을 읽고 고친다. 고친 것은 새 커밋으로 올린다. Linux 에서 처음 도는 자리이므로 Postgres 준비 신호, `java` 경로(`JAVA_HOME`), 시험 끝의 런처 끄기(`process.kill`) 뒤 Playwright 의 처리를 먼저 의심한다.
머지는 사용자 승인 뒤다.
