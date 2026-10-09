# S4a 장애 주입, 인시던트 화면, 운영자 보류 해소 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음(Task 1·2 / Task 3 / Task 4 / Task 5·6)마다 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다.

**Goal:** 엔지니어가 화면의 장애 주입 패널로 기체에 스킬 실패나 연결 끊김을 넣으면 실행에 인시던트, IN_DOUBT, 오래됨이 생기고, 운영 영역의 인시던트 구역이 목록과 상세(현장 설정 버전, 임무 버전, 근거 윈도우)를 보이며, 설비 대기 기한으로 운영자 보류가 된 단위에 운영자가 사유를 적어 완료 확인이나 재작업을 판단하면 실행이 이어지고 판단자와 사유가 남는다.

**Architecture:** site 는 셀 대역 루프백 서버에 `POST /faults` 를 붙여 mimic 엔진을 엔진 잠금 아래에서 직접 부른다(제어 채널과 시계 RPC 는 열지 않음). 실행 호스트는 장애를 잠금 밖에서 site 로 중계하고, 인시던트 목록·상세와 운영자 판단(`Middleware.resolve`, 승인자 PERSON)을 잠금 아래에서 내며, 기한 20초 뒤 운영자 보류가 되는 템플릿 `ARRIVAL_WAIT_HOLD` 를 더한다. 운영 서비스는 인시던트를 중계하고, 장애 주입(엔지니어)과 판단(운영자, 사유 필수)을 조작 기록에 남긴다. 화면은 장애 주입 패널과 인시던트 구역, 운영자 판단을 연다. picasso 변경은 없다.

**Tech Stack:** Kotlin, Spring Boot(BOM), JDK `HttpServer`·`HttpClient`, picasso(`Middleware.resolve`, `OperatorDecision`, `Approver`, mimic 엔진), React·TypeScript·vitest, Playwright, JUnit5 + kotlin.test, Testcontainers.

**근거 스펙:** `docs/superpowers/specs/2026-10-09-s4a-faults-incidents-design.md`(스펙 검토 2회)와 요청·응답 모양 `docs/superpowers/specs/2026-10-09-s4a-json-contract.md`.

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- site 요청 본문 `{robotId, kind, state?}`, `kind` 는 `SKILL_EXECUTION_FAILED` 또는 `CONNECTION`, `state` 는 `OFFLINE`·`CONNECTION_BROKEN`·`ONLINE`(`HIBERNATING` 은 400). 오류 400 `BAD_REQUEST`·`UNSUPPORTED_FAULT`, 404 `UNKNOWN_ROBOT`, 409 `NO_RUNNING_TASK`, 409 `FAULT_REFUSED`(방어용, 정상 흐름에서는 안 남), 415. 작업 지시 직후 태스크가 아직 ACCEPTED 면 다음 시계 진행 전까지 409.
- 호스트 인시던트 목록은 기존 14칸 뒤에 `unresolved`, `resolution{decision, at, wallClockAt, decidedBy{id, kind}}`, `fault` 요약, `held`, `confirmedWithoutEvidence`(호스트가 계산). 상세 없음은 404 `INCIDENT_NOT_FOUND`. 목록 엔드포인트는 새 `IncidentController` 로 옮김.
- 호스트 판단의 결과는 늘 200 본문(본문이 틀리면 400·415), `{result, detail, incidentId, requestId}`. requestId 는 저장하지 않고 돌려주기만 한다. 없는 실행·단위는 NotHeld. 호스트 REST 는 늘 PERSON 이라 Refused 는 REST 로 나지 않는다.
- 운영 서비스 판단 경로 `POST /api/executions/{e}/units/{u}/resolve` 본문 `{decision, reason}`, 승인자는 `X-Ops-User` 만. 새 오류 이름 `FAULT_BAD_REQUEST`·`RESOLVE_BAD_REQUEST`·`INCIDENT_BAD_REQUEST`(limit 1~500), 불통은 `HOST_SILENT`. 판단 응답의 `outcome` 칸에 호스트 결과 이름. 재조회는 그 실행·단위의 인시던트 가운데 하나라도 판단자·결정이 같고 판단의 실제 시각이 요청 직전 실제 시각 이후이면 반영.
- 화면: 장애 주입 패널은 App 에서 현장·자원 영역 옆, 퇴역 기체는 선택에서 뺌, 판단 칸 값 넷(미해결, 판단됨, 판단됨(설비 근거 없이 완료 확인), 판단 대상 아님), 강조는 `held` 만, 판단 시각은 `wallClockAt`, 보류 템플릿의 화면 이름 «운영자 보류 대기 템플릿».
- 자연 실패: 시드 0 에서 humanoid-01 의 다섯째 `pick_place` 가 자연 실패이고 첫 `pick_place` 를 강제 실패시키면 열셋째로 밀린다. 통합 시험은 작업 지시 하나에 슬롯 하나를 써서 `pick_place` 넷으로 그 자리를 피한다.
- 통합 시험의 연결 끊김 오래됨 단계는 연결 기준 60초 변경(현장 설정 버전 2)과 5초마다 가상 시계 31초 밀기로 quadruped-01 을 신선하게 두고 실제 시간 약 62초. Playwright 는 기존 생애주기 시험 하나에 단계 둘(장애 주입, 보류 판단)을 더한다(스펙 §3 의 «Playwright 시험은 둘» 을 이렇게 바꿈). 그 단계들은 registry 를 멈추는 단계 바로 앞이고, 보류 단계는 신호를 먼저 끈다.
- 계획 검토(1회)가 잡아 스파이크에 더한 것: 승인자가 본문이 아니라 `X-Ops-User` 에서만 온다는 시험(주입 O32), 이름 없는 거부의 표시 문구(`HostRejection` 의 `error`·`detail` 널 가능, 신호·임무·작업 지시 알림도 같은 문구 함수, 주입 U27), 판단 폼을 인시던트마다 새로 그림(그 주입은 등가 변이라 뺌), 금지어 정정(호스트 주석, 작업 지시 알림 주석, S1·S3a 스펙 문장).
- 실측: site 32 → 38, mission-host 57 → 64, ops-service 209 → 231, e2e 50 → 57(`FaultIncidentTest` 7), vitest 106 → 138, Playwright 1(시험 약 3.4분). 결함 주입 100건(site 10, 호스트 23, 운영 서비스 32, 화면 27, 통합 7, Playwright 1).
- 문장: 커밋·PR·문서 문장은 Codex 와 Fable 초안을 취합한다.

**작업 위치 규칙(필수):**
- 모든 작업은 picasso-ops 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a`(브랜치 `feat/s4a-faults-incidents`)에서 한다. picasso-ops 메인 체크아웃과 다른 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. site·mission-host·ops-service·e2e 시험은 Testcontainers 로 Postgres 컨테이너를 띄우므로 Docker 데몬이 떠 있어야 한다. Playwright 는 compose 의 Postgres(`127.0.0.1:55432`)와 고정 포트(8781~8785, 4173)를 쓰므로 다른 Playwright·런처와 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 명령은 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 명령을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다.
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. Expected 의 수는 `<testcase>` 수다. 수는 `PYTHONUTF8=1 python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a/MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름). vitest 는 `npm test` 출력의 `Tests` 줄로 센다.
- 이 저장소는 LF 다(`.gitattributes` 의 `eol=lf`). 뽑아 둔 파일은 그대로 복사한다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc 으로 쓴다(`git commit -F -` 에 표준 입력). 형식 훅이 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/s4a`, 브랜치 `spike/s4a`, HEAD `e53d52e`)에서 시험, 전체 빌드, Playwright, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(`s4a-cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / Task 3 / Task 4 / Task 5·6)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 7)과 검토·PR 은 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/s4a-patches/` 에 두었다. 새 파일은 `s4a-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `s4a-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.
- Step 은 순서대로 하나씩 끝내고 다음으로 간다. 다음 Task 의 파일을 미리 복사하거나 패치하지 않는다.

---

## Chunk 1: 구현

### Task 0: 워크트리와 기준선(컨트롤러)

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" && git fetch -q origin
git -C "C:/Users/Eisen/Desktop/Labs/[projects] picasso-ops" worktree add "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" -b feat/s4a-faults-incidents docs/s4a-faults-incidents-design
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git submodule update --init -q && git -C picasso log --oneline -1
```
Expected: 워크트리의 기준은 `docs/s4a-faults-incidents-design`(스펙·계약·계획 커밋, 그 아래 `e482c33`). 서브모듈이 `195c1ee Merge pull request #85`(바꾸지 않는다).

- [ ] **Step 2: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && ./gradlew :site:test :mission-host:test :ops-service:test :e2e:test -q
```
Expected: site 32, mission-host 57, ops-service 209, e2e 50, 실패 0. 백그라운드로 돌린다. 이어서 `cd ui && npm ci && npm test` 로 vitest 기준선 106.

- [ ] **Step 3: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/s4a-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/s4a-patches/`(패치 5개, `files/` 아래 새 파일 18개)가 있는지 본다. 없으면 멈추고 보고한다.

### Task 1: site: 장애 주입 엔드포인트

**Files:**
- Create: `site/src/main/kotlin/dev/picasso/ops/site/SiteFaults.kt`, `site/src/test/kotlin/dev/picasso/ops/site/SiteFaultsTest.kt`
- Modify: `site/src/main/kotlin/dev/picasso/ops/site/Site.kt`, `site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt`

- [ ] **Step 1: 새 파일 2개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p site/src/main/kotlin/dev/picasso/ops/site && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/site/src/main/kotlin/dev/picasso/ops/site/SiteFaults.kt" site/src/main/kotlin/dev/picasso/ops/site/SiteFaults.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p site/src/test/kotlin/dev/picasso/ops/site && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/site/src/test/kotlin/dev/picasso/ops/site/SiteFaultsTest.kt" site/src/test/kotlin/dev/picasso/ops/site/SiteFaultsTest.kt
```

`site/src/main/kotlin/dev/picasso/ops/site/SiteFaults.kt`:

```kotlin
package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState

/** 장애 주입의 결과. 거부는 그대로 HTTP 응답이 된다. 받아들임의 [body] 가 `200` 본문이다. */
sealed interface FaultInjection {
    data class Accepted(val body: Map<String, Any?>) : FaultInjection

    data class Refused(val status: Int, val error: String, val detail: String) : FaultInjection
}

/**
 * 기체 장애 주입(S4a 스펙 §5, T1, T2). 셀 대역의 루프백 `HttpServer` 에 붙는 `POST /faults` 하나이며 호출자는 실행 호스트뿐이다.
 * mimic 제어 채널을 열지 않고 같은 프로세스의 엔진 객체를 `MimicServer.exclusive` 아래에서 직접 부른다. 시계 밀기·시계 모드·
 * 시드는 어느 경로로도 내지 않는다.
 *
 * 종류는 둘이다.
 *
 * - 스킬 실패([SKILL_FAULTS]): 그 기체의 진행 중(RUNNING·PAUSED·CANCELLING) 태스크 가운데 그 스킬의 것을 현장이 스스로
 *   찾아 `tasks.forceFault` 로 강제한다. 호출자는 태스크 id 를 모른다. 없으면 409 [NO_RUNNING_TASK] 다.
 * - 연결 상태([CONNECTION]): `events.setConnection` 으로 [CONNECTION_STATES] 가운데 하나로 바꾼다. `HIBERNATING` 은 받지
 *   않는다.
 *
 * 지울 때까지 유지되는 결함(PAYLOAD_LOST, LOCALIZATION_LOST, 제어권 상실)과 전송 장애는 종류 목록에 없으므로 400
 * [UNSUPPORTED_FAULT] 다. picasso 에 지우는 호출이 없어 그런 결함이 서면 현장을 재기동해야 그 기체가 풀린다.
 *
 * 엔진을 부른 뒤 밀거나 정착시키지 않는다. 열린 스트림에는 다음 시계 진행의 정착(런처는 1초 주기, 시험은 시계 밀기)이 민다.
 */
class SiteFaults(private val mimic: MimicCli.Started) {

    private val json = ObjectMapper()

    /**
     * 장애 하나를 넣는다. 판정 순서는 종류(400) → 기체(404) → 진행 중 태스크(409)다. 엔진 판정과 호출은 한 잠금 아래에서 한다.
     *
     * @param state 연결 상태 종류일 때의 목표 상태. 스킬 실패에는 쓰지 않는다.
     */
    fun inject(robotId: String, kind: String, state: String?): FaultInjection {
        val skill = SKILL_FAULTS[kind]
        val connection = state?.let { CONNECTION_STATES[it] }
        if (skill == null && kind != CONNECTION) {
            return FaultInjection.Refused(400, UNSUPPORTED_FAULT, "받지 않는 장애 종류다: $kind (받는 것: ${KINDS.joinToString(", ")})")
        }
        if (kind == CONNECTION && connection == null) {
            return FaultInjection.Refused(
                400, UNSUPPORTED_FAULT, "받지 않는 연결 상태다: $state (받는 것: ${CONNECTION_STATES.keys.joinToString(", ")})",
            )
        }
        return mimic.server.exclusive {
            val instance = mimic.instance(robotId)
                ?: return@exclusive FaultInjection.Refused(404, UNKNOWN_ROBOT, "이 현장에 없는 기체다: $robotId")
            if (connection != null) {
                val changed = instance.events.setConnection(connection)
                FaultInjection.Accepted(linkedMapOf("robotId" to robotId, "kind" to kind, "state" to state, "changed" to changed))
            } else {
                val task = instance.tasks.all.lastOrNull { it.skillType == skill && it.machine.state in RUNNING_STATES }
                    ?: return@exclusive FaultInjection.Refused(
                        409, NO_RUNNING_TASK, "$robotId 에 진행 중인 $skill 태스크가 없다(진행 중: ${RUNNING_STATES.joinToString(", ")})",
                    )
                when (val outcome = instance.tasks.forceFault(kind, task.taskId)) {
                    is ForceOutcome.Raised -> FaultInjection.Accepted(
                        linkedMapOf(
                            "robotId" to robotId,
                            "kind" to kind,
                            "taskId" to task.taskId,
                            "taskState" to outcome.taskState?.name,
                            "raised" to outcome.raised,
                        ),
                    )
                    // 잠금 아래에서 진행 중 태스크를 골랐으므로 오지 않는다. 엔진이 거부하면 그 이유를 그대로 낸다.
                    is ForceOutcome.NotFound -> FaultInjection.Refused(409, FAULT_REFUSED, "엔진이 태스크를 찾지 못했다: ${outcome.taskId}")
                    is ForceOutcome.Rejected -> FaultInjection.Refused(409, FAULT_REFUSED, outcome.detail)
                }
            }
        }
    }

    /** `POST /faults`. 셀 대역의 `HttpServer` 가 이 경로를 넘긴다. */
    fun handle(exchange: HttpExchange) {
        try {
            respondTo(exchange)
        } finally {
            exchange.close()
        }
    }

    private fun respondTo(exchange: HttpExchange) {
        val (status, body) = when {
            exchange.requestURI.path != PATH -> 404 to ByteArray(0)
            exchange.requestMethod != "POST" -> {
                exchange.responseHeaders.add("Allow", "POST")
                405 to ByteArray(0)
            }
            else -> respond(exchange)
        }
        if (body.isNotEmpty()) exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    /**
     * 본문은 `{"robotId": "<기체>", "kind": "<종류>", "state": "<목표 상태>"}` 이다. `state` 는 [CONNECTION] 일 때 필수이고 그
     * 밖에는 없어야 한다. 칸이 문자열이 아니거나 빠지면 400 [BAD_REQUEST] 다.
     *
     * `application/json` 만 받는다. 브라우저의 단순 요청(폼·`text/plain`)은 사전 요청 없이 다른 출처에서 올 수 있다.
     */
    private fun respond(exchange: HttpExchange): Pair<Int, ByteArray> {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) {
            return 415 to rejection(UNSUPPORTED_MEDIA_TYPE, "Content-Type 이 application/json 이 아니다: ${contentType ?: "없음"}")
        }
        val node: JsonNode? = runCatching { json.readTree(exchange.requestBody.readAllBytes()) }.getOrNull()?.takeIf { it.isObject }
        val robotId = node?.text("robotId")
        val kind = node?.text("kind")
        val stateNode = node?.get("state")?.takeUnless { it.isNull }
        val state = stateNode?.takeIf { it.isTextual }?.asText()
        val shaped = robotId != null && kind != null && (stateNode == null || state != null) &&
            ((kind == CONNECTION) == (state != null))
        if (!shaped) {
            return 400 to rejection(
                BAD_REQUEST,
                "본문이 {\"robotId\": \"<기체>\", \"kind\": \"<종류>\"} 모양이 아니다. state 는 $CONNECTION 일 때만 문자열로 싣는다",
            )
        }
        return when (val outcome = inject(robotId!!, kind!!, state)) {
            is FaultInjection.Accepted -> 200 to json.writeValueAsBytes(outcome.body)
            is FaultInjection.Refused -> outcome.status to rejection(outcome.error, outcome.detail)
        }
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun rejection(error: String, detail: String): ByteArray =
        json.writeValueAsBytes(linkedMapOf("error" to error, "detail" to detail))

    companion object {
        const val PATH = "/faults"

        /** 연결 상태 종류. 목표 상태를 `state` 에 싣는다. */
        const val CONNECTION = "CONNECTION"

        /**
         * 스킬 실패 종류와 그 스킬. 종류 이름은 picasso 프로파일의 `error_type` 이다. 스스로 재시도 가능하고 새 태스크까지만
         * 유지되는 것만 둔다(T2).
         */
        val SKILL_FAULTS: Map<String, String> = mapOf("SKILL_EXECUTION_FAILED" to "pick_place")

        /** 받는 연결 상태. 바깥 이름은 접두사 없는 이름이다. */
        val CONNECTION_STATES: Map<String, ConnectionState> = linkedMapOf(
            "OFFLINE" to ConnectionState.CONNECTION_STATE_OFFLINE,
            "CONNECTION_BROKEN" to ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN,
            "ONLINE" to ConnectionState.CONNECTION_STATE_ONLINE,
        )

        /** 받는 종류 전부. 오류 설명에 쓴다. */
        val KINDS: List<String> get() = SKILL_FAULTS.keys.toList() + CONNECTION

        /** 진행 중 태스크 상태. mimic 이 결함을 받는 상태(`TaskHost.FAULTABLE`, 비공개)와 같은 셋이다. */
        val RUNNING_STATES: Set<TaskState> = setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING)

        /** 오류 이름(S4a JSON 계약 §1). 실행 호스트가 같은 이름을 그대로 넘긴다. */
        const val BAD_REQUEST = SiteCell.BAD_REQUEST
        const val UNSUPPORTED_MEDIA_TYPE = SiteCell.UNSUPPORTED_MEDIA_TYPE
        const val UNSUPPORTED_FAULT = "UNSUPPORTED_FAULT"
        const val UNKNOWN_ROBOT = "UNKNOWN_ROBOT"
        const val NO_RUNNING_TASK = "NO_RUNNING_TASK"
        const val FAULT_REFUSED = "FAULT_REFUSED"
    }
}
```

`site/src/test/kotlin/dev/picasso/ops/site/SiteFaultsTest.kt`:

```kotlin
package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.TaskState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 기체 장애 주입 `POST /faults`(S4a 스펙 §5, §10 의 site 행). registry 없이 mimic 하나와 셀 대역만 띄우고 기체에 `pick_place` 를
 * 직접 걸어 가상 시계로 민다. 시계를 미는 순서는 [Site.advance] 와 같다(민 직후 훑기).
 */
class SiteFaultsTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private inner class Bench : AutoCloseable {
        val mimic: MimicCli.Started = checkNotNull(
            MimicCli().start(
                robots = mapOf(
                    HUMANOID to root.resolve("picasso/profile/profiles/humanoid-a.json"),
                    QUADRUPED to root.resolve("picasso/profile/profiles/quadruped-b.json"),
                ),
                schema = root.resolve(SiteConfig.PROFILE_SCHEMA),
                port = 0,
                virtual = true,
                seed = 0L,
                err = System.err,
            ),
        ) { "mimic 기동 거부" }
        val cell = SiteCell(mimic)
        private val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", mimic.server.port).usePlaintext().build()
        val client = PicassoClient(channel, "site-faults-test")

        init {
            advance(Duration.between(Instant.EPOCH, START))
        }

        fun advance(by: Duration) = mimic.server.exclusive {
            mimic.server.advance(by)
            cell.scan()
        }

        fun pickPlace(taskId: String, destination: String = S01) {
            val response = client.start(
                HUMANOID, taskId, 1, "pick_place",
                listOf(
                    ParameterValue.newBuilder().setKey("object_id").setStringValue(SOURCE).build(),
                    ParameterValue.newBuilder().setKey("destination").setStringValue(destination).build(),
                ),
            )
            assertTrue(response.hasHandle(), "태스크 시작이 거부됐다: ${response.rejection}")
        }

        fun state(taskId: String): TaskState? = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.find(taskId)?.machine?.state }

        fun connection(robotId: String): ConnectionState = mimic.server.exclusive { mimic.instance(robotId)!!.events.connectionState }

        fun post(body: String, contentType: String = "application/json", path: String = "/faults"): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${cell.port}$path"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        fun inject(robotId: String, kind: String, state: String? = null): HttpResponse<String> =
            post(json.writeValueAsString(linkedMapOf("robotId" to robotId, "kind" to kind, "state" to state).filterValues { it != null }))

        override fun close() {
            channel.shutdownNow()
            cell.close()
            mimic.server.shutdown()
        }
    }

    private fun HttpResponse<String>.json(): JsonNode = json.readTree(body())

    private fun HttpResponse<String>.error(): String = json()["error"].asText()

    @Test
    fun `스킬 실패는 현장이 진행 중인 pick_place 를 찾아 강제하고 그 태스크는 RETRIABLE 이며 슬롯을 채우지 않는다`() {
        Bench().use { bench ->
            bench.pickPlace("JO-1#$S01")
            bench.advance(Duration.ofSeconds(1))
            assertEquals(TaskState.RUNNING, bench.state("JO-1#$S01"))

            val reply = bench.inject(HUMANOID, SKILL)
            assertEquals(200, reply.statusCode(), reply.body())
            assertEquals(
                mapOf("robotId" to HUMANOID, "kind" to SKILL, "taskId" to "JO-1#$S01", "taskState" to "RETRIABLE", "raised" to true),
                json.convertValue(reply.json(), Map::class.java),
            )
            assertEquals(listOf("robotId", "kind", "taskId", "taskState", "raised"), reply.json().fieldNames().asSequence().toList())
            assertEquals(TaskState.RETRIABLE, bench.state("JO-1#$S01"))
            val raised = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.faults.active().map { it.errorType } }
            assertEquals(listOf(SKILL), raised)

            // 실패로 끝난 태스크는 다음 시계 진행에서 슬롯을 채우지 않는다.
            repeat(4) { bench.advance(Duration.ofSeconds(5)) }
            assertEquals(TaskState.RETRIABLE, bench.state("JO-1#$S01"))
            assertFalse(bench.cell.snapshot.slots.single { it.id == S01 }.occupied)
        }
    }

    @Test
    fun `진행 중 태스크가 없으면 409 NO_RUNNING_TASK 이고 끝난 태스크와 pick_place 가 없는 기체도 같다`() {
        Bench().use { bench ->
            val idle = bench.inject(HUMANOID, SKILL)
            assertEquals(409, idle.statusCode(), idle.body())
            assertEquals(SiteFaults.NO_RUNNING_TASK, idle.error())

            bench.pickPlace("JO-1#$S01")
            repeat(40) { if (bench.state("JO-1#$S01")?.isTerminal != true) bench.advance(Duration.ofSeconds(5)) }
            assertEquals(TaskState.SUCCEEDED, bench.state("JO-1#$S01"))
            val done = bench.inject(HUMANOID, SKILL)
            assertEquals(409, done.statusCode(), done.body())
            assertEquals(SiteFaults.NO_RUNNING_TASK, done.error())

            val quadruped = bench.inject(QUADRUPED, SKILL)
            assertEquals(409, quadruped.statusCode(), quadruped.body())
            assertEquals(SiteFaults.NO_RUNNING_TASK, quadruped.error())
            assertTrue(bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.faults.active().isEmpty() })
        }
    }

    @Test
    fun `지울 때까지 유지되는 결함과 전송 장애와 HIBERNATING 은 400 UNSUPPORTED_FAULT 이고 모르는 기체는 404 다`() {
        Bench().use { bench ->
            bench.pickPlace("JO-1#$S01")
            bench.advance(Duration.ofSeconds(1))

            listOf("PAYLOAD_LOST", "LOCALIZATION_LOST", "CONTROL_AUTHORITY_LOST", "DISCONNECT", "DELAY", "EVENT_LOSS").forEach { kind ->
                val reply = bench.inject(HUMANOID, kind)
                assertEquals(400, reply.statusCode(), kind)
                assertEquals(SiteFaults.UNSUPPORTED_FAULT, reply.error(), kind)
            }
            listOf("HIBERNATING", "CONNECTION_STATE_OFFLINE", "offline").forEach { state ->
                val reply = bench.inject(HUMANOID, SiteFaults.CONNECTION, state)
                assertEquals(400, reply.statusCode(), state)
                assertEquals(SiteFaults.UNSUPPORTED_FAULT, reply.error(), state)
            }
            assertEquals(TaskState.RUNNING, bench.state("JO-1#$S01"))
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))

            listOf(bench.inject(GHOST, SKILL), bench.inject(GHOST, SiteFaults.CONNECTION, "OFFLINE")).forEach { reply ->
                assertEquals(404, reply.statusCode(), reply.body())
                assertEquals(SiteFaults.UNKNOWN_ROBOT, reply.error())
            }
        }
    }

    @Test
    fun `연결 상태를 바꾸고 같은 상태를 다시 넣으면 changed 가 거짓이며 ONLINE 으로 복구한다`() {
        Bench().use { bench ->
            val offline = bench.inject(HUMANOID, SiteFaults.CONNECTION, "OFFLINE")
            assertEquals(200, offline.statusCode(), offline.body())
            assertEquals(
                mapOf("robotId" to HUMANOID, "kind" to "CONNECTION", "state" to "OFFLINE", "changed" to true),
                json.convertValue(offline.json(), Map::class.java),
            )
            assertEquals(listOf("robotId", "kind", "state", "changed"), offline.json().fieldNames().asSequence().toList())
            assertEquals(ConnectionState.CONNECTION_STATE_OFFLINE, bench.connection(HUMANOID))
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(QUADRUPED))

            assertEquals(false, bench.inject(HUMANOID, SiteFaults.CONNECTION, "OFFLINE").json()["changed"].asBoolean())

            assertEquals(200, bench.inject(QUADRUPED, SiteFaults.CONNECTION, "CONNECTION_BROKEN").statusCode())
            assertEquals(ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN, bench.connection(QUADRUPED))

            assertEquals(true, bench.inject(HUMANOID, SiteFaults.CONNECTION, "ONLINE").json()["changed"].asBoolean())
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))
        }
    }

    @Test
    fun `본문 모양이 틀리면 400 BAD_REQUEST 이고 JSON 이 아니면 415 이며 POST 만 받고 다른 경로는 404 다`() {
        Bench().use { bench ->
            listOf(
                """{"kind":"$SKILL"}""",
                """{"robotId":"$HUMANOID"}""",
                """{"robotId":7,"kind":"$SKILL"}""",
                """{"robotId":"$HUMANOID","kind":"CONNECTION"}""",
                """{"robotId":"$HUMANOID","kind":"CONNECTION","state":true}""",
                """{"robotId":"$HUMANOID","kind":"$SKILL","state":"OFFLINE"}""",
                """["$HUMANOID"]""",
                "{not json",
            ).forEach { body ->
                val reply = bench.post(body)
                assertEquals(400, reply.statusCode(), body)
                assertEquals(SiteFaults.BAD_REQUEST, reply.error(), body)
            }
            // state 가 null 이면 없는 것과 같다.
            assertEquals(409, bench.post("""{"robotId":"$HUMANOID","kind":"$SKILL","state":null}""").statusCode())

            val text = bench.post("""{"robotId":"$HUMANOID","kind":"CONNECTION","state":"OFFLINE"}""", contentType = "text/plain")
            assertEquals(415, text.statusCode())
            assertEquals(SiteFaults.UNSUPPORTED_MEDIA_TYPE, text.error())
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))

            val get = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${bench.cell.port}/faults")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, get.statusCode())
            assertEquals("POST", get.headers().firstValue("Allow").orElse(null))
            assertEquals(404, bench.post("""{"robotId":"$HUMANOID","kind":"$SKILL"}""", path = "/faults/x").statusCode())
        }
    }

    @Test
    fun `장애 주입은 mimic 엔진 잠금을 기다린다`() {
        Bench().use { bench ->
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread {
                bench.mimic.server.exclusive {
                    held.countDown()
                    release.await()
                }
            }.apply { start() }
            held.await()
            val injection = CompletableFuture.supplyAsync { bench.inject(HUMANOID, SiteFaults.CONNECTION, "OFFLINE").statusCode() }
            try {
                Thread.sleep(300)
                assertTrue(!injection.isDone, "엔진 잠금을 쥔 동안 장애 주입이 끝났다")
            } finally {
                release.countDown()
                holder.join()
            }
            assertEquals(200, injection.get(5, TimeUnit.SECONDS))
            assertEquals(ConnectionState.CONNECTION_STATE_OFFLINE, bench.connection(HUMANOID))
        }
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val GHOST = "ghost-01"
        const val SKILL = "SKILL_EXECUTION_FAILED"
        const val SOURCE = "SEQ-IN-02.BIN-A"
        const val S01 = "RACK-204.S01"
        val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task1.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task1.patch"
```

```diff
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
index a3d3bfd..68fe7d7 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/Site.kt
@@ -23,7 +23,7 @@ import java.time.Instant
  *
  * 기동 직후 mimic 가상 시계를 실제 시각까지 한 번 민다. 미들웨어는 E2 시간 윈도우의 기준 시각을 mimic 응답 헤더의
  * `state_as_of` 에서 가져오고 마감은 자기 시계로 보므로, EPOCH 에서 시작하면 둘이 어긋난다. 그 뒤 런처는 [advanceTo] 로
- * 실제 시각을 따라잡는다. [now]·시계 밀기·셀 대역의 훑기·[teach] 는 모두 `MimicServer.exclusive` 아래에서 돈다. 가상
+ * 실제 시각을 따라잡는다. [now]·시계 밀기·셀 대역의 훑기·[teach]·장애 주입은 모두 `MimicServer.exclusive` 아래에서 돈다. 가상
  * 시계는 다른 스레드에서 읽을 때 최신 값이 보인다는 보장이 없으므로 잠금이 그 가시성도 맡는다.
  */
 class Site private constructor(
@@ -41,7 +41,7 @@ class Site private constructor(
     /** mimic gRPC 가 열린 포트. 시험은 0 을 주고 여기서 읽는다(S3a 스펙 §6.2). */
     val mimicPort: Int get() = mimic.server.port
 
-    /** 셀 대역 `GET /cell` 이 열린 루프백 포트(S3a 스펙 §6.3). */
+    /** 셀 대역 `GET /cell` 과 장애 주입 `POST /faults` 가 열린 루프백 포트(S3a 스펙 §6.3, S4a 스펙 §5). */
     val cellPort: Int get() = cell.port
 
     /** 셀 대역의 지금 스냅숏. `GET /cell` 이 내는 것과 같다. */
diff --git a/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt b/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt
index 8c92591..1ce0e72 100644
--- a/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt
+++ b/site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt
@@ -133,7 +133,8 @@ sealed interface SignalWrite {
  * ## 내는 곳
  *
  * 루프백 JDK `HttpServer` 의 `GET /cell` 과 `POST /cell/signals/{name}` 이다. `GET` 처리 스레드는 [snapshot] 만 읽고
- * 엔진에 닿지 않는다. 본문 모양과 오류 이름은 S3b JSON 계약 §1·§2 다.
+ * 엔진에 닿지 않는다. 본문 모양과 오류 이름은 S3b JSON 계약 §1·§2 다. 같은 서버에 기체 장애 주입 `POST /faults`([SiteFaults],
+ * S4a 스펙 §5)가 붙는다.
  *
  * @param port 0 이면 무작위(시험).
  */
@@ -159,8 +160,12 @@ class SiteCell(
 
     private val json = ObjectMapper()
 
+    /** 기체 장애 주입(S4a 스펙 §5). 같은 루프백 서버의 `POST /faults` 가 이것을 부른다. */
+    val faults = SiteFaults(mimic)
+
     private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
         createContext("/cell", ::handle)
+        createContext(SiteFaults.PATH, faults::handle)
         start()
     }
 
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && ./gradlew :site:test -q
```
Expected: site 38, 실패 0.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git add site/src/main/kotlin/dev/picasso/ops/site/SiteFaults.kt site/src/test/kotlin/dev/picasso/ops/site/SiteFaultsTest.kt site/src/main/kotlin/dev/picasso/ops/site/Site.kt site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt && git commit -F - <<'EOF'
feat(site): 셀 대역 루프백 서버의 장애 주입 `POST /faults`

- 기체의 진행 중 `pick_place` 태스크에 `SKILL_EXECUTION_FAILED` 강제, 진행 중 태스크는 site 가 스스로 찾고 없으면 409 `NO_RUNNING_TASK`
- 연결 상태 `OFFLINE`·`CONNECTION_BROKEN`·`ONLINE` 전환, mimic 엔진을 엔진 잠금 아래에서 직접 호출하며 제어 채널과 시계 RPC 는 열지 않음
- 오류 이름 400 `BAD_REQUEST`·`UNSUPPORTED_FAULT`, 404 `UNKNOWN_ROBOT`, 415

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 2: 실행 호스트: 장애 중계, 인시던트 상세, 운영자 판단, 보류 템플릿

**Files:**
- Create: `mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt`, `mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait-hold.json`, `mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt`
- Modify: `mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt`, `mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt`, `mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt`

- [ ] **Step 1: 새 파일 4개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p mission-host/src/main/kotlin/dev/picasso/ops/host/web && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt" mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p mission-host/src/main/resources/mission-templates && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait-hold.json" mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait-hold.json
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p mission-host/src/test/kotlin/dev/picasso/ops/host && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt" mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt`:

```kotlin
package dev.picasso.ops.host

import dev.picasso.middleware.Approver
import dev.picasso.middleware.FaultDetail
import dev.picasso.middleware.IncidentBundle
import dev.picasso.middleware.IncidentResolution
import dev.picasso.middleware.OperatorDecision
import dev.picasso.middleware.Verification
import java.time.Duration
import java.time.Instant

// 호스트 인시던트 REST 의 본문 모양(S3c 스펙 §7.2, S4a 스펙 §6). 칸 이름과 순서가 계약이다(S4a JSON 계약 §3).

/** 승인자. [kind] 는 `PERSON`·`AGENT` 이며 호스트가 만드는 판단자는 늘 `PERSON` 이다(ADR 47). */
data class ApproverView(val id: String, val kind: String) {
    companion object {
        fun of(approver: Approver) = ApproverView(approver.id, approver.kind.name)
    }
}

/**
 * 사람이 그 인시던트의 단위에 낸 판단(picasso `IncidentResolution`). [at] 은 미들웨어 시각(호스트 시계)이고 [wallClockAt] 은
 * 실제 시각이다. 운영 서비스의 재조회는 [wallClockAt] 을 요청 시각과 맞댄다. 통합 시험에서는 호스트 시계가 현장 가상 시계라
 * [at] 이 실제 시각보다 앞선다.
 */
data class ResolutionView(val decision: String, val at: Instant, val wallClockAt: Instant, val decidedBy: ApproverView) {
    companion object {
        fun of(resolution: IncidentResolution) =
            ResolutionView(resolution.decision.name, resolution.at, resolution.wallClockAt, ApproverView.of(resolution.decidedBy))
    }
}

/** 이 단위를 실패로 만든 결함의 요약(목록용). 하류가 결함 없이 실패를 알렸으면(설비 대기 기한 포함) 인시던트의 칸이 `null` 이다. */
data class FaultSummaryView(val failureClass: String, val errorType: String, val errorHint: String) {
    companion object {
        fun of(fault: FaultDetail) = FaultSummaryView(fault.failureClass, fault.errorType, fault.errorHint)
    }
}

/** 결함이 지목한 대상 하나. */
data class FaultReferenceView(val key: String, val value: String)

/** 결함 하나의 전부(상세용). picasso `FaultDetail` 의 칸 그대로다. */
data class FaultDetailView(
    val failureClass: String,
    val errorType: String,
    val vendorDetail: String,
    val errorHint: String,
    val references: List<FaultReferenceView>,
    val canContinueCurrentTask: Boolean,
    val canAcceptNewTask: Boolean,
    val activeUntilKind: String,
    val activeUntilTime: String,
) {
    companion object {
        fun of(fault: FaultDetail) = FaultDetailView(
            fault.failureClass, fault.errorType, fault.vendorDetail, fault.errorHint,
            fault.references.map { FaultReferenceView(it.key, it.value) },
            fault.canContinueCurrentTask, fault.canAcceptNewTask, fault.activeUntilKind, fault.activeUntilTime,
        )
    }
}

/** 근거 윈도우 안의 관측 하나. [local] 이 참이면 미들웨어가 적은 관측(셀 신호, 연결 등)이고 [sequence] 는 커서 값을 빌린 것이다. */
data class ObservedEventView(val sequence: Long, val occurredAt: String, val kind: String, val detail: String, val local: Boolean)

/** 몇 걸음 가운데 어디서 났는가. [at] 은 1 부터이고 계획에 없으면 0 이다. */
data class StepView(val at: Int, val plan: List<String>, val completed: List<String>)

data class MaterialView(val materialDefinitionId: String, val quantity: Int)

data class EquipmentView(val id: String, val equipmentUse: String, val properties: Map<String, String>)

/**
 * 무엇을 하려던 일이었나(picasso `Intent`). 시간값은 초 단위 정수다. [missionVersion] 이 `null` 이면 코드 정의이고
 * [siteSettingsVersion] 이 `null` 이면 현장 시간값 없이 봉인했다(그때 [inDoubtGraceSeconds]·[stallWindowSeconds] 도 `null`).
 */
data class IntentView(
    val workMasterId: String,
    val orderVersion: Int,
    val orderParameters: Map<String, String>,
    val materials: List<MaterialView>,
    val equipment: List<EquipmentView>,
    val capabilityMaxEvidence: String,
    val evidenceBeforeSeconds: Long,
    val evidenceAfterSeconds: Long,
    val skillType: String,
    val unitParameters: Map<String, String>,
    val source: String?,
    val destination: String?,
    val expectedIdentity: String?,
    val missionVersion: Int?,
    val siteSettingsVersion: Long?,
    val inDoubtGraceSeconds: Long?,
    val stallWindowSeconds: Long?,
)

/**
 * 인시던트 목록의 한 줄(S3c 스펙 §7.2, S4a 스펙 §6). 앞 14칸은 S3c 그대로이고 뒤 다섯 칸이 S4a 에서 더해졌다.
 *
 * @param at 봉인 라운드의 미들웨어 시각(호스트 시계)
 * @param missionVersion 임무 버전. `null` 이면 코드 정의다
 * @param siteSettingsVersion 봉인 라운드의 현장 설정 버전. 현장 시간값 없이 봉인했으면 `null` 이다
 * @param evidenceBeforeSeconds·evidenceAfterSeconds 봉인 라운드의 근거 윈도우 앞·뒤 폭. 늘 있다
 * @param inDoubtGraceSeconds·stallWindowSeconds 봉인 라운드의 값. 현장 시간값 없이 봉인했으면 `null` 이다
 * @param unresolved 봉인할 때 판정을 보류했는가. 봉인 때 한 번 정해지고 판단 뒤에도 그대로다
 * @param resolution 사람이 이 인시던트의 단위에 낸 판단. 없으면 `null` 이다
 * @param fault 이 단위를 실패로 만든 결함의 요약. 없으면 `null` 이다
 * @param held 그 단위가 지금 운영자 보류이고 이 인시던트가 그 단위의 가장 최근 미해결(`unresolved` 이고 판단 없음) 인시던트다.
 *   재작업 뒤 다시 보류가 서면 새 인시던트만 참이다
 * @param confirmedWithoutEvidence 판단이 완료 확인이고 봉인 때 확인 결과가 MATCHED 가 아니다(설비 근거 없이 완료 확인)
 */
data class IncidentView(
    val incidentId: String,
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val unitId: String,
    val at: Instant,
    val failureClass: String?,
    val route: String,
    val missionVersion: Int?,
    val siteSettingsVersion: Long?,
    val evidenceBeforeSeconds: Long,
    val evidenceAfterSeconds: Long,
    val inDoubtGraceSeconds: Long?,
    val stallWindowSeconds: Long?,
    val unresolved: Boolean,
    val resolution: ResolutionView?,
    val fault: FaultSummaryView?,
    val held: Boolean,
    val confirmedWithoutEvidence: Boolean,
)

/** `GET /host/incidents` 의 본문. [incidents] 는 최신부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
data class IncidentsView(val instanceId: String, val total: Int, val incidents: List<IncidentView>)

/**
 * `GET /host/incidents/{incidentId}` 의 본문(S4a 스펙 §6). 목록 줄의 칸에 근거 윈도우, 단계 위치, 필요·도달 근거 등급, 확인 결과,
 * 결함 전부, `blockedBy`, 의도 전체를 더한다.
 *
 * @param unitState 그 단위의 지금 상태. 실행이 없으면 `null` 이다
 * @param verification 봉인 때의 설비 확인 결과. `NOT_REQUESTED` 는 확인 결과를 묻지 않았다는 뜻이지 설비 확인을 안 했다는 뜻이 아니다
 * @param windowTruncated 근거 윈도우 밖이라 버린 관측이 있는가
 */
data class IncidentDetailView(
    val instanceId: String,
    val incidentId: String,
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val unitId: String,
    val at: Instant,
    val wallClockAt: Instant,
    val failureClass: String?,
    val route: String,
    val unresolved: Boolean,
    val resolution: ResolutionView?,
    val held: Boolean,
    val confirmedWithoutEvidence: Boolean,
    val unitState: String?,
    val fault: FaultDetailView?,
    val blockedBy: List<FaultDetailView>,
    val requiredEvidence: String,
    val reachedEvidence: String,
    val verification: String,
    val step: StepView,
    val evidenceWindow: List<ObservedEventView>,
    val windowTruncated: Boolean,
    val preconditionSubjects: List<String>,
    val expectedHold: String?,
    val observedHold: String,
    val effectMismatch: String?,
    val linkBroken: Boolean,
    val intent: IntentView,
)

/**
 * `POST /host/executions/{executionId}/units/{unitId}/resolve` 의 본문(S4a 스펙 T6). [result] 는 picasso 의 이름 그대로
 * `Resolved`·`NotHeld`·`Refused` 다.
 *
 * @param detail Refused 의 이유. 그 밖에는 `null` 이다
 * @param incidentId Resolved 일 때 판단이 붙은 인시던트. 그 단위에 판단 없는 인시던트가 없었거나 Resolved 가 아니면 `null` 이다
 * @param requestId 요청이 실은 요청 id 를 그대로 돌려준다. 호스트는 저장하지 않는다
 */
data class ResolveView(val result: String, val detail: String?, val incidentId: String?, val requestId: String?)

/** 번들을 본문으로 옮긴다. picasso 의 ISO-8601 기간 문자열은 초로 되돌린다(60초는 `PT1M` 으로 접혀 온다). */
internal object IncidentViews {

    private fun seconds(iso: String): Long = Duration.parse(iso).seconds

    fun confirmedWithoutEvidence(bundle: IncidentBundle): Boolean =
        bundle.resolution?.decision == OperatorDecision.CONFIRM_DONE && bundle.verification != Verification.MATCHED

    fun item(bundle: IncidentBundle, held: Boolean): IncidentView {
        val intent = bundle.intent
        return IncidentView(
            incidentId = bundle.incidentId,
            executionId = bundle.executionId,
            jobOrderId = bundle.jobOrderId,
            robotId = bundle.robotId,
            unitId = bundle.unitId,
            at = bundle.at,
            failureClass = bundle.failureClass,
            route = bundle.route,
            missionVersion = intent.missionVersion,
            siteSettingsVersion = intent.siteSettingsVersion,
            evidenceBeforeSeconds = seconds(intent.evidenceWindowBefore),
            evidenceAfterSeconds = seconds(intent.evidenceWindowAfter),
            inDoubtGraceSeconds = intent.inDoubtGrace?.let(::seconds),
            stallWindowSeconds = intent.stallWindow?.let(::seconds),
            unresolved = bundle.unresolved,
            resolution = bundle.resolution?.let(ResolutionView::of),
            fault = bundle.fault?.let(FaultSummaryView::of),
            held = held,
            confirmedWithoutEvidence = confirmedWithoutEvidence(bundle),
        )
    }

    fun detail(instanceId: String, bundle: IncidentBundle, held: Boolean, unitState: String?): IncidentDetailView {
        val intent = bundle.intent
        return IncidentDetailView(
            instanceId = instanceId,
            incidentId = bundle.incidentId,
            executionId = bundle.executionId,
            jobOrderId = bundle.jobOrderId,
            robotId = bundle.robotId,
            unitId = bundle.unitId,
            at = bundle.at,
            wallClockAt = bundle.wallClockAt,
            failureClass = bundle.failureClass,
            route = bundle.route,
            unresolved = bundle.unresolved,
            resolution = bundle.resolution?.let(ResolutionView::of),
            held = held,
            confirmedWithoutEvidence = confirmedWithoutEvidence(bundle),
            unitState = unitState,
            fault = bundle.fault?.let(FaultDetailView::of),
            blockedBy = bundle.blockedBy.map(FaultDetailView::of),
            requiredEvidence = bundle.requiredEvidence.name,
            reachedEvidence = bundle.reachedEvidence.name,
            verification = bundle.verification.name,
            step = StepView(bundle.step.at, bundle.step.plan, bundle.step.completed),
            evidenceWindow = bundle.evidenceWindow.map { ObservedEventView(it.sequence, it.occurredAt, it.kind, it.detail, it.local) },
            windowTruncated = bundle.windowTruncated,
            preconditionSubjects = bundle.preconditionSubjects,
            expectedHold = bundle.expectedHold?.name,
            observedHold = bundle.observedHold.name,
            effectMismatch = bundle.effectMismatch,
            linkBroken = bundle.observation.linkBroken,
            intent = IntentView(
                workMasterId = intent.workMasterId,
                orderVersion = intent.orderVersion,
                orderParameters = intent.orderParameters,
                materials = intent.materials.map { MaterialView(it.materialDefinitionId, it.quantity) },
                equipment = intent.equipment.map { EquipmentView(it.id, it.equipmentUse, it.properties) },
                capabilityMaxEvidence = intent.capabilityMaxEvidence.name,
                evidenceBeforeSeconds = seconds(intent.evidenceWindowBefore),
                evidenceAfterSeconds = seconds(intent.evidenceWindowAfter),
                skillType = intent.skillType,
                unitParameters = intent.unitParameters,
                source = intent.source,
                destination = intent.destination,
                expectedIdentity = intent.expectedIdentity,
                missionVersion = intent.missionVersion,
                siteSettingsVersion = intent.siteSettingsVersion,
                inDoubtGraceSeconds = intent.inDoubtGrace?.let(::seconds),
                stallWindowSeconds = intent.stallWindow?.let(::seconds),
            ),
        )
    }
}
```

`mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt`:

```kotlin
package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.cell.CellBandClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 인시던트 조회, 운영자 판단, 장애 주입 전달(S3c 스펙 §7.2, S4a 스펙 §6). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다. 모드
 * 검사(주입은 엔지니어, 판단은 운영자)와 사유, 조작 기록은 운영 서비스가 한다.
 *
 * 조회와 판단은 호스트 잠금 아래에서 하고, 장애 주입은 신호 조작처럼 잠금 밖에서 현장에 넘긴다. POST 는 `application/json` 만
 * 받는다(S3a 와 같은 까닭). 판단의 결과(Resolved·NotHeld·Refused)는 늘 200 의 본문에 있다.
 */
@RestController
class IncidentController(
    private val host: MissionHost,
    private val cellBand: CellBandClient,
    private val json: ObjectMapper,
) {

    /** 최신부터 많아야 [limit] 개. 정수가 아니거나 1~[MAX_LIMIT] 밖이면 400 `BAD_REQUEST` 다. */
    @GetMapping("/host/incidents")
    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val count = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $limit"))
        return ResponseEntity.ok(host.incidents(count))
    }

    /** 인시던트 하나의 상세. 없으면 404 `INCIDENT_NOT_FOUND` 다. 호스트를 재기동하면 앞 인시던트는 사라진다(S4b 의 자리). */
    @GetMapping("/host/incidents/{incidentId}")
    fun incident(@PathVariable incidentId: String): ResponseEntity<Any> {
        val detail = host.incident(incidentId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(HostRejection(INCIDENT_NOT_FOUND, "그 인시던트가 없다: $incidentId"))
        return ResponseEntity.ok(detail)
    }

    /**
     * 운영자 판단. 승인자는 늘 `Approver(approverId, PERSON)` 이다(ADR 43 에 따라 기본값이 없고 approverId 는 필수다). 실행이 없거나
     * 그 단위가 보류가 아니면 NotHeld 이며 404 를 내지 않는다.
     */
    @PostMapping("/host/executions/{executionId}/units/{unitId}/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolve(
        @PathVariable executionId: String,
        @PathVariable unitId: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val request = try {
            HostRequests.resolution(body?.let { runCatching { json.readTree(it) }.getOrNull() })
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        return ResponseEntity.ok(
            host.resolve(executionId, unitId, request.decision, Approver(request.approverId, ApproverKind.PERSON), request.requestId),
        )
    }

    /**
     * 장애 주입을 현장 `POST /faults` 에 그대로 넘긴다(T1). 현장의 응답(200·400·404·409·415)은 상태 코드와 본문 그대로 돌려준다.
     * 호스트는 종류를 해석하지 않고 잠금을 잡지 않는다. 현장이 안 닿으면 신호 조작과 같은 503 `CELL_SILENT` 다.
     */
    @PostMapping("/host/faults", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun injectFault(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val relay = cellBand.injectFault(body ?: ByteArray(0))
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(HostRejection(MissionController.CELL_SILENT, "현장 셀 대역이 답하지 않는다"))
        val builder = ResponseEntity.status(relay.status)
        relay.contentType?.let { builder.contentType(MediaType.parseMediaType(it)) }
        return builder.body(relay.body)
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500

        /** 오류 이름(S4a JSON 계약 §3). */
        const val INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"
    }
}
```

`mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait-hold.json`:

```json
{
"schemaVersion": 1,
"workMasterId": "PrepareSequencedRack",
"maxEvidence": "E2",
"preferredOptionals": { "verify_grasp": "true" },
"steps": [
{"kind": "wait", "id": "rack-arrival", "signal": "rack_present", "expect": "true", "deadlineSeconds": 20, "onDeadline": "OPERATOR_HOLD"},
{
"kind": "unit",
"id": "place",
"skill": "pick_place",
"forEach": "destination",
"pairWith": { "equipmentUse": "source", "property": "material" },
"whenUnpaired": "NO_SOURCE_FOR_MATERIAL",
"unitId": { "from": "ITEM_ID" },
"parameters": { "object_id": { "from": "PAIRED_ID", "otherwise": "" }, "destination": { "from": "ITEM_ID" } },
"expectedIdentity": { "from": "ITEM_PROPERTY", "property": "material" },
"source": { "from": "PAIRED_ID" },
"destination": { "from": "ITEM_ID" }
}
]
}
```

`mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt`:

```kotlin
package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.OperatorDecision
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실행 호스트의 인시던트 목록·상세, 운영자 판단, 장애 주입 전달(S4a 스펙 §6, §10 의 호스트 행).
 *
 * 운영자 보류는 `ARRIVAL_WAIT_HOLD` 템플릿을 S3b 흐름으로 활성화하고 신호를 켜지 않은 채 가상 시계를 밀어 만든다. 스킬 실패는
 * 대역 mimic 엔진에 직접 강제한다(현장의 `POST /faults` 와 같은 엔진 호출, 밀기 없이 다음 시계 진행에 맡김).
 */
class IncidentResolveTest {

    private val robots = listOf(HUMANOID, QUADRUPED)
    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    /** 운영자 보류 대기 템플릿을 초안 저장, 모의 실행, 활성화한다. 새 세트라 버전 1 이다. */
    private fun HostBench.activateHold() {
        val definition = get("/host/missions/templates/$PSR")["templates"].single { it["id"].asText() == "ARRIVAL_WAIT_HOLD" }["definition"].asText()
        val saved = post("/host/missions/$PSR/drafts", JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to "${UUID.randomUUID()}")))
        val draftId = saved.body!!["draft"]["draftId"].asLong()
        val mock = post("/host/missions/drafts/$draftId/mock-run", JSON.writeValueAsString(mapOf("robotIds" to robots, "requestId" to "${UUID.randomUUID()}")))
        assertEquals("PASSED", mock.body!!["result"].asText(), mock.body.toString())
        val activation = post(
            "/host/missions/drafts/$draftId/activate",
            JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "보류 시연", "robotIds" to robots, "requestId" to "${UUID.randomUUID()}")),
        )
        assertEquals("ACTIVATED", activation.body!!["result"].asText(), activation.body.toString())
        assertEquals(1, activation.body["version"].asInt())
    }

    /** 셀 대역을 한 번 읽힌 뒤 보류 버전을 활성화하고 작업 지시 하나를 낸다. 실행 id 를 돌려준다. */
    private fun HostBench.submitHeldOrder(): String {
        mimic.server.advance(Duration.ofSeconds(1))
        awaitPump()
        activateHold()
        val submitted = post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        return submitted["executionId"].asText()
    }

    /** 실행이 운영자 보류가 될 때까지 민다. 기한 20초이므로 가상 시간 30초(5초씩 여섯 번) 안에 서야 한다. */
    private fun HostBench.driveToHold(executionId: String): JsonNode {
        val held = driveUntil(executionId, settled + "OPERATOR_HOLD", rounds = 6)
        assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), held.toString())
        assertEquals("OPERATOR_HOLD", held["units"][0]["state"].asText(), held.toString())
        return held
    }

    private fun HostBench.resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: String? = null): HostBench.Reply =
        post(
            "/host/executions/$executionId/units/$unitId/resolve",
            JSON.writeValueAsString(linkedMapOf("decision" to decision, "approverId" to approverId, "requestId" to requestId)),
        )

    private fun HostBench.incidents(): List<JsonNode> = get("/host/incidents")["incidents"].toList()

    @Test
    fun `보류 버전의 대기가 기한 20초를 넘기면 운영자 보류이고 인시던트는 미해결이며 보류 중이고 상세가 근거와 의도를 싣는다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder()
            bench.driveToHold(executionId)

            val item = bench.incidents().single()
            assertEquals(LIST_FIELDS, item.fieldNames().asSequence().toList())
            assertEquals(
                listOf(executionId, WAIT, "SIGNAL_DEADLINE", "SIGNAL", 1, 1L),
                listOf(item["executionId"].asText(), item["unitId"].asText(), item["failureClass"].asText(), item["route"].asText(), item["missionVersion"].asInt(), item["siteSettingsVersion"].asLong()),
            )
            assertEquals(true, item["unresolved"].asBoolean())
            assertTrue(item["resolution"].isNull, item.toString())
            assertTrue(item["fault"].isNull, item.toString())
            assertEquals(true, item["held"].asBoolean())
            assertEquals(false, item["confirmedWithoutEvidence"].asBoolean())

            val detail = bench.get("/host/incidents/${item["incidentId"].asText()}")
            assertEquals(DETAIL_FIELDS, detail.fieldNames().asSequence().toList())
            assertEquals(item["incidentId"], detail["incidentId"])
            assertEquals("OPERATOR_HOLD", detail["unitState"].asText())
            assertEquals(true, detail["held"].asBoolean())
            assertEquals(true, detail["unresolved"].asBoolean())
            assertTrue(detail["resolution"].isNull && detail["fault"].isNull, detail.toString())
            assertTrue(detail["blockedBy"].isArray && detail["blockedBy"].isEmpty, detail.toString())
            assertEquals(listOf("E2", "NOT_REQUESTED"), listOf(detail["requiredEvidence"].asText(), detail["verification"].asText()))
            assertTrue(detail["reachedEvidence"].isTextual)
            assertEquals(JSON.readTree("""{"at":1,"plan":["$WAIT","$S01"],"completed":[]}"""), detail["step"])
            // 기한 시점의 신호 관측이 근거 윈도우에 든다(미들웨어가 기한에 한 번 더 남긴다).
            val deadline = detail["evidenceWindow"].filter { it["kind"].asText() == "CELL_SIGNAL" && "at deadline" in it["detail"].asText() }
            assertEquals(1, deadline.size, detail["evidenceWindow"].toString())
            assertEquals(listOf("sequence", "occurredAt", "kind", "detail", "local"), deadline.single().fieldNames().asSequence().toList())

            val intent = detail["intent"]
            assertEquals(INTENT_FIELDS, intent.fieldNames().asSequence().toList())
            assertEquals(
                listOf(PSR, 1, 1, 1L, 30L, 15L, 60L, 300L),
                listOf(
                    intent["workMasterId"].asText(), intent["orderVersion"].asInt(), intent["missionVersion"].asInt(), intent["siteSettingsVersion"].asLong(),
                    intent["evidenceBeforeSeconds"].asLong(), intent["evidenceAfterSeconds"].asLong(),
                    intent["inDoubtGraceSeconds"].asLong(), intent["stallWindowSeconds"].asLong(),
                ),
            )
            assertEquals(JSON.readTree("""[{"materialDefinitionId":"${HostBench.MATERIAL}","quantity":1}]"""), intent["materials"])
            assertEquals(listOf(S01, HostBench.SOURCE), intent["equipment"].map { it["id"].asText() })

            val missing = bench.fetch("/host/incidents/incident-999")
            assertEquals(404, missing.status)
            assertEquals("INCIDENT_NOT_FOUND", missing.body!!["error"].asText())
        }
    }

    @Test
    fun `판단 결과는 picasso 이름 그대로 Resolved NotHeld Refused 이고 Refused 와 NotHeld 는 아무것도 바꾸지 않는다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder()
            bench.driveToHold(executionId)
            val incidentId = bench.incidents().single()["incidentId"].asText()

            // REST 는 늘 PERSON 승인자를 만든다. 에이전트 승인자는 호스트를 직접 불러 만든다.
            val refused = bench.host.resolve(executionId, WAIT, OperatorDecision.REWORK, Approver("planner-bot", ApproverKind.AGENT), null)
            assertEquals("Refused", refused.result)
            assertTrue(!refused.detail.isNullOrBlank() && refused.incidentId == null, refused.toString())

            val notHeld = bench.resolve(executionId, S01, "CONFIRM_DONE", "kim")
            assertEquals(200, notHeld.status)
            assertEquals(JSON.readTree("""{"result":"NotHeld","detail":null,"incidentId":null,"requestId":null}"""), notHeld.body)
            assertEquals("NotHeld", bench.resolve("exec-999", WAIT, "REWORK", "kim").body!!["result"].asText())
            val untouched = bench.incidents().single()
            assertTrue(untouched["resolution"].isNull && untouched["held"].asBoolean(), untouched.toString())
            assertEquals("OPERATOR_HOLD", bench.execution(executionId)!!["units"][0]["state"].asText())

            val requestId = UUID.randomUUID().toString()
            val resolved = bench.resolve(executionId, WAIT, "REWORK", "kim", requestId)
            assertEquals(200, resolved.status)
            assertEquals(
                JSON.readTree("""{"result":"Resolved","detail":null,"incidentId":"$incidentId","requestId":"$requestId"}"""),
                resolved.body,
            )
        }
    }

    @Test
    fun `재작업 뒤 두 번째 보류는 새 인시던트만 보류 중이고 unresolved 는 봉인 값 그대로이며 근거 없는 완료 확인이 드러난다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder()
            bench.driveToHold(executionId)
            val first = bench.incidents().single()["incidentId"].asText()

            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
            val reworked = bench.incidents().single()
            assertEquals(true, reworked["unresolved"].asBoolean())
            assertEquals(false, reworked["held"].asBoolean())
            assertEquals(listOf("decision", "at", "wallClockAt", "decidedBy"), reworked["resolution"].fieldNames().asSequence().toList())
            assertEquals("REWORK", reworked["resolution"]["decision"].asText())
            assertEquals(JSON.readTree("""{"id":"kim","kind":"PERSON"}"""), reworked["resolution"]["decidedBy"])
            assertEquals(false, reworked["confirmedWithoutEvidence"].asBoolean())
            assertEquals("RUNNING", bench.execution(executionId)!!["physicalState"].asText())

            // 대기가 새로 시작되고 신호가 없으니 다시 보류가 선다.
            bench.driveToHold(executionId)
            val (second, again) = bench.incidents()
            assertEquals(first, again["incidentId"].asText())
            assertEquals(listOf(true, false), listOf(second["held"].asBoolean(), again["held"].asBoolean()))
            assertTrue(second["resolution"].isNull, second.toString())
            assertEquals("REWORK", again["resolution"]["decision"].asText())

            val confirmed = bench.resolve(executionId, WAIT, "CONFIRM_DONE", "park").body!!
            assertEquals(second["incidentId"], confirmed["incidentId"])
            val (confirmedItem, reworkedItem) = bench.incidents()
            assertEquals("CONFIRM_DONE", confirmedItem["resolution"]["decision"].asText())
            assertEquals("park", confirmedItem["resolution"]["decidedBy"]["id"].asText())
            assertEquals(true, confirmedItem["confirmedWithoutEvidence"].asBoolean())
            assertEquals(true, confirmedItem["unresolved"].asBoolean())
            assertEquals(listOf(false, false), listOf(confirmedItem["held"].asBoolean(), reworkedItem["held"].asBoolean()))
            assertEquals("REWORK", reworkedItem["resolution"]["decision"].asText())
            assertEquals("kim", reworkedItem["resolution"]["decidedBy"]["id"].asText())

            val detail = bench.get("/host/incidents/${confirmedItem["incidentId"].asText()}")
            assertEquals("DONE", detail["unitState"].asText())
            assertEquals(true, detail["confirmedWithoutEvidence"].asBoolean())
            assertEquals("NotHeld", bench.resolve(executionId, WAIT, "CONFIRM_DONE", "park").body!!["result"].asText())
        }
    }

    @Test
    fun `진행 중 스킬 실패는 근거가 없으면 GRASP_FAILED 인시던트로 남고 결함 요약과 원문을 싣되 보류가 아니다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val submitted = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            val executionId = submitted["executionId"].asText()
            var taskId: String? = null
            repeat(20) {
                if (taskId == null) {
                    bench.mimic.server.advance(Duration.ofSeconds(1))
                    bench.awaitPump()
                    taskId = bench.mimic.server.exclusive {
                        bench.mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId
                    }
                }
            }
            val running = checkNotNull(taskId) { "pick_place 가 RUNNING 이 되지 않았다" }
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())

            val done = bench.driveUntil(executionId, settled)
            assertEquals("FAILED", done["units"][0]["state"].asText(), done.toString())
            val item = bench.incidents().single()
            assertEquals("GRASP_FAILED", item["failureClass"].asText())
            assertEquals(JSON.readTree("""{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","errorHint":""}""").fieldNames().asSequence().toList(), item["fault"].fieldNames().asSequence().toList())
            assertEquals(listOf("GRASP_FAILED", "SKILL_EXECUTION_FAILED"), listOf(item["fault"]["failureClass"].asText(), item["fault"]["errorType"].asText()))
            assertEquals(listOf(false, false), listOf(item["unresolved"].asBoolean(), item["held"].asBoolean()))
            assertTrue(item["missionVersion"].isNull, item.toString())

            val detail = bench.get("/host/incidents/${item["incidentId"].asText()}")
            assertEquals("FAILED", detail["unitState"].asText())
            assertEquals("NOT_REQUESTED", detail["verification"].asText())
            assertEquals(item["fault"]["errorType"], detail["fault"]["errorType"])
            assertTrue(detail["fault"]["references"].any { it["value"].asText() == running }, detail["fault"].toString())
            assertFalse(detail["fault"]["canContinueCurrentTask"].asBoolean())
            assertEquals("pick_place", detail["intent"]["skillType"].asText())
            assertEquals(S01, detail["intent"]["destination"].asText())
        }
    }

    @Test
    fun `로봇 수준 결함이 다음 단위를 막으면 인시던트 상세의 blockedBy 에 그 결함 원문이 실린다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val submitted = bench.post("/host/job-orders", request(HostBench.inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val executionId = submitted["executionId"].asText()
            var taskId: String? = null
            repeat(20) {
                if (taskId == null) {
                    bench.mimic.server.advance(Duration.ofSeconds(1))
                    bench.awaitPump()
                    taskId = bench.mimic.server.exclusive {
                        bench.mimic.instance(QUADRUPED)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId
                    }
                }
            }
            val running = checkNotNull(taskId) { "첫 태스크가 RUNNING 이 되지 않았다" }
            // 지울 때까지 유지되고 새 태스크를 받지 못하게 하는 로봇 수준 결함이다. 현장의 장애 주입 종류에는 없다(T2).
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())

            val blocked = bench.driveUntil(executionId, settled + "OPERATOR_HOLD", rounds = 4)
            assertEquals("OPERATOR_HOLD", blocked["physicalState"].asText(), blocked.toString())
            val item = bench.incidents().first()
            val detail = bench.get("/host/incidents/${item["incidentId"].asText()}")
            assertEquals(listOf("LOCALIZATION_LOST"), detail["blockedBy"].map { it["errorType"].asText() }, detail.toString())
            assertEquals(false, detail["blockedBy"][0]["canAcceptNewTask"].asBoolean())
            assertEquals("KIND_UNTIL_CLEARED", detail["blockedBy"][0]["activeUntilKind"].asText())
            // 막힘은 실행 수준이라 단위 보류가 아니다. 판단 대상이 아니다.
            assertEquals(false, item["held"].asBoolean())
            assertEquals("NotHeld", bench.resolve(executionId, item["unitId"].asText(), "REWORK", "kim").body!!["result"].asText())
        }
    }

    @Test
    fun `판단 요청 본문이 틀리면 400 BAD_REQUEST 이고 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            listOf(
                """{"decision":"RELEASE","approverId":"kim"}""",
                """{"decision":"REWORK"}""",
                """{"decision":"REWORK","approverId":" "}""",
                """{"decision":"REWORK","approverId":"kim","requestId":"not-a-uuid"}""",
                """{"decision":"REWORK","approverId":"kim","requestId":7}""",
                """["REWORK"]""",
            ).forEach { body ->
                val reply = bench.post("/host/executions/exec-1/units/$WAIT/resolve", body)
                assertEquals(400, reply.status, body)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), body)
            }
            assertEquals(415, bench.post("/host/executions/exec-1/units/$WAIT/resolve", """{"decision":"REWORK","approverId":"kim"}""", contentType = "text/plain").status)
        }
    }

    @Test
    fun `장애 주입은 현장에 그대로 넘기고 현장의 응답을 그대로 돌려주며 현장이 안 닿으면 503 CELL_SILENT 다`() {
        HostBench().use { bench ->
            val body = """{"robotId":"humanoid-01","kind":"CONNECTION","state":"OFFLINE"}"""
            val ok = bench.post("/host/faults", body)
            assertEquals(200, ok.status)
            assertEquals(JSON.readTree(bench.faultReply.second), ok.body)
            assertEquals("application/json" to body, bench.faultWrites.single())

            listOf(
                409 to """{"error":"NO_RUNNING_TASK","detail":"x"}""",
                404 to """{"error":"UNKNOWN_ROBOT","detail":"x"}""",
                400 to """{"error":"UNSUPPORTED_FAULT","detail":"x"}""",
            ).forEach { (status, reply) ->
                bench.faultReply = status to reply
                val relayed = bench.post("/host/faults", """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED"}""")
                assertEquals(status, relayed.status)
                assertEquals(JSON.readTree(reply), relayed.body)
            }
            assertEquals(415, bench.post("/host/faults", body, contentType = "text/plain").status)

            bench.stopCell()
            val silent = bench.post("/host/faults", body)
            assertEquals(503, silent.status)
            assertEquals("CELL_SILENT", silent.body!!["error"].asText())
        }
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
        const val WAIT = "rack-arrival"
        const val S01 = "RACK-204.S01"

        val LIST_FIELDS = listOf(
            "incidentId", "executionId", "jobOrderId", "robotId", "unitId", "at", "failureClass", "route", "missionVersion",
            "siteSettingsVersion", "evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds",
            "unresolved", "resolution", "fault", "held", "confirmedWithoutEvidence",
        )

        val DETAIL_FIELDS = listOf(
            "instanceId", "incidentId", "executionId", "jobOrderId", "robotId", "unitId", "at", "wallClockAt", "failureClass", "route",
            "unresolved", "resolution", "held", "confirmedWithoutEvidence", "unitState", "fault", "blockedBy", "requiredEvidence",
            "reachedEvidence", "verification", "step", "evidenceWindow", "windowTruncated", "preconditionSubjects", "expectedHold",
            "observedHold", "effectMismatch", "linkBroken", "intent",
        )

        val INTENT_FIELDS = listOf(
            "workMasterId", "orderVersion", "orderParameters", "materials", "equipment", "capabilityMaxEvidence", "evidenceBeforeSeconds",
            "evidenceAfterSeconds", "skillType", "unitParameters", "source", "destination", "expectedIdentity", "missionVersion",
            "siteSettingsVersion", "inDoubtGraceSeconds", "stallWindowSeconds",
        )
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task2.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task2.patch"
```

```diff
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
index 4d102fe..cb3bc99 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt
@@ -1,14 +1,19 @@
 package dev.picasso.ops.host
 
+import dev.picasso.middleware.Approver
+import dev.picasso.middleware.IncidentBundle
 import dev.picasso.middleware.InspectAsset
 import dev.picasso.middleware.JobOrder
 import dev.picasso.middleware.JobResponse
 import dev.picasso.middleware.Middleware
+import dev.picasso.middleware.OperatorDecision
 import dev.picasso.middleware.PrepareSequencedRack
 import dev.picasso.middleware.Route
+import dev.picasso.middleware.ResolveOutcome
 import dev.picasso.middleware.RobotPort
 import dev.picasso.middleware.SiteTimingsSource
 import dev.picasso.middleware.Unassigned
+import dev.picasso.middleware.UnitState
 import dev.picasso.ops.host.cell.CellBandClient
 import dev.picasso.ops.host.cell.CellBandSignals
 import dev.picasso.ops.host.cell.CellSnapshot
@@ -108,42 +113,12 @@ data class ExecutionView(
  */
 data class ExecutionsView(val instanceId: String, val pumpedAt: Instant?, val executions: List<ExecutionView>)
 
-/**
- * 인시던트 하나(S3c 스펙 §7.2). 미들웨어 `IncidentBundle` 에서 통합 시험과 화면이 쓰는 칸만 옮긴다. 시간값은 picasso 의 ISO-8601
- * 문자열(`Duration.toString()`, 60초면 `PT1M`)을 초 단위 정수로 되돌린 것이다.
- *
- * @param at 봉인 라운드의 미들웨어 시각(호스트 시계)
- * @param missionVersion 임무 버전. `null` 이면 코드 정의다
- * @param siteSettingsVersion 봉인 라운드의 현장 설정 버전. 현장 시간값 없이 봉인했으면 `null` 이다
- * @param evidenceBeforeSeconds·evidenceAfterSeconds 봉인 라운드의 근거 윈도우 앞·뒤 폭. 늘 있다
- * @param inDoubtGraceSeconds·stallWindowSeconds 봉인 라운드의 값. 현장 시간값 없이 봉인했으면 `null` 이다
- */
-data class IncidentView(
-    val incidentId: String,
-    val executionId: String,
-    val jobOrderId: String,
-    val robotId: String,
-    val unitId: String,
-    val at: Instant,
-    val failureClass: String?,
-    val route: String,
-    val missionVersion: Int?,
-    val siteSettingsVersion: Long?,
-    val evidenceBeforeSeconds: Long,
-    val evidenceAfterSeconds: Long,
-    val inDoubtGraceSeconds: Long?,
-    val stallWindowSeconds: Long?,
-)
-
-/** `GET /host/incidents` 의 본문. [incidents] 는 최신부터 많아야 limit 개이고 [total] 은 자르기 전의 수다. */
-data class IncidentsView(val instanceId: String, val total: Int, val incidents: List<IncidentView>)
-
 /**
  * 미들웨어 실행 호스트(S3a 스펙 §7).
  *
  * ## 잠금 하나
  *
- * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회를 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
+ * 미들웨어에는 스레드도 잠금도 없다. pump, 판정, 제출, 조회, 운영자 판단을 모두 [lock] 하나 아래에서 돈다. 잠금 순서는 호스트 잠금에서
  * mimic 엔진 잠금으로 한 방향뿐이다(gRPC 호출이 잠금 아래에서 나간다).
  *
  * 임무 버전 활성화도 이 잠금 아래에서 한다([exclusive], S3b 스펙 T2). 판정과 배정 사이에 활성화가 끼면 한 제출 안에서 판정
@@ -279,35 +254,58 @@ class MissionHost(
     }
 
     /**
-     * 봉인된 인시던트(S3c 스펙 §7.2). 최신부터 많아야 [limit] 개다. 번들은 미들웨어 안의 값이라 호스트 잠금 아래에서 옮긴다.
+     * 봉인된 인시던트(S3c 스펙 §7.2, S4a 스펙 §6). 최신부터 많아야 [limit] 개다. 번들은 미들웨어 안의 값이라 호스트 잠금 아래에서
+     * 옮긴다. 보류 중 여부는 자르기 전의 전부로 정한다.
      */
     fun incidents(limit: Int): IncidentsView = lock.withLock {
         val all = middleware.incidents()
+        val held = heldIncidents(all)
         IncidentsView(
             instanceId = middleware.instanceId,
             total = all.size,
-            incidents = all.asReversed().take(limit).map { bundle ->
-                val intent = bundle.intent
-                IncidentView(
-                    incidentId = bundle.incidentId,
-                    executionId = bundle.executionId,
-                    jobOrderId = bundle.jobOrderId,
-                    robotId = bundle.robotId,
-                    unitId = bundle.unitId,
-                    at = bundle.at,
-                    failureClass = bundle.failureClass,
-                    route = bundle.route,
-                    missionVersion = intent.missionVersion,
-                    siteSettingsVersion = intent.siteSettingsVersion,
-                    evidenceBeforeSeconds = seconds(intent.evidenceWindowBefore),
-                    evidenceAfterSeconds = seconds(intent.evidenceWindowAfter),
-                    inDoubtGraceSeconds = intent.inDoubtGrace?.let(::seconds),
-                    stallWindowSeconds = intent.stallWindow?.let(::seconds),
-                )
-            },
+            incidents = all.asReversed().take(limit).map { IncidentViews.item(it, it.incidentId in held) },
         )
     }
 
+    /** 인시던트 하나의 상세(S4a 스펙 §6). 없으면 `null` 이다. 호스트 잠금 아래에서 읽는다. */
+    fun incident(incidentId: String): IncidentDetailView? = lock.withLock {
+        val bundle = middleware.incident(incidentId) ?: return@withLock null
+        val unitState = middleware.executions().firstOrNull { it.executionId == bundle.executionId }
+            ?.units?.firstOrNull { it.unitId == bundle.unitId }?.state?.name
+        IncidentViews.detail(middleware.instanceId, bundle, incidentId in heldIncidents(middleware.incidents()), unitState)
+    }
+
+    /**
+     * 운영자 판단(S4a 스펙 T4, T6). 호스트 잠금 아래에서 `Middleware.resolve` 를 부르고 결과 이름을 picasso 그대로 돌려준다.
+     * 판단은 그 단위의 판단 없는 가장 최근 인시던트에 붙는다(picasso `IncidentLog.noteResolution`). Resolved 이면 그 인시던트 id 를
+     * 같이 낸다. REST 는 늘 `PERSON` 승인자를 만들므로 Refused 는 이 메서드를 직접 부를 때만 난다.
+     */
+    fun resolve(executionId: String, unitId: String, decision: OperatorDecision, approver: Approver, requestId: String?): ResolveView =
+        lock.withLock {
+            val target = middleware.incidents().lastOrNull {
+                it.executionId == executionId && it.unitId == unitId && it.resolution == null
+            }?.incidentId
+            when (val outcome = middleware.resolve(executionId, unitId, decision, approver)) {
+                ResolveOutcome.Resolved -> ResolveView(RESOLVED, null, target, requestId)
+                ResolveOutcome.NotHeld -> ResolveView(NOT_HELD, null, null, requestId)
+                is ResolveOutcome.Refused -> ResolveView(REFUSED, outcome.reason, null, requestId)
+            }
+        }
+
+    /**
+     * 보류 중인 인시던트. 그 단위가 지금 운영자 보류이고, 그 단위의 인시던트 가운데 `unresolved` 이고 판단이 없는 가장 최근의
+     * 것이다. 재작업 뒤 두 번째 보류가 서면 앞 인시던트는 판단이 붙어 빠지므로 한 단위에 많아야 하나다.
+     */
+    private fun heldIncidents(all: List<IncidentBundle>): Set<String> {
+        val holding = middleware.executions().flatMap { execution ->
+            execution.units.filter { it.state == UnitState.OPERATOR_HOLD }.map { execution.executionId to it.unitId }
+        }.toSet()
+        return all.filter { (it.executionId to it.unitId) in holding && it.unresolved && it.resolution == null }
+            .groupBy { it.executionId to it.unitId }
+            .values.map { it.last().incidentId }
+            .toSet()
+    }
+
     /** 마지막 pump 가 읽은 셀 대역 스냅숏. 못 읽었으면 `null` 이다. */
     fun cell(): CellSnapshot? = lock.withLock { latestCell }
 
@@ -357,9 +355,6 @@ class MissionHost(
         return HostEligibility(robotId, fit, missing, running, passed = reasons.isEmpty(), reasons = reasons)
     }
 
-    /** picasso 의 ISO-8601 기간 문자열을 초로 되돌린다. 60초는 `PT1M` 으로 접혀 온다. */
-    private fun seconds(iso: String): Long = Duration.parse(iso).seconds
-
     private fun view(response: JobResponse) = JobResponseView(
         jobResponseId = response.jobResponseId,
         version = response.version,
@@ -390,6 +385,11 @@ class MissionHost(
         /** 현장 시간값 미적용 동안 판정이 기체마다 더하는 이유(S3c 스펙 T8). 화면에 그대로 보인다. */
         const val UNAPPLIED_REASON = "현장 시간값 미적용: 실행 호스트가 현장 설정을 아직 읽지 못했다"
 
+        /** 운영자 판단의 결과 이름. picasso `ResolveOutcome` 의 이름 그대로다(S4a 스펙 T6). */
+        const val RESOLVED = "Resolved"
+        const val NOT_HELD = "NotHeld"
+        const val REFUSED = "Refused"
+
         /** 받는 WorkMaster. DeliverContainer 는 플릿 포트 구현이 없어 받지 않는다(스펙 §1). */
         val WORK_MASTERS: Set<String> = setOf(InspectAsset.WORK_MASTER, PrepareSequencedRack.WORK_MASTER)
     }
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt
index 9e26e21..f05ae87 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt
@@ -49,7 +49,7 @@ data class CellSnapshot(val presentations: List<CellPlace>, val slots: List<Cell
     fun signalSpecs(): List<SignalSpec>? = signals?.map { it.spec() }
 }
 
-/** 신호 조작을 현장에 넘긴 결과. 현장이 답하면 그 상태 코드와 본문 그대로다. */
+/** 신호 조작이나 장애 주입을 현장에 넘긴 결과. 현장이 답하면 그 상태 코드와 본문 그대로다. */
 data class SignalRelay(val status: Int, val contentType: String?, val body: ByteArray)
 
 /**
@@ -90,10 +90,18 @@ class CellBandClient(
      * `POST /cell/signals/{name}` 을 현장에 그대로 넘긴다. 현장이 안 닿으면(연결 실패, 시간 초과) `null` 이다. 이름은 경로
      * 조각으로 인코딩한다. 판정(404·400·403)은 현장의 몫이다.
      */
-    fun writeSignal(name: String, body: ByteArray): SignalRelay? = try {
-        val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
+    fun writeSignal(name: String, body: ByteArray): SignalRelay? =
+        relay("$base/cell/signals/${URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")}", body)
+
+    /**
+     * 기체 장애 주입 `POST /faults` 를 현장에 그대로 넘긴다(S4a 스펙 §6). 현장이 안 닿으면 `null` 이다. 종류의 해석과 판정은
+     * 현장의 몫이다. 현장의 주입도 mimic 엔진 잠금을 기다리므로 신호 조작과 같은 요청 제한을 쓴다.
+     */
+    fun injectFault(body: ByteArray): SignalRelay? = relay("$base/faults", body)
+
+    private fun relay(url: String, body: ByteArray): SignalRelay? = try {
         val response = http.send(
-            HttpRequest.newBuilder(URI.create("$base/cell/signals/$encoded"))
+            HttpRequest.newBuilder(URI.create(url))
                 .timeout(writeTimeout)
                 .header("Content-Type", "application/json")
                 .POST(HttpRequest.BodyPublishers.ofByteArray(body))
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt
index d8dcfbf..8ad95cd 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt
@@ -12,17 +12,25 @@ data class MissionTemplate(val id: String, val title: String, val definition: St
  * - [DATA_V1]: 코드 `PrepareSequencedRack` 을 데이터로 옮긴 것(버전 1 의 모양).
  * - [ARRIVAL_WAIT]: 그 앞에 랙 도착 대기(신호 `rack_present`, 기대 `true`, 기한 120초)를 둔 것(버전 2 의 모양). 사본에서
  *   `onDeadline` 만 ABORTED 로 바꿨다(픽스처 기본값은 OPERATOR_HOLD, 결정 4). 운영자 보류와 그 해소 화면은 S4 다.
+ * - [ARRIVAL_WAIT_HOLD]: [ARRIVAL_WAIT] 와 같되 기한이 20초이고 기한 뒤 운영자 보류다(S4a 스펙 T3). 시계 RPC 를 열지 않는
+ *   런처에서 실제 시간으로 기다릴 수 있게 기한을 줄였다.
  */
 object MissionTemplates {
 
     const val DATA_V1 = "DATA_V1"
     const val ARRIVAL_WAIT = "ARRIVAL_WAIT"
+    const val ARRIVAL_WAIT_HOLD = "ARRIVAL_WAIT_HOLD"
 
     /** WorkMaster 마다 시작용 정의. 편집 대상이 PrepareSequencedRack 하나다(T6). */
     fun of(workMasterId: String): List<MissionTemplate> = when (workMasterId) {
         PrepareSequencedRack.WORK_MASTER -> listOf(
             MissionTemplate(DATA_V1, "코드 PrepareSequencedRack 을 옮긴 데이터 정의", read("PrepareSequencedRack.data-v1.json")),
             MissionTemplate(ARRIVAL_WAIT, "랙 도착 대기(rack_present = true, 기한 120초, 기한 뒤 ABORTED)", read("PrepareSequencedRack.arrival-wait.json")),
+            MissionTemplate(
+                ARRIVAL_WAIT_HOLD,
+                "랙 도착 대기(rack_present = true, 기한 20초, 기한 뒤 운영자 보류)",
+                read("PrepareSequencedRack.arrival-wait-hold.json"),
+            ),
         )
         else -> emptyList()
     }
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt
index 72b327a..9a89a67 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt
@@ -5,11 +5,19 @@ import dev.picasso.middleware.EquipmentRequirement
 import dev.picasso.middleware.Evidence
 import dev.picasso.middleware.JobOrder
 import dev.picasso.middleware.MaterialRequirement
+import dev.picasso.middleware.OperatorDecision
 import dev.picasso.ops.host.MissionHost
+import java.util.UUID
 
 /** 거부한 요청의 답. 운영 서비스의 사전 거부(`PreRejection`)와 같은 모양이다. */
 data class HostRejection(val error: String, val detail: String)
 
+/**
+ * 운영자 판단 요청(S4a 스펙 T6). [requestId] 는 운영 서비스가 실은 요청 id 이며 호스트는 응답에 그대로 돌려줄 뿐 저장하지 않는다.
+ * 응답 없음 뒤의 재조회는 인시던트의 판단으로 대조한다.
+ */
+data class ResolveRequest(val decision: OperatorDecision, val approverId: String, val requestId: String?)
+
 /** 본문을 못 받는 까닭. [error] 가 응답의 `error` 칸이다. */
 class BadRequest(val error: String, detail: String) : RuntimeException(detail)
 
@@ -63,6 +71,23 @@ object HostRequests {
         )
     }
 
+    /**
+     * `{decision, approverId, requestId?}` 를 읽는다. decision 은 `CONFIRM_DONE`·`REWORK` 이고 approverId 는 비어 있지 않은
+     * 문자열이다. requestId 는 없거나 `null` 이거나 UUID 문자열이다.
+     */
+    fun resolution(body: JsonNode?): ResolveRequest {
+        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
+        val decision = text(body, "decision").let { value ->
+            OperatorDecision.entries.firstOrNull { it.name == value }
+                ?: throw BadRequest(BAD_REQUEST, "decision 이 ${OperatorDecision.entries.joinToString("·")} 가 아니다: $value")
+        }
+        val requestId = body.get("requestId")?.takeUnless { it.isNull }?.let { node ->
+            node.takeIf { it.isTextual }?.asText()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
+                ?: throw BadRequest(BAD_REQUEST, "requestId 가 UUID 문자열이 아니다")
+        }
+        return ResolveRequest(decision, text(body, "approverId"), requestId)
+    }
+
     /** 비어 있지 않은 문자열 칸. */
     private fun text(node: JsonNode, field: String): String =
         node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
diff --git a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt
index a33c300..1aa0549 100644
--- a/mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt
+++ b/mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt
@@ -1,13 +1,9 @@
 package dev.picasso.ops.host.web
 
 import dev.picasso.middleware.SiteTimings
-import dev.picasso.ops.host.MissionHost
 import dev.picasso.ops.host.timings.RejectedTimings
 import dev.picasso.ops.host.timings.SiteTimingsReader
-import org.springframework.http.HttpStatus
-import org.springframework.http.ResponseEntity
 import org.springframework.web.bind.annotation.GetMapping
-import org.springframework.web.bind.annotation.RequestParam
 import org.springframework.web.bind.annotation.RestController
 import java.time.Instant
 
@@ -42,11 +38,11 @@ data class SiteTimingsStateView(
 )
 
 /**
- * 현장 시간값 적용 상태와 인시던트 조회(S3c 스펙 §7.2, T9). 둘 다 읽기만 한다. 운영 서비스가 적용 상태를 대신 읽어 화면에 보이고,
- * 통합 시험이 인시던트에 실린 설정 버전과 시간값을 확인한다.
+ * 현장 시간값 적용 상태 조회(S3c 스펙 §7.2, T9). 읽기만 한다. 운영 서비스가 적용 상태를 대신 읽어 화면에 보인다. 인시던트 조회는
+ * S4a 에서 [IncidentController] 로 옮겼다.
  */
 @RestController
-class SiteTimingsController(private val host: MissionHost, private val timings: SiteTimingsReader) {
+class SiteTimingsController(private val timings: SiteTimingsReader) {
 
     @GetMapping("/host/site-timings")
     fun siteTimings(): SiteTimingsStateView {
@@ -59,18 +55,4 @@ class SiteTimingsController(private val host: MissionHost, private val timings:
             rejected = state.rejected,
         )
     }
-
-    /** 최신부터 많아야 [limit] 개. 정수가 아니거나 1~[MAX_LIMIT] 밖이면 400 `BAD_REQUEST` 다. */
-    @GetMapping("/host/incidents")
-    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
-        val count = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
-            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
-                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $limit"))
-        return ResponseEntity.ok(host.incidents(count))
-    }
-
-    companion object {
-        const val DEFAULT_LIMIT = 50
-        const val MAX_LIMIT = 500
-    }
 }
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
index 3d57be8..c7892a4 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt
@@ -101,7 +101,23 @@ class HostBench(
     /** 셀 대역 대역이 받은 신호 쓰기(경로의 이름, Content-Type, 본문). */
     val signalWrites = CopyOnWriteArrayList<Triple<String, String?, String>>()
 
+    /** 셀 대역 대역이 장애 주입에 답할 상태 코드와 본문. */
+    @Volatile
+    var faultReply: Pair<Int, String> = 200 to """{"robotId":"humanoid-01","kind":"CONNECTION","state":"OFFLINE","changed":true}"""
+
+    /** 셀 대역 대역이 받은 장애 주입(Content-Type, 본문). */
+    val faultWrites = CopyOnWriteArrayList<Pair<String?, String>>()
+
     private val cell: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
+        createContext("/faults") { exchange ->
+            faultWrites += exchange.requestHeaders.getFirst("Content-Type") to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
+            val (status, body) = faultReply
+            val bytes = body.toByteArray()
+            exchange.responseHeaders.add("Content-Type", "application/json")
+            exchange.sendResponseHeaders(status, bytes.size.toLong())
+            exchange.responseBody.write(bytes)
+            exchange.close()
+        }
         createContext("/cell") { exchange ->
             val path = exchange.requestURI.path
             val (status, body) = if (path.startsWith("/cell/signals/") && exchange.requestMethod == "POST") {
diff --git a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
index 0169c15..29f0136 100644
--- a/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
+++ b/mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
@@ -83,7 +83,7 @@ class MissionVersionsTest {
     private fun HostBench.activeVersion(): JsonNode = get("/host/missions/$PSR")["active"]["version"]
 
     @Test
-    fun `버전이 없으면 개요는 코드 정의를 내고 템플릿 둘은 버전 1 모양과 ABORTED 대기 버전 2 모양이다`() {
+    fun `버전이 없으면 개요는 코드 정의를 내고 템플릿 셋은 버전 1 모양과 ABORTED 대기와 운영자 보류 대기다`() {
         HostBench().use { bench ->
             val overview = bench.get("/host/missions/$PSR")
             assertEquals(PSR, overview["workMasterId"].asText())
@@ -94,7 +94,7 @@ class MissionVersionsTest {
             assertEquals(0, overview["drafts"].size())
 
             val templates = bench.get("/host/missions/templates/$PSR")["templates"]
-            assertEquals(listOf("DATA_V1", "ARRIVAL_WAIT"), templates.map { it["id"].asText() })
+            assertEquals(listOf("DATA_V1", "ARRIVAL_WAIT", "ARRIVAL_WAIT_HOLD"), templates.map { it["id"].asText() })
             val wait = JSON.readTree(templates[1]["definition"].asText())["steps"][0]
             assertEquals("rack-arrival", wait["id"].asText())
             assertEquals("rack_present", wait["signal"].asText())
@@ -102,6 +102,13 @@ class MissionVersionsTest {
             assertEquals(120, wait["deadlineSeconds"].asInt())
             assertEquals("ABORTED", wait["onDeadline"].asText())
             assertEquals(1, JSON.readTree(templates[0]["definition"].asText())["steps"].size())
+
+            // 운영자 보류 대기는 기한과 기한 뒤 동작만 다르다(S4a 스펙 T3).
+            val hold = JSON.readTree(templates[2]["definition"].asText())
+            assertEquals(20, hold["steps"][0]["deadlineSeconds"].asInt())
+            assertEquals("OPERATOR_HOLD", hold["steps"][0]["onDeadline"].asText())
+            (hold["steps"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("deadlineSeconds", 120).put("onDeadline", "ABORTED")
+            assertEquals(JSON.readTree(templates[1]["definition"].asText()), hold)
         }
     }
 
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && ./gradlew :mission-host:test -q
```
Expected: mission-host 64, 실패 0. 백그라운드로 돌린다(Docker, Testcontainers).

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git add mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait-hold.json mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt && git commit -F - <<'EOF'
feat(mission-host): 장애 중계와 인시던트 상세, 운영자 판단 엔드포인트

- `POST /host/faults` 를 잠금 밖에서 site 로 중계, 불통이면 503 `CELL_SILENT`
- 인시던트 목록에 미해결·판단(`resolution`)·결함 요약·보류 중(`held`)·설비 근거 없이 완료 확인 칸, 상세 `GET /host/incidents/{id}` 에 근거 윈도우 이벤트·단계 위치·필요 및 도달 근거 등급·확인 결과·`blockedBy`·의도 전체
- 운영자 판단 `POST /host/executions/{executionId}/units/{unitId}/resolve`(CONFIRM_DONE·REWORK, `Approver` 는 PERSON, 결과 Resolved·NotHeld·Refused), 템플릿 `ARRIVAL_WAIT_HOLD` 의 기한 20초 뒤 운영자 보류

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4a-cmp.sh" site/src/main/kotlin/dev/picasso/ops/site/SiteFaults.kt site/src/test/kotlin/dev/picasso/ops/site/SiteFaultsTest.kt site/src/main/kotlin/dev/picasso/ops/site/Site.kt site/src/main/kotlin/dev/picasso/ops/site/SiteCell.kt mission-host/src/main/kotlin/dev/picasso/ops/host/IncidentViews.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/IncidentController.kt mission-host/src/main/resources/mission-templates/PrepareSequencedRack.arrival-wait-hold.json mission-host/src/test/kotlin/dev/picasso/ops/host/IncidentResolveTest.kt mission-host/src/main/kotlin/dev/picasso/ops/host/MissionHost.kt mission-host/src/main/kotlin/dev/picasso/ops/host/cell/CellBand.kt mission-host/src/main/kotlin/dev/picasso/ops/host/mission/MissionTemplates.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/HostRequests.kt mission-host/src/main/kotlin/dev/picasso/ops/host/web/SiteTimingsController.kt mission-host/src/test/kotlin/dev/picasso/ops/host/HostBench.kt mission-host/src/test/kotlin/dev/picasso/ops/host/MissionVersionsTest.kt
```
Expected: 15개 모두 `같음`.

### Task 3: 운영 서비스: 인시던트 중계, 장애 주입, 운영자 판단

**Files:**
- Create: `ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/FaultOperations.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt`, `ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt`
- Modify: `ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt`, `ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt`

- [ ] **Step 1: 새 파일 7개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/incidents && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/FaultOperations.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/FaultOperations.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/incidents && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ops-service/src/main/kotlin/dev/picasso/ops/service/web && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt" ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ops-service/src/test/kotlin/dev/picasso/ops/service && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt" ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/FaultOperations.kt`:

```kotlin
package dev.picasso.ops.service.incidents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostFaults
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRejection
import java.time.Duration
import java.util.UUID

/**
 * 장애 주입의 200 응답(S4a 스펙 §7). 신호 조작의 응답과 같은 자리에 같은 칸을 둔다.
 *
 * @param result 조작 기록의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 늘 널이다. 장애 주입은 재조회하지 않는다(확인할 칸이 없다). 다른 조작 응답과 모양을 맞추려고 둔다
 * @param fault 현장의 200 본문(S4a JSON 계약 §1.2). 성공일 때만 있다
 * @param rejection 현장이나 호스트가 4xx 로 막았을 때만 있다. 현장의 오류 이름(`NO_RUNNING_TASK`, `UNKNOWN_ROBOT`,
 *   `UNSUPPORTED_FAULT`, `BAD_REQUEST` 등)이 그대로 온다
 */
data class FaultInjectionOutcome(
    val requestId: UUID,
    val robotId: String,
    val kind: String,
    val state: String?,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val fault: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 장애 주입(S4a 스펙 §7, T1·T2). 엔지니어 모드의 조작이며(모드 검사는 컨트롤러의 몫) 호스트를 거쳐 현장에 닿고 조작 기록을
 * 직접 쓴다. 사유는 조작 기록의 사유 칸에 남는다.
 *
 * 종류와 값의 검사(받는 종류 둘, 연결 상태 셋)와 진행 중 태스크 찾기는 현장이 하고 운영 서비스는 그 거부를 그대로 넘긴다.
 * 거부는 조작 기록에 거부로 남고 응답 칸의 본문에 현장의 오류 이름이 있다.
 *
 * 호스트가 안 닿거나 5xx(현장이 안 닿은 503 `CELL_SILENT` 포함)면 «응답 없음» 만 남긴다. 장애 주입은 재조회하지 않는다.
 * 주입의 효과(태스크 상태, 연결 상태)는 다른 조작과 섞여 이 요청의 반영을 가를 칸이 없기 때문이다. 그래서 실행기는 재조회
 * 지연 없이 쓴다.
 */
class FaultOperations(
    private val faults: HostFaults,
    log: OperationLog,
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, Duration.ZERO, json)

    /** [reason] 은 컨트롤러가 앞뒤 공백을 깎고 비어 있지 않음을 본 값이다. */
    fun inject(actor: Actor, robotId: String, kind: String, state: String?, reason: String): FaultInjectionOutcome {
        val request = json.createObjectNode().put("op", OP).put("robotId", robotId).put("kind", kind).put("state", state)
        val body = json.createObjectNode().put("robotId", robotId).put("kind", kind)
        if (state != null) body.put("state", state)
        val ran = runner.run(
            actor, robotId, request, reason,
            accepted = { answer -> if (answer.path("robotId").isTextual) OperationResult.SUCCEEDED else null },
            recheck = { null },
        ) { faults.injectFault(body) }
        return FaultInjectionOutcome(ran.requestId, robotId, kind, state, ran.result, ran.confirmation, ran.body, ran.rejection)
    }

    companion object {
        const val OP = "INJECT_FAULT"
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt`:

```kotlin
package dev.picasso.ops.service.incidents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostIncidents
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 운영자 판단의 200 응답(S4a 스펙 §7, §8.3).
 *
 * @param result 조작 기록의 결과. `Resolved` 는 성공, `NotHeld`·`Refused` 는 거부, 호스트가 안 닿으면 응답 없음이다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param outcome 호스트 판단 결과 이름 그대로(`Resolved`, `NotHeld`, `Refused`). 호스트가 200 으로 답했을 때만 있다
 * @param answer 호스트의 200 본문(S4a JSON 계약 §5.2) 그대로
 * @param rejection 호스트가 4xx 로 막았을 때만 있다(본문이 틀린 400 `BAD_REQUEST`. 운영 서비스가 먼저 검사해 정상 흐름에서는
 *   나오지 않는다)
 */
data class HoldResolveOutcome(
    val requestId: UUID,
    val executionId: String,
    val unitId: String,
    val decision: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: String?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 운영자 보류 해소(S4a 스펙 §7, T4·T6). 운영자 모드의 조작이며(모드 검사는 컨트롤러의 몫) 승인자는 행위자(`X-Ops-User`)다.
 * 호스트는 그 id 로 `Approver(id, PERSON)` 을 만든다(ADR 43: 기본값을 두지 않는다). 사유는 picasso 인시던트에 자리가 없어
 * 조작 기록의 사유 칸이 유일한 자리다.
 *
 * 결과는 호스트 200 본문의 `result` 로 가른다. `Resolved` 는 성공, `NotHeld`·`Refused` 는 거부로 남기고 응답 칸의 본문에
 * 원래 이름이 있다(S3a 작업 지시의 UNASSIGNED 선례). 모르는 이름은 본문을 못 읽은 것과 같이 응답 없음이다.
 *
 * 응답 없음 뒤 재조회(S4a JSON 계약 §5.4): 호스트는 요청 id 를 저장하지 않으므로 인시던트로 대조한다. 요청을 보내기 직전의
 * 실제 시각을 정해 두고, 같은 실행·단위의 인시던트 가운데 하나라도 판단이 붙었고 판단자가 행위자이며 결정이 같고 판단의
 * 실제 시각(`wallClockAt`)이 그 시각 이후이면 반영됨이다. 같은 사람이 같은 단위를 앞서 판단한 기록은 시각 조건이 거른다.
 * «가장 최근 인시던트» 와 «보류가 아님» 은 보지 않는다. 재작업 뒤 대기가 기한을 다시 넘기면 새 보류가 서서 반영된 재작업을
 * 반영 안 됨으로 읽기 때문이다. 판단 시각 `at` 은 호스트 시계(통합 시험에서는 가상 시각)라 쓰지 않는다.
 */
class HoldResolutions(
    private val host: HostIncidents,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val clock: Clock = Clock.systemUTC(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    /** [decision] 은 컨트롤러가 둘 중 하나임을, [reason] 은 비어 있지 않음을 본 값이다. */
    fun resolve(actor: Actor, executionId: String, unitId: String, decision: String, reason: String): HoldResolveOutcome {
        val request = json.createObjectNode()
            .put("op", OP)
            .put("executionId", executionId)
            .put("unitId", unitId)
            .put("decision", decision)
            .put("approverId", actor.user)
        var sentAt: Instant? = null
        val ran = runner.run(
            actor, "$executionId/$unitId", request, reason,
            accepted = { body -> resultOf(body.path("result").takeIf { it.isTextual }?.asText()) },
            recheck = { sentAt?.let { at -> recheck(executionId, unitId, decision, actor.user, at) } },
        ) { requestId ->
            sentAt = clock.instant()
            host.resolve(executionId, unitId, decision, actor.user, requestId)
        }
        val outcome = ran.body?.path("result")?.takeIf { it.isTextual }?.asText()
        return HoldResolveOutcome(
            ran.requestId, executionId, unitId, decision, ran.result, ran.confirmation, outcome, ran.body, ran.rejection,
        )
    }

    /**
     * 목록을 못 읽으면 확인하지 못한 것이라 널이다. 반영 안 됨이면 관측으로 그 단위의 가장 최근 인시던트를 남긴다(없으면 널).
     */
    private fun recheck(executionId: String, unitId: String, decision: String, approver: String, sentAt: Instant): HostRecheck? {
        val body = (host.incidents(REQUERY_LIMIT) as? HostCall.Ok)?.value ?: return null
        val listed = body.path("incidents").takeIf { it.isArray } ?: return null
        val unit = listed.filter { it.path("executionId").asText() == executionId && it.path("unitId").asText() == unitId }
        val match = unit.firstOrNull { incident ->
            val resolution = incident.path("resolution").takeIf { it.isObject } ?: return@firstOrNull false
            resolution.path("decidedBy").path("id").asText() == approver &&
                resolution.path("decision").asText() == decision &&
                instant(resolution.path("wallClockAt"))?.let { it >= sentAt } == true
        }
        return if (match != null) HostRecheck(true, match) else HostRecheck(false, unit.firstOrNull())
    }

    private fun instant(node: JsonNode): Instant? =
        node.takeIf { it.isTextual }?.let { runCatching { Instant.parse(it.asText()) }.getOrNull() }

    companion object {
        const val OP = "RESOLVE_OPERATOR_HOLD"

        /** picasso `ResolveOutcome` 이름 그대로(S4a JSON 계약 §5.2). */
        const val RESOLVED = "Resolved"
        const val NOT_HELD = "NotHeld"
        const val REFUSED = "Refused"

        /** 판단 종류 둘(picasso `OperatorDecision`). */
        val DECISIONS: Set<String> = setOf("CONFIRM_DONE", "REWORK")

        /** 재조회가 읽는 인시던트 수. 호스트가 받는 최대다(S4a JSON 계약 §3). */
        const val REQUERY_LIMIT = 500

        fun resultOf(name: String?): OperationResult? = when (name) {
            RESOLVED -> OperationResult.SUCCEEDED
            NOT_HELD, REFUSED -> OperationResult.REJECTED
            else -> null
        }
    }
}
```

`ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt`:

```kotlin
package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.host.HostIncidents
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 장애 주입, 인시던트 읽기, 운영자 판단 API(S4a 스펙 §7, T7).
 *
 * 두 읽기는 모드와 관계없고 호스트 본문을 그대로 넘긴다. 호스트가 안 닿으면 503 `HOST_SILENT` 다. 단건의 404
 * `INCIDENT_NOT_FOUND` 는 호스트 본문 그대로 404 로 넘긴다.
 *
 * 장애 주입은 엔지니어 모드, 판단은 운영자 모드만 한다. 판정 순서는 관문(헤더 400 `ACTOR_REQUIRED`, 모드 403) → 본문(400
 * `FAULT_BAD_REQUEST`·`RESOLVE_BAD_REQUEST`) → 사유(400 `REASON_REQUIRED`)다. 모두 호스트에 닿지 않은 사전 거부라 조작
 * 기록에 남지 않는다. 본문은 바이트로 받아 관문을 지난 뒤 직접 읽는다([SiteSettingsController] 와 같은 이유). 쓰기 본문은
 * `application/json` 만 받는다(다른 출처 방어는 [RobotOperationsController]).
 *
 * 두 조작은 호스트가 무엇을 답했든 200 과 조작 결과다. 현장·호스트의 거부와 판단 결과 이름은 본문에 있다.
 */
@RestController
class IncidentController(
    private val faults: FaultOperations,
    private val resolutions: HoldResolutions,
    private val host: HostIncidents,
) {
    private val json = ObjectMapper()

    @GetMapping("/api/incidents")
    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val parsed = limit?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                ?: return reject(HttpStatus.BAD_REQUEST, INCIDENT_BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $raw")
        }
        return when (val call = host.incidents(parsed)) {
            is HostCall.Ok -> ResponseEntity.ok(call.value)
            is HostCall.Silent -> hostSilent(call.cause)
        }
    }

    @GetMapping("/api/incidents/{incidentId}")
    fun incident(@PathVariable incidentId: String): ResponseEntity<Any> = when (val found = host.incident(incidentId)) {
        is HostIncident.Found -> ResponseEntity.ok(found.body)
        is HostIncident.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(found.body)
        is HostIncident.Silent -> hostSilent(found.cause)
    }

    @PostMapping("/api/faults", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun inject(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.ENGINEER) { actor ->
        val node = read(body)
        val robotId = text(node, "robotId")
        val kind = text(node, "kind")
        val state = node?.get("state")
        if (robotId == null || kind == null || (state != null && !state.isNull && !state.isTextual)) {
            return@guarded reject(
                HttpStatus.BAD_REQUEST, FAULT_BAD_REQUEST,
                "robotId·kind 는 비어 있지 않은 문자열이고 state 는 없거나 null 이거나 문자열이다",
            )
        }
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "장애 주입 사유가 없다")
        ResponseEntity.ok(faults.inject(actor, robotId, kind, state?.takeIf { it.isTextual }?.asText(), reason))
    }

    @PostMapping("/api/executions/{executionId}/units/{unitId}/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolve(
        @PathVariable executionId: String,
        @PathVariable unitId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val node = read(body)
        val decision = text(node, "decision")?.takeIf { it in HoldResolutions.DECISIONS }
            ?: return@guarded reject(
                HttpStatus.BAD_REQUEST, RESOLVE_BAD_REQUEST,
                "decision 은 ${HoldResolutions.DECISIONS.joinToString(" 또는 ")} 이다",
            )
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "판단 사유가 없다")
        ResponseEntity.ok(resolutions.resolve(actor, executionId, unitId, decision, reason))
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    private fun text(node: JsonNode?, field: String): String? =
        node?.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun reason(node: JsonNode?): String? = node?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun hostSilent(cause: String): ResponseEntity<Any> =
        reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: $cause")

    companion object {
        const val FAULT_BAD_REQUEST = "FAULT_BAD_REQUEST"
        const val RESOLVE_BAD_REQUEST = "RESOLVE_BAD_REQUEST"
        const val INCIDENT_BAD_REQUEST = "INCIDENT_BAD_REQUEST"

        /** 현장 설정 변경·임무 활성화와 같은 이름이다. */
        const val REASON_REQUIRED = MissionVersionController.REASON_REQUIRED

        /** 호스트 인시던트 목록이 받는 최대(S4a JSON 계약 §3). */
        const val MAX_LIMIT = 500
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.host.HostWrite
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 실행 호스트 클라이언트의 장애 주입 전달, 인시던트 읽기, 운영자 판단(S4a JSON 계약 §2~§5). 호스트는 JDK HttpServer 대역이다. */
class HostIncidentsClientTest {

    private var server: HttpServer? = null
    private val pool = Executors.newCachedThreadPool()
    private val json = ObjectMapper()
    private val requestId = UUID.fromString("3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10")

    /** 받은 요청(방법, 인코딩된 경로, 쿼리, Content-Type, 본문). */
    private val seen = mutableListOf<Seen>()

    private data class Seen(val method: String, val rawPath: String, val rawQuery: String?, val contentType: String?, val body: String)

    /** 경로 앞부분마다 (상태 코드, 본문)으로 답한다. */
    private fun serve(vararg routes: Pair<String, Pair<Int, String>>): HostClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, answer) ->
            s.createContext(path) { exchange ->
                seen += Seen(
                    exchange.requestMethod,
                    exchange.requestURI.rawPath,
                    exchange.requestURI.rawQuery,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                val bytes = answer.second.toByteArray()
                exchange.sendResponseHeaders(answer.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        s.executor = pool
        s.start()
        server = s
        return HostClient("http://127.0.0.1:${s.address.port}")
    }

    private fun stop() {
        server?.stop(0)
        server = null
    }

    @AfterTest
    fun close() {
        stop()
        pool.shutdownNow()
    }

    @Test
    fun `장애 주입은 본문을 그대로 POST host faults 로 보내고 현장의 응답을 그대로 돌려준다`() {
        val refused = """{"error":"NO_RUNNING_TASK","detail":"x"}"""
        val client = serve("/host/faults" to (409 to refused))
        val body = json.createObjectNode().put("robotId", "humanoid-01").put("kind", "SKILL_EXECUTION_FAILED")
        assertEquals(HostWrite.Answered(409, refused), client.injectFault(body))
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/host/faults", request.rawPath)
        assertEquals("application/json", request.contentType)
        assertEquals(body, json.readTree(request.body))
    }

    @Test
    fun `인시던트 목록은 limit 을 쿼리로 싣고 객체 본문 그대로이며 200 아님은 모름이다`() {
        val listed = """{"instanceId":"i-1","total":0,"incidents":[]}"""
        val client = serve("/host/incidents" to (200 to listed))
        assertEquals(json.readTree(listed), (client.incidents() as HostCall.Ok).value)
        assertEquals(json.readTree(listed), (client.incidents(500) as HostCall.Ok).value)
        assertEquals(listOf(null, "limit=500"), seen.map { it.rawQuery })
        assertEquals(listOf("/host/incidents", "/host/incidents"), seen.map { it.rawPath })
        stop()
        val refusing = serve("/host/incidents" to (400 to """{"error":"BAD_REQUEST","detail":"x"}"""))
        assertEquals(HostCall.Silent("HTTP 400"), refusing.incidents(9999))
    }

    @Test
    fun `인시던트 상세는 찾음, 404 INCIDENT_NOT_FOUND 면 없음 본문 그대로, 그 밖은 못 읽음이다`() {
        val found = serve("/host/incidents/" to (200 to """{"instanceId":"i-1","incidentId":"incident-1"}"""))
        assertEquals("incident-1", assertIs<HostIncident.Found>(found.incident("incident-1")).body["incidentId"].asText())
        assertEquals("/host/incidents/incident-1", seen.single().rawPath)
        found.incident("a/b")
        assertEquals("/host/incidents/a%2Fb", seen.last().rawPath)
        stop()
        val missing = """{"error":"INCIDENT_NOT_FOUND","detail":"없다"}"""
        assertEquals(
            HostIncident.NotFound(json.readTree(missing)),
            serve("/host/incidents/" to (404 to missing)).incident("incident-9"),
        )
        stop()
        // 경로가 없는 서버의 404 는 호스트의 판단이 아니다.
        assertEquals(HostIncident.Silent("HTTP 404"), serve("/other" to (200 to "{}")).incident("incident-1"))
        stop()
        // 스프링 기본 404 본문에도 error 칸이 있다. 이름이 다르면 호스트의 판단이 아니다.
        val springDefault = """{"timestamp":"2026-10-09T00:00:00Z","status":404,"error":"Not Found","path":"/host/incidents/incident-1"}"""
        assertEquals(HostIncident.Silent("HTTP 404"), serve("/host/incidents/" to (404 to springDefault)).incident("incident-1"))
        stop()
        assertEquals(HostIncident.Silent("본문 모양이 다르다"), serve("/host/incidents/" to (200 to "[]")).incident("incident-1"))
        stop()
        assertEquals(HostIncident.Silent("HTTP 500"), serve("/host/incidents/" to (500 to "{}")).incident("incident-1"))
    }

    @Test
    fun `판단은 실행과 단위를 경로 조각으로 인코딩해 결정과 승인자와 요청 id 를 보내고 응답을 그대로 돌려준다`() {
        val answer = """{"result":"NotHeld","detail":null,"incidentId":null,"requestId":"$requestId"}"""
        val client = serve("/host/executions/" to (200 to answer))
        assertEquals(HostWrite.Answered(200, answer), client.resolve("exec-1", "RACK-204.S01/x", "CONFIRM_DONE", "kim", requestId))
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/host/executions/exec-1/units/RACK-204.S01%2Fx/resolve", request.rawPath)
        assertEquals("application/json", request.contentType)
        assertEquals(
            json.readTree("""{"decision":"CONFIRM_DONE","approverId":"kim","requestId":"$requestId"}"""),
            json.readTree(request.body),
        )
    }

    @Test
    fun `닿지 않는 호스트는 읽기가 모름이고 쓰기가 응답 없음이다`() {
        val client = serve()
        stop()
        assertIs<HostCall.Silent>(client.incidents())
        assertIs<HostIncident.Silent>(client.incident("incident-1"))
        assertIs<HostWrite.NoResponse>(client.resolve("exec-1", "rack-arrival", "REWORK", "kim", requestId))
        assertIs<HostWrite.NoResponse>(client.injectFault(json.createObjectNode()))
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostFaults
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.host.HostIncidents
import dev.picasso.ops.service.host.HostWrite
import java.time.Instant
import java.util.UUID

/**
 * 장애 주입·인시던트·운영자 판단 시험의 호스트 대역(S4a 스펙 §7). 기본은 호스트가 주입을 받아들이고(스킬 실패) 판단을
 * `Resolved` 로 답하며, 인시던트 목록이 비어 있는 것이다. 시험이 칸을 바꿔 한 칸씩 어긋나게 한다.
 */
class IncidentBench {

    private val json = jacksonObjectMapper()

    var faultAnswer: HostWrite = HostWrite.Answered(200, SKILL_RAISED)
    var resolveAnswer: HostWrite = HostWrite.Answered(200, resolved("incident-1"))
    var incidents: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"instanceId":"i-1","total":0,"incidents":[]}"""))
    var incident: HostIncident = HostIncident.Silent("응답 없음: ConnectException")

    /** 판단 호출을 받을 때 부른다. 시험이 «호스트가 받았을 때» 의 시각을 정하는 데 쓴다. */
    var onResolve: () -> Unit = {}

    data class Call(
        val op: String,
        val body: JsonNode? = null,
        val executionId: String? = null,
        val unitId: String? = null,
        val decision: String? = null,
        val approverId: String? = null,
        val requestId: UUID? = null,
        val limit: Int? = null,
        val incidentId: String? = null,
    )

    val calls = mutableListOf<Call>()

    inner class FakeHost : HostIncidents, HostFaults {
        override fun incidents(limit: Int?): HostCall<JsonNode> {
            calls += Call("incidents", limit = limit)
            return this@IncidentBench.incidents
        }

        override fun incident(incidentId: String): HostIncident {
            calls += Call("incident", incidentId = incidentId)
            return this@IncidentBench.incident
        }

        override fun resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: UUID): HostWrite {
            calls += Call("resolve", executionId = executionId, unitId = unitId, decision = decision, approverId = approverId, requestId = requestId)
            onResolve()
            return resolveAnswer
        }

        override fun injectFault(body: ObjectNode): HostWrite {
            calls += Call("injectFault", body = body.deepCopy())
            return faultAnswer
        }
    }

    val host = FakeHost()

    /** 호스트가 받은 쓰기 호출(읽기 둘을 뺀 것). */
    fun writes(): List<Call> = calls.filter { it.op in setOf("resolve", "injectFault") }

    /** 인시던트 목록 본문. 줄은 [row] 로 만든다. */
    fun listed(vararg rows: String): HostCall<JsonNode> =
        HostCall.Ok(json.readTree("""{"instanceId":"i-1","total":${rows.size},"incidents":[${rows.joinToString(",")}]}"""))

    companion object {
        const val SKILL_RAISED =
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","taskId":"JO-1#RACK-204.S01","taskState":"RETRIABLE","raised":true}"""

        fun resolved(incidentId: String?, result: String = "Resolved", detail: String? = null): String =
            """{"result":"$result","detail":${detail?.let { "\"$it\"" } ?: "null"},"incidentId":${incidentId?.let { "\"$it\"" } ?: "null"},"requestId":null}"""

        /** 판단 하나. [wallClockAt] 은 실제 시각, `at` 은 호스트 가상 시각이라 1970 근처다. */
        fun resolution(decision: String, by: String, wallClockAt: Instant): String =
            """{"decision":"$decision","at":"1970-01-01T00:01:05Z","wallClockAt":"$wallClockAt","decidedBy":{"id":"$by","kind":"PERSON"}}"""

        /** 목록 한 줄(S4a JSON 계약 §3, 19칸). */
        fun row(
            incidentId: String,
            executionId: String = "exec-1",
            unitId: String = "rack-arrival",
            resolution: String? = null,
            held: Boolean = resolution == null,
        ): String =
            """{"incidentId":"$incidentId","executionId":"$executionId","jobOrderId":"JO-1","robotId":"humanoid-01","unitId":"$unitId",
               "at":"1970-01-01T00:00:25Z","failureClass":"SIGNAL_DEADLINE","route":"SIGNAL","missionVersion":2,"siteSettingsVersion":1,
               "evidenceBeforeSeconds":30,"evidenceAfterSeconds":10,"inDoubtGraceSeconds":null,"stallWindowSeconds":null,
               "unresolved":true,"resolution":${resolution ?: "null"},"fault":null,"held":$held,"confirmedWithoutEvidence":false}"""
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.incidents.FaultInjectionOutcome
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.incidents.HoldResolveOutcome
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.service.web.IncidentController
import dev.picasso.ops.service.web.PreRejection
import dev.picasso.registry.PostgresSupport
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 장애 주입·인시던트·운영자 판단 API 의 관문과 사전 거부와 읽기 중계(S4a 스펙 §7, §9). 컨트롤러를 스프링 없이 바로 부른다.
 * `application/json` 제한은 스프링의 몫이라 통합 시험이 본다.
 */
class IncidentControllerTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = IncidentBench()
    private val controller = IncidentController(
        FaultOperations(bench.host, log),
        HoldResolutions(bench.host, log, requeryDelay = Duration.ZERO),
        bench.host,
    )
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun ResponseEntity<Any>.rejection(): PreRejection = assertIs<PreRejection>(body)

    private fun inject(mode: String?, user: String? = "lee", body: String = FAULT): ResponseEntity<Any> =
        controller.inject(mode, user, body.toByteArray())

    private fun resolve(mode: String?, user: String? = "kim", body: String = RESOLVE): ResponseEntity<Any> =
        controller.resolve("exec-1", "rack-arrival", mode, user, body.toByteArray())

    @Test
    fun `장애 주입은 운영자 모드면 403 이고 판단은 엔지니어 모드면 403 이며 호스트를 부르지 않고 기록하지 않는다`() {
        listOf(inject("operator"), inject("operator", body = "장애")).forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 engineer 모드에서 한다", reply.rejection().detail)
        }
        listOf(resolve("engineer"), resolve("engineer", body = "판단")).forEach { reply ->
            assertEquals(403, reply.statusCode.value())
            assertEquals("MODE_NOT_ALLOWED", reply.rejection().error)
            assertEquals("이 조작은 operator 모드에서 한다", reply.rejection().detail)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `행위자 헤더가 없거나 틀리면 둘 다 400 ACTOR_REQUIRED 다`() {
        listOf(
            inject(null), inject("engineer", user = null), inject("engineer", user = "이 엔지"),
            resolve(null), resolve("operator", user = null), resolve("operator", user = "김 운영"),
        ).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals("ACTOR_REQUIRED", reply.rejection().error)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `사유가 없거나 비었거나 문자열이 아니면 400 REASON_REQUIRED 이고 호스트를 부르지 않는다`() {
        val faults = listOf(
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED"}""",
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":"  "}""",
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":3}""",
        )
        val decisions = listOf(
            """{"decision":"REWORK"}""",
            """{"decision":"REWORK","reason":""}""",
            """{"decision":"REWORK","reason":null}""",
        )
        (faults.map { inject("engineer", body = it) } + decisions.map { resolve("operator", body = it) }).forEach { reply ->
            assertEquals(400, reply.statusCode.value())
            assertEquals(IncidentController.REASON_REQUIRED, reply.rejection().error)
        }
        assertEquals(emptyList(), bench.calls)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `본문 모양이 틀리면 사유보다 먼저 400 이다`() {
        listOf(
            "장애",
            """{"kind":"SKILL_EXECUTION_FAILED","reason":"r"}""",
            """{"robotId":" ","kind":"SKILL_EXECUTION_FAILED","reason":"r"}""",
            """{"robotId":"humanoid-01","kind":7,"reason":"r"}""",
            """{"robotId":"humanoid-01","kind":"CONNECTION","state":0,"reason":"r"}""",
        ).forEach { body ->
            assertEquals(IncidentController.FAULT_BAD_REQUEST, inject("engineer", body = body).rejection().error, body)
        }
        listOf("판단", """{"reason":"r"}""", """{"decision":"RELEASE","reason":"r"}""", """{"decision":"rework"}""").forEach { body ->
            assertEquals(IncidentController.RESOLVE_BAD_REQUEST, resolve("operator", body = body).rejection().error, body)
        }
        assertEquals(emptyList(), bench.calls)
    }

    @Test
    fun `맞는 모드와 사유면 호스트에 닿고 결과는 200 본문이다`() {
        val injected = inject("engineer", body = """{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE","reason":" 묵은 값 "}""")
        assertEquals(200, injected.statusCode.value())
        val fault = assertIs<FaultInjectionOutcome>(injected.body)
        assertEquals(OperationResult.SUCCEEDED, fault.result)
        assertEquals("OFFLINE", fault.state)

        bench.resolveAnswer = dev.picasso.ops.service.host.HostWrite.Answered(200, IncidentBench.resolved(null, "NotHeld"))
        val resolved = resolve("operator")
        assertEquals(200, resolved.statusCode.value())
        val outcome = assertIs<HoldResolveOutcome>(resolved.body)
        assertEquals("NotHeld", outcome.outcome)
        assertEquals(OperationResult.REJECTED, outcome.result)

        assertEquals(listOf("묵은 값", "보류 해소"), log.list().reversed().map { it.reason })
        assertEquals("kim", bench.writes().last().approverId)
    }

    @Test
    fun `판단 본문에 승인자 칸을 실어도 승인자는 행위자 헤더다`() {
        val reply = resolve("operator", user = "kim", body = """{"decision":"CONFIRM_DONE","reason":"설비 확인","approverId":"mallory"}""")
        assertEquals(200, reply.statusCode.value())
        assertEquals("kim", bench.writes().last().approverId)
        val row = log.list().single()
        assertEquals("kim", row.user)
        assertEquals("kim", json.readTree(row.request)["approverId"].asText())
    }

    @Test
    fun `인시던트 읽기는 호스트 본문 그대로이고 호스트가 안 닿으면 503 HOST_SILENT 이며 상세의 404 는 그대로 넘긴다`() {
        val listed = bench.listed(IncidentBench.row("incident-1"))
        bench.incidents = listed
        val list = controller.incidents(null)
        assertEquals(200, list.statusCode.value())
        assertSame((listed as HostCall.Ok).value, list.body)
        assertEquals(listOf(null), bench.calls.map { it.limit })

        assertEquals(200, controller.incidents("500").statusCode.value())
        assertEquals(500, bench.calls.last().limit)
        listOf("0", "501", "x", "").forEach { raw ->
            val reply = controller.incidents(raw)
            assertEquals(400, reply.statusCode.value(), raw)
            assertEquals(IncidentController.INCIDENT_BAD_REQUEST, reply.rejection().error)
        }

        bench.incidents = HostCall.Silent("응답 없음: ConnectException")
        val silent = controller.incidents(null)
        assertEquals(503, silent.statusCode.value())
        assertEquals("HOST_SILENT", silent.rejection().error)

        val detail = json.readTree("""{"instanceId":"i-1","incidentId":"incident-1"}""")
        bench.incident = HostIncident.Found(detail)
        assertSame(detail, controller.incident("incident-1").body)
        assertEquals("incident-1", bench.calls.last().incidentId)

        val missing = json.readTree("""{"error":"INCIDENT_NOT_FOUND","detail":"없는 인시던트다: incident-9"}""")
        bench.incident = HostIncident.NotFound(missing)
        val notFound = controller.incident("incident-9")
        assertEquals(404, notFound.statusCode.value())
        assertSame(missing, notFound.body)

        bench.incident = HostIncident.Silent("HTTP 500")
        val down = controller.incident("incident-1")
        assertEquals(503, down.statusCode.value())
        assertEquals("HOST_SILENT", down.rejection().error)
    }

    companion object {
        const val FAULT = """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","reason":"스킬 실패 시연"}"""
        const val RESOLVE = """{"decision":"REWORK","reason":"보류 해소"}"""
    }
}
```

`ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt`:

```kotlin
package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.IncidentBench.Companion.resolution
import dev.picasso.ops.service.IncidentBench.Companion.row
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 장애 주입과 운영자 판단의 조작 기록(S4a 스펙 §7, T7). 결과 매김, 거부의 원래 이름, 응답 없음 뒤 판단 재조회의 대조를 본다.
 * 호스트는 대역이다.
 */
class IncidentOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = IncidentBench()
    private val clock = MovableClock(T0)
    private val faults = FaultOperations(bench.host, log)
    private val resolutions = HoldResolutions(bench.host, log, requeryDelay = Duration.ZERO, clock = clock)
    private val lee = Actor(Mode.ENGINEER, "lee")
    private val kim = Actor(Mode.OPERATOR, "kim")
    private val json = ObjectMapper()

    /** 시험이 옮기는 시계. 호스트가 판단을 받는 순간 앞으로 옮겨 «요청 직전» 과 «요청 뒤» 를 가른다. */
    class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
        // 호스트는 요청을 받은 뒤 판단하므로 판단의 실제 시각은 요청 직전 시각보다 늦다.
        bench.onResolve = { clock.now = T0.plusSeconds(10) }
    }

    @Test
    fun `받아들인 장애 주입은 성공이고 기체를 대상으로 종류와 값과 사유를 기록한다`() {
        val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "스킬 실패 시연")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertNull(outcome.confirmation)
        assertNull(outcome.rejection)
        assertEquals("RETRIABLE", outcome.fault!!["taskState"].asText())
        assertEquals(json.readTree("""{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED"}"""), bench.writes().single().body)
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals("humanoid-01", row.target)
        assertEquals(Mode.ENGINEER, row.mode)
        assertEquals("lee", row.user)
        assertEquals("스킬 실패 시연", row.reason)
        assertEquals(
            json.readTree("""{"op":"${FaultOperations.OP}","robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","state":null}"""),
            json.readTree(row.request),
        )
        assertEquals("JO-1#RACK-204.S01", json.readTree(row.targetResponse)["body"]["taskId"].asText())

        bench.faultAnswer = HostWrite.Answered(200, """{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE","changed":true}""")
        assertEquals(OperationResult.SUCCEEDED, faults.inject(lee, "quadruped-01", "CONNECTION", "OFFLINE", "묵은 값 시연").result)
        assertEquals(json.readTree("""{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE"}"""), bench.writes().last().body)
    }

    @Test
    fun `현장의 거부는 거부로 남기고 원래 오류 이름을 응답 칸과 응답에 그대로 넘긴다`() {
        val refusals = listOf(
            HostRejection(409, "NO_RUNNING_TASK", "humanoid-01 에 진행 중인 pick_place 태스크가 없다"),
            HostRejection(404, "UNKNOWN_ROBOT", "이 현장에 없는 기체다: ghost-01"),
            HostRejection(400, "UNSUPPORTED_FAULT", "받지 않는 장애다: PAYLOAD_LOST"),
        )
        refusals.forEach { refusal ->
            bench.faultAnswer = HostWrite.Answered(refusal.status, """{"error":"${refusal.error}","detail":"${refusal.detail}"}""")
            val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "거부 시연")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertNull(outcome.fault)
            assertEquals(refusal, outcome.rejection)
        }
        val rows = log.list()
        assertEquals(List(3) { OperationResult.REJECTED }, rows.map { it.result })
        assertEquals(
            refusals.map { it.error }.reversed(),
            rows.map { json.readTree(it.targetResponse)["body"]["error"].asText() },
        )
    }

    @Test
    fun `현장이나 호스트가 안 닿으면 장애 주입은 응답 없음 하나만 남기고 재조회하지 않는다`() {
        listOf(
            HostWrite.Answered(503, """{"error":"CELL_SILENT","detail":"현장 셀 대역이 답하지 않는다"}"""),
            HostWrite.NoResponse("응답 없음: HttpTimeoutException"),
        ).forEach { answer ->
            bench.faultAnswer = answer
            val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "불통 시연")
            assertEquals(OperationResult.NO_RESPONSE, outcome.result)
            assertNull(outcome.confirmation)
            assertNull(outcome.rejection)
        }
        assertEquals(List(2) { OperationResult.NO_RESPONSE }, log.list().map { it.result })
        assertEquals(listOf("injectFault", "injectFault"), bench.calls.map { it.op })
        assertEquals("CELL_SILENT", json.readTree(log.list().last().targetResponse)["body"]["error"].asText())
    }

    @Test
    fun `판단 Resolved 는 성공이고 승인자와 결정과 실행과 단위와 사유와 결과 이름을 기록한다`() {
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "랙 재배치 뒤 재작업")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals("Resolved", outcome.outcome)
        assertNull(outcome.confirmation)
        assertNull(outcome.rejection)
        val call = bench.writes().single()
        assertEquals(IncidentBench.Call("resolve", executionId = "exec-1", unitId = "rack-arrival", decision = "REWORK", approverId = "kim", requestId = outcome.requestId), call)
        val row = log.list().single()
        assertEquals("exec-1/rack-arrival", row.target)
        assertEquals(Mode.OPERATOR, row.mode)
        assertEquals("랙 재배치 뒤 재작업", row.reason)
        assertEquals(
            json.readTree("""{"op":"${HoldResolutions.OP}","executionId":"exec-1","unitId":"rack-arrival","decision":"REWORK","approverId":"kim"}"""),
            json.readTree(row.request),
        )
        assertEquals("Resolved", json.readTree(row.targetResponse)["body"]["result"].asText())
        assertEquals("incident-1", json.readTree(row.targetResponse)["body"]["incidentId"].asText())
    }

    @Test
    fun `판단 NotHeld 와 Refused 는 거부로 남기고 응답 칸에 원래 결과 이름이 있다`() {
        listOf("NotHeld" to null, "Refused" to "에이전트는 운영자 판단을 내지 못한다").forEach { (name, detail) ->
            bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, name, detail))
            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "CONFIRM_DONE", "확인")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertEquals(name, outcome.outcome)
            assertNull(outcome.rejection)
            assertNull(outcome.confirmation)
        }
        val rows = log.list()
        assertEquals(List(2) { OperationResult.REJECTED }, rows.map { it.result })
        assertEquals(listOf("Refused", "NotHeld"), rows.map { json.readTree(it.targetResponse)["body"]["result"].asText() })
    }

    @Test
    fun `판단 응답이 없으면 같은 실행과 단위의 인시던트 하나라도 행위자의 같은 결정이 요청 뒤에 붙었으면 반영됨이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 반영된 재작업 뒤 대기가 기한을 다시 넘겨 새 보류가 가장 최근이다. 앞 인시던트의 판단으로 반영을 읽어야 한다.
        bench.incidents = bench.listed(
            row("incident-2"),
            row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        assertEquals(500, bench.calls.single { it.op == "incidents" }.limit)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals("incident-1", json.readTree(rows.first().targetResponse)["observed"]["incidentId"].asText())
    }

    @Test
    fun `같은 사람이 요청 앞에 낸 판단이나 다른 사람 다른 결정 다른 단위 다른 실행의 판단은 반영 안 됨이다`() {
        bench.resolveAnswer = HostWrite.Answered(503, "<html>bad gateway</html>")
        val misses = listOf(
            // 같은 사람, 같은 결정, 같은 단위지만 요청 직전 시각보다 앞선 판단.
            row("incident-1", resolution = resolution("REWORK", "kim", T0.minusMillis(1))),
            row("incident-1", resolution = resolution("REWORK", "park", T0.plusSeconds(1))),
            row("incident-1", resolution = resolution("CONFIRM_DONE", "kim", T0.plusSeconds(1))),
            row("incident-1", unitId = "RACK-204.S01", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
            row("incident-1", executionId = "exec-2", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
        )
        misses.forEach { miss ->
            clock.now = T0
            bench.incidents = bench.listed(miss)
            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
            assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation, miss)
        }
        // 요청 직전 시각과 같은 판단 시각은 반영이다(경계).
        clock.now = T0
        bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0)))
        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업").confirmation)
    }

    @Test
    fun `대조 시각은 요청을 보내기 직전의 시각이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 호스트가 받은 뒤(T0+10초)가 아니라 보내기 직전(T0)과 비교해야 T0+1초의 판단이 반영으로 읽힌다.
        bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업").confirmation)
    }

    @Test
    fun `판단 재조회가 목록을 못 읽으면 확인 행이 없고 모르는 결과 이름은 응답 없음이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        bench.incidents = HostCall.Silent("응답 없음: ConnectException")
        assertNull(resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업").confirmation)

        bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, "RESOLVED"))
        bench.incidents = bench.listed()
        clock.now = T0
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertEquals(
            listOf(OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.NO_RESPONSE, OperationResult.NO_RESPONSE),
            log.list().map { it.result },
        )
    }

    @Test
    fun `판단 본문이 틀렸다는 호스트 400 은 거부이고 오류 이름을 넘긴다`() {
        bench.resolveAnswer = HostWrite.Answered(400, """{"error":"BAD_REQUEST","detail":"decision 이 두 값이 아니다"}""")
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "재작업")
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals("BAD_REQUEST", outcome.rejection!!.error)
        assertNull(outcome.outcome)
    }

    companion object {
        val T0: Instant = Instant.parse("2026-10-09T02:31:00Z")
    }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task3.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task3.patch"
```

```diff
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
index eb1bec3..5e3113e 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt
@@ -5,6 +5,8 @@ import dev.picasso.ops.service.profiles.ProfileListService
 import dev.picasso.ops.service.adapters.AdapterListService
 import dev.picasso.ops.service.cell.CellSignalOperations
 import dev.picasso.ops.service.host.HostClient
+import dev.picasso.ops.service.incidents.FaultOperations
+import dev.picasso.ops.service.incidents.HoldResolutions
 import dev.picasso.ops.service.joborders.JobOrderEligibility
 import dev.picasso.ops.service.joborders.JobOrderOperations
 import dev.picasso.ops.service.log.OperationLog
@@ -140,6 +142,14 @@ open class OpsApplication {
     open fun cellSignalOperations(host: HostClient, log: OperationLog): CellSignalOperations =
         CellSignalOperations(host, host, log)
 
+    @Bean
+    open fun faultOperations(host: HostClient, log: OperationLog): FaultOperations = FaultOperations(host, log)
+
+    /** 재조회 대조의 «요청 직전 실제 시각» 은 운영 서비스 시계에서 읽는다(S4a JSON 계약 §5.4). */
+    @Bean
+    open fun holdResolutions(host: HostClient, log: OperationLog, clock: Clock): HoldResolutions =
+        HoldResolutions(host, log, clock = clock)
+
     /** [migrated] 는 쓰지 않는다. 받는 것만으로 ops 마이그레이션 뒤에 이 빈이 만들어진다. */
     @Bean
     open fun operationLog(
diff --git a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
index af5882e..9fab83f 100644
--- a/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
+++ b/ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
@@ -172,7 +172,43 @@ fun interface HostSiteTimings {
 }
 
 /**
- * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7, S3c 스펙 §8). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
+ * 인시던트 단건 읽기 한 번의 결과(S4a JSON 계약 §4). «그런 인시던트가 없다» 는 호스트의 응답(404 `INCIDENT_NOT_FOUND`)이고
+ * «못 읽음» 과 다르다. 앞의 것은 화면에 404 로 그대로 넘기고, 뒤의 것은 503 이다.
+ */
+sealed interface HostIncident {
+    /** 상세 본문(S4a JSON 계약 §4) 그대로. */
+    data class Found(val body: JsonNode) : HostIncident
+
+    /** 호스트의 404 본문 `{error, detail}` 그대로. */
+    data class NotFound(val body: JsonNode) : HostIncident
+
+    data class Silent(val cause: String) : HostIncident
+}
+
+/**
+ * 호스트의 인시던트 REST(S4a 스펙 §6·§7). 시험이 호스트 없이 대신 끼운다.
+ *
+ * 두 읽기는 본문을 해석하지 않고 넘긴다. 판단은 응답이 오면 코드와 본문을 그대로 넘기고 분류는 부르는 쪽이 한다.
+ */
+interface HostIncidents {
+    /** `GET /host/incidents[?limit=]` 본문 그대로. [limit] 이 널이면 쿼리를 싣지 않는다(호스트 기본 50). */
+    fun incidents(limit: Int? = null): HostCall<JsonNode>
+
+    /** `GET /host/incidents/{incidentId}`. */
+    fun incident(incidentId: String): HostIncident
+
+    /** `POST /host/executions/{executionId}/units/{unitId}/resolve`(S4a JSON 계약 §5.1). */
+    fun resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: UUID): HostWrite
+}
+
+/** 장애 주입 전달(S4a 스펙 §7, T1). 시험이 호스트 없이 대신 끼운다. */
+fun interface HostFaults {
+    /** `POST /host/faults`. 호스트는 [body] 를 해석하지 않고 현장에 넘기며 현장의 상태 코드와 본문을 그대로 돌려준다. */
+    fun injectFault(body: ObjectNode): HostWrite
+}
+
+/**
+ * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7, S3c 스펙 §8, S4a 스펙 §7). 호스트는 루프백·무인증이라 토큰을 싣지 않는다.
  *
  * 연결 제한은 registry 와 같고 요청 제한은 더 길다. 호스트는 판정과 제출을 자기 잠금 아래에서 하며, 그 안에서 mimic 에
  * gRPC 를 부르고, mimic 은 엔진 잠금 아래에서 registry 로 태스크 관측을 동기 HTTP 로 적재한다(요청 제한 3초, 스펙 §5.3).
@@ -188,7 +224,7 @@ class HostClient(
     private val json: ObjectMapper = jacksonObjectMapper(),
     private val requestTimeout: Duration = REQUEST_TIMEOUT,
     private val mockRunTimeout: Duration = MOCK_RUN_TIMEOUT,
-) : HostReads, HostWrites, HostMissions, HostSignals, HostSiteTimings, AutoCloseable {
+) : HostReads, HostWrites, HostMissions, HostSignals, HostSiteTimings, HostIncidents, HostFaults, AutoCloseable {
 
     private val base = checkBaseUrl(baseUrl)
 
@@ -270,6 +306,37 @@ class HostClient(
     override fun writeSignal(name: String, value: String): HostWrite =
         post("/host/cell/signals/${segment(name)}", json.createObjectNode().put("value", value))
 
+    override fun injectFault(body: ObjectNode): HostWrite = post("/host/faults", body)
+
+    override fun incidents(limit: Int?): HostCall<JsonNode> = get("/host/incidents" + (limit?.let { "?limit=$it" } ?: ""))
+
+    /**
+     * 없다는 응답은 404 와 `INCIDENT_NOT_FOUND` 가 함께일 때만이다. 다른 404(그 경로가 없는 서버 등)는 호스트의 판단이
+     * 아니므로 못 읽음이다.
+     */
+    override fun incident(incidentId: String): HostIncident {
+        val request = HttpRequest.newBuilder(URI.create("$base/host/incidents/${segment(incidentId)}")).GET()
+        val response = when (val write = send(request, requestTimeout)) {
+            is HostWrite.NoResponse -> return HostIncident.Silent(write.cause)
+            is HostWrite.Answered -> write
+        }
+        val body = objectOrNull(response.body)
+        return when {
+            response.status == 200 && body != null -> HostIncident.Found(body)
+            response.status == 404 && body?.get("error")?.asText() == INCIDENT_NOT_FOUND -> HostIncident.NotFound(body)
+            response.status == 200 -> HostIncident.Silent("본문 모양이 다르다")
+            else -> HostIncident.Silent("HTTP ${response.status}")
+        }
+    }
+
+    override fun resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: UUID): HostWrite {
+        val body = json.createObjectNode()
+            .put("decision", decision)
+            .put("approverId", approverId)
+            .put("requestId", requestId.toString())
+        return post("/host/executions/${segment(executionId)}/units/${segment(unitId)}/resolve", body)
+    }
+
     /** 객체 본문만 받는다. 호스트의 GET 은 늘 객체를 준다(S3a JSON 계약 §5·§6, S3b JSON 계약 §4). */
     private fun get(path: String): HostCall<JsonNode> {
         val response = when (val write = send(HttpRequest.newBuilder(URI.create(base + path)).GET(), requestTimeout)) {
@@ -328,6 +395,9 @@ class HostClient(
         /** 재조회에서 호스트가 그 요청을 아직 처리 중이라는 오류 이름(S3b JSON 계약 §3). */
         const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
 
+        /** 인시던트 단건에서 그런 인시던트가 없다는 호스트 오류 이름(S4a JSON 계약 §4). */
+        const val INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"
+
         /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 호스트 불통으로 보인다. */
         fun checkBaseUrl(baseUrl: String): String {
             val trimmed = baseUrl.trimEnd('/')
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && ./gradlew :ops-service:check -q
```
Expected: ops-service 231, 실패 0(`check` 는 시험과 함께 운영 서비스가 picasso 를 쓰지 않는 경계 `checkNoPicassoOnMain` 을 집행한다). 백그라운드로 돌린다.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git add ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/FaultOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt && git commit -F - <<'EOF'
feat(ops-service): 인시던트 조회 중계와 장애 주입, 운영자 판단 API

- `GET /api/incidents`·`GET /api/incidents/{id}` 를 호스트로 중계, 호스트 불통 503 `HOST_SILENT`
- `POST /api/faults` 는 엔지니어 모드만, 사유 필수, 조작 기록 `INJECT_FAULT`, 재조회 없음
- `POST /api/executions/{e}/units/{u}/resolve` 는 운영자 모드만, 사유 필수, 승인자는 `X-Ops-User`, 조작 기록 `RESOLVE_OPERATOR_HOLD`, Resolved 는 SUCCEEDED, NotHeld·Refused 는 REJECTED 와 원래 이름, 응답 없음 뒤 판단자·결정·판단 실제 시각으로 재조회

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4a-cmp.sh" ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/FaultOperations.kt ops-service/src/main/kotlin/dev/picasso/ops/service/incidents/HoldResolutions.kt ops-service/src/main/kotlin/dev/picasso/ops/service/web/IncidentController.kt ops-service/src/test/kotlin/dev/picasso/ops/service/HostIncidentsClientTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentBench.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentControllerTest.kt ops-service/src/test/kotlin/dev/picasso/ops/service/IncidentOperationsTest.kt ops-service/src/main/kotlin/dev/picasso/ops/service/OpsApplication.kt ops-service/src/main/kotlin/dev/picasso/ops/service/host/HostClient.kt
```
Expected: 9개 모두 `같음`.

### Task 4: 화면: 장애 주입 패널, 인시던트 구역, 운영자 판단

**Files:**
- Create: `ui/src/components/FaultPanel.test.tsx`, `ui/src/components/FaultPanel.tsx`, `ui/src/components/IncidentSection.test.tsx`, `ui/src/components/IncidentSection.tsx`
- Modify: `ui/src/App.tsx`, `ui/src/api.ts`, `ui/src/components/JobOrderNotice.tsx`, `ui/src/components/MissionNotice.tsx`, `ui/src/components/MissionsArea.test.tsx`, `ui/src/components/OperationsArea.tsx`, `ui/src/components/SignalNotice.tsx`, `ui/src/labels.ts`, `ui/src/styles.css`, `ui/src/testing/fakeOps.ts`

- [ ] **Step 1: 새 파일 4개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ui/src/components/FaultPanel.test.tsx" ui/src/components/FaultPanel.test.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ui/src/components/FaultPanel.tsx" ui/src/components/FaultPanel.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ui/src/components/IncidentSection.test.tsx" ui/src/components/IncidentSection.test.tsx
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p ui/src/components && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/ui/src/components/IncidentSection.tsx" ui/src/components/IncidentSection.tsx
```

`ui/src/components/FaultPanel.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { FaultInjectionOutcome, RobotListView, Session } from '../api'
import { faultInjected, installFakeOps, robotView } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { FaultPanel } from './FaultPanel'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const robots: RobotListView = {
  registry: 'OK',
  checkedAt: 't1',
  robots: [robotView('humanoid-01', 'ACTIVE'), robotView('old-01', 'RETIRED'), robotView('quadruped-01', 'ACTIVE')],
  robotsAsOf: 't1',
}

const FAULTS = '/api/faults'

const posts = (fake: FakeOps) => fake.calls.filter((call) => call.method === 'POST' && call.url === FAULTS)

function open(session: Session = engineer, onChanged: () => void = () => undefined) {
  return render(<FaultPanel robots={robots} session={session} onChanged={onChanged} />)
}

async function inject(reason: string) {
  const form = screen.getByRole('form', { name: '장애 주입 폼' })
  if (reason !== '') await userEvent.type(within(form).getByLabelText('장애 주입 사유'), reason)
  await userEvent.click(within(form).getByRole('button', { name: '장애 넣기' }))
}

describe('장애 주입', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('퇴역하지 않은 기체만 고르고 스킬 실패는 상태 없이 사유와 함께 엔지니어 모드로 보내며 받아들임을 보인다', async () => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, { status: 200, body: faultInjected() })
    const onChanged = vi.fn()
    open(engineer, onChanged)
    const form = screen.getByRole('form', { name: '장애 주입 폼' })
    const robot = within(form).getByLabelText('기체')
    expect(within(robot).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'humanoid-01',
      'quadruped-01',
    ])
    expect(
      within(within(form).getByLabelText('장애 종류')).getAllByRole('option').map((option) => option.textContent),
    ).toEqual(['스킬 실패(진행 중 태스크)', '연결 상태'])
    expect(within(form).queryByLabelText('연결 상태')).not.toBeInTheDocument()

    await inject('  스킬 실패 시연  ')
    expect(await screen.findByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
      'humanoid-01 스킬 실패: 받아들임(태스크 JO-1#RACK-204.S01, RETRIABLE)',
    )
    const post = posts(fake).at(-1)!
    expect(post.body).toEqual({ robotId: 'humanoid-01', kind: 'SKILL_EXECUTION_FAILED', reason: '스킬 실패 시연' })
    expect(post.headers['X-Ops-Mode']).toBe('engineer')
    expect(post.headers['X-Ops-User']).toBe('lee')
    expect(post.headers['Content-Type']).toBe('application/json')
    expect(onChanged).toHaveBeenCalled()
  })

  it('연결 상태는 상태 셋 가운데 고른 것을 싣고 같은 상태를 다시 넣으면 바뀐 것 없음으로 보인다', async () => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, {
      status: 200,
      body: faultInjected({
        robotId: 'quadruped-01',
        kind: 'CONNECTION',
        state: 'CONNECTION_BROKEN',
        fault: { robotId: 'quadruped-01', kind: 'CONNECTION', state: 'CONNECTION_BROKEN', changed: true },
      }),
    })
    open()
    const form = screen.getByRole('form', { name: '장애 주입 폼' })
    await userEvent.selectOptions(within(form).getByLabelText('기체'), 'quadruped-01')
    await userEvent.selectOptions(within(form).getByLabelText('장애 종류'), '연결 상태')
    const state = within(form).getByLabelText('연결 상태')
    expect(within(state).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'OFFLINE',
      'CONNECTION_BROKEN',
      'ONLINE(복구)',
    ])
    await userEvent.selectOptions(state, 'CONNECTION_BROKEN')
    await inject('묵은 값 시연')
    expect(await screen.findByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
      'quadruped-01 연결 상태 CONNECTION_BROKEN: 받아들임(연결 상태 CONNECTION_BROKEN)',
    )
    expect(posts(fake).at(-1)!.body).toEqual({
      robotId: 'quadruped-01',
      kind: 'CONNECTION',
      state: 'CONNECTION_BROKEN',
      reason: '묵은 값 시연',
    })

    fake.answers.set(FAULTS, {
      status: 200,
      body: faultInjected({
        robotId: 'quadruped-01',
        kind: 'CONNECTION',
        state: 'CONNECTION_BROKEN',
        fault: { robotId: 'quadruped-01', kind: 'CONNECTION', state: 'CONNECTION_BROKEN', changed: false },
      }),
    })
    await inject('한 번 더')
    await waitFor(() =>
      expect(screen.getByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
        'quadruped-01 연결 상태 CONNECTION_BROKEN: 받아들임(이미 CONNECTION_BROKEN 상태라 바뀐 것 없음)',
      ),
    )
  })

  it('사유가 비었거나 공백뿐이면 보내지 않는다', async () => {
    const fake = installFakeOps(robots)
    open()
    await inject('')
    expect(screen.getByRole('alert')).toHaveTextContent('장애 주입 사유를 넣으십시오')
    await inject('   ')
    expect(screen.getByRole('alert')).toHaveTextContent('장애 주입 사유를 넣으십시오')
    expect(posts(fake)).toEqual([])
  })

  it('운영자 모드에서는 폼 없이 엔지니어 모드에서 한다고 보인다', () => {
    installFakeOps(robots)
    open(operator)
    const region = screen.getByRole('region', { name: '장애 주입' })
    expect(within(region).getByText('장애 주입은 엔지니어 모드에서 합니다')).toBeInTheDocument()
    expect(within(region).queryByRole('form')).not.toBeInTheDocument()
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
  })

  it.each<[FaultInjectionOutcome, string]>([
    [
      faultInjected({
        result: 'REJECTED',
        fault: null,
        rejection: { status: 409, error: 'NO_RUNNING_TASK', detail: '진행 중인 pick_place 태스크가 없다: humanoid-01' },
      }),
      'humanoid-01 스킬 실패: 현장이 거부함(진행 중 태스크 없음). 진행 중인 pick_place 태스크가 없다: humanoid-01',
    ],
    [
      faultInjected({
        result: 'REJECTED',
        fault: null,
        rejection: { status: 404, error: 'UNKNOWN_ROBOT', detail: '이 현장에 없는 기체다' },
      }),
      'humanoid-01 스킬 실패: 현장이 거부함(모르는 기체). 이 현장에 없는 기체다',
    ],
    [
      faultInjected({
        result: 'REJECTED',
        fault: null,
        rejection: { status: 400, error: 'UNSUPPORTED_FAULT', detail: '받지 않는 종류다' },
      }),
      'humanoid-01 스킬 실패: 현장이 거부함(받지 않는 장애 종류). 받지 않는 종류다',
    ],
    [
      // 거부 본문을 읽지 못한 4xx(스프링 기본 415 등)는 이름과 문장이 없다.
      faultInjected({ result: 'REJECTED', fault: null, rejection: { status: 415, error: null, detail: null } }),
      'humanoid-01 스킬 실패: 현장이 거부함(이유 없음)',
    ],
    [
      faultInjected({ result: 'NO_RESPONSE', fault: null }),
      'humanoid-01 스킬 실패: 응답 없음. 넣었는지 모릅니다. 실행 목록과 기체 목록에서 확인하십시오',
    ],
  ])('현장의 거부는 이유를 풀어 적고 응답 없음은 하나로 보이며 둘 다 받아들임이 아니다(%#)', async (body, text) => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, { status: 200, body })
    open()
    await inject('시연')
    const shown = await screen.findByRole('status', { name: '장애 주입 결과' })
    expect(shown).toHaveTextContent(text)
    expect(shown).not.toHaveTextContent('받아들임')
    expect(shown).not.toHaveTextContent('null')
  })

  it('운영 서비스의 사전 거부는 보내지 않음과 그 이유다', async () => {
    const fake = installFakeOps(robots)
    fake.answers.set(FAULTS, {
      status: 403,
      body: { error: 'MODE_NOT_ALLOWED', detail: '이 조작은 engineer 모드에서 한다' },
    })
    open()
    await inject('시연')
    expect(await screen.findByRole('status', { name: '장애 주입 결과' })).toHaveTextContent(
      'humanoid-01 스킬 실패: 보내지 않음(이 모드에서 할 수 없는 조작). 이 조작은 engineer 모드에서 한다',
    )
  })

  it('현장·자원 영역에 현장 설정과 따로 장애 주입 구역이 있고 기체 목록을 다시 읽어 고를 기체를 채운다', async () => {
    installFakeOps(robots)
    render(<App />)
    await userEvent.click(screen.getByRole('button', { name: '현장·자원' }))
    const region = screen.getByRole('region', { name: '장애 주입' })
    expect(screen.getByRole('region', { name: '현장 설정' })).toBeInTheDocument()
    const robot = await within(region).findByLabelText('기체')
    expect(within(robot).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'humanoid-01',
      'quadruped-01',
    ])
  })
})
```

`ui/src/components/FaultPanel.tsx`:

```tsx
import { useState } from 'react'
import type { FormEvent } from 'react'
import { injectFault } from '../api'
import type { ConnectionFaultState, Delivered, FaultInjectionOutcome, FaultKind, RobotListView, Session } from '../api'
import { FAULT_KIND_LABEL, kindLabel, rejectionText } from '../labels'

interface Props {
  /** 기체 목록(App 의 다섯 조회). 운영 서비스에 닿지 않았으면 null 이다. */
  robots: RobotListView | null
  session: Session
  /** 조작이 끝나면 부른다. 조작 기록을 다시 읽는다. */
  onChanged: () => void
}

/** 연결 상태 셋(S4a JSON 계약 §1.1). 표시 문구는 계약의 이름 그대로이고 ONLINE 에만 복구를 붙인다. */
const STATES: readonly { value: ConnectionFaultState; label: string }[] = [
  { value: 'OFFLINE', label: 'OFFLINE' },
  { value: 'CONNECTION_BROKEN', label: 'CONNECTION_BROKEN' },
  { value: 'ONLINE', label: 'ONLINE(복구)' },
]

const KINDS: readonly FaultKind[] = ['SKILL_EXECUTION_FAILED', 'CONNECTION']

/**
 * 현장·자원 영역의 «장애 주입» 구역(S4a 스펙 §8.1). 기체 하나에 스킬 실패나 연결 상태를 넣는다. 엔지니어 모드에서만 조작하고
 * 운영자 모드에서는 현장 설정처럼 읽기만 한다. 지울 때까지 유지되는 결함과 전송 장애는 현장이 받지 않으므로 종류에 두지 않는다.
 *
 * 장애 주입은 재조회하지 않는다(S4a JSON 계약 §9.4). 그래서 받아들임도 효과를 보증하지 않고, 효과는 실행 목록과 기체 목록에서
 * 확인하게 한다.
 */
export function FaultPanel({ robots, session, onChanged }: Props) {
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<FaultInjectionOutcome> } | null>(null)

  const inject = (robotId: string, kind: FaultKind, state: ConnectionFaultState | null, reason: string) => {
    const what = kind === 'CONNECTION' ? `${robotId} 연결 상태 ${state}` : `${robotId} ${FAULT_KIND_LABEL[kind]}`
    setBusy(true)
    injectFault(session, robotId, kind, state, reason)
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        onChanged()
      })
  }

  // 퇴역한 기체는 고르지 않는다. 현장에 없는 기체는 현장이 거부하고 그 이유를 보인다.
  const ids =
    robots === null || robots.robots === null
      ? null
      : robots.robots.filter((view) => view.robot.status !== 'RETIRED').map((view) => view.robot.robotId)

  return (
    <section aria-label="장애 주입">
      <h2>장애 주입</h2>
      <p>
        기체 하나에 장애를 넣습니다. 받아들임은 현장이 장애를 넣었다는 뜻이고, 그 효과는 운영 영역의 실행 목록과 인시던트,
        로봇·연결 영역의 기체 목록에서 확인합니다
      </p>
      {last !== null && <FaultNotice what={last.what} sent={last.sent} />}
      {session.mode !== 'engineer' ? (
        <p>장애 주입은 엔지니어 모드에서 합니다</p>
      ) : ids === null ? (
        <p>모름: 기체 목록을 아직 읽지 못했습니다</p>
      ) : ids.length === 0 ? (
        <p>장애를 넣을 기체가 없습니다</p>
      ) : (
        <FaultForm ids={ids} busy={busy} onInject={inject} />
      )}
    </section>
  )
}

interface FormProps {
  ids: string[]
  busy: boolean
  onInject: (robotId: string, kind: FaultKind, state: ConnectionFaultState | null, reason: string) => void
}

function FaultForm({ ids, busy, onInject }: FormProps) {
  const [picked, setPicked] = useState<string | null>(null)
  const [kind, setKind] = useState<FaultKind>('SKILL_EXECUTION_FAILED')
  const [state, setState] = useState<ConnectionFaultState>('OFFLINE')
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  // 다시 읽은 목록에서 고른 기체가 빠지면 첫 기체로 돌아간다.
  const robotId = picked !== null && ids.includes(picked) ? picked : ids[0]

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (reason.trim() === '') {
      setProblem('장애 주입 사유를 넣으십시오')
      return
    }
    setProblem(null)
    onInject(robotId, kind, kind === 'CONNECTION' ? state : null, reason.trim())
    setReason('')
  }

  return (
    <form aria-label="장애 주입 폼" onSubmit={submit}>
      <label>
        기체
        <select value={robotId} onChange={(event) => setPicked(event.target.value)}>
          {ids.map((id) => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>{' '}
      <label>
        장애 종류
        <select value={kind} onChange={(event) => setKind(event.target.value as FaultKind)}>
          {KINDS.map((value) => (
            <option key={value} value={value}>
              {value === 'SKILL_EXECUTION_FAILED' ? '스킬 실패(진행 중 태스크)' : FAULT_KIND_LABEL[value]}
            </option>
          ))}
        </select>
      </label>{' '}
      {kind === 'CONNECTION' && (
        <label>
          연결 상태
          <select value={state} onChange={(event) => setState(event.target.value as ConnectionFaultState)}>
            {STATES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      )}
      <div>
        <label>
          장애 주입 사유
          <input value={reason} onChange={(event) => setReason(event.target.value)} />
        </label>{' '}
        <button type="submit" disabled={busy}>
          장애 넣기
        </button>
      </div>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}

/**
 * 장애 주입의 결과(S4a 스펙 §8.1). 현장 불통과 실행 호스트 불통은 운영 서비스가 둘 다 `NO_RESPONSE` 로 내므로 화면에서는
 * «응답 없음» 하나다. 재조회하지 않으므로 응답 없음은 반영 여부를 끝내 모른다.
 */
export function FaultNotice({ what, sent }: { what: string; sent: Delivered<FaultInjectionOutcome> }) {
  return (
    <p role="status" aria-label="장애 주입 결과">
      {what}: {describe(sent)}
    </p>
  )
}

function describe(sent: Delivered<FaultInjectionOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 실행 목록과 기체 목록에서 확인하십시오`
  const { result, fault, rejection } = sent.outcome
  if (result === 'SUCCEEDED' && fault !== null) {
    if (fault.kind === 'SKILL_EXECUTION_FAILED') {
      const again = fault.raised ? '' : '. 같은 결함이 이미 서 있었습니다'
      return `받아들임(태스크 ${fault.taskId}, ${fault.taskState})${again}`
    }
    return fault.changed ? `받아들임(연결 상태 ${fault.state})` : `받아들임(이미 ${fault.state} 상태라 바뀐 것 없음)`
  }
  if (result === 'SUCCEEDED') return '받아들임'
  if (result === 'REJECTED' && rejection !== null) {
    return rejectionText('현장이 거부함', rejection)
  }
  return '응답 없음. 넣었는지 모릅니다. 실행 목록과 기체 목록에서 확인하십시오'
}
```

`ui/src/components/IncidentSection.test.tsx`:

```tsx
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import type { FaultDetail, HoldResolveOutcome, IncidentResolution, Session } from '../api'
import { POLL_MS, SIGNAL_SETTLE_MS } from '../poll'
import { holdResolved, incidentDetail, incidentRow, incidentsView, installFakeOps } from '../testing/fakeOps'
import type { FakeOps } from '../testing/fakeOps'
import { OperationsArea } from './OperationsArea'

const engineer: Session = { mode: 'engineer', user: 'lee' }
const operator: Session = { mode: 'operator', user: 'kim' }

const emptyList = { registry: 'OK' as const, checkedAt: 't1', robots: [], robotsAsOf: 't1' }

const RESOLVE = '/api/executions/exec-1/units/rack-arrival/resolve'

/** 재작업 판단(S4a JSON 계약 §3). */
const rework: IncidentResolution = {
  decision: 'REWORK',
  at: 'h20',
  wallClockAt: 'w20',
  decidedBy: { id: 'kim', kind: 'PERSON' },
}

/** 스킬 실패의 결함 원문(S4a JSON 계약 §4 실측 값). */
const grasp: FaultDetail = {
  failureClass: 'GRASP_FAILED',
  errorType: 'SKILL_EXECUTION_FAILED',
  vendorDetail: '',
  errorHint: '',
  references: [
    { key: 'KEY_SKILL_ID', value: 'pick_place' },
    { key: 'KEY_TASK_ID', value: 'JO-1#RACK-204.S01' },
  ],
  canContinueCurrentTask: false,
  canAcceptNewTask: true,
  activeUntilKind: 'KIND_UNTIL_NEW_TASK',
  activeUntilTime: '',
}

/** 실행 전체를 막던 로봇 수준 결함(S4a JSON 계약 §4 실측 값). */
const localization: FaultDetail = {
  ...grasp,
  failureClass: 'LOCALIZATION_LOST',
  errorType: 'LOCALIZATION_LOST',
  errorHint: '기체를 다시 위치시키십시오',
  references: [],
  canAcceptNewTask: false,
  activeUntilKind: 'KIND_UNTIL_CLEARED',
}

/** 줄 셋. 호스트가 준 순서(최신부터)다: 두 번째 보류, 재작업 판단된 첫 보류, 코드 정의의 스킬 실패. */
function threeRows() {
  return incidentsView([
    incidentRow({ incidentId: 'incident-3', at: 'h30' }),
    incidentRow({ incidentId: 'incident-2', at: 'h10', held: false, resolution: rework }),
    incidentRow({
      incidentId: 'incident-1',
      at: 'h5',
      executionId: 'exec-0',
      unitId: 'RACK-204.S01',
      failureClass: 'GRASP_FAILED',
      route: 'ROBOT',
      missionVersion: null,
      siteSettingsVersion: null,
      unresolved: false,
      held: false,
      fault: { failureClass: 'GRASP_FAILED', errorType: 'SKILL_EXECUTION_FAILED', errorHint: '' },
    }),
  ])
}

const calls = (fake: FakeOps, method: string, url: string) =>
  fake.calls.filter((call) => call.method === method && call.url === url)

/** 한 행의 칸 글자. */
const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent)

/** 칸 이름(dt) 바로 뒤의 값(dd)을 읽는다. */
function field(region: HTMLElement, name: string) {
  const term = within(region).getByText(name, { selector: 'dt' })
  return term.nextElementSibling?.textContent
}

function open(session: Session = operator) {
  return render(<OperationsArea session={session} onChanged={() => undefined} />)
}

async function select(incidentId: string) {
  await userEvent.click(await screen.findByRole('button', { name: `${incidentId} 상세 보기` }))
  return screen.findByRole('region', { name: '인시던트 상세' })
}

describe('인시던트', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('목록은 호스트가 준 최신부터의 순서로 시각·기체·실행·단위·실패 종류·경로·버전·판단 칸을 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = { ...threeRows(), total: 7 }
    open()
    const region = screen.getByRole('region', { name: '인시던트' })
    const table = await within(region).findByRole('table', { name: '인시던트 목록' })
    expect(within(region).getByText('실행 호스트 인스턴스 mw-1, 인시던트 7건 가운데 최신 3건')).toBeInTheDocument()
    expect(within(table).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['incident-3', 'h30', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '미해결', '보류 중'],
      ['incident-2', 'h10', 'humanoid-01', 'exec-1', 'rack-arrival', 'SIGNAL_DEADLINE', 'SIGNAL', '버전 2', '버전 3', '판단됨', '-'],
      ['incident-1', 'h5', 'humanoid-01', 'exec-0', 'RACK-204.S01', 'GRASP_FAILED', 'ROBOT', '모름', '코드 정의', '판단 대상 아님', '-'],
    ])
  })

  it('보류 중 강조는 held 인 줄에만 있고 미해결이어도 held 가 아니면 강조하지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([
      incidentRow({ incidentId: 'incident-5', held: false }),
      incidentRow({ incidentId: 'incident-4', held: true }),
    ])
    open()
    const table = await screen.findByRole('table', { name: '인시던트 목록' })
    const [, newer, older] = within(table).getAllByRole('row')
    expect(cells(newer).at(-2)).toBe('미해결')
    expect(cells(newer).at(-1)).toBe('-')
    expect(newer).not.toHaveClass('held')
    expect(cells(older).at(-1)).toBe('보류 중')
    expect(older).toHaveClass('held')
    expect(within(table).getAllByText('보류 중')).toHaveLength(1)
  })

  it('줄을 고르면 상세에 설정 버전과 시간값, 임무 버전, 단계 위치, 필요·도달 근거, 확인 결과 코드 이름, 근거 윈도우를 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    open()
    const detail = await select('incident-1')
    const observed = await within(detail).findByRole('region', { name: '관측' })
    expect(field(observed, '현장 설정 버전')).toBe('버전 2')
    expect(field(observed, '시간값')).toBe('근거 윈도우 앞 폭 30초, 뒤 폭 15초, inDoubtGrace 60초, stallWindow 300초')
    expect(field(observed, '임무 버전')).toBe('PrepareSequencedRack 버전 3')
    expect(field(observed, '단계 위치')).toBe('1/2. 계획 rack-arrival → RACK-204.S01. 끝난 단위 없음')
    expect(field(observed, '필요 근거 등급')).toBe('E2')
    expect(field(observed, '도달 근거 등급')).toBe('E0')
    expect(field(observed, '확인 결과(코드 이름)')).toBe('NOT_REQUESTED')
    expect(field(observed, '단위의 지금 상태')).toBe('OPERATOR_HOLD')
    expect(detail).not.toHaveTextContent('확인 안 함')
    expect(within(observed).getByText('없음(결함 없이 실패를 알림)')).toBeInTheDocument()
    const window = within(observed).getByRole('table', { name: '근거 윈도우' })
    expect(within(window).getAllByRole('row').slice(1).map(cells)).toEqual([
      ['41', 'h9', 'CELL_SIGNAL', 'signal rack_present at deadline: false', '미들웨어 기록'],
    ])
    expect(within(observed).getByText('근거 윈도우(앞 30초, 뒤 15초)')).toBeInTheDocument()
    expect(calls(fake, 'GET', '/api/incidents/incident-1').length).toBeGreaterThan(0)
  })

  it('상세는 이 단위의 결함과 실행을 막던 결함(blockedBy) 원문을 보이고 코드 정의 임무는 코드 정의다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = threeRows()
    fake.incidentDetails.set(
      'incident-1',
      incidentDetail({
        incidentId: 'incident-1',
        unitId: 'RACK-204.S01',
        failureClass: 'GRASP_FAILED',
        route: 'ROBOT',
        unresolved: false,
        held: false,
        unitState: 'FAILED',
        fault: grasp,
        blockedBy: [localization],
        windowTruncated: true,
        intent: { ...incidentDetail().intent, missionVersion: null, siteSettingsVersion: null },
      }),
    )
    open()
    const detail = await select('incident-1')
    const own = await within(detail).findByLabelText('이 단위의 결함')
    expect(field(own, '분류')).toBe('GRASP_FAILED(SKILL_EXECUTION_FAILED)')
    expect(field(own, '참조')).toBe('KEY_SKILL_ID=pick_place, KEY_TASK_ID=JO-1#RACK-204.S01')
    expect(field(own, '조치 힌트')).toBe('없음')
    expect(field(own, '유지')).toBe('KIND_UNTIL_NEW_TASK')
    const blocked = within(detail).getByLabelText('실행을 막던 결함 1')
    expect(field(blocked, '분류')).toBe('LOCALIZATION_LOST(LOCALIZATION_LOST)')
    expect(field(blocked, '새 태스크 받기')).toBe('불가')
    expect(field(blocked, '유지')).toBe('KIND_UNTIL_CLEARED')
    expect(within(detail).getByText('윈도우 밖이라 버린 관측이 있습니다')).toBeInTheDocument()
    const observed = within(detail).getByRole('region', { name: '관측' })
    expect(field(observed, '임무 버전')).toBe('PrepareSequencedRack 코드 정의')
    expect(field(observed, '현장 설정 버전')).toBe('모름')
  })

  it('판단 없는 인시던트는 관측만 보이고 판단된 인시던트는 판단자와 실제 시각, 결정을 관측과 다른 구역에 보인다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = threeRows()
    fake.incidentDetails.set('incident-3', incidentDetail({ incidentId: 'incident-3' }))
    fake.incidentDetails.set(
      'incident-2',
      incidentDetail({ incidentId: 'incident-2', held: false, unitState: 'RUNNING', resolution: rework }),
    )
    open()
    let detail = await select('incident-3')
    expect(await within(detail).findByText('사람의 판단 없음. 아래 값은 모두 관측입니다')).toBeInTheDocument()
    expect(within(detail).queryByRole('region', { name: '사람의 판단' })).not.toBeInTheDocument()
    expect(detail).not.toHaveTextContent('사람이 판단함')

    detail = await select('incident-2')
    const asserted = await within(detail).findByRole('region', { name: '사람의 판단' })
    expect(asserted).toHaveTextContent('사람이 판단함: kim, w20')
    expect(asserted).toHaveTextContent('결정 재작업(REWORK), 호스트 시각 h20')
    expect(asserted).toHaveClass('asserted')
    const observed = within(detail).getByRole('region', { name: '관측' })
    expect(observed).not.toHaveTextContent('사람이 판단함')
    expect(detail).not.toHaveTextContent('설비 근거 없이 완료 확인')
  })

  it('설비 근거 없이 완료 확인은 호스트가 준 표시로만 보이고 화면이 다시 계산하지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    const confirm: IncidentResolution = { ...rework, decision: 'CONFIRM_DONE' }
    fake.incidents = incidentsView([
      incidentRow({ incidentId: 'incident-2', held: false, resolution: confirm, confirmedWithoutEvidence: true }),
      incidentRow({ incidentId: 'incident-1', held: false, resolution: confirm, confirmedWithoutEvidence: false }),
    ])
    fake.incidentDetails.set(
      'incident-2',
      incidentDetail({ incidentId: 'incident-2', held: false, resolution: confirm, confirmedWithoutEvidence: true }),
    )
    // 확인 결과가 MATCHED 가 아니어도 호스트가 거짓이라고 했으면 그대로 따른다.
    fake.incidentDetails.set(
      'incident-1',
      incidentDetail({ incidentId: 'incident-1', held: false, resolution: confirm, confirmedWithoutEvidence: false }),
    )
    open()
    const table = await screen.findByRole('table', { name: '인시던트 목록' })
    expect(within(table).getAllByRole('row').slice(1).map((row) => cells(row).at(-2))).toEqual([
      '판단됨(설비 근거 없이 완료 확인)',
      '판단됨',
    ])
    let detail = await select('incident-2')
    const asserted = await within(detail).findByRole('region', { name: '사람의 판단' })
    expect(asserted).toHaveTextContent('결정 완료 확인(CONFIRM_DONE)')
    expect(within(asserted).getByText('설비 근거 없이 완료 확인')).toBeInTheDocument()

    detail = await select('incident-1')
    await waitFor(() => expect(within(detail).getByRole('heading', { name: 'incident-1 상세' })).toBeInTheDocument())
    expect(await within(detail).findByRole('region', { name: '사람의 판단' })).not.toHaveTextContent(
      '설비 근거 없이 완료 확인',
    )
  })

  it('판단 버튼 둘은 운영자 모드에서 보류 중인 상세에만 있고 엔지니어 모드와 보류가 아닌 상세에는 없다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = threeRows()
    fake.incidentDetails.set('incident-3', incidentDetail({ incidentId: 'incident-3' }))
    fake.incidentDetails.set('incident-2', incidentDetail({ incidentId: 'incident-2', held: false, resolution: rework }))
    const { rerender } = open(operator)
    let detail = await select('incident-3')
    const form = await within(detail).findByRole('form', { name: '운영자 판단' })
    expect(within(form).getAllByRole('button').map((button) => button.textContent)).toEqual(['완료 확인', '재작업'])
    expect(form).toHaveTextContent('exec-1/rack-arrival 보류를 판단합니다. 판단자는 kim 입니다')

    rerender(<OperationsArea session={engineer} onChanged={() => undefined} />)
    expect(await within(detail).findByText('보류 중입니다. 운영자 판단은 운영자 모드에서 합니다')).toBeInTheDocument()
    expect(within(detail).queryByRole('form', { name: '운영자 판단' })).not.toBeInTheDocument()
    expect(within(detail).queryByRole('button', { name: '재작업' })).not.toBeInTheDocument()

    rerender(<OperationsArea session={operator} onChanged={() => undefined} />)
    detail = await select('incident-2')
    await within(detail).findByRole('region', { name: '사람의 판단' })
    expect(within(detail).queryByRole('button', { name: '완료 확인' })).not.toBeInTheDocument()
    expect(within(detail).queryByRole('button', { name: '재작업' })).not.toBeInTheDocument()
  })

  it('다른 보류 인시던트를 고르면 판단 사유 입력이 비어 있다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([
      incidentRow({ incidentId: 'incident-5', executionId: 'exec-2' }),
      incidentRow({ incidentId: 'incident-3' }),
    ])
    fake.incidentDetails.set('incident-5', incidentDetail({ incidentId: 'incident-5', executionId: 'exec-2' }))
    fake.incidentDetails.set('incident-3', incidentDetail({ incidentId: 'incident-3' }))
    open()
    let detail = await select('incident-3')
    await userEvent.type(await within(detail).findByLabelText('판단 사유'), 'exec-1 랙 확인')
    detail = await select('incident-5')
    const form = await within(detail).findByRole('form', { name: '운영자 판단' })
    expect(form).toHaveTextContent('exec-2/rack-arrival 보류를 판단합니다')
    expect(within(form).getByLabelText('판단 사유')).toHaveValue('')
  })

  it('판단 사유가 비었거나 공백뿐이면 보내지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    open()
    const detail = await select('incident-1')
    await userEvent.click(await within(detail).findByRole('button', { name: '재작업' }))
    expect(within(detail).getByRole('alert')).toHaveTextContent('판단 사유를 넣으십시오')
    await userEvent.type(within(detail).getByLabelText('판단 사유'), '   ')
    await userEvent.click(within(detail).getByRole('button', { name: '완료 확인' }))
    expect(within(detail).getByRole('alert')).toHaveTextContent('판단 사유를 넣으십시오')
    expect(fake.calls.filter((call) => call.method === 'POST' && call.url.endsWith('/resolve'))).toEqual([])
  })

  it('판단은 실행과 단위 경로로 결정과 사유만 운영자 모드로 보내고 Resolved 를 보인 뒤 곧바로와 pump 뒤 다시 읽는다', async () => {
    // 시계는 손으로만 민다. 대역의 fetch 는 타이머를 쓰지 않으므로 act 로 약속만 흘려보낸다.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow({ executionId: 'exec 1', unitId: 'rack/arrival' })])
    fake.incidentDetails.set('incident-1', incidentDetail({ executionId: 'exec 1', unitId: 'rack/arrival' }))
    const path = '/api/executions/exec%201/units/rack%2Farrival/resolve'
    fake.answers.set(path, { status: 200, body: holdResolved({ executionId: 'exec 1', unitId: 'rack/arrival' }) })
    const flush = async () => {
      for (let round = 0; round < 5; round++) await act(async () => undefined)
    }
    open()
    await flush()
    fireEvent.click(screen.getByRole('button', { name: 'incident-1 상세 보기' }))
    await flush()
    const detail = screen.getByRole('region', { name: '인시던트 상세' })
    fireEvent.change(within(detail).getByLabelText('판단 사유'), { target: { value: '  랙 재배치 뒤 재작업 ' } })
    const reads = () => ({
      incidents: calls(fake, 'GET', '/api/incidents').length,
      executions: calls(fake, 'GET', '/api/executions').length,
      detail: calls(fake, 'GET', '/api/incidents/incident-1').length,
    })
    const before = reads()
    fireEvent.click(within(detail).getByRole('button', { name: '재작업' }))
    await flush()

    expect(screen.getByRole('status', { name: '판단 결과' })).toHaveTextContent(
      'exec 1/rack/arrival 재작업: 판단이 섰습니다(incident-1)',
    )
    const post = calls(fake, 'POST', path).at(-1)!
    expect(post.body).toEqual({ decision: 'REWORK', reason: '랙 재배치 뒤 재작업' })
    expect(post.headers['X-Ops-Mode']).toBe('operator')
    expect(post.headers['X-Ops-User']).toBe('kim')
    const now = reads()
    expect(now).toEqual({
      incidents: before.incidents + 1,
      executions: before.executions + 1,
      detail: before.detail + 1,
    })
    await act(async () => vi.advanceTimersByTime(SIGNAL_SETTLE_MS))
    await flush()
    expect(reads()).toEqual({ incidents: now.incidents + 1, executions: now.executions + 1, detail: now.detail + 1 })
    expect(within(detail).getByLabelText('판단 사유')).toHaveValue('')
  })

  it.each<[HoldResolveOutcome | { status: number; body: unknown }, string]>([
    [
      holdResolved({
        result: 'REJECTED',
        outcome: 'NotHeld',
        answer: { result: 'NotHeld', detail: null, incidentId: null, requestId: null },
      }),
      'exec-1/rack-arrival 재작업: 보류 단위가 아닙니다',
    ],
    [
      holdResolved({
        result: 'REJECTED',
        outcome: 'Refused',
        answer: { result: 'Refused', detail: '에이전트는 운영자 판단을 내지 못한다', incidentId: null, requestId: null },
      }),
      'exec-1/rack-arrival 재작업: 에이전트 판단은 거부됩니다. 에이전트는 운영자 판단을 내지 못한다',
    ],
    [
      holdResolved({ result: 'NO_RESPONSE', outcome: null, answer: null, confirmation: 'CONFIRMED_APPLIED' }),
      'exec-1/rack-arrival 재작업: 응답은 없었으나 다시 읽어 보니 판단이 붙음',
    ],
    [
      holdResolved({ result: 'NO_RESPONSE', outcome: null, answer: null, confirmation: 'CONFIRMED_NOT_APPLIED' }),
      'exec-1/rack-arrival 재작업: 응답 없음. 다시 읽어 보니 판단이 붙지 않음',
    ],
    [
      holdResolved({ result: 'NO_RESPONSE', outcome: null, answer: null, confirmation: null }),
      'exec-1/rack-arrival 재작업: 응답 없음. 판단이 붙었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다',
    ],
    [
      // 거부 본문을 읽지 못한 호스트 4xx 는 이름과 문장이 없다.
      holdResolved({ result: 'REJECTED', outcome: null, answer: null, rejection: { status: 415, error: null, detail: null } }),
      'exec-1/rack-arrival 재작업: 실행 호스트가 거부함(이유 없음)',
    ],
    [
      { status: 403, body: { error: 'MODE_NOT_ALLOWED', detail: '이 조작은 operator 모드에서 한다' } },
      'exec-1/rack-arrival 재작업: 보내지 않음(이 모드에서 할 수 없는 조작). 이 조작은 operator 모드에서 한다',
    ],
  ])('판단 결과는 outcome 으로 가르고 NotHeld·Refused·응답 없음·사전 거부는 판단이 섰다고 보이지 않는다(%#)', async (answer, text) => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    fake.answers.set(RESOLVE, 'status' in answer ? answer : { status: 200, body: answer })
    open()
    const detail = await select('incident-1')
    await userEvent.type(await within(detail).findByLabelText('판단 사유'), '확인')
    await userEvent.click(within(detail).getByRole('button', { name: '재작업' }))
    const shown = await screen.findByRole('status', { name: '판단 결과' })
    expect(shown).toHaveTextContent(text)
    expect(shown).not.toHaveTextContent('판단이 섰습니다')
    expect(shown).not.toHaveTextContent('null')
  })

  it('주기마다 인시던트 목록과 고른 상세를 다시 읽는다', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    fake.incidentDetails.set('incident-1', incidentDetail())
    const flush = async () => {
      for (let round = 0; round < 5; round++) await act(async () => undefined)
    }
    open()
    await flush()
    fireEvent.click(screen.getByRole('button', { name: 'incident-1 상세 보기' }))
    await flush()
    const list = calls(fake, 'GET', '/api/incidents').length
    const detail = calls(fake, 'GET', '/api/incidents/incident-1').length
    expect(detail).toBe(1)
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await flush()
    expect(calls(fake, 'GET', '/api/incidents')).toHaveLength(list + 1)
    expect(calls(fake, 'GET', '/api/incidents/incident-1')).toHaveLength(detail + 1)

    // 다시 읽은 상세가 판단을 실으면 그대로 바뀐다.
    fake.incidentDetails.set('incident-1', incidentDetail({ held: false, resolution: rework }))
    await act(async () => vi.advanceTimersByTime(POLL_MS))
    await flush()
    expect(screen.getByRole('region', { name: '사람의 판단' })).toHaveTextContent('사람이 판단함: kim, w20')
  })

  it('운영 영역을 열기 전에는 인시던트를 읽지 않는다', async () => {
    const fake = installFakeOps(emptyList)
    render(<App />)
    expect(await screen.findByText('선언된 기체가 없습니다')).toBeInTheDocument()
    expect(fake.calls.filter((call) => call.url.startsWith('/api/incidents'))).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '운영' }))
    expect(await screen.findByText('인시던트가 없습니다')).toBeInTheDocument()
    expect(calls(fake, 'GET', '/api/incidents').length).toBeGreaterThan(0)
  })

  it('실행 호스트가 503 이면 목록은 직전 값과 불통을 보이고 호스트에 없는 인시던트의 상세는 없음이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.incidents = incidentsView([incidentRow()])
    const { rerender } = open()
    const region = screen.getByRole('region', { name: '인시던트' })
    await within(region).findByRole('table', { name: '인시던트 목록' })
    const detail = await select('incident-1')
    expect(
      await within(detail).findByText('실행 호스트에 이 인시던트가 없습니다. 실행 호스트를 재기동했을 수 있습니다'),
    ).toBeInTheDocument()

    fake.failing.add('/api/incidents')
    rerender(<OperationsArea session={{ ...operator }} onChanged={() => undefined} />)
    expect(
      await within(region).findByText(
        '직전 값입니다. 실행 호스트 불통: 실행 호스트가 답하지 않는다: 응답 없음: ConnectException',
      ),
    ).toBeInTheDocument()
    expect(within(region).getByRole('row', { name: /incident-1/ })).toBeInTheDocument()
  })

  it('인시던트 목록을 한 번도 못 읽으면 모름이다', async () => {
    const fake = installFakeOps(emptyList)
    fake.failing.add('/api/incidents')
    open()
    expect(
      await screen.findByText(
        '모름: 인시던트 목록을 아직 읽지 못했습니다 (실행 호스트가 답하지 않는다: 응답 없음: ConnectException)',
      ),
    ).toBeInTheDocument()
  })
})
```

`ui/src/components/IncidentSection.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { fetchIncident, resolveHold } from '../api'
import type {
  Delivered,
  FaultDetail,
  HoldDecision,
  HoldResolveOutcome,
  IncidentDetail,
  IncidentLookup,
  IncidentResolution,
  IncidentRow,
  IncidentsView,
  Session,
} from '../api'
import { DECISION_LABEL, kindLabel, rejectionText } from '../labels'
import type { HostRead } from './ExecutionList'

interface Props {
  read: HostRead<IncidentsView>
  session: Session
  /** 주기와 조작마다 오른다. 고른 인시던트의 상세도 같이 다시 읽는다. */
  tick: number
  /** 판단을 보낸 뒤 부른다. 인시던트와 실행 목록을 곧바로, 그리고 조금 뒤 한 번 더 읽는다. */
  onDecided: () => void
}

const message = (error: unknown) => (error instanceof Error ? error.message : String(error))

const DECISIONS: readonly HoldDecision[] = ['CONFIRM_DONE', 'REWORK']

/** 임무 버전 칸. null 은 코드 정의 임무다(S3a JSON 계약 §5). */
const missionVersion = (version: number | null) => (version === null ? '코드 정의' : `버전 ${version}`)
/** 현장 설정 버전 칸. null 은 봉인 때 버전을 몰랐던 것이다. */
const settingsVersion = (version: number | null) => (version === null ? '모름' : `버전 ${version}`)
const seconds = (value: number | null) => (value === null ? '모름' : `${value}초`)

/**
 * 목록의 판단 칸(S4a 스펙 §8.2). «미해결» 은 봉인 때 보류·불확실이었고 판단이 없는 것이다. 단위가 실패로 끝나 사람이 판단할
 * 것이 없는 인시던트는 판단 대상이 아니다.
 */
function judgement(row: IncidentRow): string {
  if (row.resolution !== null) {
    return row.confirmedWithoutEvidence ? '판단됨(설비 근거 없이 완료 확인)' : '판단됨'
  }
  return row.unresolved ? '미해결' : '판단 대상 아님'
}

/**
 * 운영 영역의 «인시던트» 구역(S4a 스펙 §8.2·§8.3). 목록은 실행 호스트가 준 순서(최신부터)이고, 줄을 고르면 상세를 읽는다.
 * 관측(봉인 때의 근거)과 사람의 판단을 따로 보인다(운영 관리 화면 설계 제안 §9). 판단 버튼은 보류 중인 단위의 상세에만,
 * 운영자 모드에서만 있다.
 */
export function IncidentSection({ read, session, tick, onDecided }: Props) {
  const [selected, setSelected] = useState<string | null>(null)
  const [detail, setDetail] = useState<{ id: string; lookup: IncidentLookup | null; error: string | null } | null>(null)
  const [busy, setBusy] = useState(false)
  const [last, setLast] = useState<{ what: string; sent: Delivered<HoldResolveOutcome> } | null>(null)

  useEffect(() => {
    if (selected === null) return
    let alive = true
    fetchIncident(session, selected)
      .then((lookup) => {
        if (alive) setDetail({ id: selected, lookup, error: null })
      })
      .catch((error: unknown) => {
        if (!alive) return
        // 못 읽으면 같은 인시던트의 직전 상세를 지우지 않는다.
        setDetail((previous) =>
          previous !== null && previous.id === selected
            ? { ...previous, error: message(error) }
            : { id: selected, lookup: null, error: message(error) },
        )
      })
    return () => {
      alive = false
    }
  }, [session, tick, selected])

  const decide = (target: IncidentDetail, decision: HoldDecision, reason: string) => {
    const what = `${target.executionId}/${target.unitId} ${DECISION_LABEL[decision]}`
    setBusy(true)
    resolveHold(session, target.executionId, target.unitId, decision, reason)
      .then((sent) => setLast({ what, sent }))
      .finally(() => {
        setBusy(false)
        onDecided()
      })
  }

  // 운영 서비스가 다른 모양을 주면(예: 시험 대역의 빈 배열) 목록이 없다. 없는 목록도 모름이다.
  const value = read.value !== null && Array.isArray(read.value.incidents) ? read.value : null
  const { error } = read
  const shown = detail !== null && detail.id === selected ? detail : null

  return (
    <section aria-label="인시던트">
      <h2>인시던트</h2>
      {last !== null && <DecisionNotice what={last.what} sent={last.sent} />}
      {value === null ? (
        <p>모름: 인시던트 목록을 아직 읽지 못했습니다{error !== null && ` (${error})`}</p>
      ) : (
        <>
          {error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {error}</p>}
          <p>
            실행 호스트 인스턴스 {value.instanceId}, 인시던트 {value.total}건 가운데 최신 {value.incidents.length}건
          </p>
          {value.incidents.length === 0 ? (
            <p>인시던트가 없습니다</p>
          ) : (
            <IncidentTable rows={value.incidents} selected={selected} onSelect={setSelected} />
          )}
        </>
      )}
      {selected !== null && (
        <section aria-label="인시던트 상세">
          <h3>{selected} 상세</h3>
          {shown === null || (shown.lookup === null && shown.error === null) ? (
            <p>상세를 읽는 중입니다</p>
          ) : shown.lookup === null ? (
            <p>모름: 상세를 읽지 못했습니다 ({shown.error})</p>
          ) : shown.lookup.kind === 'missing' ? (
            <p>실행 호스트에 이 인시던트가 없습니다. 실행 호스트를 재기동했을 수 있습니다</p>
          ) : (
            <>
              {shown.error !== null && <p className="stale">직전 값입니다. 실행 호스트 불통: {shown.error}</p>}
              <Detail
                detail={shown.lookup.detail}
                session={session}
                busy={busy}
                onDecide={(decision, reason) => {
                  if (shown.lookup?.kind === 'found') decide(shown.lookup.detail, decision, reason)
                }}
              />
            </>
          )}
        </section>
      )}
    </section>
  )
}

function IncidentTable({
  rows,
  selected,
  onSelect,
}: {
  rows: IncidentRow[]
  selected: string | null
  onSelect: (incidentId: string) => void
}) {
  return (
    <table aria-label="인시던트 목록">
      <thead>
        <tr>
          <th>인시던트</th>
          <th>발생 시각</th>
          <th>기체</th>
          <th>실행 id</th>
          <th>단위</th>
          <th>실패 종류</th>
          <th>경로</th>
          <th>현장 설정 버전</th>
          <th>임무 버전</th>
          <th>판단</th>
          <th>보류</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.incidentId}
            className={[row.incidentId === selected ? 'selected' : '', row.held ? 'held' : ''].join(' ').trim() || undefined}
          >
            <td>
              <button className="link" aria-label={`${row.incidentId} 상세 보기`} onClick={() => onSelect(row.incidentId)}>
                {row.incidentId}
              </button>
            </td>
            <td>{row.at}</td>
            <td>{row.robotId}</td>
            <td>{row.executionId}</td>
            <td>{row.unitId}</td>
            <td>{row.failureClass ?? '-'}</td>
            <td>{row.route}</td>
            <td>{settingsVersion(row.siteSettingsVersion)}</td>
            <td>{missionVersion(row.missionVersion)}</td>
            <td>{judgement(row)}</td>
            <td>{row.held ? <strong>보류 중</strong> : '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

interface DetailProps {
  detail: IncidentDetail
  session: Session
  busy: boolean
  onDecide: (decision: HoldDecision, reason: string) => void
}

/**
 * 인시던트 상세(S4a 스펙 §8.2). 판단은 «사람의 판단» 에, 봉인 때의 근거는 «관측» 에 따로 둔다. 확인 결과는 코드 이름 그대로
 * 보인다. `NOT_REQUESTED` 를 «설비 확인 안 함» 으로 풀면, 설비 슬롯을 실제로 읽은 인시던트를 틀리게 읽힌다(S4a JSON 계약 §4).
 */
function Detail({ detail, session, busy, onDecide }: DetailProps) {
  const { intent, step } = detail
  const position = step.at === 0 ? `계획에 없음(계획 ${step.plan.length}개)` : `${step.at}/${step.plan.length}`
  return (
    <>
      <Judgement resolution={detail.resolution} withoutEvidence={detail.confirmedWithoutEvidence} />
      <section aria-label="관측" className="observed">
        <h4>관측(봉인 때 기록)</h4>
        <dl>
          <dt>발생 시각(호스트 시계)</dt>
          <dd>{detail.at}</dd>
          <dt>봉인한 실제 시각</dt>
          <dd>{detail.wallClockAt}</dd>
          <dt>기체</dt>
          <dd>{detail.robotId}</dd>
          <dt>실행</dt>
          <dd>
            {detail.executionId}(작업 지시 {detail.jobOrderId})
          </dd>
          <dt>단위</dt>
          <dd>{detail.unitId}</dd>
          <dt>단위의 지금 상태</dt>
          <dd>{detail.unitState ?? '모름(실행 없음)'}</dd>
          <dt>실패 종류</dt>
          <dd>{detail.failureClass ?? '-'}</dd>
          <dt>경로</dt>
          <dd>{detail.route}</dd>
          <dt>현장 설정 버전</dt>
          <dd>{settingsVersion(intent.siteSettingsVersion)}</dd>
          <dt>시간값</dt>
          <dd>
            근거 윈도우 앞 폭 {seconds(intent.evidenceBeforeSeconds)}, 뒤 폭 {seconds(intent.evidenceAfterSeconds)},
            inDoubtGrace {seconds(intent.inDoubtGraceSeconds)}, stallWindow {seconds(intent.stallWindowSeconds)}
          </dd>
          <dt>임무 버전</dt>
          <dd>
            {intent.workMasterId} {missionVersion(intent.missionVersion)}
          </dd>
          <dt>단계 위치</dt>
          <dd>
            {position}. 계획 {step.plan.join(' → ') || '-'}. 끝난 단위 {step.completed.join(', ') || '없음'}
          </dd>
          <dt>필요 근거 등급</dt>
          <dd>{detail.requiredEvidence}</dd>
          <dt>도달 근거 등급</dt>
          <dd>{detail.reachedEvidence}</dd>
          <dt>확인 결과(코드 이름)</dt>
          <dd>{detail.verification}</dd>
          {detail.preconditionSubjects.length > 0 && (
            <>
              <dt>위반된 사전 조건</dt>
              <dd>{detail.preconditionSubjects.join(', ')}</dd>
            </>
          )}
          <dt>파지(기대/관측)</dt>
          <dd>
            {detail.expectedHold ?? '-'} / {detail.observedHold}
          </dd>
          {detail.effectMismatch !== null && (
            <>
              <dt>효과와 관측의 어긋남</dt>
              <dd>{detail.effectMismatch}</dd>
            </>
          )}
          {detail.linkBroken && (
            <>
              <dt>연결</dt>
              <dd>연결이 끊긴 채 돌던 중</dd>
            </>
          )}
        </dl>
        <h4>결함</h4>
        {detail.fault === null ? (
          <p>없음(결함 없이 실패를 알림)</p>
        ) : (
          <FaultCard fault={detail.fault} label="이 단위의 결함" />
        )}
        <h4>실행을 막던 결함(blockedBy)</h4>
        {detail.blockedBy.length === 0 ? (
          <p>없음</p>
        ) : (
          detail.blockedBy.map((fault, index) => (
            <FaultCard key={index} fault={fault} label={`실행을 막던 결함 ${index + 1}`} />
          ))
        )}
        <h4>
          근거 윈도우(앞 {seconds(intent.evidenceBeforeSeconds)}, 뒤 {seconds(intent.evidenceAfterSeconds)})
        </h4>
        {detail.windowTruncated && <p>윈도우 밖이라 버린 관측이 있습니다</p>}
        {detail.evidenceWindow.length === 0 ? (
          <p>윈도우 안의 관측이 없습니다</p>
        ) : (
          <table aria-label="근거 윈도우">
            <thead>
              <tr>
                <th>순번</th>
                <th>시각</th>
                <th>종류</th>
                <th>내용</th>
                <th>출처</th>
              </tr>
            </thead>
            <tbody>
              {detail.evidenceWindow.map((event, index) => (
                <tr key={`${event.sequence}-${index}`}>
                  <td>{event.sequence}</td>
                  <td>{event.occurredAt}</td>
                  <td>{event.kind}</td>
                  <td>{event.detail}</td>
                  <td>{event.local ? '미들웨어 기록' : '현장 관측'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {detail.held && <DecisionForm key={detail.incidentId} detail={detail} session={session} busy={busy} onDecide={onDecide} />}
    </>
  )
}

/**
 * 사람의 판단(운영 관리 화면 설계 제안 §9). 관측과 다른 모양으로, 판단자와 판단한 실제 시각을 함께 보인다. 판단이 없으면 이
 * 상세의 값은 모두 관측이다.
 */
function Judgement({ resolution, withoutEvidence }: { resolution: IncidentResolution | null; withoutEvidence: boolean }) {
  if (resolution === null) return <p>사람의 판단 없음. 아래 값은 모두 관측입니다</p>
  return (
    <section aria-label="사람의 판단" className="asserted">
      <p>
        사람이 판단함: {resolution.decidedBy.id}, {resolution.wallClockAt}
      </p>
      <p>
        결정 {DECISION_LABEL[resolution.decision] ?? resolution.decision}({resolution.decision}), 호스트 시각{' '}
        {resolution.at}
      </p>
      {withoutEvidence && (
        <p>
          <strong>설비 근거 없이 완료 확인</strong>
        </p>
      )}
    </section>
  )
}

function FaultCard({ fault, label }: { fault: FaultDetail; label: string }) {
  return (
    <dl aria-label={label}>
      <dt>분류</dt>
      <dd>
        {fault.failureClass}({fault.errorType})
      </dd>
      <dt>조치 힌트</dt>
      <dd>{fault.errorHint === '' ? '없음' : fault.errorHint}</dd>
      {fault.vendorDetail !== '' && (
        <>
          <dt>벤더 원문</dt>
          <dd>{fault.vendorDetail}</dd>
        </>
      )}
      <dt>참조</dt>
      <dd>{fault.references.map((ref) => `${ref.key}=${ref.value}`).join(', ') || '없음'}</dd>
      <dt>지금 태스크 계속</dt>
      <dd>{fault.canContinueCurrentTask ? '가능' : '불가'}</dd>
      <dt>새 태스크 받기</dt>
      <dd>{fault.canAcceptNewTask ? '가능' : '불가'}</dd>
      <dt>유지</dt>
      <dd>
        {fault.activeUntilKind}
        {fault.activeUntilTime !== '' && ` ${fault.activeUntilTime}`}
      </dd>
    </dl>
  )
}

interface DecisionProps {
  detail: IncidentDetail
  session: Session
  busy: boolean
  onDecide: (decision: HoldDecision, reason: string) => void
}

/**
 * 운영자 판단(S4a 스펙 §8.3). 보류 중인 단위에만 있고 운영자 모드에서만 조작한다. 판단자는 운영 서비스가 행위자로 정하므로
 * 화면이 고르지 않는다(ADR 43). 사유는 조작 기록에만 남는다.
 */
function DecisionForm({ detail, session, busy, onDecide }: DecisionProps) {
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  if (session.mode !== 'operator') return <p>보류 중입니다. 운영자 판단은 운영자 모드에서 합니다</p>

  const send = (decision: HoldDecision) => {
    if (reason.trim() === '') {
      setProblem('판단 사유를 넣으십시오')
      return
    }
    setProblem(null)
    onDecide(decision, reason.trim())
    setReason('')
  }

  return (
    <form aria-label="운영자 판단" onSubmit={(event) => event.preventDefault()}>
      <p>
        {detail.executionId}/{detail.unitId} 보류를 판단합니다. 판단자는 {session.user} 입니다
      </p>
      <label>
        판단 사유
        <input value={reason} onChange={(event) => setReason(event.target.value)} />
      </label>{' '}
      <span className="actions">
        {DECISIONS.map((decision) => (
          <button key={decision} type="button" disabled={busy} onClick={() => send(decision)}>
            {DECISION_LABEL[decision]}
          </button>
        ))}
      </span>
      {problem !== null && <p role="alert">{problem}</p>}
    </form>
  )
}

/**
 * 판단의 결과(S4a JSON 계약 §9.5). picasso 결과 이름(`outcome`)으로 가른다. 응답 없음은 운영 서비스가 인시던트를 다시 읽어
 * 대조한 결과를 보이고, 확인되기 전에는 판단이 섰다고 보이지 않는다.
 */
export function DecisionNotice({ what, sent }: { what: string; sent: Delivered<HoldResolveOutcome> }) {
  return (
    <p role="status" aria-label="판단 결과">
      {what}: {describeDecision(sent)}
    </p>
  )
}

function describeDecision(sent: Delivered<HoldResolveOutcome>): string {
  if (sent.kind === 'refused') return `보내지 않음(${kindLabel(sent.refusal.error)}). ${sent.refusal.detail}`
  if (sent.kind === 'unknown') return `결과 모름(${sent.cause}). 인시던트 목록에서 확인하십시오`
  const { outcome, answer, rejection, confirmation } = sent.outcome
  switch (outcome) {
    case 'Resolved':
      return answer?.incidentId ? `판단이 섰습니다(${answer.incidentId})` : '판단이 섰습니다'
    case 'NotHeld':
      return '보류 단위가 아닙니다'
    case 'Refused':
      return answer?.detail ? `에이전트 판단은 거부됩니다. ${answer.detail}` : '에이전트 판단은 거부됩니다'
  }
  if (rejection !== null) return rejectionText('실행 호스트가 거부함', rejection)
  switch (confirmation) {
    case 'CONFIRMED_APPLIED':
      return '응답은 없었으나 다시 읽어 보니 판단이 붙음'
    case 'CONFIRMED_NOT_APPLIED':
      return '응답 없음. 다시 읽어 보니 판단이 붙지 않음. 늦게 붙을 수 있으니 인시던트 목록에서 확인하십시오'
    default:
      return '응답 없음. 판단이 붙었을 수 있음. 다시 읽지도 못해 확인하지 못했습니다. 인시던트 목록에서 확인하십시오'
  }
}
```

- [ ] **Step 2: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task4.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task4.patch"
```

```diff
diff --git a/ui/src/App.tsx b/ui/src/App.tsx
index 21e6e81..7deaf43 100644
--- a/ui/src/App.tsx
+++ b/ui/src/App.tsx
@@ -3,6 +3,7 @@ import { fetchAdapters, fetchOperations, fetchProfiles, fetchRobots, fetchSiteSe
 import type { AdapterListView, OperationRecord, ProfileListView, RobotListView, Session, SiteSettingsView } from './api'
 import { AREAS } from './areas'
 import type { AreaId } from './areas'
+import { FaultPanel } from './components/FaultPanel'
 import { HistoryArea } from './components/HistoryArea'
 import { MissionsArea } from './components/MissionsArea'
 import { ModeSwitch } from './components/ModeSwitch'
@@ -94,12 +95,15 @@ export default function App() {
           />
         )}
         {current.id === 'site' && (
-          <SiteArea
-            view={settings}
-            opsError={opsError}
-            session={session}
-            onChanged={() => setTick((value) => value + 1)}
-          />
+          <>
+            <SiteArea
+              view={settings}
+              opsError={opsError}
+              session={session}
+              onChanged={() => setTick((value) => value + 1)}
+            />
+            <FaultPanel robots={view} session={session} onChanged={() => setTick((value) => value + 1)} />
+          </>
         )}
         {current.id === 'missions' && (
           <MissionsArea session={session} onChanged={() => setTick((value) => value + 1)} />
diff --git a/ui/src/api.ts b/ui/src/api.ts
index 2309adc..b90b3cd 100644
--- a/ui/src/api.ts
+++ b/ui/src/api.ts
@@ -718,11 +718,14 @@ export interface DraftSaved {
   draft: DraftView
 }
 
-/** 호스트가 4xx 로 막은 쓰기(S3b JSON 계약 §10.1). 200 본문에 실린다. */
+/**
+ * 호스트가 4xx 로 막은 쓰기(S3b JSON 계약 §10.1). 200 본문에 실린다. 4xx 본문을 `{error, detail}` 로 읽지 못하면(스프링 기본
+ * 415 등) 두 칸이 null 이다(S4a JSON 계약 §9.1).
+ */
 export interface HostRejection {
   status: number
-  error: string
-  detail: string
+  error: string | null
+  detail: string | null
 }
 
 /**
@@ -794,3 +797,231 @@ export const activateMissionDraft = (session: Session, workMasterId: string, dra
   })
 export const writeCellSignal = (session: Session, name: string, value: string) =>
   postMission<SignalWriteOutcome>(`/api/cell/signals/${encodeURIComponent(name)}`, session, { value })
+
+/** 사람이 인시던트의 단위에 낸 판단(S4a JSON 계약 §3). `at` 은 호스트 시계, `wallClockAt` 은 실제 시각이다. */
+export interface IncidentResolution {
+  decision: HoldDecision
+  at: string
+  wallClockAt: string
+  decidedBy: { id: string; kind: string }
+}
+
+/** 인시던트 줄의 결함 요약(S4a JSON 계약 §3). `errorHint` 는 빈 문자열일 수 있다. */
+export interface FaultSummary {
+  failureClass: string
+  errorType: string
+  errorHint: string
+}
+
+/**
+ * 인시던트 한 줄(S4a JSON 계약 §3, 19칸). `unresolved` 는 봉인 때 한 번 정해지고 판단 뒤에도 그대로다. «미해결» 은
+ * `unresolved` 이고 `resolution` 이 없을 때이고, «보류 중» 강조는 `held` 만 본다. `missionVersion` 이 null 이면 코드 정의다.
+ */
+export interface IncidentRow {
+  incidentId: string
+  executionId: string
+  jobOrderId: string
+  robotId: string
+  unitId: string
+  at: string
+  failureClass: string | null
+  route: string
+  missionVersion: number | null
+  siteSettingsVersion: number | null
+  evidenceBeforeSeconds: number
+  evidenceAfterSeconds: number
+  inDoubtGraceSeconds: number | null
+  stallWindowSeconds: number | null
+  unresolved: boolean
+  resolution: IncidentResolution | null
+  fault: FaultSummary | null
+  held: boolean
+  confirmedWithoutEvidence: boolean
+}
+
+/** 운영 서비스의 `GET /api/incidents`(S4a JSON 계약 §9.2). `incidents` 는 최신부터이고 `total` 은 자르기 전의 수다. */
+export interface IncidentsView {
+  instanceId: string
+  total: number
+  incidents: IncidentRow[]
+}
+
+/** 결함 원문(S4a JSON 계약 §4, 9칸). `vendorDetail`·`errorHint`·`activeUntilTime` 은 빈 문자열일 수 있다. */
+export interface FaultDetail {
+  failureClass: string
+  errorType: string
+  vendorDetail: string
+  errorHint: string
+  references: { key: string; value: string }[]
+  canContinueCurrentTask: boolean
+  canAcceptNewTask: boolean
+  activeUntilKind: string
+  activeUntilTime: string
+}
+
+/** 근거 윈도우 안의 관측 하나(S4a JSON 계약 §4). `local` 이면 미들웨어가 적은 관측이다. */
+export interface EvidenceEvent {
+  sequence: number
+  occurredAt: string
+  kind: string
+  detail: string
+  local: boolean
+}
+
+/** 인시던트의 의도 전체(S4a JSON 계약 §4, 17칸). 버전과 시간값은 목록 줄과 같은 뜻이다. */
+export interface IncidentIntent {
+  workMasterId: string
+  orderVersion: number
+  orderParameters: Record<string, string>
+  materials: { materialDefinitionId: string; quantity: number }[]
+  equipment: { id: string; equipmentUse: string; properties: Record<string, string> }[]
+  capabilityMaxEvidence: string
+  evidenceBeforeSeconds: number
+  evidenceAfterSeconds: number
+  skillType: string
+  unitParameters: Record<string, string>
+  source: string | null
+  destination: string | null
+  expectedIdentity: string | null
+  missionVersion: number | null
+  siteSettingsVersion: number | null
+  inDoubtGraceSeconds: number | null
+  stallWindowSeconds: number | null
+}
+
+/**
+ * 운영 서비스의 `GET /api/incidents/{incidentId}`(S4a JSON 계약 §4, 29칸). `unitState` 는 그 단위의 지금 상태이고 나머지 근거
+ * 칸은 봉인 때의 관측이다.
+ */
+export interface IncidentDetail {
+  instanceId: string
+  incidentId: string
+  executionId: string
+  jobOrderId: string
+  robotId: string
+  unitId: string
+  at: string
+  wallClockAt: string
+  failureClass: string | null
+  route: string
+  unresolved: boolean
+  resolution: IncidentResolution | null
+  held: boolean
+  confirmedWithoutEvidence: boolean
+  unitState: string | null
+  fault: FaultDetail | null
+  blockedBy: FaultDetail[]
+  requiredEvidence: string
+  reachedEvidence: string
+  verification: string
+  step: { at: number; plan: string[]; completed: string[] }
+  evidenceWindow: EvidenceEvent[]
+  windowTruncated: boolean
+  preconditionSubjects: string[]
+  expectedHold: string | null
+  observedHold: string
+  effectMismatch: string | null
+  linkBroken: boolean
+  intent: IncidentIntent
+}
+
+/** 상세 읽기의 결과. 실행 호스트가 그 id 를 모르면(재기동으로 사라진 id 포함) `missing` 이다(S4a JSON 계약 §9.3). */
+export type IncidentLookup = { kind: 'found'; detail: IncidentDetail } | { kind: 'missing'; detail: string }
+
+export const fetchIncidents = (session: Session) => getHostJson<IncidentsView>('/api/incidents', session)
+
+/** 404 `INCIDENT_NOT_FOUND` 만 없음이다. 그 밖의 실패(503 `HOST_SILENT` 포함)는 못 읽음이라 던진다. */
+export async function fetchIncident(session: Session, incidentId: string): Promise<IncidentLookup> {
+  const response = await fetch(`/api/incidents/${encodeURIComponent(incidentId)}`, { headers: actorHeaders(session) })
+  if (response.ok) return { kind: 'found', detail: (await response.json()) as IncidentDetail }
+  const body = (await response.json().catch(() => null)) as Partial<PreRejection> | null
+  const detail = typeof body?.detail === 'string' && body.detail !== '' ? body.detail : `운영 서비스 응답 ${response.status}`
+  if (response.status === 404 && body?.error === 'INCIDENT_NOT_FOUND') return { kind: 'missing', detail }
+  throw new Error(detail)
+}
+
+/** 화면이 고르게 하는 장애 종류 둘과 연결 상태 셋(S4a JSON 계약 §1.1). 그 밖의 값은 현장이 거부하므로 두지 않는다. */
+export type FaultKind = 'SKILL_EXECUTION_FAILED' | 'CONNECTION'
+export type ConnectionFaultState = 'OFFLINE' | 'CONNECTION_BROKEN' | 'ONLINE'
+
+/** 현장이 받아들인 장애 주입의 본문(S4a JSON 계약 §1.2). */
+export type SiteFaultAnswer =
+  | { robotId: string; kind: 'SKILL_EXECUTION_FAILED'; taskId: string; taskState: string; raised: boolean }
+  | { robotId: string; kind: 'CONNECTION'; state: string; changed: boolean }
+
+/**
+ * 장애 주입의 200 응답(S4a JSON 계약 §9.4). 재조회하지 않으므로 `confirmation` 은 늘 null 이다. 현장의 거부는 `rejection`
+ * 이고, 현장 불통과 호스트 불통은 둘 다 `NO_RESPONSE` 다.
+ */
+export interface FaultInjectionOutcome {
+  requestId: string
+  robotId: string
+  kind: string
+  state: string | null
+  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
+  confirmation: null
+  fault: SiteFaultAnswer | null
+  rejection: HostRejection | null
+}
+
+export type HoldDecision = 'CONFIRM_DONE' | 'REWORK'
+
+/** 실행 호스트 판단의 200 본문(S4a JSON 계약 §5.2). */
+export interface HoldResolveAnswer {
+  result: string
+  detail: string | null
+  incidentId: string | null
+  requestId: string | null
+}
+
+/**
+ * 운영자 판단의 200 응답(S4a JSON 계약 §9.5). 화면은 `result` 가 아니라 `outcome`(picasso 이름 그대로)으로 가른다. `outcome`
+ * 은 호스트가 200 으로 답했을 때만 있다.
+ */
+export interface HoldResolveOutcome {
+  requestId: string
+  executionId: string
+  unitId: string
+  decision: HoldDecision
+  result: 'SUCCEEDED' | 'REJECTED' | 'NO_RESPONSE'
+  confirmation: 'CONFIRMED_APPLIED' | 'CONFIRMED_NOT_APPLIED' | null
+  outcome: 'Resolved' | 'NotHeld' | 'Refused' | null
+  answer: HoldResolveAnswer | null
+  rejection: HostRejection | null
+}
+
+/** 사전 거부(400 `REASON_REQUIRED`·403 `MODE_NOT_ALLOWED` 등)는 `refused` 다(S4a JSON 계약 §9.1). */
+const postOps = <T>(path: string, session: Session, body: unknown) =>
+  deliver<T>(path, {
+    method: 'POST',
+    headers: { ...actorHeaders(session), 'Content-Type': 'application/json' },
+    body: JSON.stringify(body),
+  })
+
+/** 연결 상태일 때만 `state` 를 싣는다. 사유는 앞뒤 공백을 뗀 값이다. */
+export const injectFault = (
+  session: Session,
+  robotId: string,
+  kind: FaultKind,
+  state: ConnectionFaultState | null,
+  reason: string,
+) =>
+  postOps<FaultInjectionOutcome>(
+    '/api/faults',
+    session,
+    kind === 'CONNECTION' ? { robotId, kind, state, reason } : { robotId, kind, reason },
+  )
+
+/** 승인자는 싣지 않는다. 운영 서비스가 `X-Ops-User` 로 정한다(S4a JSON 계약 §9.5, ADR 43). */
+export const resolveHold = (
+  session: Session,
+  executionId: string,
+  unitId: string,
+  decision: HoldDecision,
+  reason: string,
+) =>
+  postOps<HoldResolveOutcome>(
+    `/api/executions/${encodeURIComponent(executionId)}/units/${encodeURIComponent(unitId)}/resolve`,
+    session,
+    { decision, reason },
+  )
diff --git a/ui/src/components/JobOrderNotice.tsx b/ui/src/components/JobOrderNotice.tsx
index 128fc04..8064b7c 100644
--- a/ui/src/components/JobOrderNotice.tsx
+++ b/ui/src/components/JobOrderNotice.tsx
@@ -9,7 +9,7 @@ interface Props {
 
 /**
  * 작업 지시 제출의 결과(S3a 스펙 §8·§9.1). 기존 조작 결과([OutcomeNotice])와 모양이 달라 따로 읽는다. 실행 호스트의 판단은
- * 200 본문의 `outcome` 에 있으므로 상태 코드로 결과를 가르지 않는다. 운영 서비스가 먼저 막은 것(400·403)은 «막힘», 그 밖의
+ * 200 본문의 `outcome` 에 있으므로 상태 코드로 결과를 구분하지 않는다. 운영 서비스가 먼저 막은 것(400·403)은 «막힘», 그 밖의
  * 비정상은 요청이 호스트까지 갔는지 모르므로 «결과 모름» 이다. 응답 없음은 재조회로 확인되기 전에는 성공으로도 실패로도
  * 보이지 않는다.
  */
diff --git a/ui/src/components/MissionNotice.tsx b/ui/src/components/MissionNotice.tsx
index d55abf3..428e917 100644
--- a/ui/src/components/MissionNotice.tsx
+++ b/ui/src/components/MissionNotice.tsx
@@ -9,7 +9,7 @@ import type {
   MissionOperationOutcome,
   MissionValidationReply,
 } from '../api'
-import { kindLabel, mockRunVerdict } from '../labels'
+import { kindLabel, mockRunVerdict, rejectionLabel } from '../labels'
 import { FindingCard } from './FindingCard'
 import { MockRunReport } from './MockRunReport'
 
@@ -136,7 +136,7 @@ function operated<T>(
     return (
       <>
         <p>
-          {what}: 실행 호스트가 거부함({kindLabel(operation.rejection.error)})
+          {what}: 실행 호스트가 거부함({rejectionLabel(operation.rejection.error)})
         </p>
         <p>{operation.rejection.detail}</p>
       </>
diff --git a/ui/src/components/MissionsArea.test.tsx b/ui/src/components/MissionsArea.test.tsx
index 31c4e9a..af7f6c0 100644
--- a/ui/src/components/MissionsArea.test.tsx
+++ b/ui/src/components/MissionsArea.test.tsx
@@ -4,6 +4,7 @@ import { afterEach, describe, expect, it, vi } from 'vitest'
 import type { Finding, MissionOperationOutcome, Session } from '../api'
 import {
   ARRIVAL_WAIT,
+  ARRIVAL_WAIT_HOLD,
   DATA_V1,
   MISSION_PATH,
   draftRow,
@@ -146,6 +147,8 @@ describe('임무·정책 영역', () => {
 
     await click('랙 도착 대기 템플릿 불러오기')
     expect(editor().value).toBe(ARRIVAL_WAIT)
+    await click('운영자 보류 대기 템플릿 불러오기')
+    expect(editor().value).toBe(ARRIVAL_WAIT_HOLD)
     await click('데이터 정의 템플릿 불러오기')
     expect(editor().value).toBe(DATA_V1)
   })
diff --git a/ui/src/components/OperationsArea.tsx b/ui/src/components/OperationsArea.tsx
index 8a61b83..cc64555 100644
--- a/ui/src/components/OperationsArea.tsx
+++ b/ui/src/components/OperationsArea.tsx
@@ -1,9 +1,10 @@
 import { useEffect, useRef, useState } from 'react'
-import { checkEligibility, fetchCell, fetchExecutions, submitJobOrder, writeCellSignal } from '../api'
+import { checkEligibility, fetchCell, fetchExecutions, fetchIncidents, submitJobOrder, writeCellSignal } from '../api'
 import type {
   CellView,
   Delivered,
   ExecutionsView,
+  IncidentsView,
   JobOrderForm,
   JobOrderOutcome,
   Session,
@@ -17,6 +18,7 @@ import { EligibilityTable } from './EligibilityTable'
 import type { EligibilityRead } from './EligibilityTable'
 import { ExecutionList } from './ExecutionList'
 import type { HostRead } from './ExecutionList'
+import { IncidentSection } from './IncidentSection'
 import { JobOrderFormView } from './JobOrderFormView'
 import { JobOrderNotice } from './JobOrderNotice'
 import { SignalNotice } from './SignalNotice'
@@ -32,9 +34,10 @@ const NO_ELIGIBILITY: EligibilityRead = { view: null, refusal: null, error: null
 const message = (error: unknown) => (error instanceof Error ? error.message : String(error))
 
 /**
- * «운영» 영역(S3a 스펙 §9). 작업 지시 폼, 기체별 배정 가능 표, 실행 목록, 셀 대역 표시와 신호 조작(S3b 스펙 §8).
+ * «운영» 영역(S3a 스펙 §9). 작업 지시 폼, 기체별 배정 가능 표, 실행 목록, 인시던트와 운영자 판단(S4a 스펙 §8.2·§8.3),
+ * 셀 대역 표시와 신호 조작(S3b 스펙 §8).
  *
- * 실행 목록·셀·배정 가능은 실행 호스트를 거친다. 그래서 App 의 다섯 조회(`Promise.all`)와 따로, 이 영역이 열려 있을 때만
+ * 실행 목록·인시던트·셀·배정 가능은 실행 호스트를 거친다. 그래서 App 의 다섯 조회(`Promise.all`)와 따로, 이 영역이 열려 있을 때만
  * 읽는다(S3a 스펙 §9.3). 호스트가 멈춰도 다섯 조회가 직전 값이 되지 않게 하기 위해서다. 셋은 서로도 따로 실패하고, 못 읽으면
  * 직전 값을 지우지 않고 불통을 표시한다.
  *
@@ -47,6 +50,7 @@ export function OperationsArea({ session, onChanged }: Props) {
   const [tick, setTick] = useState(0)
   const [executions, setExecutions] = useState<HostRead<ExecutionsView>>({ value: null, error: null })
   const [cell, setCell] = useState<HostRead<CellView>>({ value: null, error: null })
+  const [incidents, setIncidents] = useState<HostRead<IncidentsView>>({ value: null, error: null })
   const [eligibility, setEligibility] = useState<EligibilityRead>(NO_ELIGIBILITY)
   const [draft, setDraft] = useState<JobOrderDraft>(EMPTY_DRAFT)
   const [problem, setProblem] = useState<string | null>(null)
@@ -60,7 +64,7 @@ export function OperationsArea({ session, onChanged }: Props) {
     return () => clearInterval(timer)
   }, [])
 
-  // 신호 조작 뒤 pump 가 돈 다음에 한 번 더 읽는 타이머. 영역을 닫으면 치우고, 닫은 뒤에 끝난 조작은 타이머를 걸지 않는다.
+  // 신호 조작과 운영자 판단 뒤 pump 가 돈 다음에 한 번 더 읽는 타이머. 영역을 닫으면 치우고, 닫은 뒤에 끝난 조작은 타이머를 걸지 않는다.
   const mounted = useRef(true)
   const settle = useRef<ReturnType<typeof setTimeout> | null>(null)
   useEffect(() => {
@@ -74,7 +78,7 @@ export function OperationsArea({ session, onChanged }: Props) {
 
   useEffect(() => {
     let alive = true
-    // 둘을 묶지 않는다. 셀 대역만 못 읽어도 실행 목록은 새 값이어야 한다.
+    // 셋을 묶지 않는다. 셀 대역만 못 읽어도 실행 목록은 새 값이어야 한다.
     fetchExecutions(session)
       .then((value) => {
         if (alive) setExecutions({ value, error: null })
@@ -89,6 +93,13 @@ export function OperationsArea({ session, onChanged }: Props) {
       .catch((error: unknown) => {
         if (alive) setCell((previous) => ({ ...previous, error: message(error) }))
       })
+    fetchIncidents(session)
+      .then((value) => {
+        if (alive) setIncidents({ value, error: null })
+      })
+      .catch((error: unknown) => {
+        if (alive) setIncidents((previous) => ({ ...previous, error: message(error) }))
+      })
     return () => {
       alive = false
     }
@@ -142,7 +153,19 @@ export function OperationsArea({ session, onChanged }: Props) {
   }
 
   // 바뀐 값은 실행 호스트의 다음 pump 부터 보인다. 곧바로 한 번, pump 가 돈 뒤 한 번 더 읽는다. 조작이 잇따르면 마지막 조작
-  // 뒤의 한 번이 앞의 것을 대신한다.
+  // 뒤의 한 번이 앞의 것을 대신한다. 신호 조작과 운영자 판단이 같이 쓴다.
+  const rereadNowAndAfterPump = () => {
+    setTick((value) => value + 1)
+    if (settle.current !== null) clearTimeout(settle.current)
+    settle.current = mounted.current
+      ? setTimeout(() => {
+          settle.current = null
+          setTick((value) => value + 1)
+        }, SIGNAL_SETTLE_MS)
+      : null
+    onChanged()
+  }
+
   const writeSignal = (name: string, value: string) => {
     const what = `${name} ${value === 'true' ? '켜기' : '끄기'}`
     setSignalBusy(true)
@@ -150,15 +173,7 @@ export function OperationsArea({ session, onChanged }: Props) {
       .then((sent) => setSignalLast({ what, sent }))
       .finally(() => {
         setSignalBusy(false)
-        setTick((value) => value + 1)
-        if (settle.current !== null) clearTimeout(settle.current)
-        settle.current = mounted.current
-          ? setTimeout(() => {
-              settle.current = null
-              setTick((value) => value + 1)
-            }, SIGNAL_SETTLE_MS)
-          : null
-        onChanged()
+        rereadNowAndAfterPump()
       })
   }
 
@@ -189,6 +204,7 @@ export function OperationsArea({ session, onChanged }: Props) {
         <h2>실행</h2>
         <ExecutionList read={executions} highlight={submitted} />
       </section>
+      <IncidentSection read={incidents} session={session} tick={tick} onDecided={rereadNowAndAfterPump} />
       <section aria-label="셀 대역">
         <h2>셀 대역</h2>
         {signalLast !== null && <SignalNotice what={signalLast.what} sent={signalLast.sent} />}
diff --git a/ui/src/components/SignalNotice.tsx b/ui/src/components/SignalNotice.tsx
index e839dc1..0c8c6b1 100644
--- a/ui/src/components/SignalNotice.tsx
+++ b/ui/src/components/SignalNotice.tsx
@@ -1,5 +1,5 @@
 import type { Delivered, SignalWriteOutcome } from '../api'
-import { kindLabel } from '../labels'
+import { kindLabel, rejectionText } from '../labels'
 
 interface Props {
   /** 어느 신호를 어떻게 썼는지. 예: `rack_present 켜기` */
@@ -25,7 +25,7 @@ function describe(sent: Delivered<SignalWriteOutcome>): string {
   const { result, confirmation, signal, rejection } = sent.outcome
   if (result === 'SUCCEEDED') return `반영됨(값 ${signal?.value ?? sent.outcome.value})`
   if (result === 'REJECTED' && rejection !== null) {
-    return `현장이 거부함(${kindLabel(rejection.error)}). ${rejection.detail}`
+    return rejectionText('현장이 거부함', rejection)
   }
   switch (confirmation) {
     case 'CONFIRMED_APPLIED':
diff --git a/ui/src/labels.ts b/ui/src/labels.ts
index 248d149..fb946a2 100644
--- a/ui/src/labels.ts
+++ b/ui/src/labels.ts
@@ -1,6 +1,8 @@
 import type {
   CommissioningState,
   Connection,
+  FaultKind,
+  HoldDecision,
   HostSubmitResult,
   MockRunFailure,
   MockRunView,
@@ -81,6 +83,13 @@ export const KIND_LABEL: Record<string, string> = {
   SIGNAL_BAD_REQUEST: '신호 값 형식 오류',
   UNKNOWN_SIGNAL: '모르는 신호',
   SAFETY_SIGNAL_READ_ONLY: '안전 신호는 쓸 수 없음',
+  // 장애 주입의 현장 거부와 운영 서비스의 사전 거부(S4a JSON 계약 §1.3·§9.7).
+  NO_RUNNING_TASK: '진행 중 태스크 없음',
+  UNSUPPORTED_FAULT: '받지 않는 장애 종류',
+  FAULT_REFUSED: 'mimic 엔진이 강제를 거부함',
+  UNSUPPORTED_MEDIA_TYPE: 'JSON 이 아닌 본문',
+  FAULT_BAD_REQUEST: '장애 주입 본문 오류',
+  RESOLVE_BAD_REQUEST: '판단 본문 오류',
 }
 
 /** «시운전» 칸(P2·S1d 스펙 §8.5). 연결 칸과 합치지 않는다. */
@@ -108,6 +117,13 @@ export const TEST_REQUEST_LABEL: Record<TestRequestState, string> = {
 
 export const kindLabel = (kind: string) => KIND_LABEL[kind] ?? kind
 
+/** 호스트·현장 거부의 이름. 거부 본문을 못 읽어 이름이 없으면(null) 이유 없음이다. */
+export const rejectionLabel = (error: string | null) => (error === null ? '이유 없음' : kindLabel(error))
+
+/** 거부 알림 한 줄. 사람이 읽는 문장(detail)이 없으면 이름만 적는다. */
+export const rejectionText = (who: string, rejection: { error: string | null; detail: string | null }) =>
+  `${who}(${rejectionLabel(rejection.error)})${rejection.detail ? `. ${rejection.detail}` : ''}`
+
 /** 실행 호스트의 스킬 적합(S3a 스펙 §9.1). `UNKNOWN` 은 기체 케이퍼빌리티를 못 물어본 것이다. */
 export const SKILL_FIT_LABEL: Record<SkillFit, string> = {
   FIT: '적합',
@@ -143,4 +159,17 @@ export function mockRunVerdict(run: MockRunView): string {
 export const TEMPLATE_LABEL: Record<string, string> = {
   DATA_V1: '데이터 정의 템플릿',
   ARRIVAL_WAIT: '랙 도착 대기 템플릿',
+  ARRIVAL_WAIT_HOLD: '운영자 보류 대기 템플릿',
+}
+
+/** 장애 주입 종류(S4a JSON 계약 §1.1). */
+export const FAULT_KIND_LABEL: Record<FaultKind, string> = {
+  SKILL_EXECUTION_FAILED: '스킬 실패',
+  CONNECTION: '연결 상태',
+}
+
+/** 운영자 판단 둘(S4a 스펙 §8.3). 버튼 이름이기도 하다. */
+export const DECISION_LABEL: Record<HoldDecision, string> = {
+  CONFIRM_DONE: '완료 확인',
+  REWORK: '재작업',
 }
diff --git a/ui/src/styles.css b/ui/src/styles.css
index c427a23..5f2d2f6 100644
--- a/ui/src/styles.css
+++ b/ui/src/styles.css
@@ -15,3 +15,7 @@ tr.selected { background: #e8ebf3; }
 .editor textarea, pre { font-family: ui-monospace, monospace; font-size: 13px; }
 pre { white-space: pre-wrap; border: 1px solid #ccc; padding: 8px; margin: 0; }
 .actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
+tr.held { background: #fbeee6; }
+tr.held strong { color: #b23a1d; }
+.asserted { border-left: 4px double #2b3f6b; padding-left: 8px; }
+.observed h4 { margin-bottom: 4px; }
diff --git a/ui/src/testing/fakeOps.ts b/ui/src/testing/fakeOps.ts
index 095965e..da9f71c 100644
--- a/ui/src/testing/fakeOps.ts
+++ b/ui/src/testing/fakeOps.ts
@@ -7,8 +7,13 @@ import type {
   DraftView,
   EligibilityView,
   ExecutionsView,
+  FaultInjectionOutcome,
   Finding,
+  HoldResolveOutcome,
   HostTimings,
+  IncidentDetail,
+  IncidentRow,
+  IncidentsView,
   MissionOverview,
   MissionTemplates,
   MockRunView,
@@ -45,6 +50,9 @@ export interface FakeOps {
   /** 임무 개요와 템플릿(S3b). 운영 서비스가 호스트 본문을 그대로 넘기는 모양이다. */
   mission: MissionOverview
   templates: MissionTemplates
+  /** 인시던트 목록과 상세(S4a). 상세 맵에 없는 id 는 404 `INCIDENT_NOT_FOUND` 다. */
+  incidents: IncidentsView
+  incidentDetails: Map<string, IncidentDetail>
   /** `POST /api/job-orders/eligibility` 의 답. 폼 거부(400)를 만들려면 [eligibilityStatus] 와 본문을 바꾼다. */
   eligibility: EligibilityView | PreRejection
   eligibilityStatus: number
@@ -62,7 +70,8 @@ export const MISSION_PATH = '/api/missions/PrepareSequencedRack'
 export const TEMPLATES_PATH = '/api/missions/templates/PrepareSequencedRack'
 
 /** 실행 호스트를 거치는 경로. 503 일 때 운영 서비스가 `HOST_SILENT` 를 싣는다(S3a JSON 계약 §9.4, S3b JSON 계약 §10.3). */
-const HOST_PATHS = new Set(['/api/executions', '/api/cell', MISSION_PATH, TEMPLATES_PATH])
+const HOST_PATHS = new Set(['/api/executions', '/api/cell', '/api/incidents', MISSION_PATH, TEMPLATES_PATH])
+const INCIDENT_PREFIX = '/api/incidents/'
 const ELIGIBILITY_PATH = '/api/job-orders/eligibility'
 
 /** 실행 목록. 기본은 실행이 없는 호스트 인스턴스 하나다. */
@@ -91,12 +100,14 @@ export function cellView(): CellView {
   }
 }
 
-/** 템플릿 두 정의의 글자. 시험은 글자 그대로 오가는지만 본다. */
+/** 템플릿 세 정의의 글자. 시험은 글자 그대로 오가는지만 본다. */
 export const DATA_V1 = '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["place"]}'
 export const ARRIVAL_WAIT =
   '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["rack-arrival", "place"]}'
+export const ARRIVAL_WAIT_HOLD =
+  '{"schemaVersion": 1, "workMasterId": "PrepareSequencedRack", "nodes": ["rack-arrival(hold)", "place"]}'
 
-/** 템플릿 둘(S3b JSON 계약 §4.2). */
+/** 템플릿 셋(S3b JSON 계약 §4.2, S4a JSON 계약 §6). */
 export function missionTemplates(): MissionTemplates {
   return {
     workMasterId: 'PrepareSequencedRack',
@@ -107,6 +118,11 @@ export function missionTemplates(): MissionTemplates {
         title: '랙 도착 대기(rack_present = true, 기한 120초, 기한 뒤 ABORTED)',
         definition: ARRIVAL_WAIT,
       },
+      {
+        id: 'ARRIVAL_WAIT_HOLD',
+        title: '랙 도착 대기(rack_present = true, 기한 20초, 기한 뒤 운영자 보류)',
+        definition: ARRIVAL_WAIT_HOLD,
+      },
     ],
   }
 }
@@ -333,6 +349,137 @@ export function outcome(partial: Partial<OperationOutcome>): OperationOutcome {
   }
 }
 
+/** 인시던트 목록(S4a JSON 계약 §3). 기본은 인시던트가 없는 호스트 인스턴스 하나다. */
+export function incidentsView(incidents: IncidentRow[] = []): IncidentsView {
+  return { instanceId: 'mw-1', total: incidents.length, incidents }
+}
+
+/**
+ * 인시던트 한 줄(S4a JSON 계약 §3). 기본은 보류 버전 1 의 대기 기한 보류 직후다(미해결, 보류 중, 판단 없음, 결함 없음).
+ */
+export function incidentRow(partial: Partial<IncidentRow> = {}): IncidentRow {
+  return {
+    incidentId: 'incident-1',
+    executionId: 'exec-1',
+    jobOrderId: 'JO-20261009-aaaaaaaa',
+    robotId: 'humanoid-01',
+    unitId: 'rack-arrival',
+    at: 'h10',
+    failureClass: 'SIGNAL_DEADLINE',
+    route: 'SIGNAL',
+    missionVersion: 3,
+    siteSettingsVersion: 2,
+    evidenceBeforeSeconds: 30,
+    evidenceAfterSeconds: 15,
+    inDoubtGraceSeconds: 60,
+    stallWindowSeconds: 300,
+    unresolved: true,
+    resolution: null,
+    fault: null,
+    held: true,
+    confirmedWithoutEvidence: false,
+    ...partial,
+  }
+}
+
+/** 인시던트 상세(S4a JSON 계약 §4). 기본은 [incidentRow] 기본과 같은 대기 기한 보류의 실측 값이다. */
+export function incidentDetail(partial: Partial<IncidentDetail> = {}): IncidentDetail {
+  return {
+    instanceId: 'mw-1',
+    incidentId: 'incident-1',
+    executionId: 'exec-1',
+    jobOrderId: 'JO-20261009-aaaaaaaa',
+    robotId: 'humanoid-01',
+    unitId: 'rack-arrival',
+    at: 'h10',
+    wallClockAt: 'w10',
+    failureClass: 'SIGNAL_DEADLINE',
+    route: 'SIGNAL',
+    unresolved: true,
+    resolution: null,
+    held: true,
+    confirmedWithoutEvidence: false,
+    unitState: 'OPERATOR_HOLD',
+    fault: null,
+    blockedBy: [],
+    requiredEvidence: 'E2',
+    reachedEvidence: 'E0',
+    verification: 'NOT_REQUESTED',
+    step: { at: 1, plan: ['rack-arrival', 'RACK-204.S01'], completed: [] },
+    evidenceWindow: [
+      { sequence: 41, occurredAt: 'h9', kind: 'CELL_SIGNAL', detail: 'signal rack_present at deadline: false', local: true },
+    ],
+    windowTruncated: false,
+    preconditionSubjects: [],
+    expectedHold: null,
+    observedHold: 'HOLD_KIND_UNSPECIFIED',
+    effectMismatch: null,
+    linkBroken: false,
+    intent: {
+      workMasterId: 'PrepareSequencedRack',
+      orderVersion: 1,
+      orderParameters: {},
+      materials: [],
+      equipment: [],
+      capabilityMaxEvidence: 'E2',
+      evidenceBeforeSeconds: 30,
+      evidenceAfterSeconds: 15,
+      skillType: 'equipment_wait',
+      unitParameters: { signal: 'rack_present', expect: 'true', deadlineSeconds: '20', onDeadline: 'OPERATOR_HOLD' },
+      source: null,
+      destination: null,
+      expectedIdentity: null,
+      missionVersion: 3,
+      siteSettingsVersion: 2,
+      inDoubtGraceSeconds: 60,
+      stallWindowSeconds: 300,
+    },
+    ...partial,
+  }
+}
+
+/** 장애 주입 200 본문(S4a JSON 계약 §9.4). 기본은 humanoid-01 의 스킬 실패를 현장이 받아들인 것이다. */
+export function faultInjected(partial: Partial<FaultInjectionOutcome> = {}): FaultInjectionOutcome {
+  return {
+    requestId: '6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a',
+    robotId: 'humanoid-01',
+    kind: 'SKILL_EXECUTION_FAILED',
+    state: null,
+    result: 'SUCCEEDED',
+    confirmation: null,
+    fault: {
+      robotId: 'humanoid-01',
+      kind: 'SKILL_EXECUTION_FAILED',
+      taskId: 'JO-1#RACK-204.S01',
+      taskState: 'RETRIABLE',
+      raised: true,
+    },
+    rejection: null,
+    ...partial,
+  }
+}
+
+/** 운영자 판단 200 본문(S4a JSON 계약 §9.5). 기본은 exec-1 의 rack-arrival 재작업이 선 것이다. */
+export function holdResolved(partial: Partial<HoldResolveOutcome> = {}): HoldResolveOutcome {
+  return {
+    requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
+    executionId: 'exec-1',
+    unitId: 'rack-arrival',
+    decision: 'REWORK',
+    result: 'SUCCEEDED',
+    confirmation: null,
+    outcome: 'Resolved',
+    answer: {
+      result: 'Resolved',
+      detail: null,
+      incidentId: 'incident-1',
+      requestId: '3f0c6c1e-6a0e-4f43-9a52-2a3b4a9e8d10',
+    },
+    rejection: null,
+    ...partial,
+  }
+}
+
 /** 브라우저처럼 ISO-8859-1 밖의 문자가 헤더에 있으면 보내기 전에 던지는 fetch 대역을 끼운다. */
 export function installFakeOps(
   view: RobotListView,
@@ -350,6 +497,8 @@ export function installFakeOps(
     cell: cellView(),
     mission: missionOverview(),
     templates: missionTemplates(),
+    incidents: incidentsView(),
+    incidentDetails: new Map(),
     eligibility: eligibilityView(),
     eligibilityStatus: 200,
     failing: new Set(),
@@ -368,11 +517,17 @@ export function installFakeOps(
       const method = init?.method ?? 'GET'
       fake.calls.push({ method, url, headers, body: init?.body ? JSON.parse(init.body as string) : undefined })
       if (fake.failing.has(url) && (method === 'GET' || url === ELIGIBILITY_PATH)) {
-        const body = HOST_PATHS.has(url)
+        const body = HOST_PATHS.has(url) || url.startsWith(INCIDENT_PREFIX)
           ? JSON.stringify({ error: 'HOST_SILENT', detail: '실행 호스트가 답하지 않는다: 응답 없음: ConnectException' })
           : ''
         return new Response(body, { status: 503 })
       }
+      if (method === 'GET' && url.startsWith(INCIDENT_PREFIX)) {
+        const found = fake.incidentDetails.get(decodeURIComponent(url.slice(INCIDENT_PREFIX.length)))
+        return found === undefined
+          ? new Response(JSON.stringify({ error: 'INCIDENT_NOT_FOUND', detail: '인시던트가 없다' }), { status: 404 })
+          : new Response(JSON.stringify(found), { status: 200 })
+      }
       if (method === 'GET') {
         const body =
           url === '/api/robots'
@@ -391,7 +546,9 @@ export function installFakeOps(
                         ? fake.mission
                         : url === TEMPLATES_PATH
                           ? fake.templates
-                          : []
+                          : url === '/api/incidents'
+                            ? fake.incidents
+                            : []
         return new Response(JSON.stringify(body), { status: 200 })
       }
       if (url === ELIGIBILITY_PATH) {
```

- [ ] **Step 3: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && cd ui && npm ci && npm test && npm run lint && npx tsc -b && npm run build
```
Expected: vitest 138 통과, lint·tsc·build 종료 0.

- [ ] **Step 4: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git add ui/src/components/FaultPanel.test.tsx ui/src/components/FaultPanel.tsx ui/src/components/IncidentSection.test.tsx ui/src/components/IncidentSection.tsx ui/src/App.tsx ui/src/api.ts ui/src/components/JobOrderNotice.tsx ui/src/components/MissionNotice.tsx ui/src/components/MissionsArea.test.tsx ui/src/components/OperationsArea.tsx ui/src/components/SignalNotice.tsx ui/src/labels.ts ui/src/styles.css ui/src/testing/fakeOps.ts && git commit -F - <<'EOF'
feat(ui): 장애 주입 패널과 인시던트 구역, 보류 단위의 운영자 판단

- 현장·자원 영역 옆 장애 주입 패널, 엔지니어 모드만 조작, 기체·종류·연결 상태·사유 입력과 결과 알림
- 운영 영역 인시던트 구역, 최신부터 나열과 보류 중 강조, 상세에 설정 버전·임무 버전·근거 윈도우·근거 등급·확인 결과
- 보류 단위의 운영자 판단(완료 확인·재작업, 사유 필수, 운영자 모드만), 사람이 판단함 표시와 설비 근거 없이 완료 확인 표시, 판단 뒤 곧바로와 잠시 뒤 다시 읽기

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4a-cmp.sh" ui/src/components/FaultPanel.test.tsx ui/src/components/FaultPanel.tsx ui/src/components/IncidentSection.test.tsx ui/src/components/IncidentSection.tsx ui/src/App.tsx ui/src/api.ts ui/src/components/JobOrderNotice.tsx ui/src/components/MissionNotice.tsx ui/src/components/MissionsArea.test.tsx ui/src/components/OperationsArea.tsx ui/src/components/SignalNotice.tsx ui/src/labels.ts ui/src/styles.css ui/src/testing/fakeOps.ts
```
Expected: 14개 모두 `같음`.

### Task 5: 통합 시험

**Files:**
- Create: `e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt`

- [ ] **Step 1: 새 파일 1개 복사**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && mkdir -p e2e/src/test/kotlin/dev/picasso/ops/e2e && cp "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/files/e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt" e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt
```

`e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt`:

```kotlin
package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.e2e.Commissioned.QUADRUPED
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S4a 완료 판정의 통합 쪽(S4a 스펙 §3·§10). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 만으로 장애를 넣고, 인시던트를 읽고,
 * 운영자 보류를 판단한다. 장애 주입은 운영 서비스, 실행 호스트, 현장 `POST /faults` 를 거쳐 mimic 엔진에 닿는다.
 *
 * 순서가 있고 새 스택에서 돈다. 셀 대역은 슬롯을 비우지 않으므로 스킬 실패(단계 1)는 아직 채운 적 없는 슬롯 `S01` 에서 먼저 한다.
 * 그래야 근거가 없어 단위가 FAILED 가 된다. 작업 지시마다 슬롯 하나를 쓰고 S01~S04 를 차례로 쓴다.
 *
 * ## 실패 모드
 *
 * `pick_place` 의 실패 모드는 기체별 시드 0 으로 추첨된다. 강제한 태스크는 완주 추첨을 하지 않으므로, 첫 `pick_place` 를 강제하면
 * 자연 실패는 13번째 `pick_place` 로 밀린다(S4a JSON 계약 §8). 이 시험의 `pick_place` 는 넷이다(S01 강제, S02, S03, S04).
 *
 * ## 시계
 *
 * 시간은 현장 가상 시계를 밀어서 간다([ExecutionDriver]). 예외가 둘이다. 단계 2a 는 시계를 밀지 않는다. 밀면 OFFLINE 동안에도
 * mimic 태스크가 계속 돌아 끝난다. 단계 2b 의 오래됨은 운영 서비스가 registry 의 실제 수신 시각으로 판정하므로 실제 시간 60초
 * 넘게 기다리고, 그동안 [REPORT] 씩 거듭 밀어 quadruped-01 의 생존 보고가 이어지게 한다.
 *
 * ## 보류
 *
 * 단위 수준 보류는 `ARRIVAL_WAIT_HOLD` 버전(기한 20초, 기한 뒤 운영자 보류)으로 만든다. 셀 대역 신호는 스스로 돌아가지 않으므로
 * 단계 5 는 `rack_present` 를 먼저 false 로 되돌린다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FaultIncidentTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MISSIONS = "/api/missions/$WORK_MASTER"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val PRESENTATION = "SEQ-IN-02.BIN-A"
        private const val WAIT_UNIT = "rack-arrival"

        /** `ARRIVAL_WAIT_HOLD` 의 대기 기한. */
        private val DEADLINE: Duration = Duration.ofSeconds(20)

        /** 생존 보고 주기(가상 30초)를 넘겨 미는 폭. 공용 시운전 픽스처와 같다. */
        private val REPORT: Duration = Duration.ofSeconds(31)

        /** 단계 2b 에서 밀기 사이에 실제 시간으로 쉬는 폭. 연결 기준 60초보다 훨씬 짧아 quadruped-01 이 신선하게 남는다. */
        private val STALE_POLL: Duration = Duration.ofSeconds(5)

        /** 단계 2b 의 오래됨을 기다리는 상한(실제 시간). 기준 60초에 읽기 주기와 여유를 더한다. */
        private val STALE_WAIT: Duration = Duration.ofSeconds(120)

        /** 시계를 밀지 않고 미들웨어가 연결 변화를 보기를 기다리는 상한(실제 시간). pump 는 250ms 다. */
        private val LINK_WAIT: Duration = Duration.ofSeconds(5)

        /** 단계 사이에 넘기는 값. */
        private var graspIncident = ""
        private var firstHold = ""
        private var holdExecution = ""

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }

        private fun rack(slot: String): String =
            """{"workMasterId":"$WORK_MASTER","slots":["$slot"],"material":"$MATERIAL","presentation":"$PRESENTATION"}"""
    }

    @Test
    @Order(1)
    fun `단계 1 데이터 정의 버전으로 도는 pick_place 에 스킬 실패를 넣으면 단위가 FAILED 이고 GRASP_FAILED 인시던트가 두 버전과 함께 보인다`() {
        activate("DATA_V1", "데이터 정의로 옮김", 1)
        val (jobOrderId, executionId) = submit(rack("RACK-204.S01"))

        // 작업 지시 직후의 태스크는 ACCEPTED 라 한 번 밀어야 RUNNING 이다(S4a JSON 계약 §8).
        driver.push(ExecutionDriver.STEP)
        assertEquals("RUNNING", unit(executionId, "RACK-204.S01")["state"].asText(), "${driver.execution(executionId)}")

        val injected = engineer("/api/faults", """{"robotId":"$HUMANOID","kind":"SKILL_EXECUTION_FAILED","reason":"스킬 실패 시연"}""")
        assertEquals("SUCCEEDED", injected["result"].asText(), "$injected")
        val fault = injected["fault"]
        assertEquals("$jobOrderId#RACK-204.S01", fault["taskId"].asText(), "$fault")
        assertEquals("RETRIABLE" to true, fault["taskState"].asText() to fault["raised"].asBoolean(), "$fault")

        // 현장은 엔진을 부른 뒤 밀지 않는다. 다음 시계 진행에서 미들웨어가 실패를 보고, 슬롯에 근거가 없어 단위가 FAILED 다.
        val settled = driver.drive(executionId)
        assertEquals("FAILED", settled["physicalState"].asText(), "$settled")
        assertEquals("FAILED", unit(executionId, "RACK-204.S01")["state"].asText(), "$settled")

        val list = incidents()
        val row = list["incidents"].single { it["executionId"].asText() == executionId }
        assertEquals(
            listOf(jobOrderId, HUMANOID, "RACK-204.S01", "GRASP_FAILED", "ROBOT"),
            listOf("jobOrderId", "robotId", "unitId", "failureClass", "route").map { row[it].asText() },
            "$row",
        )
        assertEquals(1 to 1L, row["missionVersion"].asInt() to row["siteSettingsVersion"].asLong(), "$row")
        assertEquals(listOf(false, false, false), listOf(row["unresolved"], row["held"], row["confirmedWithoutEvidence"]).map { it.asBoolean() }, "$row")
        assertTrue(row["resolution"].isNull, "$row")
        assertEquals("GRASP_FAILED" to "SKILL_EXECUTION_FAILED", row["fault"]["failureClass"].asText() to row["fault"]["errorType"].asText(), "$row")
        graspIncident = row["incidentId"].asText()

        val detail = stack.get("/api/incidents/$graspIncident")
        assertEquals("FAILED", detail["unitState"].asText(), "$detail")
        // 설비 슬롯은 실제로 읽었지만 확인 결과는 NOT_REQUESTED 로 남는다(스펙 §3 단계 1).
        assertEquals("NOT_REQUESTED", detail["verification"].asText(), "$detail")
        assertEquals(1 to 1L, detail["intent"]["missionVersion"].asInt() to detail["intent"]["siteSettingsVersion"].asLong(), "$detail")
        val references = detail["fault"]["references"].associate { it["key"].asText() to it["value"].asText() }
        assertEquals(mapOf("KEY_SKILL_ID" to "pick_place", "KEY_TASK_ID" to fault["taskId"].asText()), references, "$detail")
        assertTrue(detail["evidenceWindow"].any { it["kind"].asText() == "FAULT_RAISED" }, "$detail")
        assertEquals(0, detail["blockedBy"].size(), "$detail")

        val logged = operations("INJECT_FAULT").single()
        assertEquals(
            listOf("ENGINEER", "kim", HUMANOID, "SUCCEEDED", "스킬 실패 시연"),
            listOf("mode", "user", "target", "result", "reason").map { logged[it].asText() },
            "$logged",
        )
        val request = json.readTree(logged["request"].asText())
        assertEquals("SKILL_EXECUTION_FAILED" to true, request["kind"].asText() to request["state"].isNull, "$logged")
    }

    @Test
    @Order(2)
    fun `단계 2a 로봇 단위가 도는 중 연결을 OFFLINE 으로 바꾸면 시계를 밀지 않아도 실행이 IN_DOUBT 이고 ONLINE 이면 RUNNING 으로 돌아온다`() {
        val (_, executionId) = submit(rack("RACK-204.S02"))
        driver.push(ExecutionDriver.STEP)
        assertEquals("RUNNING", driver.execution(executionId)["physicalState"].asText(), "${driver.execution(executionId)}")
        assertEquals("RUNNING", unit(executionId, "RACK-204.S02")["state"].asText())

        val clock = stack.site.now()
        assertEquals("SUCCEEDED", connection(HUMANOID, "OFFLINE", "단절 시연")["result"].asText())
        val doubt = awaitPhysical(executionId, "IN_DOUBT")
        // 단위는 도는 그대로이고 인시던트도 없다. 실행만 결과를 확인하지 못한 상태다.
        assertEquals("RUNNING", doubt["units"].single()["state"].asText(), "$doubt")
        assertEquals(clock, stack.site.now(), "단계 2a 는 시계를 밀지 않는다")

        assertEquals("SUCCEEDED", connection(HUMANOID, "ONLINE", "단절 복구")["result"].asText())
        awaitPhysical(executionId, "RUNNING")
        assertEquals(1, incidents()["total"].asInt(), "연결 단절은 인시던트를 남기지 않는다: ${incidents()}")

        val done = driver.drive(executionId)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple("RACK-204.S02", "DONE", "E2")), units(done), "$done")
    }

    @Test
    @Order(3)
    fun `단계 2b 유휴 humanoid-01 을 OFFLINE 으로 두고 연결 기준을 60초로 줄이면 실제 시간으로 오래됨 막힘이 서고 ONLINE 이면 신선으로 돌아온다`() {
        val injectedAt = Instant.now()
        assertEquals("SUCCEEDED", connection(HUMANOID, "OFFLINE", "묵은 값 시연")["result"].asText())
        val changed = stack.send(
            "PUT", "/api/site-settings", "engineer",
            body = """{"baseVersion":1,"connectionThresholdSeconds":60,"reason":"묵은 값 시연"}""",
        )
        assertEquals("SUCCEEDED", changed.body!!["result"].asText(), "${changed.body}")

        // 가상 시계를 거듭 밀어 quadruped-01 의 보고를 잇는다. humanoid-01 은 OFFLINE 이라 보고가 멈춘다.
        val deadline = Instant.now().plus(STALE_WAIT)
        var robots = stack.get("/api/robots")
        while (robot(robots, HUMANOID)["connection"].asText() != "STALE") {
            check(Instant.now().isBefore(deadline)) { "${STALE_WAIT.seconds}초 안에 오래됨이 되지 않았다: ${robot(robots, HUMANOID)}" }
            assertEquals("FRESH", robot(robots, QUADRUPED)["connection"].asText(), "${robot(robots, QUADRUPED)}")
            driver.push(REPORT)
            Thread.sleep(STALE_POLL.toMillis())
            robots = stack.get("/api/robots")
        }
        assertTrue(Duration.between(injectedAt, Instant.now()) > Duration.ofSeconds(60), "오래됨은 실제 시간 60초 뒤에만 선다")
        val stale = robot(robots, HUMANOID)["blockers"].single { it["kind"].asText() == "REPORT_STALE" }
        assertEquals("60초 안의 보고" to 2L, stale["expected"].asText() to stale["basisVersion"].asLong(), "$stale")
        val quadruped = robot(robots, QUADRUPED)
        assertEquals("FRESH", quadruped["connection"].asText(), "$quadruped")
        assertTrue(quadruped["blockers"].none { it["kind"].asText() == "REPORT_STALE" }, "$quadruped")

        // 오래된 기체는 배정 불가다.
        val eligibility = stack.send("POST", "/api/job-orders/eligibility", mode = null, body = rack("RACK-204.S03")).body!!
        val humanoid = eligibility["robots"].single { it["robotId"].asText() == HUMANOID }
        assertEquals(false, humanoid["eligible"].asBoolean(), "$humanoid")
        assertTrue(humanoid["reasons"].any { it.asText() == "연결이 오래됐다(기준 60초, 현장 설정 버전 2)" }, "$humanoid")

        // ONLINE 으로 바꾸는 순간 연결 메시지가 registry 로 가서 마지막 보고 시각이 새로 선다.
        assertEquals("SUCCEEDED", connection(HUMANOID, "ONLINE", "묵은 값 복구")["result"].asText())
        val until = Instant.now().plus(LINK_WAIT)
        while (robot(stack.get("/api/robots"), HUMANOID)["connection"].asText() != "FRESH") {
            check(Instant.now().isBefore(until)) { "ONLINE 뒤 신선으로 돌아오지 않았다: ${robot(stack.get("/api/robots"), HUMANOID)}" }
            Thread.sleep(200)
        }
        assertTrue(robot(stack.get("/api/robots"), HUMANOID)["blockers"].none { it["kind"].asText() == "REPORT_STALE" })
    }

    @Test
    @Order(4)
    fun `단계 3 보류 버전으로 낸 작업 지시가 신호 없이 기한 20초를 넘기면 운영자 보류이고 인시던트가 미해결이며 보류 중이다`() {
        activate("ARRIVAL_WAIT_HOLD", "운영자 보류 대기 도입", 2)
        val (jobOrderId, executionId) = submit(rack("RACK-204.S03"))
        holdExecution = executionId
        val waitFrom = stack.site.now()
        driver.push(ExecutionDriver.STEP)
        assertEquals(
            listOf(Triple(WAIT_UNIT, "equipment_wait", "RUNNING"), Triple("RACK-204.S03", "pick_place", "PENDING")),
            driver.execution(executionId)["units"].map { Triple(it["unitId"].asText(), it["skillType"].asText(), it["state"].asText()) },
        )

        val held = pushUntilHeld(executionId)
        assertTrue(Duration.between(waitFrom, stack.site.now()) >= DEADLINE, "기한 전에 보류가 섰다: ${stack.site.now()}")
        assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), "$held")

        val row = incidents()["incidents"].first()
        assertEquals(
            listOf(executionId, jobOrderId, WAIT_UNIT, "SIGNAL_DEADLINE", "SIGNAL"),
            listOf("executionId", "jobOrderId", "unitId", "failureClass", "route").map { row[it].asText() },
            "$row",
        )
        assertEquals(2 to 2L, row["missionVersion"].asInt() to row["siteSettingsVersion"].asLong(), "$row")
        assertEquals(true to true, row["unresolved"].asBoolean() to row["held"].asBoolean(), "$row")
        assertTrue(row["resolution"].isNull && row["fault"].isNull, "$row")
        firstHold = row["incidentId"].asText()

        val detail = stack.get("/api/incidents/$firstHold")
        assertEquals("OPERATOR_HOLD", detail["unitState"].asText(), "$detail")
        assertEquals(listOf("E2", "E0", "NOT_REQUESTED"), listOf("requiredEvidence", "reachedEvidence", "verification").map { detail[it].asText() })
        assertEquals("20" to "OPERATOR_HOLD", detail["intent"]["unitParameters"]["deadlineSeconds"].asText() to detail["intent"]["unitParameters"]["onDeadline"].asText())
        assertEquals(2 to 2L, detail["intent"]["missionVersion"].asInt() to detail["intent"]["siteSettingsVersion"].asLong(), "$detail")
        assertTrue(detail["evidenceWindow"].any { it["kind"].asText() == "CELL_SIGNAL" }, "$detail")

        // 아무도 판단하지 않으면 시계를 밀어도 보류 그대로다.
        driver.push(ExecutionDriver.STEP)
        assertEquals("OPERATOR_HOLD", driver.execution(executionId)["physicalState"].asText())
    }

    @Test
    @Order(5)
    fun `단계 4 운영자가 사유를 적어 재작업을 판단하면 대기가 새로 시작되고 신호를 켜면 끝나며 판단자와 사유가 남는다`() {
        val reply = resolve(holdExecution, WAIT_UNIT, "REWORK", "랙 재배치 뒤 재작업")
        assertEquals(200, reply.status, "${reply.body}")
        val body = reply.body!!
        assertEquals(listOf("SUCCEEDED", "Resolved", "REWORK"), listOf(body["result"], body["outcome"], body["decision"]).map { it.asText() }, "$body")
        assertEquals(firstHold, body["answer"]["incidentId"].asText(), "$body")

        // 재작업은 단위를 새 정체성으로 다시 계획한다. 실행은 곧바로 RUNNING 이고 대기가 다시 돈다.
        val after = driver.execution(holdExecution)
        assertEquals("RUNNING", after["physicalState"].asText(), "$after")
        assertTrue(unit(holdExecution, WAIT_UNIT)["state"].asText() in setOf("PENDING", "RUNNING"), "$after")
        val row = incidents()["incidents"].single { it["incidentId"].asText() == firstHold }
        assertEquals(true to false, row["unresolved"].asBoolean() to row["held"].asBoolean(), "$row")
        val resolution = row["resolution"]
        assertEquals(listOf("REWORK", "kim", "PERSON"), listOf(resolution["decision"], resolution["decidedBy"]["id"], resolution["decidedBy"]["kind"]).map { it.asText() }, "$row")

        val logged = operations("RESOLVE_OPERATOR_HOLD").single()
        assertEquals(
            listOf("OPERATOR", "kim", "$holdExecution/$WAIT_UNIT", "SUCCEEDED", "랙 재배치 뒤 재작업"),
            listOf("mode", "user", "target", "result", "reason").map { logged[it].asText() },
            "$logged",
        )
        val request = json.readTree(logged["request"].asText())
        assertEquals("REWORK" to "kim", request["decision"].asText() to request["approverId"].asText(), "$logged")
        assertEquals("Resolved", json.readTree(logged["targetResponse"].asText())["body"]["result"].asText(), "$logged")

        // 대기가 다시 도는 동안 신호를 켠다. 시계를 밀기 전이라 새 기한을 넘기지 않는다.
        signal("true")
        val done = driver.drive(holdExecution)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E2"), Triple("RACK-204.S03", "DONE", "E2")), units(done), "$done")
        assertEquals(1, incidents()["incidents"].count { it["executionId"].asText() == holdExecution }, "재작업 뒤 두 번째 보류가 서지 않았다")
    }

    @Test
    @Order(6)
    fun `단계 5 신호를 되돌린 뒤 또 하나의 보류에서 완료 확인을 판단하면 단위가 근거 없이 완료되고 그 표시가 선다`() {
        signal("false")
        val (_, executionId) = submit(rack("RACK-204.S04"))
        driver.push(ExecutionDriver.STEP)
        pushUntilHeld(executionId)
        val rows = incidents()["incidents"]
        val hold = rows.first()
        assertEquals(executionId to true, hold["executionId"].asText() to hold["held"].asBoolean(), "$rows")
        // 앞 보류는 판단을 든 채 보류가 아니다.
        assertEquals(false, rows.single { it["incidentId"].asText() == firstHold }["held"].asBoolean(), "$rows")

        val reply = resolve(executionId, WAIT_UNIT, "CONFIRM_DONE", "현장 육안으로 랙 도착 확인")
        assertEquals("SUCCEEDED" to "Resolved", reply.body!!["result"].asText() to reply.body["outcome"].asText(), "${reply.body}")
        assertEquals("DONE", unit(executionId, WAIT_UNIT)["state"].asText(), "${driver.execution(executionId)}")

        val done = driver.drive(executionId)
        assertEquals(listOf(Triple(WAIT_UNIT, "DONE", "E0"), Triple("RACK-204.S04", "DONE", "E2")), units(done), "$done")

        val row = incidents()["incidents"].single { it["incidentId"].asText() == hold["incidentId"].asText() }
        assertEquals("CONFIRM_DONE" to true, row["resolution"]["decision"].asText() to row["confirmedWithoutEvidence"].asBoolean(), "$row")
        assertEquals(false, row["held"].asBoolean(), "$row")
        val detail = stack.get("/api/incidents/${hold["incidentId"].asText()}")
        assertNotEquals("MATCHED", detail["verification"].asText(), "$detail")
        assertEquals(true, detail["confirmedWithoutEvidence"].asBoolean(), "$detail")
        // 재작업의 판단 기록은 근거 없는 완료 확인이 아니다.
        assertEquals(false, incidents()["incidents"].single { it["incidentId"].asText() == firstHold }["confirmedWithoutEvidence"].asBoolean())
    }

    @Test
    @Order(7)
    fun `단계 6 모드가 틀리거나 사유가 없으면 사전 거부이고 보류가 아닌 단위의 판단과 도는 태스크 없는 스킬 실패는 거부로 남는다`() {
        val before = stack.get("/api/operations").size()

        val engineerResolve = resolve(holdExecution, WAIT_UNIT, "REWORK", "모드 확인", mode = "engineer")
        assertEquals(403 to "MODE_NOT_ALLOWED", engineerResolve.status to engineerResolve.body!!["error"].asText(), "${engineerResolve.body}")
        val noReason = stack.send("POST", "/api/executions/$holdExecution/units/$WAIT_UNIT/resolve", "operator", body = """{"decision":"REWORK"}""")
        assertEquals(400 to "REASON_REQUIRED", noReason.status to noReason.body!!["error"].asText(), "${noReason.body}")
        val operatorFault = stack.send("POST", "/api/faults", "operator", body = """{"robotId":"$HUMANOID","kind":"SKILL_EXECUTION_FAILED","reason":"모드 확인"}""")
        assertEquals(403 to "MODE_NOT_ALLOWED", operatorFault.status to operatorFault.body!!["error"].asText(), "${operatorFault.body}")
        // 사전 거부는 실행 호스트에 닿지 않았으므로 기록하지 않는다.
        assertEquals(before, stack.get("/api/operations").size())

        // 끝난 실행의 대기 단위는 보류가 아니다.
        val notHeld = resolve(holdExecution, WAIT_UNIT, "CONFIRM_DONE", "보류 아님 확인")
        assertEquals(200, notHeld.status, "${notHeld.body}")
        assertEquals("REJECTED" to "NotHeld", notHeld.body!!["result"].asText() to notHeld.body["outcome"].asText(), "${notHeld.body}")
        val resolveRow = operations("RESOLVE_OPERATOR_HOLD").first()
        assertEquals("REJECTED" to "보류 아님 확인", resolveRow["result"].asText() to resolveRow["reason"].asText(), "$resolveRow")
        assertEquals("NotHeld", json.readTree(resolveRow["targetResponse"].asText())["body"]["result"].asText(), "$resolveRow")

        // 유휴 기체에는 진행 중 태스크가 없다.
        val idle = engineer("/api/faults", """{"robotId":"$HUMANOID","kind":"SKILL_EXECUTION_FAILED","reason":"유휴 기체 확인"}""")
        assertEquals("REJECTED", idle["result"].asText(), "$idle")
        assertEquals(409 to "NO_RUNNING_TASK", idle["rejection"]["status"].asInt() to idle["rejection"]["error"].asText(), "$idle")
        val faultRow = operations("INJECT_FAULT").first()
        assertEquals("REJECTED" to "유휴 기체 확인", faultRow["result"].asText() to faultRow["reason"].asText(), "$faultRow")
        assertEquals("NO_RUNNING_TASK", json.readTree(faultRow["targetResponse"].asText())["body"]["error"].asText(), "$faultRow")

        assertEquals(before + 2, stack.get("/api/operations").size())
        // 거부된 판단은 인시던트를 바꾸지 않는다.
        assertEquals(graspIncident, incidents()["incidents"].last()["incidentId"].asText())
    }

    /** 실행의 단위를 (단위 id, 상태, 근거 등급)으로 낸다. */
    private fun units(execution: JsonNode): List<Triple<String, String, String>> =
        execution["units"].map { Triple(it["unitId"].asText(), it["state"].asText(), it["reached"].asText()) }

    private fun unit(executionId: String, unitId: String): JsonNode =
        driver.execution(executionId)["units"].single { it["unitId"].asText() == unitId }

    private fun incidents(): JsonNode = stack.get("/api/incidents")

    private fun robot(view: JsonNode, robotId: String): JsonNode =
        view["robots"].single { it["robot"]["robotId"].asText() == robotId }

    /** 조작 기록에서 [op] 의 행만 최신부터 낸다. */
    private fun operations(op: String): List<JsonNode> =
        stack.get("/api/operations").filter { json.readTree(it["request"].asText())["op"].asText() == op }

    /** 시계를 밀지 않고 실제 시간으로 실행 상태가 [state] 가 되기를 기다린다. */
    private fun awaitPhysical(executionId: String, state: String): JsonNode {
        val until = Instant.now().plus(LINK_WAIT)
        var seen = driver.execution(executionId)
        while (seen["physicalState"].asText() != state) {
            check(Instant.now().isBefore(until)) { "${LINK_WAIT.seconds}초 안에 $state 가 되지 않았다: $seen" }
            Thread.sleep(100)
            seen = driver.execution(executionId)
        }
        return seen
    }

    /** 대기 단위가 운영자 보류가 될 때까지 [ExecutionDriver.STEP] 씩 민다. 기한 20초면 다섯 번 안이다. */
    private fun pushUntilHeld(executionId: String): JsonNode {
        repeat(8) {
            val seen = driver.execution(executionId)
            if (seen["units"].single { it["unitId"].asText() == WAIT_UNIT }["state"].asText() == "OPERATOR_HOLD") return seen
            driver.push(ExecutionDriver.STEP)
        }
        error("대기 단위가 운영자 보류가 되지 않았다: ${driver.execution(executionId)}")
    }

    private fun connection(robotId: String, state: String, reason: String): JsonNode =
        engineer("/api/faults", """{"robotId":"$robotId","kind":"CONNECTION","state":"$state","reason":"$reason"}""")

    private fun resolve(executionId: String, unitId: String, decision: String, reason: String, mode: String = "operator"): E2eStack.Reply =
        stack.send("POST", "/api/executions/$executionId/units/$unitId/resolve", mode, body = """{"decision":"$decision","reason":"$reason"}""")

    private fun signal(value: String) {
        val written = stack.send("POST", "/api/cell/signals/rack_present", "operator", body = """{"value":"$value"}""")
        assertEquals("SUCCEEDED", written.body!!["result"].asText(), "${written.body}")
    }

    /** S3b 흐름으로 템플릿을 초안 저장, 모의 실행, 활성화한다. */
    private fun activate(template: String, reason: String, version: Int) {
        val definition = stack.get("/api/missions/templates/$WORK_MASTER")["templates"].single { it["id"].asText() == template }["definition"].asText()
        val saved = engineer("$MISSIONS/drafts", json.createObjectNode().put("definition", definition).toString())
        val draftId = saved["outcome"]["draft"]["draftId"].asLong()
        val mocked = engineer("$MISSIONS/drafts/$draftId/mock-run", "{}")
        assertEquals("PASSED", mocked["outcome"]["result"].asText(), "$mocked")
        val activated = engineer("$MISSIONS/drafts/$draftId/activate", """{"reason":"$reason"}""")
        assertEquals("ACTIVATED" to version, activated["outcome"]["result"].asText() to activated["outcome"]["version"].asInt(), "$activated")
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    /** 운영자 모드로 작업 지시를 내고 작업 지시 id 와 실행 id 를 낸다. 배정 기체는 `pick_place` 를 가진 humanoid-01 하나다. */
    private fun submit(form: String): Pair<String, String> {
        val reply = stack.send("POST", "/api/job-orders", "operator", body = form)
        assertEquals(200, reply.status, "${reply.body}")
        val outcome = reply.body!!["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${reply.body}")
        return reply.body["jobOrderId"].asText() to outcome["executionId"].asText()
    }
}
```

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && ./gradlew :e2e:test -q
```
Expected: e2e 57, 실패 0(`FaultIncidentTest` 7 포함, 그 클래스 약 95~111초, 그 가운데 연결 끊김의 오래됨 단계가 실제 시간 약 62초). 백그라운드로 돌린다. Docker 데몬이 떠 있어야 한다(Testcontainers).

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git add e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt && git commit -F - <<'EOF'
test(e2e): 장애 주입과 인시던트 통합 시험 `FaultIncidentTest` 7개

- 스킬 실패 주입과 인시던트, 연결 끊김 중 IN_DOUBT 와 복구, 유휴 기체 연결 끊김의 오래됨과 복구
- 보류 템플릿의 기한 보류, 재작업 판단 뒤 신호로 종료, 완료 확인 판단과 근거 없음 표시
- 모드·사유·보류 아님·진행 중 태스크 없음 거부

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

### Task 6: Playwright, README

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md`, `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md`, `ui/e2e/lifecycle.spec.ts`

- [ ] **Step 1: 패치 적용**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git apply --check "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task6.patch" && git apply "C:/Users/Eisen/AppData/Local/Temp/s4a-patches/task6.patch"
```

````diff
diff --git a/README.md b/README.md
index 5bdea38..7c3efe5 100644
--- a/README.md
+++ b/README.md
@@ -2,7 +2,7 @@
 
 picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임무, 엔드포인트의 운영 가능성을 PoC 로 입증합니다. 실제 하드웨어 현장은 없습니다. 보안과 인증은 생략합니다. 운영 중 변경은 코드 수정이 아니라 관리 화면에서 처리합니다.
 
-지금 단계는 S3c 현장 시간값입니다. S3b 임무 버전 위에 올립니다. 서브모듈 `picasso` 는 P5 머지 커밋을 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금, P5 현장 시간값 주입과 인시던트의 현장 설정 버전이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 같은 구역에 미들웨어 시간값 넷이 있습니다. 근거 윈도우 앞 폭(5~120초, 기본 30초), 근거 윈도우 뒤 폭(5~120초, 기본 15초), `inDoubtGrace`(10~600초, 기본 60초), `stallWindow`(30~3600초, 기본 300초)입니다. 앞의 셋은 결과 판정 값이고 `stallWindow` 는 정체 표시 시점만 바꿉니다. 화면은 두 묶음으로 나눠 묶음마다 무엇이 바뀌는지 적습니다. 엔지니어 모드만 바꿉니다. 값 하나만 바꿔도 현장 설정 세트 전체가 새 버전이고, 범위 밖 값은 화면이 막고 운영 서비스도 `SETTING_OUT_OF_RANGE` 로 거부합니다. 허용 범위의 주인은 picasso 이고 운영 서비스는 그 사본을 둡니다. 실행 호스트는 ops 스키마의 뷰 `ops.site_timings_current` 를 1초마다 읽어 다음 pump 부터 그 값으로 판정합니다. 화면은 운영 서비스의 최신 버전과 따로 «실행 호스트 반영: 버전 N» 을 보입니다. 호스트가 닿지 않으면 모름, 호스트가 아직 뷰를 읽지 못했으면 미적용입니다. 미적용 동안 실행 호스트는 작업 지시를 받지 않습니다. 배정 가능 판정이 기체마다 미적용 이유를 더하고 제출은 미배정입니다. 설비 대기가 기한을 넘겨 `SIGNAL_DEADLINE` 인시던트가 봉인되면 그 인시던트에 봉인한 라운드의 현장 설정 버전과 시간값 넷이 실립니다. 인시던트 화면은 아직 없고 실행 호스트 `GET /host/incidents` 로 읽습니다. 적용 상태는 `GET /host/site-timings` 입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 `InspectAsset`, `PrepareSequencedRack` 2개입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 임무 버전은 코드 정의면 `코드 정의`, 데이터 버전이면 `버전 N` 으로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 같은 영역의 셀 대역 신호 표는 이름 있는 신호의 이름, 종류, 값, 관측 시각을 보입니다. 안전이 아닌 BOOLEAN 신호는 켜기와 끄기 버튼이 있습니다. 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드와 엔지니어 모드 둘 다 합니다. 안전 신호는 값만 보입니다. 임무·정책 영역은 `PrepareSequencedRack` 의 활성 버전과 버전 이력, 초안과 그 마지막 모의 실행을 보입니다. 엔지니어 모드는 정의 JSON 을 편집기에서 고쳐 초안으로 저장하고, 검증하고, 모의 실행하고, 사유를 적어 활성화합니다. 시작용 정의 2개(데이터 정의, 랙 도착 대기)를 템플릿으로 불러올 수 있습니다. 초안은 자유롭게 저장되고 활성화만 관문입니다. 활성화는 지금의 신호 사양과 시운전 완료 기체의 스킬로 다시 검증하고, 그 초안의 마지막 모의 실행이 통과여야 합니다. 신호 사양에 없는 신호를 참조하는 것 같은 거부는 거부 카드로 보이고, 해결 담당과 바로 갈 작업이 함께 보입니다. 활성화한 버전은 다음 작업 지시부터 쓰이고, 도는 실행은 생성 때의 버전으로 끝납니다. 운영자 모드는 보기만 합니다. 인시던트 화면, 보류 해소, 장애 주입은 S4 의 몫입니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. S3b 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md` 에 있습니다. S3c 설계 스펙은 `docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
+지금 단계는 S4a 장애 주입, 인시던트 화면, 운영자 보류 해소입니다. S3c 현장 시간값 위에 올립니다. picasso 는 바꾸지 않습니다. 서브모듈 `picasso` 는 P5 머지 커밋을 가리킵니다. 그 버전에는 P2a 시험 실행기, P2b 리비전·바인딩 REST, P3 임무 정의 버전, P4 스트림 재부착과 mimic 엔진 잠금, P5 현장 시간값 주입과 인시던트의 현장 설정 버전이 들어 있습니다. 로봇·연결 영역에서 엔지니어 모드는 기체를 선언합니다. 같은 영역에서 엔지니어 모드는 어댑터 제품 선언, 빌드 선언, 인스턴스 등록을 합니다. 같은 영역 왼쪽에 프로파일 구역이 있습니다. 그 구역에서 엔지니어 모드는 기종 프로파일 리비전을 파일로 골라 제출하고, 시험을 요청하고, 활성화합니다. 시험 결과는 사람이 적지 않습니다. 런처가 띄운 시험 실행기가 적습니다. 기체 상세에서 엔지니어 모드는 빌드와 활성 리비전을 골라 바인딩하고, 사이트 명칭 등록을 기록합니다. 명칭 티칭은 화면 밖 현장 작업입니다. 기체 목록에 시운전 칸이 있습니다. 값은 완료, 미완, 퇴역입니다. 완료 조건은 셋입니다. 원장 상태가 `CONFIRMED` 이고 퇴역이 아니어야 합니다. 활성 바인딩이 있어야 합니다. 사이트 명칭 상태가 `CONFIRMED` 또는 `NOT_REQUIRED` 여야 합니다. 빠진 조건은 막힘으로 보입니다. 운영자 모드는 퇴역과 복귀를 합니다. 어댑터 적합성 기록은 열지 않습니다. 그래서 빌드와 인스턴스는 모두 `UNTESTED` 로 보입니다. 현장·자원 영역에는 현장 설정 구역이 있습니다. 지금 버전, 연결 기준 시간, 허용 범위(60~3600초), 버전 이력을 보입니다. 엔지니어 모드는 연결 기준 시간을 바꿉니다. 바꿀 때는 사유가 필요합니다. 바꾸면 새 버전이 생깁니다. 다음 기체 목록 읽기부터 그 값으로 연결 칸과 막힘을 판정합니다. 재기동은 필요 없습니다. 운영자 모드는 바꾸지 못합니다. 막힘 카드는 어느 현장 설정 버전으로 판정했는지를 근거 버전으로 보입니다. 같은 구역에 미들웨어 시간값 넷이 있습니다. 근거 윈도우 앞 폭(5~120초, 기본 30초), 근거 윈도우 뒤 폭(5~120초, 기본 15초), `inDoubtGrace`(10~600초, 기본 60초), `stallWindow`(30~3600초, 기본 300초)입니다. 앞의 셋은 결과 판정 값이고 `stallWindow` 는 정체 표시 시점만 바꿉니다. 화면은 두 묶음으로 나눠 묶음마다 무엇이 바뀌는지 적습니다. 엔지니어 모드만 바꿉니다. 값 하나만 바꿔도 현장 설정 세트 전체가 새 버전이고, 범위 밖 값은 화면이 막고 운영 서비스도 `SETTING_OUT_OF_RANGE` 로 거부합니다. 허용 범위의 주인은 picasso 이고 운영 서비스는 그 사본을 둡니다. 실행 호스트는 ops 스키마의 뷰 `ops.site_timings_current` 를 1초마다 읽어 다음 pump 부터 그 값으로 판정합니다. 화면은 운영 서비스의 최신 버전과 따로 «실행 호스트 반영: 버전 N» 을 보입니다. 호스트가 닿지 않으면 모름, 호스트가 아직 뷰를 읽지 못했으면 미적용입니다. 미적용 동안 실행 호스트는 작업 지시를 받지 않습니다. 배정 가능 판정이 기체마다 미적용 이유를 더하고 제출은 미배정입니다. 설비 대기가 기한을 넘겨 `SIGNAL_DEADLINE` 인시던트가 봉인되면 그 인시던트에 봉인한 라운드의 현장 설정 버전과 시간값 넷이 실립니다. 적용 상태는 `GET /host/site-timings` 입니다. 기체 상세에는 연결 판정 기준이 보입니다. 기준 초와 버전입니다. 운영 영역에서 운영자 모드는 작업 지시를 냅니다. 임무는 `InspectAsset`, `PrepareSequencedRack` 2개입니다. `InspectAsset` 은 점검 대상의 id 와 장소 이름을 넣습니다. `PrepareSequencedRack` 은 셀 대역의 슬롯과 자재를 고릅니다. 제시 자리는 자재에서 정해집니다. 폼을 채우면 기체별 배정 가능 표가 보입니다. 배정 가능 조건은 넷입니다. 시운전 완료, 연결 신선, 도는 실행 없음, 스킬 적합입니다. 앞의 둘은 운영 서비스가, 뒤의 둘은 실행 호스트가 판정합니다. 못 물어본 칸은 모름이고 모름이 있으면 배정 불가입니다. 운영 서비스는 배정 가능한 기체만 후보로 실행 호스트에 넘깁니다. 실행 호스트의 picasso 미들웨어가 후보 중 하나를 골라 mimic 기체에서 실행합니다. 엔지니어 모드는 작업 지시를 내지 못합니다. 실행 목록은 실행 상태, 단위 상태와 근거 등급, 작업 응답, 임무 버전을 보입니다. 임무 버전은 코드 정의면 `코드 정의`, 데이터 버전이면 `버전 N` 으로 보입니다. 셀 대역 표는 제시 자리와 슬롯의 점유와 자재를 보입니다. 같은 영역의 셀 대역 신호 표는 이름 있는 신호의 이름, 종류, 값, 관측 시각을 보입니다. 안전이 아닌 BOOLEAN 신호는 켜기와 끄기 버튼이 있습니다. 사람이 PLC 역할을 하는 정상 조작이라 운영자 모드와 엔지니어 모드 둘 다 합니다. 안전 신호는 값만 보입니다. 임무·정책 영역은 `PrepareSequencedRack` 의 활성 버전과 버전 이력, 초안과 그 마지막 모의 실행을 보입니다. 엔지니어 모드는 정의 JSON 을 편집기에서 고쳐 초안으로 저장하고, 검증하고, 모의 실행하고, 사유를 적어 활성화합니다. 시작용 정의 3개(데이터 정의, 랙 도착 대기, 운영자 보류 대기)를 템플릿으로 불러올 수 있습니다. 운영자 보류 대기(`ARRIVAL_WAIT_HOLD`)는 랙 도착 대기와 같되 기한이 20초이고 기한을 넘기면 실행을 중단하지 않고 대기 단위를 운영자 보류로 둡니다. 초안은 자유롭게 저장되고 활성화만 관문입니다. 활성화는 지금의 신호 사양과 시운전 완료 기체의 스킬로 다시 검증하고, 그 초안의 마지막 모의 실행이 통과여야 합니다. 신호 사양에 없는 신호를 참조하는 것 같은 거부는 거부 카드로 보이고, 해결 담당과 바로 갈 작업이 함께 보입니다. 활성화한 버전은 다음 작업 지시부터 쓰이고, 도는 실행은 생성 때의 버전으로 끝납니다. 운영자 모드는 보기만 합니다. 현장·자원 영역에는 장애 주입 구역이 있습니다. 엔지니어 모드만 넣고 운영자 모드는 «장애 주입은 엔지니어 모드에서 합니다» 만 봅니다. 기체와 종류를 고르고 사유를 적어 넣습니다. 종류는 2개입니다. 스킬 실패는 그 기체의 진행 중 `pick_place` 태스크에 `SKILL_EXECUTION_FAILED`(GRASP_FAILED)를 강제합니다. 태스크는 현장이 스스로 찾고, 진행 중 태스크가 없으면 «진행 중 태스크 없음» 으로 거부합니다. 작업 지시 직후의 태스크는 아직 `ACCEPTED` 라 다음 시계 진행 뒤에 넣어야 합니다. 연결 상태는 `OFFLINE`, `CONNECTION_BROKEN` 으로 바꾸고 `ONLINE` 으로 복구합니다. 로봇 단위가 도는 중에 끊으면 실행이 `IN_DOUBT` 로 보이고 복구하면 `RUNNING` 으로 돌아옵니다. 끊긴 동안 생존 보고가 멈추므로 연결 기준 시간을 넘기면 오래됨(`REPORT_STALE`)으로 막히고 배정 불가가 됩니다. 요청은 운영 서비스, 실행 호스트를 거쳐 현장 루프백 `POST /faults` 에 닿고, 현장은 mimic 엔진 잠금 아래에서 엔진을 직접 부릅니다. mimic 제어 채널은 열지 않으므로 시계 밀기, 시계 모드, 시드는 어느 경로로도 나가지 않습니다. 지울 때까지 유지되는 결함(`PAYLOAD_LOST`, `LOCALIZATION_LOST`, 제어권 상실)은 넣지 않습니다. picasso 에 지우는 호출이 없어 넣으면 현장을 재기동할 때까지 그 기체가 막히기 때문입니다. 전송 장애(끊김, 지연, 유실, 중복, 순서 바뀜)도 넣지 않습니다. registry 로 가는 발행 축에만 걸려 실행에는 보이지 않습니다. 지연은 연결 상태의 묵은 값으로 대신 봅니다. 장애 주입은 조작 기록에 `INJECT_FAULT` 로 남고 재조회하지 않습니다. 운영 영역에는 인시던트 구역이 있습니다. 목록은 최신부터 발생 시각, 기체, 실행, 단위, 실패 종류, 경로, 현장 설정 버전, 임무 버전, 판단, 보류를 보입니다. 줄을 고르면 상세에 설정 버전과 시간값, 임무 버전, 단계 위치, 필요·도달 근거 등급, 확인 결과(코드 이름 그대로), 이 단위의 결함, 실행을 막던 결함(`blockedBy`), 근거 윈도우가 보입니다. 판단 없는 인시던트는 관측으로만 보이고, 판단된 인시던트는 «사람이 판단함: <판단자>, <시각>» 과 결정을 관측과 다른 구역에 보입니다. 완료 확인인데 봉인 때 설비 확인이 `MATCHED` 가 아니었으면 «설비 근거 없이 완료 확인» 이 붙습니다. 보류 중인 단위의 상세에서 운영자 모드는 사유를 적고 완료 확인 또는 재작업을 판단합니다. 엔지니어 모드는 버튼 없이 보류 중이라는 것만 봅니다. 판단자는 화면의 사용자 이름이고 picasso 에 `PERSON` 승인자로 넘어갑니다. 재작업은 그 단위를 다시 계획해 대기가 새 기한으로 다시 돕니다. 완료 확인은 그 단위를 근거 없이 완료로 두고 다음 단위로 갑니다. 판단은 picasso 인시던트의 `resolution` 과 조작 기록의 `RESOLVE_OPERATOR_HOLD`(승인자, 결정, 사유, 결과) 양쪽에 남습니다. 사유는 조작 기록에만 있습니다. 실행 수준 보류 풀이와 실행 중단은 화면에 없습니다. S1 설계 스펙은 `docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md` 에 있습니다. P2·S1d 설계 스펙은 `docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md` 에 있습니다. S2 설계 스펙은 `docs/superpowers/specs/2026-10-08-s2-site-settings-design.md` 에 있습니다. S3a 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md` 에 있습니다. S3b 설계 스펙은 `docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md` 에 있습니다. S3c 설계 스펙은 `docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md` 에 있습니다. S4a 설계 스펙은 `docs/superpowers/specs/2026-10-09-s4a-faults-incidents-design.md` 에 있습니다. 구현 계획은 `docs/superpowers/plans/` 아래에 있습니다.
 
 ## 구성
 
@@ -12,7 +12,7 @@ picasso 를 라이브러리로 쓰는 담는 측 저장소입니다. 로봇, 임
 - `mission-host/`: 실행 호스트입니다(Kotlin, Spring Boot). picasso 미들웨어를 세워 mimic 기체에서 작업 지시를 실행합니다. 250ms 마다 셀 대역을 읽고 미들웨어를 pump 합니다. 임무 버전(초안, 모의 실행, 버전)은 같은 Postgres 의 자기 스키마 `mission` 에 덧붙이기 전용으로 저장합니다. 재기동하면 활성 버전은 그대로이고 실행과 인시던트는 사라집니다. 현장 시간값은 운영 서비스가 만든 뷰 `ops.site_timings_current` 를 1초마다 읽기 전용으로 읽습니다. 표는 읽지 않습니다. 뷰가 아직 없으면 미적용으로 뜨고 다시 읽습니다. 모의 실행은 호스트 안에 별도 mimic 기체와 가상 시계를 띄워 초안 정의로 표본 작업 지시 하나를 끝까지 돌립니다. 현장 기체, 셀 대역, registry 에는 닿지 않습니다. 모의 실행 기체 프로파일(`mission-host/mock-run/humanoid-a.json`)과 프로파일 스키마 경로는 작업 디렉터리 기준이므로 저장소 루트에서 띄웁니다. 토큰을 받지 않습니다. `.env` 의 `HOST_PORT` 에 루프백으로 엽니다. 호출자는 운영 서비스뿐입니다.
 - `ops-service/`: 운영 서비스입니다(Kotlin, Spring Boot). 화면의 유일한 백엔드입니다. 운영자 토큰을 쥡니다. registry REST 와 실행 호스트 REST 를 부릅니다. picasso 모듈을 쓰지 않습니다.
 - `ui/`: 관리 화면입니다(React, Vite, TypeScript). 운영 서비스만 부릅니다.
-- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다. 임무 버전 시험은 실행 호스트만 같은 포트로 다시 띄워 활성 버전이 남는지 봅니다. 스택은 실행 호스트를 띄우기 전에 ops 스키마 마이그레이션을 먼저 돌립니다. 그래서 실행 호스트는 현장 설정 버전 1 을 적용한 채로 뜹니다.
+- `e2e/`: 통합 시험입니다. 한 JVM 에 Postgres, registry, mimic, 실행 호스트, 운영 서비스를 띄웁니다. 실행 호스트의 시계는 현장 가상 시계이고 시험이 시계를 직접 밉니다. 임무 버전 시험은 실행 호스트만 같은 포트로 다시 띄워 활성 버전이 남는지 봅니다. 스택은 실행 호스트를 띄우기 전에 ops 스키마 마이그레이션을 먼저 돌립니다. 그래서 실행 호스트는 현장 설정 버전 1 을 적용한 채로 뜹니다. 장애·인시던트 시험은 새 스택에서 스킬 실패를 먼저 넣고, 오래됨 단계만 실제 시간 60초 넘게 기다립니다.
 - `docs/`: 설계 스펙과 구현 계획입니다.
 
 ## 선행 도구
@@ -73,7 +73,7 @@ cd ui && npx playwright install chromium
 cd ui && npx playwright test
 ```
 
-Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 이어서 엔지니어 모드로 `stallWindow` 를 600초로 바꿔 버전 3 이력 행과 «실행 호스트 반영: 버전 3» 을 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 임무·정책 영역에서 엔지니어 모드로 데이터 정의 템플릿을 불러와 초안 저장, 검증, 모의 실행, 활성화를 하고 버전 이력에 `버전 1 (활성)` 을 봅니다. 운영 영역의 셀 대역 신호 표에서 `rack_present` 를 켜고 신호 조작 결과와 바뀐 값을 봅니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 3분 걸립니다. 로컬 실측은 2.7분입니다. Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
+Playwright 가 Postgres, 런처, 실행 호스트, 운영 서비스, 화면을 띄웁니다. 시험이 끝나면 모두 끕니다. 시험 하나가 화면에서 기체 생애주기와 어댑터 등록(제품, 빌드, 인스턴스)을 한 번 돕니다. 이어서 리비전 제출, 시험 요청, 실행기의 `TESTED` 기록, 활성화, 바인딩, 명칭 기록, `humanoid-01` 시운전 완료까지 돕니다. `quadruped-01` 의 명칭 기록이 기체가 아는 명칭 없음으로 막히는 것도 봅니다. 현장·자원 영역에서 연결 기준 시간을 120초로 바꿔 버전 2 와 이력 행을 봅니다. 운영자 모드에서는 바꾸지 못하는 것과 기체 상세의 연결 판정 기준이 버전 2 로 바뀐 것도 봅니다. 이어서 엔지니어 모드로 `stallWindow` 를 600초로 바꿔 버전 3 이력 행과 «실행 호스트 반영: 버전 3» 을 봅니다. 운영 영역에서 운영자 모드로 `InspectAsset` 작업 지시를 냅니다. 시운전 완료인 `humanoid-01` 만 배정 가능이고 배정됩니다. 실행 목록에 `코드 정의` 행이 보이고 실행이 `PHYSICALLY_DONE` 으로 끝납니다. 임무·정책 영역에서 엔지니어 모드로 데이터 정의 템플릿을 불러와 초안 저장, 검증, 모의 실행, 활성화를 하고 버전 이력에 `버전 1 (활성)` 을 봅니다. 운영 영역의 셀 대역 신호 표에서 `rack_present` 를 켜고 신호 조작 결과와 바뀐 값을 봅니다. 운영자 모드로 슬롯 하나짜리 `PrepareSequencedRack` 을 내고 `pick_place` 가 도는 동안 엔지니어 모드로 스킬 실패를 넣어 `GRASP_FAILED` 인시던트 줄을 봅니다. 이어서 운영자 보류 대기 템플릿을 활성화하고, 신호를 끈 채 작업 지시를 내 기한 20초를 실제 시간으로 기다립니다. 보류 중인 인시던트 상세에서 엔지니어 모드는 판단 버튼이 없는 것을 보고, 운영자 모드로 사유를 적어 재작업을 판단합니다. 대기가 다시 도는 즉시 신호를 켜 실행이 끝나고 «사람이 판단함» 이 보이는 것을 봅니다. 보류 버전은 DB 에 남으므로 이 단계는 끝 쪽에 둡니다. 끝에서 registry 를 멈춰 모름을 봅니다. 시계는 실제 시각을 따라갑니다. 시험은 약 4분 걸립니다. 로컬 실측은 3.4분입니다(스택 기동 포함 4.0분). Playwright 판정은 출력의 `N passed`, `N failed` 줄과 실패 시험 이름으로 합니다.
 
 ## 띄우기
 
@@ -130,9 +130,14 @@ Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니
 - 토큰은 2개입니다. 적재 토큰은 site 만 받습니다. 운영 서비스는 운영자 토큰만 받습니다. 어느 토큰도 브라우저로 가지 않습니다.
 - registry, 실행 호스트, 운영 서비스, 셀 대역은 `127.0.0.1` 에만 엽니다. mimic gRPC 는 picasso 가 주소를 정하므로 모든 인터페이스에 열립니다. 인증 없는 기체 제어 API 표면이고 포트가 `.env` 로 고정됩니다.
 - 실행 호스트 REST 와 셀 대역은 인증이 없습니다. 같은 기계의 다른 프로세스는 모드 검사 없이 작업 지시를 내고, 임무 버전을 활성화하고, 신호를 쓸 수 있습니다.
-- `pick_place` 에는 실패 모드가 있습니다(단위 하나에 약 6%). 기체별 시드로 추첨하므로 런처로 PrepareSequencedRack 을 돌리면 슬롯 넷짜리 작업 지시는 약 22% 가 어딘가에서 실패하고, 실패는 실행 목록에 보입니다. 화면 시험이 현장 기체로 돌리는 작업 지시는 실패 모드가 없는 InspectAsset 뿐입니다. 모의 실행은 실패 모드를 뺀 프로파일 사본으로 돌므로 추첨이 없습니다.
-- 운영자 보류를 푸는 수단이 아직 없습니다. 보류에 선 실행의 기체는 실행 호스트를 재기동할 때까지 배정 불가입니다.
-- 랙 도착 대기 템플릿의 대기 노드는 기한 120초가 지나면 실행을 중단합니다(ABORTED). 기체는 다시 배정할 수 있습니다. 런처는 가상 시계를 실제 시각에 맞춰 밀므로, 그 작업 지시를 낸 뒤 2분 안에 셀 대역 신호 표에서 `rack_present` 를 켜야 진행합니다. 셀 대역은 신호를 스스로 되돌리지 않으므로 한 번 켜면 다음 작업 지시의 대기는 바로 풀립니다.
+- `pick_place` 에는 실패 모드가 있습니다(단위 하나에 약 6%). 기체별 시드로 추첨하므로 런처로 PrepareSequencedRack 을 돌리면 슬롯 넷짜리 작업 지시는 약 22% 가 어딘가에서 실패하고, 실패는 실행 목록에 보입니다. 화면 시험이 현장 기체로 돌리는 `pick_place` 는 둘이고, 첫째는 스킬 실패를 강제해 완주 추첨을 하지 않으므로 자연 실패가 섞이지 않습니다(시드 0). 모의 실행은 실패 모드를 뺀 프로파일 사본으로 돌므로 추첨이 없습니다.
+- 단위 보류는 인시던트 상세에서 운영자가 판단해 풉니다. 아무도 판단하지 않으면 실행은 정착하지 않고 그 기체는 배정 불가로 남습니다. 실행 수준 보류(로봇 수준 결함이 다음 단위를 막음)의 풀이와 실행 중단은 화면에 없습니다.
+- 판단 뒤 작업 응답은 바로 갱신되지 않습니다. picasso 의 판단이 작업 응답 갱신을 부르지 않기 때문입니다. 실행 목록의 상태와 인시던트는 바로 바뀝니다.
+- 판단 사유는 picasso 인시던트에 실리지 않고 조작 기록에만 있습니다.
+- 판단 요청에 응답이 없으면 운영 서비스가 1초 뒤 인시던트 목록을 한 번 다시 읽어 행위자의 같은 결정이 요청 뒤에 붙었는지 봅니다. 실행 호스트는 요청 id 를 저장하지 않아 처리 중을 구분하지 못하므로, 잠금을 기다리다 늦게 붙은 판단은 반영 안 됨으로 남을 수 있습니다. 이 대조는 운영 서비스와 실행 호스트가 같은 기계에 있다는 전제입니다. 장애 주입은 다시 읽지 않으므로 응답이 없으면 넣었는지 끝내 모릅니다.
+- 오래됨은 운영 서비스가 registry 의 실제 수신 시각으로 판정합니다. 연결 기준 시간의 하한이 60초라 연결을 끊은 뒤 오래됨이 보이기까지 실제 시간 1분 넘게 걸립니다.
+- 셀 대역은 슬롯을 비우지 않습니다. 같은 슬롯이 근거 윈도우 앞 폭 안에 채워진 적이 있으면 스킬 실패가 단위 실패가 아니라 운영자 보류가 될 수 있습니다. `quadruped-01` 은 `pick_place` 가 없어 연결 상태만 받습니다.
+- 랙 도착 대기 템플릿의 대기 노드는 기한 120초가 지나면 실행을 중단합니다(ABORTED). 운영자 보류 대기 템플릿은 기한 20초가 지나면 운영자 보류입니다. 활성 버전은 DB 에 남으므로 이것을 활성화하면 그 뒤의 모든 `PrepareSequencedRack` 작업 지시가 20초 대기로 시작합니다. 기체는 다시 배정할 수 있습니다. 런처는 가상 시계를 실제 시각에 맞춰 밀므로, 그 작업 지시를 낸 뒤 2분 안에 셀 대역 신호 표에서 `rack_present` 를 켜야 진행합니다. 셀 대역은 신호를 스스로 되돌리지 않으므로 한 번 켜면 다음 작업 지시의 대기는 바로 풀립니다.
 - 신호 사양은 셀 대역 픽스처의 코드 상수입니다. 화면에서 편집하지 못하고 버전도 없습니다. 신호는 값, 관측 시각, 모름만 보이고 신호 품질(응답 없음, 단절, 불확실)은 보이지 않습니다.
 - 임무 편집은 `PrepareSequencedRack` 하나만 합니다. 새 WorkMaster 는 만들지 못합니다. 편집기는 textarea 이고 편집기용 JSON Schema 파일은 없습니다. 정의의 옳고 그름은 실행 호스트가 검증합니다.
 - 모의 실행은 이상적 현장을 가정합니다. 목적지 슬롯은 처음부터 기대 자재를 들고, 이름 있는 신호는 대기 노드의 기대 값을 내고, 기체 실패 모드가 없습니다. 정의가 끝까지 도는지를 볼 뿐 현장 사실을 보증하지 않습니다. 요청 안에서 동기로 돌며 실제 시간 상한은 30초입니다. 활성화는 그 초안의 마지막 모의 실행만 보고, 앞 버전들의 모의 실행을 다시 돌리지 않습니다.
@@ -142,7 +147,7 @@ Gradle 의 run 작업 2개를 한 작업 트리에서 겹쳐 띄우지 않습니
 - 근거 기한은 단위가 완료된 순간의 뒤 폭으로 정해집니다. 그 뒤에 바꾼 뒤 폭은 이미 완료된 단위에 미치지 않습니다. 인시던트에 실리는 값은 봉인한 라운드의 값이라 근거 기한을 정한 값과 다를 수 있습니다.
 - 설비 대기의 기한 판정은 임무 정의의 `deadlineSeconds` 로 하고 시간값을 쓰지 않습니다. 시간값이 판정을 바꾸는 것은 picasso 시험이 맡고, 통합 시험은 인시던트에 버전과 값이 실리는 것까지 봅니다.
 - 모의 실행은 현장 시간값을 쓰지 않고 케이퍼빌리티 기본값으로 돕니다. 임무 검증 결과의 `basisVersion` 은 늘 비어 있습니다.
-- 인시던트는 실행 호스트 메모리에만 있습니다. 재기동하면 비어 다시 시작합니다. 새 칸(현장 설정 버전, `inDoubtGrace`, `stallWindow`)은 picasso 내보내기에 싣지 않습니다.
+- 인시던트와 그 판단은 실행 호스트 메모리에만 있습니다. 재기동하면 비어 다시 시작합니다(S4b 의 몫). 새 칸(현장 설정 버전, `inDoubtGrace`, `stallWindow`)은 picasso 내보내기에 싣지 않습니다.
 - 바닥 소유는 검사하지 않습니다. 활성화 권한은 모드 검사뿐이고 명부가 없습니다.
 - 임무 버전의 표 3개는 덧붙이기 전용입니다(트리거가 UPDATE, DELETE, TRUNCATE 를 막습니다). 지우려면 스키마째 지웁니다. 화면 시험은 시작할 때 compose 볼륨째 내리므로 매번 빈 DB 에서 시작합니다.
 - 선언 전 mimic 의 생존 보고는 registry 가 거부합니다. 거부한 보고는 어디에도 남지 않습니다. 기체를 선언하면 보고가 붙습니다.
diff --git a/docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md b/docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md
index 1a9816e..102f8f1 100644
--- a/docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md
+++ b/docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md
@@ -247,7 +247,7 @@ registry 에는 `X-Actor: <모드>/<사용자>` 로 보냅니다. registry `audi
 
 상단 메뉴는 대상별 5영역입니다. 현장·자원, 로봇·연결, 임무·정책, 운영, 이력. S1 에서 동작하는 것은 로봇·연결과 이력 2개이고, 나머지 3개 영역은 «다음 단계» 로 표시합니다.
 
-모드 전환(엔지니어/운영자)은 인증 없이 둡니다. 등록과 어댑터 관련 조작은 엔지니어 모드에서, 퇴역·복귀는 운영자 모드에서 합니다. 모드는 §7.1 의 `X-Actor` 에 실려 registry 까지 갑니다. 모드는 화면만 가르지 않습니다. 운영 서비스도 집행하며, 모드가 맞지 않는 조작을 403 으로 막습니다(§9). 화면은 다른 모드의 조작 버튼과 폼을 감추고, 그 자리에 어느 모드에서 하는지를 적습니다(«선언은 엔지니어 모드에서 합니다», «퇴역과 복귀는 운영자 모드에서 합니다»).
+모드 전환(엔지니어/운영자)은 인증 없이 둡니다. 등록과 어댑터 관련 조작은 엔지니어 모드에서, 퇴역·복귀는 운영자 모드에서 합니다. 모드는 §7.1 의 `X-Actor` 에 실려 registry 까지 갑니다. 모드는 화면에만 쓰이지 않습니다. 운영 서비스도 집행하며, 모드가 맞지 않는 조작을 403 으로 막습니다(§9). 화면은 다른 모드의 조작 버튼과 폼을 감추고, 그 자리에 어느 모드에서 하는지를 적습니다(«선언은 엔지니어 모드에서 합니다», «퇴역과 복귀는 운영자 모드에서 합니다»).
 
 | 영역 | 구성 |
 |---|---|
diff --git a/docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md b/docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md
index 2cb0eeb..0f12cfd 100644
--- a/docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md
+++ b/docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md
@@ -306,6 +306,6 @@ PrepareSequencedRack 의 E2 가 서는 순서는 이렇습니다. `pick_place` 
 - mimic gRPC 는 모든 인터페이스에 열리고, 런처는 그 포트를 `.env` 로 고정합니다. 인증 없는 기체 제어 표면입니다.
 - 호스트 REST 는 루프백·무인증이라 같은 기계의 다른 프로세스가 운영자 모드 검사 없이 작업 지시를 낼 수 있습니다.
 - 배정 가능 판정은 제출 직전에 다시 하지만, 판정과 `assign` 사이의 연결 변화는 막지 않습니다.
-- P4 의 재부착은 오류의 종류를 가르지 않습니다. 늘 실패하는 스트림은 pump 마다 다시 열리고, 그 태스크는 미들웨어의 진행 정체 판정(`stallWindow`)이 잡을 때까지 남습니다.
+- P4 의 재부착은 오류의 종류를 구분하지 않습니다. 늘 실패하는 스트림은 pump 마다 다시 열리고, 그 태스크는 미들웨어의 진행 정체 판정(`stallWindow`)이 잡을 때까지 남습니다.
 - mimic 의 태스크 관측 적재가 엔진 잠금 아래에서 동기 HTTP 로 돌아, registry 장애가 mimic 의 응답 지연으로 번집니다.
 - 다음은 S3b(임무 버전 저장·JSON 편집기·검증·모의 실행·활성화·도는 중 전환, 이름 있는 신호를 셀 대역에), S3c, S4 입니다.
diff --git a/ui/e2e/lifecycle.spec.ts b/ui/e2e/lifecycle.spec.ts
index 78ea2d3..416398e 100644
--- a/ui/e2e/lifecycle.spec.ts
+++ b/ui/e2e/lifecycle.spec.ts
@@ -215,6 +215,89 @@ test('화면에서 기체 생애주기와 어댑터 등록을 한 번 돌고 reg
   await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
   await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN true / })).toBeVisible()
 
+  // 장애 주입(S4a 스펙 §3). 운영자가 데이터 정의 버전 1 로 슬롯 하나짜리 PrepareSequencedRack 을 내고, pick_place 가 도는 동안
+  // 엔지니어가 현장·자원 영역에서 스킬 실패를 넣는다. 슬롯 S01 은 아직 채운 적이 없어 근거가 없으므로 단위가 FAILED 이고
+  // GRASP_FAILED 인시던트가 선다. 현장 기체의 pick_place 는 이것이 처음이고 강제한 태스크는 자연 실패 추첨을 하지 않는다.
+  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
+  await order.getByLabel('RACK-204.S01').check()
+  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
+  await order.getByRole('button', { name: '작업 지시 내기' }).click()
+  const rackRuns = executions.getByRole('row').filter({ hasText: 'PrepareSequencedRack' })
+  const graspRun = rackRuns.filter({ hasText: 'RACK-204.S01 pick_place' })
+  // 작업 지시 직후의 태스크는 ACCEPTED 라 현장이 거부한다. RUNNING 이 보인 뒤에 넣는다. pick_place 는 45초 ±10% 다.
+  await expect(graspRun).toContainText('RACK-204.S01 pick_place: RUNNING')
+  await page.getByLabel('엔지니어').check()
+  await page.getByRole('button', { name: '현장·자원' }).click()
+  const faultForm = page.getByRole('form', { name: '장애 주입 폼' })
+  await faultForm.getByLabel('기체').selectOption('humanoid-01')
+  await faultForm.getByLabel('장애 종류').selectOption('SKILL_EXECUTION_FAILED')
+  await faultForm.getByLabel('장애 주입 사유').fill('스킬 실패 시연')
+  await faultForm.getByRole('button', { name: '장애 넣기' }).click()
+  await expect(page.getByRole('status', { name: '장애 주입 결과' })).toHaveText(
+    /^humanoid-01 스킬 실패: 받아들임\(태스크 .+#RACK-204\.S01, RETRIABLE\)$/,
+  )
+  await page.getByRole('button', { name: '운영', exact: true }).click()
+  const incidentTable = page.getByRole('table', { name: '인시던트 목록' })
+  const grasp = incidentTable.getByRole('row').filter({ hasText: 'GRASP_FAILED' })
+  // 현장 설정은 버전 3(stallWindow 변경), 임무는 데이터 정의 버전 1 이다.
+  await expect(grasp).toContainText('humanoid-01')
+  await expect(grasp.getByRole('cell').nth(7)).toHaveText('버전 3')
+  await expect(grasp.getByRole('cell').nth(8)).toHaveText('버전 1')
+  await expect(grasp.getByRole('cell').nth(9)).toHaveText('판단 대상 아님')
+  await expect(graspRun).toContainText('RACK-204.S01 pick_place: FAILED')
+
+  // 운영자 보류(S4a 스펙 §3). 활성화한 보류 버전은 DB 에 남으므로 맨 끝에 둔다. 런처는 1:1 실시간이고 시계를 밀 수단이 없어
+  // 기한 20초를 실제 시간으로 기다린다. 재작업 뒤 대기는 새 기한 20초로 다시 돌므로 다시 돈 것을 본 즉시 신호를 켠다.
+  await page.getByRole('button', { name: '임무·정책' }).click()
+  await editor.getByRole('button', { name: '운영자 보류 대기 템플릿 불러오기' }).click()
+  await editor.getByRole('button', { name: '초안 저장' }).click()
+  await expect(missionNotice).toHaveText('초안 저장: 초안 2 저장됨')
+  await editor.getByRole('button', { name: '모의 실행', exact: true }).click()
+  await expect(missionNotice).toContainText('초안 2 모의 실행: 통과')
+  await editor.getByLabel('활성화 사유').fill('운영자 보류 대기 도입')
+  await editor.getByRole('button', { name: '활성화', exact: true }).click()
+  await expect(missionNotice).toHaveText('초안 2 활성화: 버전 2 활성화됨. 다음 작업 지시부터 이 버전을 씁니다')
+
+  // 셀 대역 신호는 스스로 돌아가지 않는다. 앞에서 켠 rack_present 를 끄지 않으면 대기가 곧바로 끝난다.
+  await page.getByLabel('운영자').check()
+  await page.getByRole('button', { name: '운영', exact: true }).click()
+  await signals.getByRole('button', { name: 'rack_present 끄기' }).click()
+  await expect(signals.getByRole('row', { name: /^rack_present BOOLEAN false / })).toBeVisible()
+  // 영역을 옮기면 작업 지시 폼이 처음 값(InspectAsset)으로 돌아온다.
+  await order.getByLabel('임무').selectOption('PrepareSequencedRack')
+  await order.getByLabel('RACK-204.S02').check()
+  await order.getByLabel('자재').selectOption('ENGINE-COVER-A')
+  await order.getByRole('button', { name: '작업 지시 내기' }).click()
+  const holdRun = rackRuns.filter({ hasText: 'rack-arrival equipment_wait' })
+  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING')
+  const held = incidentTable.getByRole('row').filter({ hasText: '보류 중' })
+  await expect(held).toContainText('SIGNAL_DEADLINE')
+  await expect(held.getByRole('cell').nth(8)).toHaveText('버전 2')
+  await expect(held.getByRole('cell').nth(9)).toHaveText('미해결')
+  await held.getByRole('button', { name: /상세 보기$/ }).click()
+  const incidentDetail = page.getByRole('region', { name: '인시던트 상세' })
+  await expect(incidentDetail).toContainText('사람의 판단 없음. 아래 값은 모두 관측입니다')
+  // 판단 버튼은 운영자 모드에서만 보인다.
+  await page.getByLabel('엔지니어').check()
+  await expect(incidentDetail.getByText('보류 중입니다. 운영자 판단은 운영자 모드에서 합니다', { exact: true })).toBeVisible()
+  await expect(incidentDetail.getByRole('button', { name: '재작업' })).toHaveCount(0)
+  await page.getByLabel('운영자').check()
+  const decision = incidentDetail.getByRole('form', { name: '운영자 판단' })
+  await decision.getByLabel('판단 사유').fill('랙 재배치 뒤 재작업')
+  await decision.getByRole('button', { name: '재작업' }).click()
+  await expect(page.getByRole('status', { name: '판단 결과' })).toHaveText(/\/rack-arrival 재작업: 판단이 섰습니다\(incident-\d+\)$/)
+  await expect(holdRun).toContainText('rack-arrival equipment_wait: RUNNING', { timeout: 10_000 })
+  await signals.getByRole('button', { name: 'rack_present 켜기' }).click()
+  await expect(page.getByRole('status', { name: '신호 조작 결과' })).toHaveText('rack_present 켜기: 반영됨(값 true)')
+  await expect(holdRun).toContainText('PHYSICALLY_DONE', { timeout: 90_000 })
+  const asserted = incidentDetail.getByRole('region', { name: '사람의 판단' })
+  await expect(asserted).toContainText('사람이 판단함: local, ')
+  await expect(asserted).toContainText('결정 재작업(REWORK)')
+  const deadline = incidentTable.getByRole('row').filter({ hasText: 'SIGNAL_DEADLINE' })
+  await expect(deadline).toHaveCount(1)
+  await expect(deadline.getByRole('cell').nth(9)).toHaveText('판단됨')
+  await expect(deadline.getByRole('cell').nth(10)).toHaveText('-')
+
   // registry 를 멈춘다. 런처(registry 와 mimic 이 든 프로세스)를 끈다.
   const pidFile = fileURLToPath(new URL('../../build/site.pid', import.meta.url))
   process.kill(Number(readFileSync(pidFile, 'utf8')))
````

- [ ] **Step 2: 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && ./gradlew :site:installDist :ops-service:installDist :mission-host:installDist -q && cd ui && npx playwright test
```
Expected: Playwright 1 통과(시험 약 3.4분, 기동 포함 약 4.0분, 시험 제한 420초 안). 백그라운드로 돌린다.

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" && git add README.md docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md ui/e2e/lifecycle.spec.ts && git commit -F - <<'EOF'
test(ui): Playwright 생애주기의 장애 주입과 운영자 판단, README S4a

- 생애주기에 스킬 실패 주입과 인시던트 표시, 보류 버전 활성화 뒤 운영자 재작업 판단과 신호 켜기로 종료 단계
- README 에 S4a 장애 주입·인시던트 화면·운영자 판단·보류 템플릿·한계
- S1·S3a 스펙 문장의 용어집 금지어 정정

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```

- [ ] **묶음 대조(컨트롤러)**

```bash
bash "C:/Users/Eisen/AppData/Local/Temp/s4a-cmp.sh" e2e/src/test/kotlin/dev/picasso/ops/e2e/FaultIncidentTest.kt README.md docs/superpowers/specs/2026-10-07-s1-skeleton-robot-lifecycle-design.md docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md ui/e2e/lifecycle.spec.ts
```
Expected: 5개 모두 `같음`.

## Chunk 2: 검증과 병합(컨트롤러)

### Task 7: 결함 주입, 새 클론 빌드, PR

- [ ] **Step 1: 트리 대조**

```bash
git -C "C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" diff --stat origin/main HEAD -- . ':!docs' | tail -1 && git -C "C:/Users/Eisen/AppData/Local/Temp/s4a" diff --stat e482c33 HEAD | tail -1
```
Expected: 두 줄의 파일 수와 줄 수가 같다(43개 파일, 그 가운데 문서 둘은 `:!docs` 로 빠져 41). 그리고 워크트리 `HEAD` 의 코드 경로마다 스파이크 `HEAD` 와 `s4a-cmp.sh` 로 `같음`.

- [ ] **Step 2: 결함 주입 100건**

```bash
cd "C:/Users/Eisen/AppData/Local/Temp/s4a-inject" && S4A_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-ops-wt/s4a" PYTHONUTF8=1 python inject.py
```
Expected: 모두 `탐지`. 백그라운드로 돌린다(오래 걸린다). 같은 워크트리에서 다른 Gradle·npm·Playwright 를 겹쳐 돌리지 않는다. 주입 목록(site S1~S10, 호스트 H1~H23, 운영 서비스 O1~O32, 화면 U1~U27, 통합 I1~I7, Playwright P1)과 잡는 시험 이름은 실행기에 있다.

- [ ] **Step 3: 새 클론 전체 빌드와 Playwright**

워크트리 브랜치를 스크래치에 새로 클론해(서브모듈 포함) `./gradlew build`, ui 의 `npm ci && npm test && npm run lint && npx tsc -b && npm run build`, installDist 셋과 `npx playwright test` 를 백그라운드로 차례로 돌린다(결함 주입과 겹치지 않는다). Expected: site 38, mission-host 64, ops-service 231, e2e 57, vitest 138, Playwright 1, 실패 0.

- [ ] **Step 4: 실행 결과와 PR**

이 계획 끝에 «실행 결과» 절을 더한다(문장은 Codex·Fable 취합). 코드 커밋은 묶음별로 남긴다(스택 보존, 스쿼시 안 함). 푸시, PR, CI 를 한 번 확인한 뒤 `gh pr merge --merge`(`--delete-branch` 쓰지 않음).
