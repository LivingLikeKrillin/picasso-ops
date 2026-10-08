# P3 임무 정의 데이터와 임무 버전 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. 단, 이 계획은 묶음(Task 1·2 / Task 3)마다 구현자 하나가 하고, 검토는 묶음이 끝난 뒤 컨트롤러가 기계 대조와 시험으로 한다. Task 1 은 컴파일되지 않은 채 끝나므로 Task 1 끝에서는 빌드·검토를 하지 않는다.

**Goal:** picasso 에서 임무 정의를 노드형 JSON 데이터로 읽어 실행하고(설비 대기 노드 포함), 검증기를 통과한 정의만 활성화하며, 실행이 생성 때 임무 버전을 쥐어 도는 중에 새 버전이 활성화돼도 옛 실행은 옛 버전으로 끝나게 한다.

**Architecture:** 미션 쪽 새 코드는 `dev.picasso.middleware.mission` 패키지(정의 모델, 엄격 JSON, 파서, 신호 사양, 해석기 `DefinedCapability`, 검증기, 메모리 카탈로그)에 두고, 포트(`MissionCatalog`, `CellSignals.signal`)와 엔진 변경(`Route.SIGNAL`, 설비 대기 단위의 진행, 실행의 `missionVersion`, `submit`·`revise`·`adopt` 의 카탈로그 사용)은 기존 파일에 둔다. 인시던트 번들의 의도에 `missionVersion` 을 더해 해시에 넣고, 내보내기 버전을 6 으로 올린다(인시던트 줄 `route` 의 하위 범주 `SIGNAL`). 인계 번들 네 세트를 다시 산출하고, 문서(ADR 50, 설계 문서 §15.209, seams·orchestration·limits·용어집 등)를 고친다.

**Tech Stack:** Kotlin, protobuf-java-util(`JsonFormat`, 새 의존 없음), JUnit5 + kotlin.test, in-process gRPC 하네스(`Harness`, `ClientRobotPort`, `CellMimic`), picasso 게이트(`DocumentClaimsTest`, `CompletionCriterionTest`, `GroundTruthTest`), `tools/stamp.py`.

**근거 스펙:** picasso-ops `docs/superpowers/specs/2026-10-08-p3-mission-definition-versions-design.md`(스펙 검토 3회). P3a·P3b 는 picasso PR 하나다(스펙 결정 (타)).

**스펙이 계획에 맡긴 것과 이 계획이 정한 것(스파이크에서 정함):**
- 정의 JSON 표기: 최상위 `schemaVersion`(1)·`workMasterId`·`maxEvidence`·`preferredOptionals`·`steps`. 단위 노드는 `id`·`skill`·`forEach`·`unitId`·`parameters` 필수, `pairWith`(`equipmentUse`·`property`)와 `whenUnpaired` 는 함께, 값 출처는 `{"from": "ITEM_ID" | "ITEM_PROPERTY" | "PAIRED_ID", "property"?, "otherwise"?}`. `unitId` 는 `ITEM_ID` 만. 값이 없음(null)이 된 파라미터는 빠진다. 대기 노드는 `id`·`signal`·`expect`·`deadlineSeconds`·`onDeadline`(형만 파서가, 범위는 검증기가). 표기 예는 `MissionDefinitionParser.kt` KDoc 과 `MissionFixtures.kt`.
- `JsonFormat` 은 중복 키에 말없이 뒤엣것을 쓰고 느슨한 문법도 받으므로, 손으로 쓴 `StrictJson` 이 문법과 중복 키를 먼저 본다.
- 거부: `MissionRefusal(kind, nodeId?, observed, expected, checkedAt, basisVersion = null)`, 종류 9개(`UNREADABLE`·`DUPLICATE_NODE_ID`·`DEADLINE_INVALID`·`SIGNAL_NOT_IN_SPEC`·`SIGNAL_VALUE_INVALID`·`FLOOR_UNOWNED`·`SKILL_NOT_IN_CONTRACT`·`SKILL_NOT_ON_SITE`·`SAFETY_SIGNAL_WAIT`), 해결 담당은 종류가 정한다(`FLOOR_UNOWNED` 만 화면 밖).
- 대기 단위: `ExecutionUnit.wait: WaitSpec?`, `WaitSpec(signal, expect, deadline, onDeadline: DeadlineOutcome)`, `skillType = "equipment_wait"`(계약 스킬 이름과 겹치지 않음을 시험이 댐), 실패 분류 `SIGNAL_DEADLINE`, 단위 id = 노드 id, `parameters` 에 대기 사양을 실음.
- 기한 뒤 `ABORTED` 의 작업 응답: `physicalState=ABORTED`, `completedUnits=[]`, `incompleteUnits` 는 대기 노드가 `SIGNAL_DEADLINE`·나머지가 `not started: …`, `operatorRequired=false`, `lastCancel` 없음(`abort(..., requested = false)`).
- 생성자: `capabilities: List<LogicalCapability>? = null`, `missions: MissionCatalog? = null`, 둘 다 주면 `require` 실패. 생성자 인자 `missions` 가 같은 이름의 속성을 가리므로 다른 속성의 초기화식에서는 `this.missions` 로 쓴다(코드 주석).
- 메모리 카탈로그: `InMemoryMissionCatalog(code, now)`, `synchronized`, `activate(json, signals, floors, siteSkills): Activated | Refused`, WorkMaster 마다 1부터, 거부는 번호를 쓰지 않음.
- 검증기가 못 보는 것: 작업 지시 설비 id 와 대기 노드 id 의 겹침(설비 id 는 작업 지시가 와야 안다) → 한계 레지스터 행. 스펙 §5.3 의 «검증기가 본다» 와 다르다. 스펙은 «정확한 규칙은 계획» 이라 했고 스파이크에서 활성화 때 알 수 없음을 확인해 한계로 돌렸다(스펙 정정 줄은 picasso-ops 의 스펙·계획 PR(`docs/p3-mission-design`)에 넣는다. 스펙은 picasso-ops 파일이라 picasso PR 에 넣지 않는다).
- 묶음: 스펙 (타)·§3 은 P3a(정의 데이터)·P3b(버전) 묶음을 말하지만, 이 계획은 파일 경계를 따라 Task 1(미션 패키지·모델·포트)·Task 2(엔진·인시던트·내보내기·인계 번들)·Task 3(문서)으로 나눈다. 패치가 파일 단위라 한 파일의 P3a·P3b 변경을 가를 수 없기 때문이다. PR 은 하나라 결과는 같고, 스펙 §3 의 완료 판정은 Task 3 끝에서 한 번에 본다.
- 리비전이 WorkMaster 를 바꾸면 거부한다(스펙 §6.2). 시험 1개와 결함 주입 I15 로 입증한다.
- 용어집(`docs/glossary.md`)에 새 용어 넷(임무 정의, 임무 버전, 설비 대기, 신호 사양)을 넣는다(스펙 §7 이 계획에 맡긴 것).
- 문서: `docs/seams.md` 의 임무 정의 카탈로그는 «9대 접합부» 의 10번이 아니라 그 절 밖의 데이터 접합부 절(9대·그림은 그대로). ADR 47 은 고치지 않고 ADR 50 이 «조회 버전 6 은 `SIGNAL` 이 썼다» 를 짚는다. 새 오픈 항목 7개, 열림 총수 139. 문서 문장은 Codex·Fable 초안 취합.
- 실측: 새 시험 46(`MissionDefinitionParserTest` 9, `DefinedCapabilityEquivalenceTest` 6, `MissionValidatorTest` 12, `InMemoryMissionCatalogTest` 5, `EquipmentWaitTest` 8, `MissionVersionScenarioTest` 6), 전체 1,931 → 1,977. 결함 주입 22건.
- 문장: 커밋·PR·문서 문장은 Codex 와 Fable 초안을 취합한다(2026-10-08 세션의 사용자 규칙. 메모리 `prose-via-gemini.md` 의 Gemini 지시보다 뒤의 규칙이다).

**작업 위치 규칙(필수):**
- 모든 작업은 picasso 워크트리 `C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission`(브랜치 `feat/p3-mission-versions`)에서 한다. picasso 메인 체크아웃(`C:/Users/Eisen/Desktop/Labs/[projects] picasso`, khala 가 docs/ 를 읽는다)과 다른 저장소는 건드리지 않는다. 하위 에이전트의 Bash 는 호출마다 작업 디렉터리가 돌아가므로 명령마다 `cd <워크트리> &&` 를 붙이거나 `git -C` 를 쓴다.
- `./gradlew --stop` 금지(데몬 풀이 다른 체크아웃과 공유된다). 같은 워크트리에서 Gradle 을 겹쳐 돌리지 않는다. Bash 도구의 시간 한도(600초)를 넘는 빌드는 백그라운드로 돌리고 끝났다는 알림을 받은 뒤 다음 Gradle 을 돌린다.
- `git add -A` 금지. 파일을 이름으로 더한다(폴더 이름으로 더할 때는 그 아래가 이 계획의 파일뿐인지 `git status` 로 본다).
- 시험 판정은 종료 코드가 아니라 `*/build/test-results/test/*.xml` 의 실패 시험 이름으로 한다. 아래 Expected 의 수는 XML 파일 수가 아니라 `<testcase>` 수다. XML 의 testcase 수는 `python -c "import glob,xml.etree.ElementTree as E;print(sum(len(list(E.parse(f).getroot().iter('testcase'))) for f in glob.glob('MOD/build/test-results/test/*.xml')))"` 로 센다(MOD 자리에 모듈 이름). Windows python 으로 XML 을 셀 때는 `C:/...` 경로를 쓴다.
- 이 저장소의 작업 트리는 CRLF 다. 새 파일은 CRLF 로 둔다(뽑아 둔 파일은 이미 CRLF).
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. 커밋 메시지는 heredoc(`git commit -F - <<'EOF'`)으로 쓴다. 형식 훅이 메시지를 읽어 제목이 `type(scope): 명사구` 가 아니거나 트레일러가 없거나 겹화살괄호가 있으면 막는다.
- 이 계획의 코드와 문서는 스크래치 스파이크(`C:/Users/Eisen/AppData/Local/Temp/p3`, 브랜치 `feat/p3`, HEAD `ca84b58`)에서 시험, 전체 빌드, 결함 주입을 다 돌린 것이다. 묶음이 끝날 때마다 커밋된 파일을 스파이크와 기계 대조한다(Task 0 Step 3 의 `p3-cmp.sh`).
- 실행 방식: 묶음(Task 1·2 / Task 3)마다 구현 하위 에이전트 1명(`model: "sonnet"`), 결함 주입(Task 4)과 검토·PR 은 컨트롤러.
- **블록을 손으로 옮겨 적지 않는다.** 컨트롤러가 이 계획의 블록을 기계로 뽑아 `C:/Users/Eisen/AppData/Local/Temp/p3-patches/` 에 두었다. 새 파일은 `C:/Users/Eisen/AppData/Local/Temp/p3-patches/files/<경로>` 를 워크트리의 같은 경로로 `cp` 하고, 기존 파일은 `C:/Users/Eisen/AppData/Local/Temp/p3-patches/<이름>.patch` 를 `git apply --check` 로 본 뒤 `git apply` 한다. 아래 블록은 읽고 검토하기 위한 것이다. 뽑은 파일이 없으면 멈추고 보고한다.
- 문서를 고친 커밋 뒤에는 docs/ 만 바뀌었어도 `:picasso:test`(`GroundTruthTest` 가 docs 전체를 훑는다)와 `:gate:test` 를 둘 다 돌린다. 스탬프는 패치에 이미 들어 있다. 문서를 더 고치게 되면 `python tools/stamp.py <파일>` 을 파일마다 돌리고 CRLF 로 되돌린다.

---

## Chunk 1: 코드

### Task 0: 워크트리, 기준선, 대조 도구

**Files:** 없음(환경)

- [ ] **Step 1: 워크트리 만들기**

```bash
cd "C:/Users/Eisen/Desktop/Labs/[projects] picasso"
git fetch -q origin
git worktree add "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" -b feat/p3-mission-versions origin/main
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" branch --unset-upstream
git -C "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" log --oneline -1
```
Expected: `origin/main` 이 `41beedb`. 다르면 멈추고 보고한다.

- [ ] **Step 2: 기준선 시험**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" && ./gradlew :picasso:test :gate:test -q
```
Expected: picasso testcase 297(XML 파일 34), gate testcase 276(XML 파일 26), 실패 0. 백그라운드로 돌린다.

- [ ] **Step 3: 대조 도구와 뽑은 블록**

`C:/Users/Eisen/AppData/Local/Temp/p3-cmp.sh` 와 `C:/Users/Eisen/AppData/Local/Temp/p3-patches/`(패치 3개, `files/` 아래 새 파일 15개)가 있는지 본다. 대조 도구는 인자로 받은 경로마다 워크트리 HEAD 의 파일과 스파이크 HEAD 의 파일을 `\r` 을 빼고 바이트 대조해 `같음`/`다름` 을 찍는다. 없으면 멈추고 보고한다.

### Task 1: 미션 패키지, 모델, 포트

**Files:**
- Create(main, `picasso/src/main/kotlin/dev/picasso/middleware/mission/`): `MissionDefinition.kt`, `StrictJson.kt`, `MissionDefinitionParser.kt`, `SignalSpec.kt`, `DefinedCapability.kt`, `MissionValidator.kt`, `InMemoryMissionCatalog.kt`
- Create(test, `picasso/src/test/kotlin/dev/picasso/middleware/mission/`): `MissionFixtures.kt`, `MissionDefinitionParserTest.kt`, `DefinedCapabilityEquivalenceTest.kt`, `MissionValidatorTest.kt`, `InMemoryMissionCatalogTest.kt`
- Modify: `picasso/src/main/kotlin/dev/picasso/middleware/Model.kt`, `Ports.kt`, `picasso/src/test/kotlin/dev/picasso/middleware/CellMimic.kt`

이 Task 만으로는 컴파일되지 않는다(`Route.SIGNAL` 을 더해 `Middleware.kt` 의 `when (unit.route)` 가 빠짐을 알린다). **이 Task 에서는 Gradle 을 돌리지 않는다.** 시험은 Task 2 뒤에 돈다.

- [ ] **Step 1: 새 파일 12개 복사**

`p3-patches/files/picasso/src/main/kotlin/dev/picasso/middleware/mission/*` 7개와 `p3-patches/files/picasso/src/test/kotlin/dev/picasso/middleware/mission/*` 5개를 워크트리의 같은 경로로 복사한다. 내용은 아래 블록과 같다.

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.Evidence

/**
 * 임무 정의 — WorkMaster 하나를 실행 단위로 펼치는 규칙을 **데이터로** 적은 것(ADR 50).
 *
 * 문서 하나가 WorkMaster 하나의 정의이고, 노드는 직선이다(분기·병렬은 없다). 노드는 둘이다 — 로봇 스킬을 부르는
 * 단위 노드([UnitStep])와 설비 신호를 기다리는 대기 노드([WaitStep]). 단위 하나의 생애(9상태)와 보장 전이는 엔진이
 * 고정하고, 정의는 그 안쪽을 그리지 않는다.
 *
 * 이 모양은 [MissionDefinitionParser] 가 읽은 그대로다 — **모양과 형만 맞다.** 기한이 양수인지, 참조한 신호가 있는지
 * 같은 판정은 [MissionValidator] 의 일이고, 활성화는 그것을 통과해야 선다([InMemoryMissionCatalog]).
 *
 * @param schemaVersion 이 문서 모양의 버전. 지금은 1 뿐이다. 임무 버전(활성화마다 오르는 번호)과 다르다.
 * @param maxEvidence 이 정의가 제공할 수 있는 최고 근거 등급(E0~E2).
 * @param preferredOptionals 로봇이 선언하면 붙이는 선택 파라미터(`LogicalCapability.preferredOptionals`).
 */
data class MissionDefinition(
    val schemaVersion: Int,
    val workMasterId: String,
    val maxEvidence: Evidence,
    val preferredOptionals: Map<String, String>,
    val steps: List<MissionStep>,
)

/** 직선 노드 하나. [id] 는 정의 안에서 겹치면 안 된다 — 검증기가 본다. */
sealed interface MissionStep {
    val id: String
}

/**
 * 단위 노드(`kind: "unit"`) — 작업 지시 설비 중 [forEach] 쓰임인 것마다 로봇 단위 하나.
 *
 * 값은 전부 [ValueSource] 로 적는다. 짝([pairWith])이 있으면 반복 중인 설비의 속성 값과 같은 값을 가진 다른 쓰임의 설비를
 * 찾는다. 짝이 없으면 단위는 계획 때 `FAILED` 이고 실패 분류는 [whenUnpaired] 다 — 시작도 못 하는 부족은 계획에서 드러난다.
 *
 * @param skill 계약 카탈로그의 스킬 이름.
 * @param unitId 단위 id 의 출처. **반복 중인 설비의 id 만 허용한다**(파서가 막는다) — 작업 응답과 리비전이 단위 id 로
 *   접으므로 비거나 갈리면 안 된다.
 * @param parameters 파라미터 이름 → 값 출처. 값이 없으면(대체값도 없으면) 그 파라미터는 빠진다.
 */
data class UnitStep(
    override val id: String,
    val skill: String,
    val forEach: String,
    val unitId: ValueSource,
    val parameters: Map<String, ValueSource>,
    val expectedIdentity: ValueSource?,
    val source: ValueSource?,
    val destination: ValueSource?,
    val pairWith: Pairing?,
    val whenUnpaired: String?,
) : MissionStep

/**
 * 설비 대기 노드(`kind: "wait"`) — 이름 있는 신호가 [expect] 를 읽을 때까지 기다린다.
 *
 * [deadlineSeconds]·[onDeadline] 이 널일 수 있는 것은 **파서가 값을 판정하지 않기 때문이다.** 없거나 허용 밖인 것은
 * 검증기의 기한 검사가 거부한다 — 그래야 그 검사를 빼는 결함이 검증기 시험에 잡힌다.
 */
data class WaitStep(
    override val id: String,
    val signal: String,
    val expect: String,
    val deadlineSeconds: Long?,
    val onDeadline: String?,
) : MissionStep

/** 짝 규칙 — [equipmentUse] 쓰임의 설비 중 [property] 가 반복 중인 설비의 같은 속성과 같은 것. 여럿이면 목록에서 뒤엣것. */
data class Pairing(val equipmentUse: String, val property: String)

/**
 * 값 하나의 출처.
 *
 * @param property [ValueFrom.ITEM_PROPERTY] 일 때 읽을 속성 이름. 다른 출처에서는 널이다.
 * @param otherwise 출처가 값을 못 낼 때(속성이 없다, 짝이 없다) 쓸 대체값. 널이면 값이 없다(`null`).
 */
data class ValueSource(val from: ValueFrom, val property: String? = null, val otherwise: String? = null)

/**
 * 값 출처의 종류 셋.
 *
 * - [ITEM_ID] — 반복 중인 설비의 id. 언제나 있다
 * - [ITEM_PROPERTY] — 반복 중인 설비의 속성. 그 속성이 없으면 값이 없다
 * - [PAIRED_ID] — 짝지은 설비의 id. 짝이 없으면 값이 없다
 */
enum class ValueFrom { ITEM_ID, ITEM_PROPERTY, PAIRED_ID }
```

```kotlin
package dev.picasso.middleware.mission

/**
 * JSON 의 **문법과 중복 키만** 본다 — 값은 읽지 않는다. 값은 `JsonFormat` 이 `Struct` 로 읽는다.
 *
 * 따로 두는 이유는 `JsonFormat` 이 너그럽기 때문이다. 같은 키가 둘이면 말없이 뒤엣것을 쓰고, 끝에 붙은 글자와 따옴표 없는
 * 키를 받아 준다. 임무 정의에서 같은 키가 둘이면 쓴 사람이 어느 쪽을 뜻했는지 모른다 — 하나를 고르면 고르지 않은 쪽이
 * 조용히 사라진다. 그래서 **중복 키는 거부한다.** 새 의존을 들이지 않으려고(결정 (차)) 손으로 쓴다.
 *
 * 문법이 틀린 자리를 만나면 거기서 멈추고 그 하나만 알린다(그 뒤는 읽을 수 없다). 중복 키는 문법이 맞는 동안 전부 모은다.
 */
internal object StrictJson {

    /**
     * @param problems 경로는 `$.steps[0].id` 모양이다. 비었으면 문법이 맞고 중복 키가 없다.
     * @param broken 문법이 틀렸는가. 참이면 **값을 읽지 않는다** — 너그러운 파서가 고쳐 읽은 값으로 칸을 대면 쓴 사람이
     *   적지 않은 문서를 판정하게 된다.
     */
    class Scanned(val problems: List<String>, val broken: Boolean)

    fun scan(text: String): Scanned {
        val scan = Scan(text)
        try {
            scan.ws()
            scan.value("$")
            scan.ws()
            if (scan.i < text.length) scan.fail("$", "JSON 값 뒤에 글자가 더 있다")
        } catch (stop: Stop) {
            return Scanned(scan.found + stop.problem, broken = true)
        }
        return Scanned(scan.found, broken = false)
    }

    private class Stop(val problem: String) : RuntimeException(problem, null, false, false)

    private class Scan(val s: String) {
        var i = 0
        val found = mutableListOf<String>()

        fun fail(path: String, what: String): Nothing = throw Stop("$path: $what (${i + 1}번째 글자)")

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(path: String) {
            if (i >= s.length) fail(path, "값이 없다")
            when (val c = s[i]) {
                '{' -> obj(path)
                '[' -> arr(path)
                '"' -> string(path)
                't' -> word(path, "true")
                'f' -> word(path, "false")
                'n' -> word(path, "null")
                else -> if (c == '-' || c.isDigit()) number(path) else fail(path, "JSON 값이 아니다 '$c'")
            }
        }

        fun obj(path: String) {
            i++ // {
            val keys = mutableSetOf<String>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail(path, "키는 큰따옴표 문자열이어야 한다")
                val key = string(path)
                if (!keys.add(key)) found += "$path: 키 '$key' 가 두 번 나온다"
                ws()
                if (i >= s.length || s[i] != ':') fail(path, "키 '$key' 뒤에 ':' 가 없다")
                i++
                ws()
                value("$path.$key")
                ws()
                if (i >= s.length) fail(path, "객체가 닫히지 않았다")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return
                    }
                    else -> fail(path, "',' 나 '}' 가 와야 한다")
                }
            }
        }

        fun arr(path: String) {
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return
            }
            var index = 0
            while (true) {
                ws()
                value("$path[$index]")
                index++
                ws()
                if (i >= s.length) fail(path, "배열이 닫히지 않았다")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return
                    }
                    else -> fail(path, "',' 나 ']' 가 와야 한다")
                }
            }
        }

        /** 문자열 하나를 읽고 풀어 쓴 값을 돌려준다 — 키 비교가 `"a"` 와 `"a"` 를 같게 보도록. */
        fun string(path: String): String {
            i++ // "
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail(path, "문자열이 닫히지 않았다")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail(path, "문자열이 닫히지 않았다")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail(path, "\\u 뒤에 16진 넷이 없다")
                                val hex = s.substring(i, i + 4)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail(path, "\\u 뒤에 16진 넷이 없다"))
                                i += 4
                            }
                            else -> fail(path, "모르는 탈출 문자 '\\$e'")
                        }
                    }
                    c < ' ' -> fail(path, "문자열 안에 제어 문자가 있다")
                    else -> out.append(c)
                }
            }
        }

        fun word(path: String, w: String) {
            if (!s.startsWith(w, i)) fail(path, "JSON 값이 아니다")
            i += w.length
        }

        fun number(path: String) {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) fail(path, "수가 아니다")
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isDigit()) fail(path, "소수점 뒤에 숫자가 없다")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isDigit()) fail(path, "지수에 숫자가 없다")
                while (i < s.length && s[i].isDigit()) i++
            }
            check(i > start)
        }
    }
}
```

````kotlin
package dev.picasso.middleware.mission

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.middleware.Evidence

/** 임무 정의를 읽은 결과 — 읽었거나, 읽을 수 없는 자리를 **모두** 든다. */
sealed interface MissionParse {
    data class Parsed(val definition: MissionDefinition) : MissionParse

    /** 틀린 곳 전부. 하나만 알리면 고치고 다시 내고 또 거부되는 왕복이 틀린 곳 수만큼 생긴다. */
    data class Unreadable(val problems: List<String>) : MissionParse
}

/**
 * 임무 정의 JSON 의 **엄격한** 읽기 — 모르는 키, 빠진 필수 칸, 형이 틀린 값을 모두 모아 한 번에 알린다.
 *
 * **모양과 형만 본다.** 기한이 양수인지, 기한 뒤 상태가 허용된 값인지, 참조한 신호·스킬이 있는지는 [MissionValidator] 의
 * 일이다. 파서가 그것까지 보면 검증기의 검사를 빼는 결함이 파서에 가려 안 잡힌다.
 *
 * 값은 `JsonFormat` 으로 `Struct` 에 읽는다 — 새 의존을 들이지 않는다(결정 (차)). `Struct` 의 수는 실수로 오므로
 * 정수 칸은 정수인지 따로 본다. 문법과 중복 키는 `JsonFormat` 이 너그러워 [StrictJson] 이 먼저 본다.
 *
 * ## 표기
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "workMasterId": "PrepareSequencedRack",
 *   "maxEvidence": "E2",
 *   "preferredOptionals": { "verify_grasp": "true" },
 *   "steps": [
 *     { "kind": "wait", "id": "rack-arrival", "signal": "rack_present", "expect": "true",
 *       "deadlineSeconds": 120, "onDeadline": "OPERATOR_HOLD" },
 *     { "kind": "unit", "id": "place", "skill": "pick_place", "forEach": "destination",
 *       "pairWith": { "equipmentUse": "source", "property": "material" },
 *       "whenUnpaired": "NO_SOURCE_FOR_MATERIAL",
 *       "unitId": { "from": "ITEM_ID" },
 *       "parameters": {
 *         "object_id": { "from": "PAIRED_ID", "otherwise": "" },
 *         "destination": { "from": "ITEM_ID" }
 *       },
 *       "expectedIdentity": { "from": "ITEM_PROPERTY", "property": "material" },
 *       "source": { "from": "PAIRED_ID" },
 *       "destination": { "from": "ITEM_ID" } }
 *   ]
 * }
 * ```
 *
 * 최상위 다섯 칸은 모두 필수다. 단위 노드는 `id`·`skill`·`forEach`·`unitId`·`parameters` 가 필수이고 나머지는 선택이며,
 * `pairWith` 와 `whenUnpaired` 는 함께 온다. 대기 노드는 `id`·`signal`·`expect` 가 필수이고 `deadlineSeconds`·`onDeadline`
 * 은 형만 본다(없음은 검증기가 거부한다).
 */
object MissionDefinitionParser {

    /** 지금 읽을 줄 아는 문서 모양의 버전. */
    const val SCHEMA_VERSION = 1

    fun parse(text: String): MissionParse {
        val scanned = StrictJson.scan(text)
        if (scanned.broken) return MissionParse.Unreadable(scanned.problems)
        val root = try {
            Struct.newBuilder().also { JsonFormat.parser().merge(text, it) }.build()
        } catch (e: InvalidProtocolBufferException) {
            return MissionParse.Unreadable(scanned.problems + "$: JSON 객체가 아니다 — ${e.message}")
        }
        val reader = Reader()
        val definition = reader.definition(root.fieldsMap)
        val problems = scanned.problems + reader.problems
        return if (problems.isEmpty() && definition != null) MissionParse.Parsed(definition) else MissionParse.Unreadable(problems)
    }

    private val TOP = setOf("schemaVersion", "workMasterId", "maxEvidence", "preferredOptionals", "steps")
    private val UNIT = setOf(
        "kind", "id", "skill", "forEach", "unitId", "parameters",
        "expectedIdentity", "source", "destination", "pairWith", "whenUnpaired",
    )
    private val WAIT = setOf("kind", "id", "signal", "expect", "deadlineSeconds", "onDeadline")
    private val PAIRING = setOf("equipmentUse", "property")
    private val VALUE_SOURCE = setOf("from", "property", "otherwise")

    /** 정의가 낼 수 있는 등급. E3(업무 확인)은 상위의 ack 이라 케이퍼빌리티가 약속할 수 없다. */
    private val EVIDENCE = listOf(Evidence.E0, Evidence.E1, Evidence.E2)

    /**
     * 읽는 동안 틀린 곳을 모은다. 칸 하나가 틀려도 **나머지를 계속 읽는다** — 그래야 한 번에 다 알린다.
     * 틀린 칸의 함수는 널을 돌려주고, 널을 받은 쪽은 문제를 다시 적지 않는다.
     */
    private class Reader {
        val problems = mutableListOf<String>()

        private fun problem(path: String, what: String) {
            problems += "$path: $what"
        }

        private fun unknownKeys(fields: Map<String, Value>, allowed: Set<String>, path: String) {
            (fields.keys - allowed).sorted().forEach { problem(path, "모르는 키 '$it'") }
        }

        private fun present(fields: Map<String, Value>, key: String, path: String, required: Boolean): Value? {
            val value = fields[key]
            if (value == null) {
                if (required) problem("$path.$key", "빠졌다")
                return null
            }
            return value
        }

        fun string(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): String? {
            val value = present(fields, key, path, required) ?: return null
            if (!value.hasStringValue()) {
                problem("$path.$key", "문자열이어야 한다(${kindOf(value)})")
                return null
            }
            if (value.stringValue.isEmpty() && required) {
                problem("$path.$key", "비었다")
                return null
            }
            return value.stringValue
        }

        fun integer(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): Long? {
            val value = present(fields, key, path, required) ?: return null
            val n = if (value.hasNumberValue()) value.numberValue else null
            // `Struct` 는 수를 실수로 준다 — 1.5 나 1e300 을 정수로 접으면 쓴 값과 다른 값으로 돈다.
            if (n == null || n != Math.rint(n) || n < Long.MIN_VALUE.toDouble() || n > Long.MAX_VALUE.toDouble()) {
                problem("$path.$key", "정수여야 한다(${kindOf(value)})")
                return null
            }
            return n.toLong()
        }

        fun obj(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): Map<String, Value>? {
            val value = present(fields, key, path, required) ?: return null
            if (!value.hasStructValue()) {
                problem("$path.$key", "객체여야 한다(${kindOf(value)})")
                return null
            }
            return value.structValue.fieldsMap
        }

        fun stringMap(fields: Map<String, Value>, key: String, path: String): Map<String, String>? {
            val map = obj(fields, key, path) ?: return null
            val out = linkedMapOf<String, String>()
            map.forEach { (k, v) ->
                if (v.hasStringValue()) out[k] = v.stringValue else problem("$path.$key.$k", "문자열이어야 한다(${kindOf(v)})")
            }
            return out
        }

        fun definition(fields: Map<String, Value>): MissionDefinition? {
            val path = "$"
            unknownKeys(fields, TOP, path)
            val schemaVersion = integer(fields, "schemaVersion", path)
            if (schemaVersion != null && schemaVersion != SCHEMA_VERSION.toLong()) {
                problem("$path.schemaVersion", "모르는 문서 버전이다($schemaVersion) — 읽을 줄 아는 것은 $SCHEMA_VERSION 이다")
            }
            val workMasterId = string(fields, "workMasterId", path)
            val maxEvidence = string(fields, "maxEvidence", path)?.let { name ->
                EVIDENCE.firstOrNull { it.name == name }
                    ?: null.also { problem("$path.maxEvidence", "${EVIDENCE.joinToString("·")} 중 하나여야 한다($name)") }
            }
            val optionals = stringMap(fields, "preferredOptionals", path)
            val steps = steps(fields, path)
            if (problems.isNotEmpty()) return null
            return MissionDefinition(
                schemaVersion!!.toInt(), workMasterId!!, maxEvidence!!, optionals!!, steps!!,
            )
        }

        private fun steps(fields: Map<String, Value>, path: String): List<MissionStep>? {
            val value = present(fields, "steps", path, required = true) ?: return null
            if (!value.hasListValue()) {
                problem("$path.steps", "배열이어야 한다(${kindOf(value)})")
                return null
            }
            val items = value.listValue.valuesList
            if (items.isEmpty()) problem("$path.steps", "노드가 하나도 없다")
            return items.mapIndexedNotNull { index, item ->
                val at = "$path.steps[$index]"
                if (!item.hasStructValue()) {
                    problem(at, "객체여야 한다(${kindOf(item)})")
                    return@mapIndexedNotNull null
                }
                val node = item.structValue.fieldsMap
                when (val kind = string(node, "kind", at)) {
                    null -> null
                    "unit" -> unit(node, at)
                    "wait" -> wait(node, at)
                    else -> null.also { problem("$at.kind", "unit 이나 wait 여야 한다($kind)") }
                }
            }
        }

        private fun unit(node: Map<String, Value>, path: String): UnitStep? {
            unknownKeys(node, UNIT, path)
            val id = string(node, "id", path)
            val skill = string(node, "skill", path)
            val forEach = string(node, "forEach", path)
            val pairWith = obj(node, "pairWith", path, required = false)?.let { pairing(it, "$path.pairWith") }
            val whenUnpaired = string(node, "whenUnpaired", path, required = false)
            if (("pairWith" in node) != ("whenUnpaired" in node)) {
                problem(path, "pairWith 와 whenUnpaired 는 함께 온다 — 짝이 없을 때의 실패 분류가 없으면 그 단위를 어떻게 둘지 모른다")
            }
            val paired = "pairWith" in node
            val unitId = source(node, "unitId", path, required = true, paired = paired)
            if (unitId != null && unitId.from != ValueFrom.ITEM_ID) {
                problem("$path.unitId.from", "ITEM_ID 여야 한다(${unitId.from}) — 단위 id 는 반복 중인 설비의 id 다")
            }
            val parameters = obj(node, "parameters", path)?.let { map ->
                val out = linkedMapOf<String, ValueSource>()
                map.forEach { (name, v) ->
                    val at = "$path.parameters.$name"
                    if (!v.hasStructValue()) {
                        problem(at, "값 출처 객체여야 한다(${kindOf(v)})")
                    } else {
                        valueSource(v.structValue.fieldsMap, at, paired)?.let { out[name] = it }
                    }
                }
                out
            }
            val expected = source(node, "expectedIdentity", path, required = false, paired = paired)
            val source = source(node, "source", path, required = false, paired = paired)
            val destination = source(node, "destination", path, required = false, paired = paired)
            if (id == null || skill == null || forEach == null || unitId == null || parameters == null) return null
            return UnitStep(id, skill, forEach, unitId, parameters, expected, source, destination, pairWith, whenUnpaired)
        }

        private fun pairing(fields: Map<String, Value>, path: String): Pairing? {
            unknownKeys(fields, PAIRING, path)
            val use = string(fields, "equipmentUse", path)
            val property = string(fields, "property", path)
            return if (use == null || property == null) null else Pairing(use, property)
        }

        private fun source(node: Map<String, Value>, key: String, path: String, required: Boolean, paired: Boolean): ValueSource? =
            obj(node, key, path, required)?.let { valueSource(it, "$path.$key", paired) }

        private fun valueSource(fields: Map<String, Value>, path: String, paired: Boolean): ValueSource? {
            unknownKeys(fields, VALUE_SOURCE, path)
            val fromName = string(fields, "from", path) ?: return null
            val from = ValueFrom.entries.firstOrNull { it.name == fromName }
                ?: return null.also { problem("$path.from", "${ValueFrom.entries.joinToString("·")} 중 하나여야 한다($fromName)") }
            val property = string(fields, "property", path, required = from == ValueFrom.ITEM_PROPERTY)
            if (from != ValueFrom.ITEM_PROPERTY && "property" in fields) {
                problem("$path.property", "${ValueFrom.ITEM_PROPERTY} 에만 온다")
            }
            if (from == ValueFrom.PAIRED_ID && !paired) {
                problem("$path.from", "${ValueFrom.PAIRED_ID} 인데 이 노드에 pairWith 가 없다")
            }
            val otherwise = string(fields, "otherwise", path, required = false)
            if (from == ValueFrom.ITEM_PROPERTY && property == null) return null
            return ValueSource(from, property, otherwise)
        }

        private fun wait(node: Map<String, Value>, path: String): WaitStep? {
            unknownKeys(node, WAIT, path)
            val id = string(node, "id", path)
            val signal = string(node, "signal", path)
            // 기대 값은 빈 문자열도 값이다 — TEXT 신호가 비었을 때를 기다릴 수 있다.
            val expect = present(node, "expect", path, required = true)?.let { v ->
                if (v.hasStringValue()) v.stringValue else null.also { problem("$path.expect", "문자열이어야 한다(${kindOf(v)})") }
            }
            val deadline = integer(node, "deadlineSeconds", path, required = false)
            val onDeadline = string(node, "onDeadline", path, required = false)
            if (id == null || signal == null || expect == null) return null
            return WaitStep(id, signal, expect, deadline, onDeadline)
        }

        private fun kindOf(value: Value): String = when (value.kindCase) {
            Value.KindCase.NULL_VALUE -> "null"
            Value.KindCase.NUMBER_VALUE -> "수 ${value.numberValue}"
            Value.KindCase.STRING_VALUE -> "문자열 '${value.stringValue}'"
            Value.KindCase.BOOL_VALUE -> "참거짓 ${value.boolValue}"
            Value.KindCase.STRUCT_VALUE -> "객체"
            Value.KindCase.LIST_VALUE -> "배열"
            else -> "값 없음"
        }
    }
}
````

```kotlin
package dev.picasso.middleware.mission

/**
 * 현장 신호 사양의 한 줄 — 설비 대기가 이름으로 참조하는 신호가 무엇인가(현장 데이터).
 *
 * 이름과 성질까지다. **어느 주소가 어느 신호인가의 매핑은 여기 없다** — 그것은 드라이버의 일이고, 이 계층은 이름으로
 * 읽는다(`CellSignals.signal`).
 *
 * @param location 그 신호를 내는 자리. 있으면 검증기의 자원 검사가 그 자리의 바닥 소유를 본다. 없으면 그 검사에서 빠진다.
 * @param safety 안전 신호인가. **안전 신호를 기다리는 대기는 거부한다** — 안전 기능은 이 소프트웨어 계약을 거치지
 *   않는다(ADR 32).
 */
data class SignalSpec(
    val name: String,
    val location: String? = null,
    val kind: SignalKind,
    val safety: Boolean = false,
)

/** 신호 값의 종류. [BOOLEAN] 이면 값은 `true`·`false` 둘이다. [TEXT] 는 어떤 문자열이든 된다. */
enum class SignalKind { BOOLEAN, TEXT }
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.Route
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.WaitSpec
import java.time.Duration

/**
 * 해석기 — 임무 정의([MissionDefinition])를 [LogicalCapability] 로 바꾼다.
 *
 * 그래서 실행·리비전·인시던트는 코드 케이퍼빌리티와 **같은 경로**를 탄다. 엔진이 아는 것은 계획된 단위 시퀀스뿐이고,
 * 그것이 코드에서 왔는지 데이터에서 왔는지 모른다.
 *
 * **검증을 통과한 정의만 받는다.** 대기 노드의 기한과 기한 뒤 상태를 생성 때 엔진의 값으로 바꾸며, 없거나 허용 밖이면
 * 여기서 실패한다 — 계획 때 실패하면 작업 지시를 받는 도중에 미들웨어가 넘어진다. 활성화는 검증기를 먼저 부르므로
 * ([InMemoryMissionCatalog.activate]) 그 길에서는 여기서 실패하지 않는다.
 */
class DefinedCapability(val definition: MissionDefinition) : LogicalCapability {

    override val workMasterId: String = definition.workMasterId

    override val maxEvidence: Evidence = definition.maxEvidence

    override val preferredOptionals: Map<String, String> = definition.preferredOptionals

    /** 대기 노드마다 엔진의 대기 사양. 생성 때 한 번 바꾼다. */
    private val waits: Map<String, WaitSpec> = definition.steps.filterIsInstance<WaitStep>().associate { step ->
        val seconds = requireNotNull(step.deadlineSeconds) { "검증을 거치지 않은 정의다: ${step.id} 에 기한이 없다" }
        require(seconds > 0) { "검증을 거치지 않은 정의다: ${step.id} 의 기한이 양수가 아니다($seconds)" }
        val outcome = DeadlineOutcome.entries.firstOrNull { it.name == step.onDeadline }
            ?: throw IllegalArgumentException("검증을 거치지 않은 정의다: ${step.id} 의 기한 뒤 상태가 허용 밖이다(${step.onDeadline})")
        step.id to WaitSpec(step.signal, step.expect, Duration.ofSeconds(seconds), outcome)
    }

    override fun plan(order: JobOrder): List<ExecutionUnit> = definition.steps.flatMap { step ->
        when (step) {
            is WaitStep -> listOf(waitUnit(step))
            is UnitStep -> units(step, order)
        }
    }

    /**
     * 대기 노드 하나 = 대기 단위 하나. 단위 id 는 노드 id 다 — 작업 응답의 완료·미완료 목록에 다른 단위처럼 오른다.
     *
     * 대기 사양을 **파라미터에도 싣는다.** 엔진은 [ExecutionUnit.wait] 를 읽고, 파라미터는 인시던트의 `intent.unitParameters`
     * 로 나가 무엇을 언제까지 기다렸는지를 칸을 늘리지 않고 보인다.
     */
    private fun waitUnit(step: WaitStep): ExecutionUnit {
        val wait = waits.getValue(step.id)
        return ExecutionUnit(
            unitId = step.id,
            route = Route.SIGNAL,
            skillType = WaitSpec.SKILL_TYPE,
            parameters = mapOf(
                WaitSpec.P_SIGNAL to wait.signal,
                WaitSpec.P_EXPECT to wait.expect,
                WaitSpec.P_DEADLINE_SECONDS to wait.deadline.seconds.toString(),
                WaitSpec.P_ON_DEADLINE to wait.onDeadline.name,
            ),
            expectedIdentity = null,
            source = null,
            destination = null,
            wait = wait,
        )
    }

    /**
     * 단위 노드 하나 = [UnitStep.forEach] 쓰임의 설비마다 로봇 단위 하나, 작업 지시의 설비 순서대로.
     *
     * 짝 규칙은 코드 `PrepareSequencedRack` 과 같다. 후보는 짝 쓰임의 설비 중 맞출 속성이 있는 것이고, 같은 값이 여럿이면
     * **목록에서 뒤엣것**이다(`associateBy`). 반복 중인 설비에 그 속성이 없으면 짝이 없다.
     */
    private fun units(step: UnitStep, order: JobOrder): List<ExecutionUnit> {
        val pairing = step.pairWith
        val partners: Map<String, EquipmentRequirement> = pairing?.let { p ->
            order.equipmentRequirements
                .filter { it.equipmentUse == p.equipmentUse && p.property in it.properties }
                .associateBy { it.properties.getValue(p.property) }
        }.orEmpty()

        return order.equipmentRequirements
            .filter { it.equipmentUse == step.forEach }
            .map { item ->
                val paired = pairing?.let { p -> item.properties[p.property]?.let { partners[it] } }
                val unpaired = pairing != null && paired == null
                val value = { source: ValueSource -> resolve(source, item, paired) }
                ExecutionUnit(
                    unitId = value(step.unitId)!!,
                    route = Route.ROBOT,
                    skillType = step.skill,
                    parameters = step.parameters.mapNotNull { (name, source) -> value(source)?.let { name to it } }.toMap(),
                    expectedIdentity = step.expectedIdentity?.let(value),
                    source = step.source?.let(value),
                    destination = step.destination?.let(value),
                    // 짝이 없으면 시작도 못 한다 — 부족은 계획에서 드러난다(코드 케이퍼빌리티와 같은 자리).
                    state = if (unpaired) UnitState.FAILED else UnitState.PENDING,
                    failureClass = if (unpaired) step.whenUnpaired else null,
                )
            }
    }

    /** 값 출처 하나를 푼다. 출처가 값을 못 내면 대체값, 그것도 없으면 `null`. */
    private fun resolve(source: ValueSource, item: EquipmentRequirement, paired: EquipmentRequirement?): String? =
        when (source.from) {
            ValueFrom.ITEM_ID -> item.id
            ValueFrom.ITEM_PROPERTY -> source.property?.let { item.properties[it] }
            ValueFrom.PAIRED_ID -> paired?.id
        } ?: source.otherwise
}
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.contracts.v1.SkillCatalog
import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.FloorOwner
import dev.picasso.middleware.FloorOwnership
import java.time.Instant

/**
 * 활성화 거부 하나 — **종류와 후속 행동**으로 보인다(운영 관리 화면 설계 제안 §8.4).
 *
 * 사유 텍스트만 주면 읽는 쪽이 문자열 대조에 기대므로 종류를 열거형으로 둔다. 후속 행동과 해결 담당은 종류가 정한다
 * ([MissionRefusalKind]) — 종류를 가르는 기준이 곧 후속 행동이기 때문이다.
 *
 * @param nodeId 막힌 노드. 정의 전체의 문제(읽을 수 없음)면 널이다.
 * @param observed 지금 값. 없는 것이 문제면 «없음» 이다.
 * @param expected 통과에 필요한 값.
 * @param checkedAt 마지막 확인 시각 — 활성화를 시도한 시각이다.
 * @param basisVersion 근거 버전 — 어느 버전의 현장 설정으로 판정했는가. 활성화 검사는 현장 설정 버전과 무관한 판정이라
 *   «해당 없음»(널)이다. 현장 설정에 버전이 생기면(S3c) 그 번호가 온다.
 */
data class MissionRefusal(
    val kind: MissionRefusalKind,
    val nodeId: String?,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val basisVersion: Int? = null,
) {
    /** 해결 담당 — 화면 안의 누구인가, 화면 밖인가. */
    val owner: RefusalOwner get() = kind.owner

    /** 바로 갈 작업 하나. */
    val nextAction: String get() = kind.nextAction
}

/** 거부를 푸는 쪽. */
enum class RefusalOwner {
    /** 화면 안의 시운전·통합 엔지니어 — 임무 버전 작성, 신호 사양, 기체 배치를 맡는다. */
    ENGINEER,

    /** 화면 밖 — 자원 소유 레지스터는 현장의 것이고 이 저장소 밖에 산다(§15.154). */
    OUTSIDE_CONSOLE,
}

/** 거부 종류. **후속 행동이 다르면 종류가 다르다.** */
enum class MissionRefusalKind(val owner: RefusalOwner, val nextAction: String) {
    /** 문서를 읽을 수 없다 — 모르는 키, 빠진 칸, 형이 틀린 값, 중복 키, 문법. */
    UNREADABLE(RefusalOwner.ENGINEER, "정의 JSON 의 틀린 칸을 고친다"),

    /** 노드 id 가 정의 안에서 겹친다. 리비전과 작업 응답이 단위 id 로 접으므로 겹치면 한쪽이 사라진다. */
    DUPLICATE_NODE_ID(RefusalOwner.ENGINEER, "노드 id 를 겹치지 않게 고친다"),

    /** 기한 검사 — 대기 노드에 양의 기한이 없거나 기한 뒤 상태가 허용 밖이다. */
    DEADLINE_INVALID(RefusalOwner.ENGINEER, "대기 노드의 기한 칸을 고친다"),

    /** 신호 검사 — 참조한 신호가 신호 사양에 없다. */
    SIGNAL_NOT_IN_SPEC(RefusalOwner.ENGINEER, "신호 이름을 고치거나 신호 사양에 더한다"),

    /** 신호 검사 — 기대 값이 그 신호의 종류로 읽힐 수 없는 값이다(참거짓 신호에 `true`·`false` 밖의 값). 영영 안 끝나는 대기다. */
    SIGNAL_VALUE_INVALID(RefusalOwner.ENGINEER, "기대 값을 신호 종류에 맞게 고친다"),

    /** 자원 검사 — 대기 신호의 자리에 바닥 소유자가 없다(`Unowned`). 관문과 같은 규칙이다. */
    FLOOR_UNOWNED(RefusalOwner.OUTSIDE_CONSOLE, "그 자리의 바닥 소유를 선언한다"),

    /** 스킬 검사 — 계약 카탈로그에 없는 스킬이다. */
    SKILL_NOT_IN_CONTRACT(RefusalOwner.ENGINEER, "스킬 이름을 계약 카탈로그의 이름으로 고친다"),

    /** 스킬 검사 — 계약에는 있으나 현장 기체가 제공하지 않는다. */
    SKILL_NOT_ON_SITE(RefusalOwner.ENGINEER, "그 스킬을 제공하는 기체를 현장에 둔다"),

    /** 안전 검사 — 안전 신호를 기다린다. 안전 기능은 이 소프트웨어 계약을 거치지 않는다(ADR 32). */
    SAFETY_SIGNAL_WAIT(RefusalOwner.ENGINEER, "대기 노드를 안전 신호가 아닌 신호로 바꾼다"),
}

/**
 * 검증기 — 임무 정의가 활성화될 수 있는가. **라이브러리다** — 같은 규칙으로 화면이 저장 전에 막고 활성화가 막는다.
 *
 * 검사는 다섯이고(기한·신호·자원·스킬·안전) 그 앞에 노드 id 중복을 본다. **모든 거부를 모아 낸다** — 하나만 알리면
 * 고치고 다시 내고 또 거부되는 왕복이 생긴다.
 *
 * 함수 이름은 관문의 판정 이름과 겹치지 않게 둔다 — 자리 대조 시험이 이름으로 판정의 자리를 찾는다.
 *
 * ## 보지 않는 것
 *
 * - **파라미터 이름.** 오타는 실행 때 하위가 `PARAMETER_INVALID` 로 거부한다
 * - **작업 지시에서 오는 자리**(슬롯·제시 자리). 정의에 고정으로 적힌 것이 아니라 실행 때 관문이 본다
 * - **작업 지시의 설비 id 와 대기 노드 id 의 겹침.** 설비 id 는 작업 지시가 오기 전에는 모른다
 */
object MissionValidator {

    /**
     * @param signals 현장 신호 사양.
     * @param floors 바닥 소유. 관문이 받는 것과 같은 것을 준다.
     * @param siteSkills 현장 기체들이 제공하는 스킬 이름의 합.
     * @param at 활성화를 시도한 시각 — 거부의 마지막 확인 시각이 된다.
     */
    fun validate(
        definition: MissionDefinition,
        signals: List<SignalSpec>,
        floors: FloorOwnership,
        siteSkills: Set<String>,
        at: Instant,
    ): List<MissionRefusal> {
        val byName = signals.associateBy { it.name }
        return duplicateNodeIds(definition, at) +
            deadlineRefusals(definition, at) +
            signalRefusals(definition, byName, at) +
            floorRefusals(definition, byName, floors, at) +
            skillRefusals(definition, siteSkills, at) +
            safetyRefusals(definition, byName, at)
    }

    /** 계약 카탈로그의 스킬 이름 전부 — 기술자의 `skill_type_name` 옵션에서 읽는다. */
    val contractSkills: Set<String> by lazy {
        SkillCatalog.getDescriptor().messageTypes
            .mapNotNull { it.options.getExtension(SkillCatalog.skillTypeName).takeIf { name -> !name.isNullOrEmpty() } }
            .toSet()
    }

    private fun duplicateNodeIds(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        definition.steps.groupingBy { it.id }.eachCount().filterValues { it > 1 }.map { (id, n) ->
            MissionRefusal(MissionRefusalKind.DUPLICATE_NODE_ID, id, "$n 번 나온다", "정의 안에서 한 번", at)
        }

    private fun waits(definition: MissionDefinition) = definition.steps.filterIsInstance<WaitStep>()

    /** 기한 — 대기 노드마다 양의 기한과 허용된 기한 뒤 상태. 파서가 아니라 여기서 본다. */
    private fun deadlineRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        waits(definition).flatMap { wait ->
            val allowed = DeadlineOutcome.entries.joinToString(" 또는 ") { it.name }
            listOfNotNull(
                wait.deadlineSeconds.let { seconds ->
                    if (seconds != null && seconds > 0) {
                        null
                    } else {
                        MissionRefusal(
                            MissionRefusalKind.DEADLINE_INVALID, wait.id,
                            "deadlineSeconds=${seconds ?: "없음"}", "양의 정수 초", at,
                        )
                    }
                },
                wait.onDeadline.let { outcome ->
                    if (DeadlineOutcome.entries.any { it.name == outcome }) {
                        null
                    } else {
                        MissionRefusal(MissionRefusalKind.DEADLINE_INVALID, wait.id, "onDeadline=${outcome ?: "없음"}", allowed, at)
                    }
                },
            )
        }

    /** 신호 — 참조한 신호가 사양에 있는가, 기대 값이 그 신호의 종류로 읽히는가. */
    private fun signalRefusals(definition: MissionDefinition, byName: Map<String, SignalSpec>, at: Instant): List<MissionRefusal> =
        waits(definition).mapNotNull { wait ->
            val spec = byName[wait.signal]
                ?: return@mapNotNull MissionRefusal(
                    MissionRefusalKind.SIGNAL_NOT_IN_SPEC, wait.id, wait.signal,
                    "신호 사양의 이름 중 하나(${byName.keys.sorted().joinToString()})", at,
                )
            if (spec.kind == SignalKind.BOOLEAN && wait.expect !in BOOLEAN_VALUES) {
                MissionRefusal(MissionRefusalKind.SIGNAL_VALUE_INVALID, wait.id, wait.expect, BOOLEAN_VALUES.joinToString(" 또는 "), at)
            } else {
                null
            }
        }

    /**
     * 자원 — 정의에 고정으로 적힌 자리(대기 신호의 자리)의 바닥 소유. **관문과 같은 규칙이다** — `Unowned` 만 거부하고
     * `Declared`·`NotDeclared` 는 통과한다. 선언 없음을 거부로 읽으면 레지스터를 안 붙인 현장에서 자리가 적힌 신호를 쓰는
     * 정의가 모두 거부된다. 자리가 없는 신호는 건너뛴다.
     */
    private fun floorRefusals(
        definition: MissionDefinition,
        byName: Map<String, SignalSpec>,
        floors: FloorOwnership,
        at: Instant,
    ): List<MissionRefusal> = waits(definition).mapNotNull { wait ->
        val where = byName[wait.signal]?.location ?: return@mapNotNull null
        if (floors.ownerOf(where) != FloorOwner.Unowned) return@mapNotNull null
        MissionRefusal(MissionRefusalKind.FLOOR_UNOWNED, wait.id, "$where: 소유자 없음", "$where: 소유자 선언", at)
    }

    /** 스킬 — 계약에 있는 스킬인가, 현장 기체가 제공하는가. 둘은 다른 물음이다. */
    private fun skillRefusals(definition: MissionDefinition, siteSkills: Set<String>, at: Instant): List<MissionRefusal> =
        definition.steps.filterIsInstance<UnitStep>().mapNotNull { unit ->
            when {
                unit.skill !in contractSkills -> MissionRefusal(
                    MissionRefusalKind.SKILL_NOT_IN_CONTRACT, unit.id, unit.skill, "계약 카탈로그의 스킬 이름", at,
                )
                unit.skill !in siteSkills -> MissionRefusal(
                    MissionRefusalKind.SKILL_NOT_ON_SITE, unit.id, unit.skill,
                    "현장 기체가 제공하는 스킬(${siteSkills.sorted().joinToString()})", at,
                )
                else -> null
            }
        }

    /** 안전 — 안전 신호를 기다리는 대기 노드가 없는가(ADR 32). */
    private fun safetyRefusals(definition: MissionDefinition, byName: Map<String, SignalSpec>, at: Instant): List<MissionRefusal> =
        waits(definition).mapNotNull { wait ->
            if (byName[wait.signal]?.safety != true) return@mapNotNull null
            MissionRefusal(MissionRefusalKind.SAFETY_SIGNAL_WAIT, wait.id, "${wait.signal}: 안전 신호", "안전 신호가 아닌 신호", at)
        }

    private val BOOLEAN_VALUES = listOf("true", "false")
}
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.ActiveMission
import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.MissionCatalog
import java.time.Instant

/** 활성화의 결과. */
sealed interface Activation {
    /** 섰다 — 이 WorkMaster 의 다음 작업 지시부터 이 버전으로 계획한다. */
    data class Activated(val workMasterId: String, val missionVersion: Int) : Activation

    /** 거부됐다 — 활성 버전은 그대로다. 거부를 전부 든다. */
    data class Refused(val refusals: List<MissionRefusal>) : Activation
}

/**
 * 메모리에 사는 임무 카탈로그 — 시험과 운영 호스트가 쓴다. 저장과 이력은 여기 없다(호스트가 붙인다).
 *
 * **코드 케이퍼빌리티와 데이터 정의가 한 카탈로그에 함께 선다.** 활성화한 적이 없는 WorkMaster 는 코드 케이퍼빌리티로
 * 답하고(버전 없음), 활성화하면 그 WorkMaster 는 그 정의로 답한다.
 *
 * ## 활성화는 관문을 지난다
 *
 * 읽을 수 없거나 검증기가 거부한 정의는 서지 않고 **활성 버전이 그대로다.** 통과하면 그 WorkMaster 의 다음 버전 번호
 * (1부터 오른다)가 활성이 된다. 거부된 시도는 번호를 쓰지 않는다.
 *
 * 검증기 입력(신호 사양·바닥 소유·현장 기체의 스킬)은 활성화 호출이 준다 — 미들웨어는 바닥 소유를 관문에만 넘기고
 * 쥐지 않는다.
 *
 * ## 스레드
 *
 * 활성화와 조회는 다른 스레드에서 올 수 있다(화면이 활성화하고 진행 루프가 조회한다). 미들웨어는 잠금이 없으므로 이
 * 카탈로그가 **스스로** 안전하다. 활성화는 다음 작업 지시부터 적용된다 — 도는 실행은 쥔 버전으로 끝난다.
 */
class InMemoryMissionCatalog(
    code: List<LogicalCapability> = MissionCatalog.codeCapabilities(),
    private val now: () -> Instant = { Instant.now() },
) : MissionCatalog {

    private val lock = Any()
    private val coded: Map<String, LogicalCapability> = code.associateBy { it.workMasterId }
    private val activated = mutableMapOf<String, ActiveMission>()
    private val issued = mutableMapOf<String, Int>()

    override fun active(workMasterId: String): ActiveMission? = synchronized(lock) {
        activated[workMasterId] ?: coded[workMasterId]?.let { ActiveMission(it, missionVersion = null) }
    }

    /**
     * 정의를 읽고 검증해 다음 버전으로 세운다.
     *
     * @param definitionJson 임무 정의 문서([MissionDefinitionParser] 의 표기).
     * @param signals 현장 신호 사양.
     * @param floors 바닥 소유.
     * @param siteSkills 현장 기체들이 제공하는 스킬 이름의 합.
     */
    fun activate(
        definitionJson: String,
        signals: List<SignalSpec>,
        floors: FloorOwnership,
        siteSkills: Set<String>,
    ): Activation {
        val at = now()
        val definition = when (val parsed = MissionDefinitionParser.parse(definitionJson)) {
            is MissionParse.Unreadable -> return Activation.Refused(
                parsed.problems.map {
                    MissionRefusal(MissionRefusalKind.UNREADABLE, null, it, "임무 정의 문서 버전 ${MissionDefinitionParser.SCHEMA_VERSION} 의 모양", at)
                },
            )
            is MissionParse.Parsed -> parsed.definition
        }
        val refusals = MissionValidator.validate(definition, signals, floors, siteSkills, at)
        if (refusals.isNotEmpty()) return Activation.Refused(refusals)

        val capability = DefinedCapability(definition)
        synchronized(lock) {
            val version = (issued[definition.workMasterId] ?: 0) + 1
            issued[definition.workMasterId] = version
            activated[definition.workMasterId] = ActiveMission(capability, version)
            return Activation.Activated(definition.workMasterId, version)
        }
    }
}
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome

/**
 * 시험이 쓰는 임무 정의 문서와 현장 데이터.
 *
 * [PREPARE_SEQUENCED_RACK] 는 코드 `PrepareSequencedRack` 을 **데이터로 옮긴 것**이다 — 동등성 시험이 그 둘이 같은
 * 계획을 내는지 칸마다 댄다. [withArrivalWait] 는 그 앞에 랙 도착 대기를 둔 버전이다(버전 2 의 모양).
 */
object MissionFixtures {

    const val RACK_PRESENT = "rack_present"
    const val WAIT_NODE = "rack-arrival"

    /** 랙 도착 신호를 내는 자리. 자원 검사가 이 자리의 바닥 소유를 본다. */
    const val DOCK = "RACK-DOCK"

    /**
     * 단위 노드 하나 — 슬롯마다 `pick_place`, 제시 자리는 material 로 짝짓는다.
     *
     * 시험이 문자열을 바꿔 틀린 문서를 만들므로 **칸마다 한 줄**이고 들여쓰기가 없다.
     */
    private val PLACE_NODE = listOf(
        "{",
        "\"kind\": \"unit\",",
        "\"id\": \"place\",",
        "\"skill\": \"pick_place\",",
        "\"forEach\": \"destination\",",
        "\"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"material\" },",
        "\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",",
        "\"unitId\": { \"from\": \"ITEM_ID\" },",
        "\"parameters\": { \"object_id\": { \"from\": \"PAIRED_ID\", \"otherwise\": \"\" }, \"destination\": { \"from\": \"ITEM_ID\" } },",
        "\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" },",
        "\"source\": { \"from\": \"PAIRED_ID\" },",
        "\"destination\": { \"from\": \"ITEM_ID\" }",
        "}",
    ).joinToString("\n")

    private fun document(vararg steps: String) = listOf(
        "{",
        "\"schemaVersion\": 1,",
        "\"workMasterId\": \"PrepareSequencedRack\",",
        "\"maxEvidence\": \"E2\",",
        "\"preferredOptionals\": { \"verify_grasp\": \"true\" },",
        "\"steps\": [",
        steps.joinToString(",\n"),
        "]",
        "}",
    ).joinToString("\n")

    /** 코드 `PrepareSequencedRack` 의 데이터판 — 버전 1 의 모양. */
    val PREPARE_SEQUENCED_RACK: String = document(PLACE_NODE)

    /** 맨 앞에 «랙 도착 신호가 기대 값이 될 때까지 대기» 를 둔 버전 — 버전 2 의 모양. 대기 노드는 한 줄이다. */
    fun withArrivalWait(
        deadlineSeconds: Long = 120,
        onDeadline: DeadlineOutcome = DeadlineOutcome.OPERATOR_HOLD,
        signal: String = RACK_PRESENT,
        expect: String = "true",
    ): String = document(
        "{\"kind\": \"wait\", \"id\": \"$WAIT_NODE\", \"signal\": \"$signal\", \"expect\": \"$expect\", " +
            "\"deadlineSeconds\": $deadlineSeconds, \"onDeadline\": \"${onDeadline.name}\"}",
        PLACE_NODE,
    )

    /** 현장 신호 사양 — 랙 도착(자리 있음), 안전 신호 하나, 텍스트 신호 하나. */
    val SIGNALS: List<SignalSpec> = listOf(
        SignalSpec(RACK_PRESENT, location = DOCK, kind = SignalKind.BOOLEAN),
        SignalSpec("guard_closed", kind = SignalKind.BOOLEAN, safety = true),
        SignalSpec("lot_code", kind = SignalKind.TEXT),
    )

    /** 현장 기체들이 제공하는 스킬. */
    val SITE_SKILLS: Set<String> = setOf("pick_place", "navigate_to", "inspect")

    fun parsed(text: String): MissionDefinition = when (val p = MissionDefinitionParser.parse(text)) {
        is MissionParse.Parsed -> p.definition
        is MissionParse.Unreadable -> error("시험 정의를 못 읽었다: ${p.problems}")
    }
}
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.Evidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 임무 정의의 엄격한 읽기 — 모르는 키·빠진 칸·형이 틀린 값을 **모두 모아 한 번에** 알린다.
 *
 * 값의 판정(기한이 양수인가, 기한 뒤 상태가 허용된 값인가)은 여기서 안 본다 — 검증기 시험이 본다. 파서가 그것을
 * 거부하면 검증기의 기한 검사를 빼는 결함이 파서에 가려진다.
 */
class MissionDefinitionParserTest {

    private fun problems(text: String): List<String> =
        assertIs<MissionParse.Unreadable>(MissionDefinitionParser.parse(text)).problems

    @Test
    fun `코드 케이퍼빌리티를 옮긴 문서를 그대로 읽는다`() {
        val definition = MissionFixtures.parsed(MissionFixtures.withArrivalWait())

        assertEquals(1, definition.schemaVersion)
        assertEquals("PrepareSequencedRack", definition.workMasterId)
        assertEquals(Evidence.E2, definition.maxEvidence)
        assertEquals(mapOf("verify_grasp" to "true"), definition.preferredOptionals)
        assertEquals(listOf("rack-arrival", "place"), definition.steps.map { it.id })

        val wait = assertIs<WaitStep>(definition.steps[0])
        assertEquals(WaitStep("rack-arrival", "rack_present", "true", 120, "OPERATOR_HOLD"), wait)

        val unit = assertIs<UnitStep>(definition.steps[1])
        assertEquals(Pairing("source", "material"), unit.pairWith)
        assertEquals("NO_SOURCE_FOR_MATERIAL", unit.whenUnpaired)
        assertEquals(ValueSource(ValueFrom.PAIRED_ID, otherwise = ""), unit.parameters["object_id"])
        assertEquals(ValueSource(ValueFrom.ITEM_PROPERTY, property = "material"), unit.expectedIdentity)
    }

    @Test
    fun `모르는 키와 빠진 칸과 형이 틀린 값을 한 번에 모은다`() {
        val text = """
            {
              "schemaVersion": 1.5,
              "workMasterId": "PrepareSequencedRack",
              "maxEvidence": "E3",
              "preferredOptionals": { "verify_grasp": true },
              "colour": "red",
              "steps": [
                { "kind": "wait", "id": "w", "signal": "rack_present", "expect": "true",
                  "deadlineSeconds": "soon", "onDeadline": 7, "retry": 2 },
                { "kind": "unit", "id": "u", "skill": "pick_place",
                  "unitId": { "from": "ITEM_ID" }, "parameters": {} },
                { "kind": "jump", "id": "j" }
              ]
            }
        """.trimIndent()

        val found = problems(text)

        val expected = listOf(
            "$.schemaVersion: 정수여야",
            "$.maxEvidence: E0·E1·E2 중 하나",
            "$.preferredOptionals.verify_grasp: 문자열이어야",
            "$: 모르는 키 'colour'",
            "$.steps[0]: 모르는 키 'retry'",
            "$.steps[0].deadlineSeconds: 정수여야",
            "$.steps[0].onDeadline: 문자열이어야",
            "$.steps[1].forEach: 빠졌다",
            "$.steps[2].kind: unit 이나 wait 여야",
        )
        expected.forEach { needle ->
            assertTrue(found.any { it.startsWith(needle) }, "'$needle' 이 안 모였다: $found")
        }
        assertEquals(expected.size, found.size, "모은 것이 기대와 다르다: $found")
    }

    @Test
    fun `기한의 값은 판정하지 않는다 — 없거나 음수거나 허용 밖이어도 읽는다`() {
        // ★검증기의 기한 검사가 이 자리를 맡는다. 여기서 거부하면 그 검사를 빼는 결함이 안 보인다.
        val text = MissionFixtures.withArrivalWait(deadlineSeconds = -5)
            .replace("\"onDeadline\": \"OPERATOR_HOLD\"", "\"onDeadline\": \"RETRY\"")
        val wait = assertIs<WaitStep>(MissionFixtures.parsed(text).steps[0])
        assertEquals(-5, wait.deadlineSeconds)
        assertEquals("RETRY", wait.onDeadline)

        val bare = MissionFixtures.withArrivalWait()
            .replace(", \"deadlineSeconds\": 120", "")
            .replace(", \"onDeadline\": \"OPERATOR_HOLD\"", "")
        val missing = assertIs<WaitStep>(MissionFixtures.parsed(bare).steps[0])
        assertNull(missing.deadlineSeconds)
        assertNull(missing.onDeadline)
    }

    @Test
    fun `같은 키가 둘이면 거부한다 — 어느 쪽을 뜻했는지 모른다`() {
        // `JsonFormat` 은 말없이 뒤엣것을 쓴다. 그러면 앞의 값이 조용히 사라진다.
        val text = MissionFixtures.withArrivalWait()
            .replace("\"deadlineSeconds\": 120", "\"deadlineSeconds\": 120, \"deadlineSeconds\": 5")
        assertEquals(listOf("$.steps[0]: 키 'deadlineSeconds' 가 두 번 나온다"), problems(text))
    }

    @Test
    fun `JSON 이 아닌 것과 뒤에 붙은 글자를 거부한다`() {
        assertTrue(problems("{ steps: [] }").single().startsWith("$: 키는 큰따옴표 문자열이어야 한다"))
        assertTrue(problems(MissionFixtures.PREPARE_SEQUENCED_RACK + " x").single().startsWith("$: JSON 값 뒤에 글자가 더 있다"))
        assertTrue(problems("[1, 2]").single().startsWith("$: JSON 객체가 아니다"))
    }

    @Test
    fun `짝 규칙과 실패 분류는 함께 오고 짝 없이 짝의 값을 못 쓴다`() {
        val noClass = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",", "")
        assertTrue(problems(noClass).single().startsWith("$.steps[0]: pairWith 와 whenUnpaired 는 함께 온다"))

        // 짝 규칙을 통째로 빼면 짝의 값을 쓰는 두 칸(object_id 파라미터, source)이 각각 걸린다.
        val noPairing = noClass.replace("\"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"material\" },", "")
        val found = problems(noPairing)
        assertEquals(2, found.count { "PAIRED_ID 인데 이 노드에 pairWith 가 없다" in it }, "$found")
        assertEquals(2, found.size, "$found")
    }

    @Test
    fun `단위 id 는 반복 중인 설비의 id 만 받는다`() {
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK
            .replace("\"unitId\": { \"from\": \"ITEM_ID\" }", "\"unitId\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" }")
        assertTrue(problems(text).single().startsWith("$.steps[0].unitId.from: ITEM_ID 여야 한다"))
    }

    @Test
    fun `값 출처의 속성은 속성 출처에만 오고 모르는 출처는 거부한다`() {
        val stray = MissionFixtures.PREPARE_SEQUENCED_RACK
            .replace("\"destination\": { \"from\": \"ITEM_ID\" }\n}", "\"destination\": { \"from\": \"ITEM_ID\", \"property\": \"x\" }\n}")
        assertTrue(problems(stray).single().startsWith("$.steps[0].destination.property: ITEM_PROPERTY 에만 온다"))

        val unknown = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"from\": \"PAIRED_ID\", \"otherwise\"", "\"from\": \"ORDER\", \"otherwise\"")
        assertTrue(problems(unknown).single().startsWith("$.steps[0].parameters.object_id.from: ITEM_ID·ITEM_PROPERTY·PAIRED_ID 중 하나"))
    }

    @Test
    fun `모르는 문서 버전과 빈 노드 목록을 거부한다`() {
        val v2 = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertTrue(problems(v2).single().startsWith("$.schemaVersion: 모르는 문서 버전이다"))

        val empty = """{"schemaVersion":1,"workMasterId":"W","maxEvidence":"E0","preferredOptionals":{},"steps":[]}"""
        assertEquals(listOf("$.steps: 노드가 하나도 없다"), problems(empty))
    }
}
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.Route
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.WaitSpec
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **데이터로 옮긴 `PrepareSequencedRack` 이 코드와 같은 계획을 내는가** — 작업 지시 모양마다, 단위마다, 칸마다.
 *
 * 기존 `SequencingRackTest` 의 작업 지시 도우미는 제시 자리를 material 로 묶은 맵으로 만들어 «같은 material 이 둘» 모양이
 * 없다. 그래서 여기서 모양을 새로 짓는다 — 짝 규칙(뒤엣것이 이긴다)이 갈리는 자리가 바로 그 모양이다.
 */
class DefinedCapabilityEquivalenceTest {

    private val code = PrepareSequencedRack()
    private val data = DefinedCapability(MissionFixtures.parsed(MissionFixtures.PREPARE_SEQUENCED_RACK))

    private fun slot(id: String, material: String?) =
        EquipmentRequirement(id, EquipmentUse.DESTINATION, material?.let { mapOf(EquipmentUse.PROP_MATERIAL to it) }.orEmpty())

    private fun bin(id: String, material: String?) =
        EquipmentRequirement(id, EquipmentUse.SOURCE, material?.let { mapOf(EquipmentUse.PROP_MATERIAL to it) }.orEmpty())

    private fun order(vararg equipment: EquipmentRequirement) =
        JobOrder("SEQ-9", PrepareSequencedRack.WORK_MASTER, version = 1, equipmentRequirements = equipment.toList())

    /** 이름 붙인 작업 지시 모양들. 이름이 실패 메시지에 나온다. */
    private val shapes: Map<String, JobOrder> = mapOf(
        "제시 자리 있음" to order(slot("S01", "A"), slot("S02", "B"), bin("BIN-A", "A"), bin("BIN-B", "B")),
        "제시 자리 없음" to order(slot("S01", "A"), slot("S02", "C"), bin("BIN-A", "A")),
        "같은 material 둘 — 뒤엣것이 이긴다" to order(bin("BIN-A1", "A"), slot("S01", "A"), bin("BIN-A2", "A"), slot("S02", "A")),
        "material 없는 슬롯" to order(slot("S01", null), slot("S02", "A"), bin("BIN-A", "A")),
        "material 없는 제시 자리" to order(slot("S01", "A"), bin("BIN-X", null), bin("BIN-A", "A")),
        "목적지 0개" to order(bin("BIN-A", "A")),
        "무관한 쓰임" to order(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_MATERIAL to "A")),
            slot("S01", "A"),
            EquipmentRequirement("BIN-Z", "staging", mapOf(EquipmentUse.PROP_MATERIAL to "A")),
            bin("BIN-A", "A"),
        ),
        "제시 자리가 슬롯보다 앞" to order(bin("BIN-B", "B"), bin("BIN-A", "A"), slot("S01", "A"), slot("S02", "B"), slot("S03", "A")),
        "빈 작업 지시" to order(),
    )

    /** 대조하는 칸. 하나씩 이름을 붙여 어느 칸이 갈렸는지 보이게 한다. */
    private val fields: Map<String, (ExecutionUnit) -> Any?> = mapOf(
        "단위 id" to { it.unitId },
        "경로" to { it.route },
        "스킬" to { it.skillType },
        "파라미터" to { it.parameters },
        "기대 식별" to { it.expectedIdentity },
        "제시 자리" to { it.source },
        "목적지" to { it.destination },
        "상태" to { it.state },
        "실패 분류" to { it.failureClass },
        "대기 사양" to { it.wait },
    )

    @Test
    fun `작업 지시 모양마다 데이터 정의가 코드와 같은 계획을 칸마다 낸다`() {
        shapes.forEach { (shape, order) ->
            val expected = code.plan(order)
            val actual = data.plan(order)
            assertEquals(expected.size, actual.size, "$shape: 단위 수가 갈렸다")
            expected.zip(actual).forEachIndexed { at, (e, a) ->
                fields.forEach { (field, of) ->
                    assertEquals(of(e), of(a), "$shape: ${at + 1}번째 단위의 $field 이 갈렸다")
                }
                // 칸 목록이 낡으면(새 칸이 생기면) 위 대조가 빠뜨린다 — 통째로도 댄다.
                assertEquals(e, a, "$shape: ${at + 1}번째 단위가 갈렸다")
            }
        }
    }

    @Test
    fun `모양들이 짝 규칙의 갈래를 전부 밟는다`() {
        // ★대조가 무언가를 가리려면 갈래가 실제로 나와야 한다 — 전부 짝이 있으면 실패 분류 칸은 늘 널끼리 같다.
        val planned = shapes.values.flatMap { code.plan(it) }
        assertTrue(planned.any { it.state == UnitState.FAILED && it.failureClass == PrepareSequencedRack.NO_SOURCE })
        assertTrue(planned.any { it.state == UnitState.PENDING })
        assertTrue(planned.any { it.expectedIdentity == null }, "material 없는 슬롯이 안 나왔다")
        assertTrue(planned.any { it.source == "BIN-A2" }, "같은 material 의 뒤엣것이 안 나왔다")
        assertTrue(shapes.values.any { code.plan(it).isEmpty() }, "목적지 0개가 안 나왔다")
    }

    @Test
    fun `최고 근거 등급과 선택 파라미터와 WorkMaster 가 같다`() {
        assertEquals(code.workMasterId, data.workMasterId)
        assertEquals(code.maxEvidence, data.maxEvidence)
        assertEquals(code.preferredOptionals, data.preferredOptionals)
        assertEquals(code.evidenceWindow, data.evidenceWindow)
        assertEquals(code.inDoubtGrace, data.inDoubtGrace)
        assertEquals(code.stallWindow, data.stallWindow)
    }

    @Test
    fun `대기 노드는 신호 경로의 단위 하나가 되고 사양을 파라미터에도 싣는다`() {
        val capability = DefinedCapability(MissionFixtures.parsed(MissionFixtures.withArrivalWait(deadlineSeconds = 90, onDeadline = DeadlineOutcome.ABORTED)))
        val planned = capability.plan(shapes.getValue("제시 자리 있음"))

        val wait = planned.first()
        assertEquals(MissionFixtures.WAIT_NODE, wait.unitId)
        assertEquals(Route.SIGNAL, wait.route)
        assertEquals(WaitSpec.SKILL_TYPE, wait.skillType)
        assertEquals(UnitState.PENDING, wait.state)
        assertEquals(WaitSpec(MissionFixtures.RACK_PRESENT, "true", Duration.ofSeconds(90), DeadlineOutcome.ABORTED), wait.wait)
        assertEquals(
            mapOf("signal" to "rack_present", "expect" to "true", "deadlineSeconds" to "90", "onDeadline" to "ABORTED"),
            wait.parameters,
        )
        // 대기 뒤는 버전 1 과 같은 계획이다.
        assertEquals(code.plan(shapes.getValue("제시 자리 있음")), planned.drop(1))
    }

    @Test
    fun `대기 단위의 스킬 이름은 계약 카탈로그와 겹치지 않는다`() {
        // 겹치면 효과·사전 조건·선택 파라미터를 읽는 자리가 대기 단위를 그 스킬로 잘못 읽는다.
        assertTrue(MissionValidator.contractSkills.isNotEmpty())
        assertTrue(WaitSpec.SKILL_TYPE !in MissionValidator.contractSkills)
    }

    @Test
    fun `검증을 거치지 않은 기한은 해석기가 받지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(MissionFixtures.parsed(MissionFixtures.withArrivalWait(deadlineSeconds = 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(
                MissionFixtures.parsed(MissionFixtures.withArrivalWait().replace("\"OPERATOR_HOLD\"", "\"RETRY\"")),
            )
        }
    }
}
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.FloorOwner
import dev.picasso.middleware.FloorOwnership
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 검증기 — 다섯 검사(기한·신호·자원·스킬·안전)와 노드 id 중복이 **각각** 잘못된 정의를 거부하고, 거부가 종류와
 * 후속 행동으로 보인다.
 *
 * 검사마다 그 검사만 걸리는 정의를 하나씩 든다. 한 정의가 둘에 걸리면 한 검사를 빼도 다른 검사가 거부해 그 결함이
 * 안 보인다 — 그래서 «그 종류 하나뿐» 을 댄다.
 */
class MissionValidatorTest {

    private val at: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun floors(vararg unowned: String) = object : FloorOwnership {
        override fun ownerOf(location: String): FloorOwner =
            if (location in unowned) FloorOwner.Unowned else FloorOwner.Declared("line-a")
    }

    private fun refusals(
        text: String,
        signals: List<SignalSpec> = MissionFixtures.SIGNALS,
        floors: FloorOwnership = FloorOwnership.None,
        siteSkills: Set<String> = MissionFixtures.SITE_SKILLS,
    ): List<MissionRefusal> = MissionValidator.validate(MissionFixtures.parsed(text), signals, floors, siteSkills, at)

    private fun only(kind: MissionRefusalKind, found: List<MissionRefusal>): MissionRefusal {
        assertEquals(listOf(kind), found.map { it.kind }, "그 종류 하나만 걸려야 한다: $found")
        return found.single()
    }

    @Test
    fun `통과하는 정의는 거부가 없다`() {
        assertEquals(emptyList(), refusals(MissionFixtures.PREPARE_SEQUENCED_RACK))
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait()))
        // 텍스트 신호는 어떤 값이든 기다릴 수 있다.
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(signal = "lot_code", expect = "LOT-7")))
    }

    @Test
    fun `기한 검사 — 양의 기한이 없으면 거부한다`() {
        val zero = only(MissionRefusalKind.DEADLINE_INVALID, refusals(MissionFixtures.withArrivalWait(deadlineSeconds = 0)))
        assertEquals(MissionFixtures.WAIT_NODE, zero.nodeId)
        assertEquals("deadlineSeconds=0", zero.observed)

        val missing = MissionFixtures.withArrivalWait().replace(", \"deadlineSeconds\": 120", "")
        assertEquals("deadlineSeconds=없음", only(MissionRefusalKind.DEADLINE_INVALID, refusals(missing)).observed)
    }

    @Test
    fun `기한 검사 — 기한 뒤 상태가 허용 밖이거나 없으면 거부한다`() {
        val retry = MissionFixtures.withArrivalWait().replace("\"onDeadline\": \"OPERATOR_HOLD\"", "\"onDeadline\": \"RETRY\"")
        val refusal = only(MissionRefusalKind.DEADLINE_INVALID, refusals(retry))
        assertEquals("onDeadline=RETRY", refusal.observed)
        assertEquals("OPERATOR_HOLD 또는 ABORTED", refusal.expected)

        val missing = MissionFixtures.withArrivalWait().replace(", \"onDeadline\": \"OPERATOR_HOLD\"", "")
        assertEquals("onDeadline=없음", only(MissionRefusalKind.DEADLINE_INVALID, refusals(missing)).observed)
    }

    @Test
    fun `신호 검사 — 신호 사양에 없는 신호를 참조하면 거부한다`() {
        val refusal = only(MissionRefusalKind.SIGNAL_NOT_IN_SPEC, refusals(MissionFixtures.withArrivalWait(signal = "rack_ready")))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals("rack_ready", refusal.observed)
        assertTrue("rack_present" in refusal.expected, refusal.expected)
    }

    @Test
    fun `신호 검사 — 참거짓 신호에 그 밖의 값을 기다리면 거부한다`() {
        // 영영 안 끝나는 대기다 — 기한까지 서 있다가 운영자에게 간다.
        val refusal = only(MissionRefusalKind.SIGNAL_VALUE_INVALID, refusals(MissionFixtures.withArrivalWait(expect = "1")))
        assertEquals("1", refusal.observed)
    }

    @Test
    fun `자원 검사 — 대기 신호의 자리에 바닥 소유자가 없으면 거부하고 선언 없음은 통과한다`() {
        val refusal = only(
            MissionRefusalKind.FLOOR_UNOWNED,
            refusals(MissionFixtures.withArrivalWait(), floors = floors(MissionFixtures.DOCK)),
        )
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals(RefusalOwner.OUTSIDE_CONSOLE, refusal.owner)

        // ★관문과 같은 규칙 — 레지스터를 안 붙인 배치(NotDeclared)와 소유자가 있는 자리(Declared)는 통과한다.
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), floors = FloorOwnership.None))
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), floors = floors("OTHER")))
        // 자리가 없는 신호는 건너뛴다.
        val nowhere = MissionFixtures.SIGNALS.map { if (it.name == MissionFixtures.RACK_PRESENT) it.copy(location = null) else it }
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), signals = nowhere, floors = floors(MissionFixtures.DOCK)))
    }

    @Test
    fun `스킬 검사 — 계약에 없는 스킬을 거부한다`() {
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"skill\": \"pick_place\"", "\"skill\": \"pick_and_place\"")
        val refusal = only(MissionRefusalKind.SKILL_NOT_IN_CONTRACT, refusals(text))
        assertEquals("place", refusal.nodeId)
        assertEquals("pick_and_place", refusal.observed)
    }

    @Test
    fun `스킬 검사 — 현장 기체가 제공하지 않는 스킬을 거부한다`() {
        val refusal = only(
            MissionRefusalKind.SKILL_NOT_ON_SITE,
            refusals(MissionFixtures.PREPARE_SEQUENCED_RACK, siteSkills = setOf("navigate_to")),
        )
        assertEquals("pick_place", refusal.observed)
        assertEquals("그 스킬을 제공하는 기체를 현장에 둔다", refusal.nextAction)
    }

    @Test
    fun `안전 검사 — 안전 신호를 기다리는 대기를 거부한다`() {
        val refusal = only(MissionRefusalKind.SAFETY_SIGNAL_WAIT, refusals(MissionFixtures.withArrivalWait(signal = "guard_closed")))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
    }

    @Test
    fun `노드 id 가 겹치면 거부한다`() {
        val text = MissionFixtures.withArrivalWait().replace("\"id\": \"place\"", "\"id\": \"${MissionFixtures.WAIT_NODE}\"")
        val refusal = only(MissionRefusalKind.DUPLICATE_NODE_ID, refusals(text))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals("2 번 나온다", refusal.observed)
    }

    @Test
    fun `거부를 전부 모으고 각 거부가 여섯 칸과 노드를 든다`() {
        val text = MissionFixtures.withArrivalWait(deadlineSeconds = -1, signal = "rack_ready")
            .replace("\"skill\": \"pick_place\"", "\"skill\": \"teleport\"")
        val found = refusals(text)
        assertEquals(
            setOf(MissionRefusalKind.DEADLINE_INVALID, MissionRefusalKind.SIGNAL_NOT_IN_SPEC, MissionRefusalKind.SKILL_NOT_IN_CONTRACT),
            found.map { it.kind }.toSet(),
        )
        found.forEach {
            assertEquals(at, it.checkedAt, "마지막 확인 시각은 활성화를 시도한 시각이다")
            assertNull(it.basisVersion, "근거 버전은 해당 없음이다")
            assertTrue(it.nextAction.isNotBlank() && it.expected.isNotBlank() && it.observed.isNotBlank())
            assertTrue(it.nodeId != null)
        }
    }

    @Test
    fun `종류마다 후속 행동이 다르다`() {
        // 종류를 가르는 기준이 후속 행동이다 — 같은 행동을 가진 두 종류는 하나로 접혀야 한다.
        val actions = MissionRefusalKind.entries.map { it.nextAction }
        assertEquals(actions.size, actions.toSet().size, "후속 행동이 같은 종류가 있다: $actions")
    }
}
```

```kotlin
package dev.picasso.middleware.mission

import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.MissionCatalog
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.RobotPort
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 메모리 카탈로그 — 활성화는 검증을 지나야 서고, 통과하면 다음 버전이, 거부되면 활성 버전 그대로다.
 */
class InMemoryMissionCatalogTest {

    private val at: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun catalog() = InMemoryMissionCatalog(now = { at })

    private fun InMemoryMissionCatalog.activate(text: String) =
        activate(text, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS)

    @Test
    fun `활성화 전에는 코드 케이퍼빌리티가 버전 없이 답한다`() {
        val catalog = catalog()
        val active = catalog.active(PrepareSequencedRack.WORK_MASTER)!!
        assertIs<PrepareSequencedRack>(active.capability)
        assertNull(active.missionVersion)
        assertIs<DeliverContainer>(catalog.active(DeliverContainer.WORK_MASTER)!!.capability)
        assertNull(catalog.active("Unknown"))
    }

    @Test
    fun `통과하면 1 부터 오르는 버전이 활성이 되고 거부된 시도는 번호를 쓰지 않는다`() {
        val catalog = catalog()
        assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 1), catalog.activate(MissionFixtures.PREPARE_SEQUENCED_RACK))
        val v1 = catalog.active(PrepareSequencedRack.WORK_MASTER)!!
        assertEquals(1, v1.missionVersion)
        assertIs<DefinedCapability>(v1.capability)

        val refused = assertIs<Activation.Refused>(catalog.activate(MissionFixtures.withArrivalWait(signal = "rack_ready")))
        assertEquals(listOf(MissionRefusalKind.SIGNAL_NOT_IN_SPEC), refused.refusals.map { it.kind })
        assertSame(v1, catalog.active(PrepareSequencedRack.WORK_MASTER), "거부가 활성 버전을 바꿨다")

        assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 2), catalog.activate(MissionFixtures.withArrivalWait()))
        assertEquals(2, catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion)
        // 다른 WorkMaster 는 손대지 않았다.
        assertNull(catalog.active(DeliverContainer.WORK_MASTER)!!.missionVersion)
    }

    @Test
    fun `읽을 수 없는 정의는 틀린 곳마다 같은 모양의 거부로 낸다`() {
        val catalog = catalog()
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"maxEvidence\": \"E2\"", "\"maxEvidence\": \"E9\", \"owner\": \"x\"")
        val refused = assertIs<Activation.Refused>(catalog.activate(text))
        assertEquals(2, refused.refusals.size)
        refused.refusals.forEach {
            assertEquals(MissionRefusalKind.UNREADABLE, it.kind)
            assertNull(it.nodeId)
            assertEquals(at, it.checkedAt)
            assertEquals(RefusalOwner.ENGINEER, it.owner)
        }
        assertNull(catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion, "읽을 수 없는 정의가 섰다")
    }

    @Test
    fun `활성화와 조회가 여러 스레드에서 겹쳐도 번호가 겹치거나 빠지지 않는다`() {
        val catalog = catalog()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val versions = java.util.Collections.synchronizedList(mutableListOf<Int>())
        repeat(40) {
            pool.submit {
                start.await()
                val result = catalog.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
                versions += (result as Activation.Activated).missionVersion
                catalog.active(PrepareSequencedRack.WORK_MASTER)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals((1..40).toList(), versions.sorted())
        assertEquals(40, catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion)
    }

    @Test
    fun `미들웨어에 케이퍼빌리티 목록과 카탈로그를 함께 주면 생성이 실패한다`() {
        // 한쪽이 조용히 무시되는 길을 두지 않는다.
        val robots = NO_ROBOT
        assertFailsWith<IllegalArgumentException> {
            Middleware(robots, capabilities = listOf(PrepareSequencedRack()), missions = catalog())
        }
        Middleware(robots, missions = catalog())
        Middleware(robots, capabilities = MissionCatalog.codeCapabilities())
        Middleware(robots)
    }

    private companion object {
        /** 생성만 보는 시험이라 부르면 안 된다. */
        val NO_ROBOT: RobotPort = java.lang.reflect.Proxy.newProxyInstance(
            RobotPort::class.java.classLoader,
            arrayOf(RobotPort::class.java),
        ) { _, method, _ -> error("생성 시험에서 로봇을 불렀다: ${method.name}") } as RobotPort
    }
}
```

- [ ] **Step 2: 모델·포트·셀 대역 패치**

`C:/Users/Eisen/AppData/Local/Temp/p3-patches/task1.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다.

```diff
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Model.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Model.kt
index 0d57b6a..52c6528 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Model.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Model.kt
@@ -125,8 +125,56 @@ data class LateEvent(
  *
  * - [ROBOT] — 계약(④)의 원자 스킬 하나. 기종은 어댑터 뒤에 있다
  * - [FLEET] — D 수준 위임. 운반 전체를 플릿에 맡기고 결과만 받는다([AmrFleetPort], 프로젝트용 계약)
+ * - [SIGNAL] — 설비 대기. 하위에 요청을 보내지 않고 이름 있는 설비 신호([CellSignals.signal])가 기대 값이
+ *   될 때까지 기다린다. 대기 사양은 단위의 [ExecutionUnit.wait] 에 있다
+ *
+ * ★**셋째 값이 생겼으므로 «로봇이 아니면 플릿» 으로 가르지 않는다.** 그렇게 가르면 설비 대기가 조용히 플릿으로
+ * 간다 — 경로마다 `when` 으로 나눈다.
+ */
+enum class Route { ROBOT, FLEET, SIGNAL }
+
+/**
+ * 설비 대기의 기한이 지났을 때 갈 상태(임무 정의의 `onDeadline`).
+ *
+ * - [OPERATOR_HOLD] — 대기 단위가 운영자 판단에 선다. 라인이 멈추고 확인(진행)·재작업(다시 기다림)·실행 취소로 푼다
+ * - [ABORTED] — 대기 단위는 `FAILED`(`SIGNAL_DEADLINE`)로 남고 실행이 중단된다. 남은 단위는 내보내지 않는다
+ *
+ * `FAILED` 로만 두고 진행을 맡기지 않는 이유: 엔진은 `FAILED` 단위 뒤에도 다음 단위를 내보낸다. 신호를 못 본 채
+ * 다음 로봇 단계를 하면 대기를 둔 뜻이 없다.
+ */
+enum class DeadlineOutcome { OPERATOR_HOLD, ABORTED }
+
+/**
+ * 설비 대기 단위([Route.SIGNAL])의 사양 — 무엇을 어떤 값이 될 때까지, 언제까지 기다리고, 그 뒤에 어디로 가는가.
+ *
+ * 판정은 신호의 **지금 값**이다. 읽은 값이 [expect] 와 같으면 끝난다. 관측 시각은 자취에만 쓴다 — 상태 신호만
+ * 다루므로 시작 전부터 그 값이었다면 그 상태가 이미 성립한 것이다. 짧게 켜졌다 꺼지는 이벤트형 신호는 다루지 않는다.
+ *
+ * @param deadline 단위가 시작한 시각부터의 기한. 재작업하면 다시 시작한다.
  */
-enum class Route { ROBOT, FLEET }
+data class WaitSpec(
+    val signal: String,
+    val expect: String,
+    val deadline: Duration,
+    val onDeadline: DeadlineOutcome,
+) {
+    companion object {
+        /**
+         * 대기 단위의 `skillType`. **계약 카탈로그의 스킬 이름과 겹치지 않는 값이다** — 겹치면 효과·사전 조건·선택
+         * 파라미터를 읽는 자리가 이 단위를 그 스킬로 잘못 읽는다. 플릿의 `transport` 와 같은 자리의 이름이다.
+         */
+        const val SKILL_TYPE = "equipment_wait"
+
+        /** 기한까지 기대 값을 못 봤다 — 대기 단위의 실패 분류. */
+        const val SIGNAL_DEADLINE = "SIGNAL_DEADLINE"
+
+        /** 대기 사양을 단위 파라미터에도 싣는 키 — 인시던트의 `intent.unitParameters` 로 나가 무엇을 기다렸는지가 보인다. */
+        const val P_SIGNAL = "signal"
+        const val P_EXPECT = "expect"
+        const val P_DEADLINE_SECONDS = "deadlineSeconds"
+        const val P_ON_DEADLINE = "onDeadline"
+    }
+}
 
 // ── 상류 인터페이스의 모양 — OPC UA ISA-95 Job Control 10031-4 의 타입을 따른다
 
@@ -236,6 +284,8 @@ data class ExecutionUnit(
      * 지금은 아무 발신자도 채우지 않는다(§15.76). 비어 있으면 `null`.
      */
     var result: String? = null,
+    /** 설비 대기 단위([Route.SIGNAL])의 사양. 다른 경로의 단위는 널이다. */
+    val wait: WaitSpec? = null,
 )
 
 /**
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
index 9aa9d6d..1439c50 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt
@@ -215,11 +215,69 @@ interface CellSignals {
      */
     fun holding(material: String): List<String>? = null
 
+    /**
+     * 이름 있는 설비 신호의 **지금 값**(설비 대기, [Route.SIGNAL]). 이름은 현장 신호 사양의 이름이다.
+     *
+     * `null` 은 **읽지 못했다**는 뜻이고 «기대 값이 아니다» 와 다르다. 설비 대기는 `null` 을 받으면 계속 기다리고,
+     * 기한이 지나면 정해 둔 상태로 간다 — 못 읽은 것을 아닌 것으로 접지 않는다.
+     *
+     * 기본값이 `null` 인 것은 **자리별 점유만 내는 설비가 이름 있는 신호를 모르기** 때문이다. 어느 주소가 어느 신호인지의
+     * 매핑은 이 층이 아니라 드라이버의 일이다.
+     */
+    fun signal(name: String): NamedSignal? = null
+
     object None : CellSignals {
         override fun observe(location: String): SlotSignal? = null
     }
 }
 
+/**
+ * 이름 있는 설비 신호 하나의 값과 그 관측 시각.
+ *
+ * 값은 문자열이다 — 신호 사양의 종류가 `BOOLEAN` 이면 `true`·`false` 다. [observedAt] 이 `null` 이면 설비가
+ * 시각을 안 주는 것이고 **읽은 순간**이 그 시각이다([SlotSignal] 과 같은 규칙). 시각은 자취에 남기는 데만 쓴다.
+ */
+data class NamedSignal(val value: String, val observedAt: Instant? = null)
+
+// ── 임무 정의 — 작업 지시를 실행 단위로 펼치는 케이퍼빌리티의 출처
+
+/**
+ * WorkMaster 마다 **지금 활성인** 케이퍼빌리티와 그 임무 버전을 주는 포트.
+ *
+ * 미들웨어는 새 작업 지시를 계획할 때만 이것을 읽는다. 실행은 생성 때 읽은 쌍을 쥐고 끝까지 그것으로 돈다 —
+ * 리비전도 그 쌍으로 계획한다. 그래서 활성 버전이 바뀌어도 **도는 실행은 옛 버전으로 끝난다.**
+ *
+ * 활성화(새 버전을 세우는 일)는 이 포트에 없다. 그것은 구현의 일이고, 검증을 통과해야 선다
+ * (`dev.picasso.middleware.mission.InMemoryMissionCatalog`). 이 층은 읽기만 한다.
+ */
+interface MissionCatalog {
+
+    /** 이 WorkMaster 의 지금 활성인 정의. 없으면 `null` — 그 작업 지시는 «모르는 논리적 능력» 으로 거부된다. */
+    fun active(workMasterId: String): ActiveMission?
+
+    companion object {
+        /** 코드로 정의한 케이퍼빌리티 셋. 미들웨어의 기본 목록이다. 부를 때마다 새 인스턴스를 만든다. */
+        fun codeCapabilities(): List<LogicalCapability> = listOf(PrepareSequencedRack(), DeliverContainer(), InspectAsset())
+
+        /** 코드 케이퍼빌리티만으로 된 카탈로그 — 임무 버전이 없다. 같은 WorkMaster 가 둘이면 뒤엣것이 남는다. */
+        fun of(capabilities: List<LogicalCapability>): MissionCatalog {
+            val byWorkMaster = capabilities.associateBy { it.workMasterId }
+            return object : MissionCatalog {
+                override fun active(workMasterId: String): ActiveMission? =
+                    byWorkMaster[workMasterId]?.let { ActiveMission(it, missionVersion = null) }
+            }
+        }
+    }
+}
+
+/**
+ * 활성인 정의 하나 — 케이퍼빌리티와 그것이 어느 임무 버전인지.
+ *
+ * @param missionVersion 데이터 정의면 그 WorkMaster 안에서 1부터 오르는 번호, 코드 케이퍼빌리티면 `null`.
+ *   작업 지시의 버전(`JobOrder.version`, 단위의 `revision`)과 다른 축이다.
+ */
+data class ActiveMission(val capability: LogicalCapability, val missionVersion: Int?)
+
 /**
  * 한 자리의 설비 신호 — 재석 여부와 설비가 읽은 식별자(부품 타입 라벨이든 용기 태그든,
  * 설비가 읽을 수 있는 것 — §15.80 의 신원 수단), 그리고 그 신호의 시각 `t_p`.
diff --git a/picasso/src/test/kotlin/dev/picasso/middleware/CellMimic.kt b/picasso/src/test/kotlin/dev/picasso/middleware/CellMimic.kt
index c278715..61b9472 100644
--- a/picasso/src/test/kotlin/dev/picasso/middleware/CellMimic.kt
+++ b/picasso/src/test/kotlin/dev/picasso/middleware/CellMimic.kt
@@ -17,6 +17,11 @@ import java.time.Instant
  *   신호 지속 시간보다 길 때 생기는 일이며, 보고서가 PLC 쪽 **래치 비트**를 요구하라고 한 이유다.
  * - [latch] — 그 자리에 래치 비트를 둔다. 펄스가 지나가도 마지막 펄스가 **시각과 함께** 남는다.
  *
+ * ## 이름 있는 신호 (설비 대기)
+ *
+ * [setSignal] 이 이름 있는 신호의 **지금 값**을 정한다 — 랙 도착 같은 상태 신호다. 정하지 않은 이름과 [clearSignal] 한
+ * 이름은 말이 없다(`null`). 시각을 주면 그 시각을, 안 주면 읽는 순간을 관측 시각으로 답한다.
+ *
  * [live] 를 주면 프로그램된 값이 없는 자리는 그 세계를 읽는다 — 시나리오 ①에서
  * 플릿 더블이 실제로 내려놓은 용기를 인계 설비가 **보는** 것을 흉내낸다. 침묵시킨
  * 자리는 세계가 어떻든 말이 없다.
@@ -34,6 +39,19 @@ class CellMimic(
     private val pulses = mutableMapOf<String, Pulse>()
     private val latched = mutableSetOf<String>()
     private val silenced = mutableSetOf<String>()
+    private val signals = mutableMapOf<String, NamedSignal>()
+
+    /** 이름 있는 신호가 이 값을 읽게 한다. [at] 이 없으면 읽는 순간이 관측 시각이다. */
+    fun setSignal(name: String, value: String, at: Instant? = null) {
+        signals[name] = NamedSignal(value, at)
+    }
+
+    /** 그 신호에 대해서는 말이 없게 한다 — 못 읽는 신호다. */
+    fun clearSignal(name: String) {
+        signals.remove(name)
+    }
+
+    override fun signal(name: String): NamedSignal? = signals[name]
 
     /**
      * 이 자재를 든 자리들. **침묵한 자리는 안 센다** — 신호가 없는 자리를 «없다» 에 넣으면 그것이
```

- [ ] **Step 3: 커밋**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission"
git add picasso/src/main/kotlin/dev/picasso/middleware/mission picasso/src/test/kotlin/dev/picasso/middleware/mission picasso/src/main/kotlin/dev/picasso/middleware/Model.kt picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt picasso/src/test/kotlin/dev/picasso/middleware/CellMimic.kt
git status --short
git commit -q -F - <<'EOF'
feat(p3): 임무 정의 모델·파서·검증기·카탈로그와 신호 포트 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
```
Expected: `git status --short` 에 이 Task 의 15개 파일만 스테이징됨.

### Task 2: 엔진, 인시던트, 내보내기, 인계 번들

**Files:**
- Modify(main): `picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt`, `Incident.kt`, `IncidentLog.kt`, `LedgerExport.kt`, `LogicalCapability.kt`(KDoc)
- Modify(test): `EvidenceWindowTest.kt`(내보내기 버전 «6»), `LedgerExportTest.kt`(해시 칸 목록에 임무 버전 행)
- Modify(data): `handoff/narrator/run-1..4/` 의 `incidents.jsonl`·`manifest.json`·`remedy-searches.jsonl`(run-3 은 앞의 둘) — 스파이크에서 `ExportFixtureTest` 로 다시 산출한 것. 패치로 넣고 다시 산출하지 않는다(산출하면 실제 시계 칸과 런 식별자가 달라진다).
- Create(test): `picasso/src/test/kotlin/dev/picasso/middleware/EquipmentWaitTest.kt`, `MissionVersionScenarioTest.kt`

- [ ] **Step 1: 새 시험 2개 복사**

`p3-patches/files/picasso/src/test/kotlin/dev/picasso/middleware/EquipmentWaitTest.kt`·`MissionVersionScenarioTest.kt` 를 워크트리의 같은 경로로 복사한다. 내용은 아래 블록과 같다.

```kotlin
package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 설비 대기 — 임무 정의의 대기 노드가 엔진에서 이름 있는 신호를 기다리고, 기한이 지나면 정해 둔 상태로 간다.
 *
 * 대역은 `SequencingRackTest` 와 같다(in-process 하네스, `ClientRobotPort`, `CellMimic`). 정의는 데이터판
 * `PrepareSequencedRack` 의 맨 앞에 «랙 도착 신호가 `true` 가 될 때까지» 대기를 둔 것이다.
 *
 * ★**대기 중에는 로봇 태스크가 하나도 없어야 한다.** 신호를 못 본 채 로봇이 움직이면 대기를 둔 뜻이 없다 — 그래서
 * 기한 뒤 갈래마다 에뮬레이터의 태스크 수를 댄다.
 */
class EquipmentWaitTest {

    private class World(wait: String) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val mw: Middleware

        init {
            val activated = catalog.activate(wait, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS)
            assertIs<Activation.Activated>(activated, "$activated")
            mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog)
        }

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        /** 시간을 [by] 만큼 밀고 미들웨어를 한 번 돌린다. */
        fun step(by: Duration) {
            harness.advance(by)
            Thread.sleep(40)
            mw.pump()
        }

        fun drive(rounds: Int = 40, step: Duration = Duration.ofSeconds(30), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                step(step)
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    private fun order(version: Int = 1) = JobOrder(
        jobOrderId = "SEQ-401",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = version,
        requiredEvidence = Evidence.E2,
        materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1), MaterialRequirement("ENGINE-COVER-B", 1)),
        equipmentRequirements = listOf(
            EquipmentRequirement("RACK-401.S01", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
            EquipmentRequirement("RACK-401.S02", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
            EquipmentRequirement("BIN-401-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
            EquipmentRequirement("BIN-401-B", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
        ),
    )

    private fun World.start(order: JobOrder = order()): Middleware.Execution {
        order.equipmentRequirements.filter { it.equipmentUse == EquipmentUse.DESTINATION }
            .forEach { cell.program(it.id, it.properties[EquipmentUse.PROP_MATERIAL]) }
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
        mw.pump() // 대기 단위가 출발한다 — 시작 시각이 지금이다
        assertEquals(UnitState.RUNNING, exec.waitUnit().state)
        return exec
    }

    private fun Middleware.Execution.waitUnit() = units.single { it.unitId == MissionFixtures.WAIT_NODE }
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null
    private fun Middleware.Execution.signalTrail() = eventTrail.filter { it.kind == "CELL_SIGNAL" && it.detail.startsWith("signal ") }

    // ── 신호를 본다

    @Test
    fun `신호가 기대 값을 읽으면 대기가 E2 로 끝나고 그 뒤에 로봇 단위가 출발한다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            assertEquals(Route.SIGNAL, exec.waitUnit().route)
            assertEquals(1, exec.missionVersion)

            w.step(Duration.ofSeconds(30))
            assertEquals(UnitState.RUNNING, exec.waitUnit().state)
            assertEquals(0, w.tasks().size, "대기 중에 로봇 태스크가 나갔다")

            val arrivedAt = w.harness.clock.now()
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true", at = arrivedAt)
            w.step(Duration.ofSeconds(1))
            val wait = exec.waitUnit()
            assertEquals(UnitState.DONE, wait.state)
            assertEquals(Evidence.E2, wait.reached, "설비가 관측한 사실이라 E2 다")
            assertEquals(Verification.MATCHED, wait.verification)
            assertEquals(arrivedAt, wait.evidenceAt)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(listOf(MissionFixtures.WAIT_NODE, "RACK-401.S01", "RACK-401.S02"), exec.completedUnits)
            assertEquals(2, w.tasks().size, "대기 단위가 로봇 태스크가 됐다")
            assertEquals(Evidence.E2, w.mw.responses().last().reachedEvidence)
        }
    }

    @Test
    fun `신호가 이미 기대 값이면 바로 끝난다 — 지금 값으로 판정한다`() {
        // 상태 신호만 다룬다. 시작 전부터 그 값이었다면 그 상태가 이미 성립한 것이다(이벤트형 신호는 한계).
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true", at = Instant.parse("2020-01-01T00:00:00Z"))
            val exec = w.start()
            w.mw.pump()
            assertEquals(UnitState.DONE, exec.waitUnit().state)
        }
    }

    @Test
    fun `신호 읽기는 값이 바뀔 때만 자취에 남는다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            repeat(5) { w.step(Duration.ofSeconds(10)) }
            assertEquals(1, exec.signalTrail().size, "같은 값을 진행마다 남겼다: ${exec.signalTrail()}")

            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true")
            w.step(Duration.ofSeconds(1))
            val trail = exec.signalTrail().map { it.detail }
            assertEquals(2, trail.size, "$trail")
            assertTrue(trail[0].startsWith("signal rack_present: value=false"), "$trail")
            assertTrue(trail[1].startsWith("signal rack_present: value=true"), "$trail")
        }
    }

    // ── 기한

    @Test
    fun `신호를 못 읽으면 계속 기다리고 기한 시각에는 아직 서 있다가 넘으면 운영자 보류로 선다`() {
        World(MissionFixtures.withArrivalWait(deadlineSeconds = 120)).use { w ->
            // 신호를 정하지 않았다 — 셀이 그 이름에 말이 없다(null). «기대 값이 아님» 으로 접지 않는다.
            val exec = w.start()
            w.step(Duration.ofSeconds(120))
            assertEquals(UnitState.RUNNING, exec.waitUnit().state, "기한 시각에 벌써 넘겼다")
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            assertEquals(listOf("signal rack_present: no signal"), exec.signalTrail().map { it.detail })

            w.step(Duration.ofSeconds(1))
            val wait = exec.waitUnit()
            assertEquals(UnitState.OPERATOR_HOLD, wait.state)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, wait.failureClass)
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertEquals(0, w.tasks().size, "기한 뒤 보류인데 로봇이 움직였다")

            // 라인이 멈춘다 — 더 돌려도 다음 단위가 안 나간다.
            repeat(3) { w.step(Duration.ofSeconds(30)) }
            assertEquals(0, w.tasks().size)
            assertTrue(w.mw.responses().last().operatorRequired)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, w.mw.responses().last().incompleteUnits[MissionFixtures.WAIT_NODE])

            val incident = w.mw.incidents().single()
            assertEquals(MissionFixtures.WAIT_NODE, incident.unitId)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, incident.failureClass)
            assertEquals("SIGNAL", incident.route)
            assertTrue(incident.unresolved)
            assertEquals(1, incident.intent.missionVersion)
            assertEquals(
                mapOf("signal" to "rack_present", "expect" to "true", "deadlineSeconds" to "120", "onDeadline" to "OPERATOR_HOLD"),
                incident.intent.unitParameters,
                "무엇을 언제까지 기다렸는지가 인시던트에 안 실렸다",
            )
            // ★기한 시점의 관측이 근거 윈도우 안에 있다 — 값이 안 바뀌어 시작 무렵에 한 번 적힌 줄은 윈도우 밖이다.
            assertTrue(
                incident.evidenceWindow.any { it.detail == "signal rack_present at deadline: no signal" },
                "${incident.evidenceWindow}",
            )
        }
    }

    @Test
    fun `보류를 재작업하면 기한이 다시 시작하고 확인하면 근거 E0 로 진행한다`() {
        World(MissionFixtures.withArrivalWait(deadlineSeconds = 60)).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            w.step(Duration.ofSeconds(61))
            assertEquals(UnitState.OPERATOR_HOLD, exec.waitUnit().state)

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, MissionFixtures.WAIT_NODE, OperatorDecision.REWORK, OPERATOR))
            w.mw.pump() // 다시 출발한다 — 새 시도, 새 시작 시각
            val restarted = exec.waitUnit()
            assertEquals(UnitState.RUNNING, restarted.state)
            assertEquals(1, restarted.attempt)
            assertEquals(w.harness.clock.now(), restarted.requestedAt)
            w.step(Duration.ofSeconds(60))
            assertEquals(UnitState.RUNNING, exec.waitUnit().state, "기한이 다시 시작하지 않았다")
            // 첫 시도의 값, 기한 시점의 값, 새 시도의 첫 값.
            assertEquals(3, exec.signalTrail().size, "새 시도의 첫 값을 다시 남기지 않았다: ${exec.signalTrail()}")
            w.step(Duration.ofSeconds(1))
            assertEquals(UnitState.OPERATOR_HOLD, exec.waitUnit().state)

            // 사람이 신호 대신 확인했다 — 근거는 E0 로 남고 작업 응답의 등급도 거기로 내려간다.
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, MissionFixtures.WAIT_NODE, OperatorDecision.CONFIRM_DONE, OPERATOR))
            assertEquals(UnitState.DONE, exec.waitUnit().state)
            assertEquals(Evidence.E0, exec.waitUnit().reached)
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E0, w.mw.responses().last().reachedEvidence)
            assertEquals(2, w.mw.incidents().size, "기한마다 인시던트 하나")
            assertEquals(listOf(OperatorDecision.REWORK, OperatorDecision.CONFIRM_DONE), w.mw.incidents().map { it.resolution?.decision })
        }
    }

    @Test
    fun `기한 뒤 ABORTED 면 대기 단위는 FAILED 로 남고 실행이 중단되며 남은 단위는 안 나간다`() {
        World(MissionFixtures.withArrivalWait(deadlineSeconds = 60, onDeadline = DeadlineOutcome.ABORTED)).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            w.step(Duration.ofSeconds(61))

            val wait = exec.waitUnit()
            assertEquals(UnitState.FAILED, wait.state)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, wait.failureClass)
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(listOf(UnitState.ABORTED, UnitState.ABORTED), exec.units.drop(1).map { it.state })
            assertNull(exec.active)
            // ★취소 요청이 아니다 — 아무도 취소하지 않았는데 취소 응답이 있으면 «누가 멈췄나» 에 거짓으로 답한다.
            assertNull(w.mw.lastCancel(exec.executionId), "기한 중단이 취소 응답을 남겼다")

            // ★남은 단위가 안 나간다 — 같은 라운드에도, 더 돌려도.
            repeat(4) { w.step(Duration.ofSeconds(30)) }
            assertEquals(0, w.tasks().size, "중단된 실행의 다음 단위가 나갔다")
            assertEquals(PhysicalState.ABORTED, exec.physicalState)

            val response = w.mw.responses().last()
            assertEquals(PhysicalState.ABORTED, response.physicalState)
            assertEquals(emptyList(), response.completedUnits)
            val notStarted = "not started: rack_present deadline aborted the execution"
            assertEquals(
                mapOf(MissionFixtures.WAIT_NODE to WaitSpec.SIGNAL_DEADLINE, "RACK-401.S01" to notStarted, "RACK-401.S02" to notStarted),
                response.incompleteUnits,
            )
            assertTrue(!response.operatorRequired, "정해 둔 중단이다 — 사람의 판단을 기다리지 않는다")
            assertEquals(1, w.mw.responses().count { it.physicalState == PhysicalState.ABORTED }, "중단 통보가 거듭 나갔다")

            val incident = w.mw.incidents().single()
            assertEquals(WaitSpec.SIGNAL_DEADLINE, incident.failureClass)
            assertTrue(!incident.unresolved, "중단은 운영자 판단을 기다리지 않는다")

            // 종료한 실행은 리비전으로 다시 열리지 않는다 — 재작업은 새 작업 지시다.
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(version = 2), ROBOT))
        }
    }

    // ── 취소와 리비전

    @Test
    fun `대기 중에 취소하면 대기 단위는 ABORTED 이고 취소 경로로 끝난다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            val exec = w.start()
            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()

            assertEquals(UnitState.ABORTED, exec.waitUnit().state)
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(MissionFixtures.WAIT_NODE, report.inProgressUnit)
            assertEquals(listOf("RACK-401.S01", "RACK-401.S02"), report.notStartedUnits)
            assertEquals("not_applicable", report.cleanup)
            assertEquals(0, w.tasks().size)
        }
    }

    @Test
    fun `대기 중의 리비전은 기다림을 그대로 잇는다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            val wait = exec.waitUnit()
            val startedAt = wait.requestedAt

            w.step(Duration.ofSeconds(30))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2), ROBOT))
            assertSame(wait, exec.waitUnit(), "대기 단위가 바뀌었다")
            assertEquals(UnitState.RUNNING, wait.state)
            assertEquals(startedAt, wait.requestedAt, "기한이 다시 시작했다")
            assertEquals(0, w.tasks().size, "리비전이 대기 단위를 로봇으로 보냈다")

            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true")
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(2, w.tasks().size)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        val OPERATOR = Approver("operator-1", ApproverKind.PERSON)
    }
}
```

```kotlin
package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import dev.picasso.middleware.mission.MissionRefusalKind
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 임무 버전 — **도는 실행은 옛 버전으로 끝나고 새 작업 지시만 새 버전으로 돈다**(운영 관리 화면 설계 제안 §10 의 3·4).
 *
 * 버전 1 은 코드 `PrepareSequencedRack` 을 데이터로 옮긴 것이고, 버전 2 는 그 앞에 랙 도착 대기를 둔 것이다. 버전 3 은
 * 신호 사양에 없는 신호를 참조한 잘못된 변경이다.
 *
 * 같은 기체에 두 실행을 겹치지 않는다 — 버전 1 의 실행은 [ROBOT_A] 와 랙 301, 버전 2 의 실행은 [ROBOT_B] 와 랙 302 다.
 * 슬롯 점유도 안 겹친다.
 */
class MissionVersionScenarioTest {

    private class World : AutoCloseable {
        val harness = Harness(
            mapOf(
                ROBOT_A to Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
                ROBOT_B to Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
            ),
        )
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog)

        fun activate(text: String) = catalog.activate(text, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS)

        fun tasks(robot: String) =
            harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(robot).build()).tasksList

        fun drive(rounds: Int = 60, step: Duration = Duration.ofSeconds(30), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: A=${tasks(ROBOT_A).map { it.taskId to it.taskState }} B=${tasks(ROBOT_B).map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    /** 랙 하나, 슬롯 둘(A형·B형), 제시 자리 둘. [wrongAt] 슬롯에는 셀 장치가 다른 부품을 본다. */
    private fun World.order(rack: String, version: Int = 1, wrongAt: String? = null): JobOrder {
        val order = JobOrder(
            jobOrderId = "SEQ-$rack",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1), MaterialRequirement("ENGINE-COVER-B", 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement("RACK-$rack.S01", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
                EquipmentRequirement("RACK-$rack.S02", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
                EquipmentRequirement("BIN-$rack-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
                EquipmentRequirement("BIN-$rack-B", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
            ),
        )
        order.equipmentRequirements.filter { it.equipmentUse == EquipmentUse.DESTINATION }.forEach {
            cell.program(it.id, if (it.id == wrongAt) "ENGINE-COVER-X" else it.properties[EquipmentUse.PROP_MATERIAL])
        }
        return order
    }

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    @Test
    fun `버전 1 실행 중 버전 2 를 활성화하면 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행은 버전 1 로 끝난다`() {
        World().use { w ->
            assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 1), w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK))

            // 버전 1 — 둘째 슬롯에서 셀 장치가 다른 부품을 본다. 그 인시던트는 버전 2 가 선 **뒤에** 난다.
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("301", wrongAt = "RACK-301.S02"), ROBOT_A)).execution
            assertEquals(1, first.missionVersion)
            w.drive { "RACK-301.S01" in first.completedUnits }
            assertTrue(w.mw.incidents().isEmpty(), "버전 2 가 서기 전에 인시던트가 났다 — 시나리오가 아무것도 안 가린다")

            // 버전 2 가 선다 — 맨 앞에 랙 도착 대기. 기한은 넉넉히 둔다(옛 실행이 끝날 때까지 기다리게 한다).
            assertEquals(
                Activation.Activated(PrepareSequencedRack.WORK_MASTER, 2),
                w.activate(MissionFixtures.withArrivalWait(deadlineSeconds = 3600)),
            )

            // 버전 3 — 신호 사양에 없는 신호. 거부되고 활성 버전은 2 로 남는다. 거부는 종류와 후속 행동으로 보인다.
            val refused = assertIs<Activation.Refused>(w.activate(MissionFixtures.withArrivalWait(signal = "rack_ready")))
            val refusal = refused.refusals.single()
            assertEquals(MissionRefusalKind.SIGNAL_NOT_IN_SPEC, refusal.kind)
            assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
            assertEquals("신호 이름을 고치거나 신호 사양에 더한다", refusal.nextAction)
            assertEquals(2, w.catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion)

            // 새 작업 지시는 버전 2 — 다른 기체, 다른 랙.
            val second = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("302"), ROBOT_B)).execution
            assertEquals(2, second.missionVersion)
            assertEquals(Route.SIGNAL, second.units.first().route)

            // 랙이 도착하기 전에는 버전 2 의 로봇이 안 움직인다. 옛 실행은 그동안 끝난다.
            w.drive { first.settled() }
            assertEquals(0, w.tasks(ROBOT_B).size, "대기 전에 버전 2 의 로봇이 움직였다")

            // 옛 실행의 리비전은 **버전 1 로** 계획한다 — 대기 단위가 들어오지 않는다. 부분 완료는 리비전으로 다시 열리므로
            // 카탈로그로 계획하면 새 버전의 대기 단위가 «새 단위» 로 붙는다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("301", version = 2, wrongAt = "RACK-301.S02"), ROBOT_A))
            assertEquals(listOf("RACK-301.S01", "RACK-301.S02"), first.units.map { it.unitId }, "리비전이 새 버전의 단위를 들였다")
            assertEquals(1, first.missionVersion)

            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true")
            w.drive { second.settled() && first.settled() }

            // 옛 실행은 버전 1 로 끝났다 — 대기 없이, 둘째 슬롯은 불일치.
            assertEquals(PhysicalState.PARTIAL, first.physicalState)
            assertTrue(first.units.none { it.route == Route.SIGNAL })
            assertEquals(Middleware.MISMATCH, first.units.single { it.unitId == "RACK-301.S02" }.failureClass)

            // 새 실행은 버전 2 로 끝났다 — 대기를 거쳤다.
            assertEquals(PhysicalState.PHYSICALLY_DONE, second.physicalState)
            assertEquals(listOf(MissionFixtures.WAIT_NODE, "RACK-302.S01", "RACK-302.S02"), second.completedUnits)

            // ★인시던트는 그 실행의 버전을 싣는다 — 활성 버전(2)이 아니다.
            val incident = w.mw.incidents().single()
            assertEquals(first.executionId, incident.executionId)
            assertEquals(1, incident.intent.missionVersion, "인시던트가 실행이 쥔 버전이 아닌 것을 실었다")
        }
    }

    @Test
    fun `버전 2 의 대기 기한 인시던트가 그 버전을 싣고 해시에 들며 내보내기에는 안 실린다`() {
        World().use { w ->
            w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
            w.activate(MissionFixtures.withArrivalWait(deadlineSeconds = 60))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("303"), ROBOT_B)).execution
            w.drive(step = Duration.ofSeconds(20)) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            // 기한 뒤에 버전이 또 올라도 인시던트는 그 실행의 버전이다.
            assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 3), w.activate(MissionFixtures.withArrivalWait(deadlineSeconds = 30)))
            val incident = w.mw.incidents().single()
            assertEquals(WaitSpec.SIGNAL_DEADLINE, incident.failureClass)
            assertEquals("SIGNAL", incident.route)
            assertEquals(2, incident.intent.missionVersion)

            // 해시에 든다 — 다른 버전으로 펼친 같은 모양의 인시던트는 다른 인시던트다.
            val other = incident.copy(intent = incident.intent.copy(missionVersion = 3))
            assertTrue(incident.digest() != other.digest(), "임무 버전이 해시에 안 들어갔다")
            assertTrue(incident.digest() != incident.copy(intent = incident.intent.copy(missionVersion = null)).digest())

            // 내보내기에는 안 실린다(ADR 9). 경로의 새 하위 범주는 나간다.
            val line = LedgerExport.incidents(listOf(incident))
            assertTrue("missionVersion" !in line, line)
            assertTrue("\"route\":\"SIGNAL\"" in line, line)
        }
    }

    @Test
    fun `카탈로그를 안 주면 코드 케이퍼빌리티가 버전 없이 돈다`() {
        World().use { w ->
            val plain = Middleware(ClientRobotPort(w.harness.client()), w.cell, now = { w.harness.clock.now() })
            val exec = assertIs<Middleware.Submission.Accepted>(plain.submit(w.order("304", wrongAt = "RACK-304.S01"), ROBOT_A)).execution
            assertNull(exec.missionVersion)
            assertIs<PrepareSequencedRack>(exec.capability)
            for (round in 1..20) {
                plain.pump()
                if (plain.incidents().isNotEmpty()) break
                w.harness.advance(Duration.ofSeconds(30))
                Thread.sleep(40)
            }
            assertNull(plain.incidents().first().intent.missionVersion)
        }
    }

    @Test
    fun `배정은 카탈로그를 한 번 읽은 버전으로 실행을 세운다`() {
        World().use { w ->
            w.activate(MissionFixtures.withArrivalWait())
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.adopt(w.order("305"), listOf(ROBOT_A))).execution
            assertEquals(1, exec.missionVersion)
            assertEquals(Route.SIGNAL, exec.units.first().route)
        }
    }

    @Test
    fun `배정 사이에 활성화가 끼어도 실행은 관문에 댄 버전을 쥔다`() {
        // ★한 스레드의 시험에서는 배정이 카탈로그를 두 번 읽어도 같은 답이 온다 — 그래서 두 번째 읽기부터 다음 버전을
        //   주는 카탈로그로 «관문을 본 뒤, 작업 수락 전» 의 활성화를 흉내 낸다.
        World().use { w ->
            w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
            val v1 = w.catalog.active(PrepareSequencedRack.WORK_MASTER)!!
            w.activate(MissionFixtures.withArrivalWait())
            val v2 = w.catalog.active(PrepareSequencedRack.WORK_MASTER)!!
            val flipping = object : MissionCatalog {
                var reads = 0
                override fun active(workMasterId: String): ActiveMission = if (reads++ == 0) v1 else v2
            }
            val mw = Middleware(ClientRobotPort(w.harness.client()), w.cell, now = { w.harness.clock.now() }, missions = flipping)

            val exec = assertIs<Middleware.Submission.Accepted>(mw.adopt(w.order("306"), listOf(ROBOT_A))).execution
            assertEquals(1, exec.missionVersion, "관문은 버전 1 로 봤는데 실행은 다른 버전을 쥐었다")
            assertTrue(exec.units.none { it.route == Route.SIGNAL }, "관문이 본 적 없는 단위가 실행에 들었다")
            assertEquals(1, flipping.reads, "배정이 카탈로그를 두 번 읽었다")
        }
    }

    @Test
    fun `리비전이 WorkMaster 를 바꾸면 거부하고 실행은 그대로다`() {
        // 리비전은 실행이 쥔 케이퍼빌리티로 계획한다. 다른 WorkMaster 의 작업 지시를 그것으로 펼치면 다른 일을 옛
        // 정의로 돌리게 된다 — 새 작업 지시다. 요구 등급을 E0 로 낮춰 근거 등급 검사가 대신 거부하지 않게 한다.
        World().use { w ->
            w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
            val order = w.order("307")
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT_A)).execution
            val planned = exec.units.map { it.unitId }

            val switched = order.copy(workMasterId = InspectAsset.WORK_MASTER, version = 2, requiredEvidence = Evidence.E0)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(switched, ROBOT_A))
            assertTrue("WorkMaster" in refused.reason, refused.reason)

            assertEquals(PrepareSequencedRack.WORK_MASTER, exec.order.workMasterId, "거부된 리비전이 작업 지시를 바꿨다")
            assertEquals(1, exec.version)
            assertEquals(planned, exec.units.map { it.unitId })
            assertEquals(1, exec.missionVersion)
        }
    }

    private companion object {
        const val ROBOT_A = "hum-02"
        const val ROBOT_B = "hum-03"
    }
}
```

- [ ] **Step 2: 엔진·인시던트·내보내기·인계 번들 패치**

`C:/Users/Eisen/AppData/Local/Temp/p3-patches/task2.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다.

```diff
diff --git a/handoff/narrator/run-1/incidents.jsonl b/handoff/narrator/run-1/incidents.jsonl
index 26ca0a7..4d8f873 100644
--- a/handoff/narrator/run-1/incidents.jsonl
+++ b/handoff/narrator/run-1/incidents.jsonl
@@ -1,9 +1,9 @@
-{"incidentId":"incident-1","jobOrderId":"PATROL-1","executionId":"exec-2","robotId":"hum-02","unitId":"remedy-1-pick_place","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-09-22T16:47:30.583823300Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["remedy-1-pick_place","PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":null,"destination":null,"expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":{"id":"narrator-1","kind":"AGENT"},"review":null,"resolution":null,"digest":"d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143"}
-{"incidentId":"incident-2","jobOrderId":"SEQ-3","executionId":"exec-4","robotId":"hum-04","unitId":"RACK-204.S03","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-09-22T16:47:35.163200200Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"X_FIXTURE_GRIPPER_SLIP","vendorDetail":"X_FIXTURE_GRIPPER_SLIP","errorHint":"그리퍼 패드의 마모를 점검하고 대상 자세를 다시 잡으십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-3#RACK-204.S03"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:39Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_GRIPPER_SLIP class=GRASP_FAILED can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:01:39Z","kind":"CELL_SIGNAL","detail":"RACK-204.S03: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S03"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S03","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S03","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S03","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"af2b5726d50290366458080e071bc297b258668ffc7dac77d1fd7c66d46dac75"}
-{"incidentId":"incident-3","jobOrderId":"PATROL-4","executionId":"exec-5","robotId":"hum-05","unitId":"PUMP-01.travel","at":"2026-09-06T00:01:40Z","wallClockAt":"2026-09-22T16:47:35.211836200Z","failureClass":"LOCALIZATION_LOST","fault":{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:40Z","kind":"FAULT_RAISED","detail":"LOCALIZATION_LOST class=LOCALIZATION_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"navigate_to","unitParameters":{"location":"PUMP-ROOM-1"},"source":null,"destination":"PUMP-ROOM-1","expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"1b2afa127508ad837ab7599d00901e53c29ba50832c67b7928b42dfa1dde2b4a"}
-{"incidentId":"incident-4","jobOrderId":"SEQ-4","executionId":"exec-6","robotId":"hum-06","unitId":"RACK-204.S05","at":"2026-09-06T00:02:29Z","wallClockAt":"2026-09-22T16:47:37.512746900Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E9001","vendorDetail":"X_FIXTURE_E9001","errorHint":"정지 코드를 벤더 문서에서 조회하십시오. 코드만으로 갈리지 않으면 현장 점검이 필요합니다.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":9,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S04 pick_place SKILL_STATE_RUNNING->SKILL_STATE_READY","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S04 TASK_STATE_RUNNING->TASK_STATE_SUCCEEDED rev=17 attempt=0","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S04: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:29Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E9001 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":16,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":17,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":18,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S05: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":true,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":2,"plan":["RACK-204.S04","RACK-204.S05"],"completed":["RACK-204.S04"]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":2}],"equipment":[{"id":"RACK-204.S04","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"RACK-204.S05","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S05","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S05","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"510bb77136e44e58e1e495c606846e0d0481da309149fd7a12103813cd501502"}
-{"incidentId":"incident-5","jobOrderId":"WT-781","executionId":"exec-7","robotId":"hum-03","unitId":"HU-1042","at":"2026-09-06T00:02:30Z","wallClockAt":"2026-09-22T16:47:37.557547500Z","failureClass":"SOURCE_CONTAINER_MISMATCH","fault":null,"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["HU-1042"],"completed":[]},"route":"FLEET","intent":{"workMasterId":"DeliverContainer","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"OUT-07","equipmentUse":"source","properties":{"container":"HU-1042"}},{"id":"SEQ-IN-02","equipmentUse":"destination","properties":{}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"transport","unitParameters":{"container":"HU-1042","destination":"SEQ-IN-02","source":"OUT-07"},"source":"OUT-07","destination":"SEQ-IN-02","expectedIdentity":"HU-1042"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":null,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"c5ffd423581ebff9d8dd5f529268e985e017294b9d2469849fdefb0f04e73cf6"}
-{"incidentId":"incident-6","jobOrderId":"SEQ-6","executionId":"exec-8","robotId":"hum-07","unitId":"RACK-204.S06","at":"2026-09-06T00:02:31Z","wallClockAt":"2026-09-22T16:47:37.609775700Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-6#RACK-204.S06"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"RESYNC","detail":"snapshot: next sequence=6, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:31Z","kind":"LINK_BROKEN","detail":"CONNECTION_STATE_CONNECTION_BROKEN while RACK-204.S06 is running — result unconfirmed","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:31Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:31Z","kind":"CELL_SIGNAL","detail":"RACK-204.S06: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S06"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S06","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S06","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S06","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":true,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"7aebacbd7e2ed96eb1760ed27f8d2773a0126ff099ea1253751f2f68d1449869"}
-{"incidentId":"incident-7","jobOrderId":"SEQ-7","executionId":"exec-9","robotId":"hum-08","unitId":"RACK-204.S07","at":"2026-09-06T00:02:32Z","wallClockAt":"2026-09-22T16:47:37.655709100Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E4412","vendorDetail":"X_FIXTURE_E4412","errorHint":"정지 코드를 벤더 문서에서 조회한 뒤 해당 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:32Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E4412 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:32Z","kind":"CELL_SIGNAL","detail":"RACK-204.S07: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S07"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S07","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S07","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S07","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"a67f01ae896df2ed3bcdb13dbcc8c0229ccdcba911e11c033925d6cf3d1b3dae"}
-{"incidentId":"incident-8","jobOrderId":"SEQ-8","executionId":"exec-10","robotId":"hum-09","unitId":"RACK-204.S08","at":"2026-09-06T00:02:33Z","wallClockAt":"2026-09-22T16:47:37.701891900Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E2075","vendorDetail":"X_FIXTURE_E2075","errorHint":"정지 코드를 기록하고 벤더 문서의 조치 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:33Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E2075 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:33Z","kind":"CELL_SIGNAL","detail":"RACK-204.S08: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S08"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S08","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S08","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S08","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"f3cbb5b6b3e5f61e09d8d7dbd99bb92f3359dc64c1f4e4f33235fca69975fe71"}
-{"incidentId":"incident-9","jobOrderId":"SEQ-9","executionId":"exec-11","robotId":"hum-10","unitId":"RACK-204.S09","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-09-22T16:47:37.749204700Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E6130","vendorDetail":"X_FIXTURE_E6130","errorHint":"정지 코드를 벤더 문서에서 조회하고 현장을 확인한 뒤 재개하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:34Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E6130 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:34Z","kind":"CELL_SIGNAL","detail":"RACK-204.S09: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S09"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S09","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S09","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S09","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"a920a52519d946038da042ae03dd72ca5ac9355542da196be588592eb724f47c"}
+{"incidentId":"incident-1","jobOrderId":"PATROL-1","executionId":"exec-2","robotId":"hum-02","unitId":"remedy-1-pick_place","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-10-08T04:29:00.967126600Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["remedy-1-pick_place","PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":null,"destination":null,"expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":{"id":"narrator-1","kind":"AGENT"},"review":null,"resolution":null,"digest":"3e0e655dd7176552ad3b85741e57008f35716cb33dc3ac786271a7553911e656"}
+{"incidentId":"incident-2","jobOrderId":"SEQ-3","executionId":"exec-4","robotId":"hum-04","unitId":"RACK-204.S03","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-10-08T04:29:05.524721200Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"X_FIXTURE_GRIPPER_SLIP","vendorDetail":"X_FIXTURE_GRIPPER_SLIP","errorHint":"그리퍼 패드의 마모를 점검하고 대상 자세를 다시 잡으십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-3#RACK-204.S03"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:39Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_GRIPPER_SLIP class=GRASP_FAILED can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:01:39Z","kind":"CELL_SIGNAL","detail":"RACK-204.S03: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S03"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S03","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S03","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S03","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"5446fc07bcc0cfd9e437818019f92843714d50c42bcb13d44269e64ed33f94ef"}
+{"incidentId":"incident-3","jobOrderId":"PATROL-4","executionId":"exec-5","robotId":"hum-05","unitId":"PUMP-01.travel","at":"2026-09-06T00:01:40Z","wallClockAt":"2026-10-08T04:29:05.570854Z","failureClass":"LOCALIZATION_LOST","fault":{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:40Z","kind":"FAULT_RAISED","detail":"LOCALIZATION_LOST class=LOCALIZATION_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"navigate_to","unitParameters":{"location":"PUMP-ROOM-1"},"source":null,"destination":"PUMP-ROOM-1","expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"1a78ed1b648d35d52e9d978534fd2d584de86c88663cc8b6c6211cfa34aa5e59"}
+{"incidentId":"incident-4","jobOrderId":"SEQ-4","executionId":"exec-6","robotId":"hum-06","unitId":"RACK-204.S05","at":"2026-09-06T00:02:29Z","wallClockAt":"2026-10-08T04:29:07.879408300Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E9001","vendorDetail":"X_FIXTURE_E9001","errorHint":"정지 코드를 벤더 문서에서 조회하십시오. 코드만으로 갈리지 않으면 현장 점검이 필요합니다.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":9,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S04 pick_place SKILL_STATE_RUNNING->SKILL_STATE_READY","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S04 TASK_STATE_RUNNING->TASK_STATE_SUCCEEDED rev=17 attempt=0","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S04: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:29Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E9001 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":16,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":17,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":18,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S05: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":true,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":2,"plan":["RACK-204.S04","RACK-204.S05"],"completed":["RACK-204.S04"]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":2}],"equipment":[{"id":"RACK-204.S04","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"RACK-204.S05","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S05","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S05","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"4e0a3fb0af7c2fed0c992172584cab959873d91911d4dd38cb791d55188d4b48"}
+{"incidentId":"incident-5","jobOrderId":"WT-781","executionId":"exec-7","robotId":"hum-03","unitId":"HU-1042","at":"2026-09-06T00:02:30Z","wallClockAt":"2026-10-08T04:29:07.924713600Z","failureClass":"SOURCE_CONTAINER_MISMATCH","fault":null,"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["HU-1042"],"completed":[]},"route":"FLEET","intent":{"workMasterId":"DeliverContainer","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"OUT-07","equipmentUse":"source","properties":{"container":"HU-1042"}},{"id":"SEQ-IN-02","equipmentUse":"destination","properties":{}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"transport","unitParameters":{"container":"HU-1042","destination":"SEQ-IN-02","source":"OUT-07"},"source":"OUT-07","destination":"SEQ-IN-02","expectedIdentity":"HU-1042"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":null,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"b3831e67626fc65fb24c8c474ec7b670b3ac105654f736a490841fce3fa94eb5"}
+{"incidentId":"incident-6","jobOrderId":"SEQ-6","executionId":"exec-8","robotId":"hum-07","unitId":"RACK-204.S06","at":"2026-09-06T00:02:31Z","wallClockAt":"2026-10-08T04:29:07.977015700Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-6#RACK-204.S06"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"RESYNC","detail":"snapshot: next sequence=6, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:31Z","kind":"LINK_BROKEN","detail":"CONNECTION_STATE_CONNECTION_BROKEN while RACK-204.S06 is running — result unconfirmed","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:31Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:31Z","kind":"CELL_SIGNAL","detail":"RACK-204.S06: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S06"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S06","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S06","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S06","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":true,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"313aa4a64f286f6501fee05a3f6bdf046c4f23af3a5eb739c397708f386aa33c"}
+{"incidentId":"incident-7","jobOrderId":"SEQ-7","executionId":"exec-9","robotId":"hum-08","unitId":"RACK-204.S07","at":"2026-09-06T00:02:32Z","wallClockAt":"2026-10-08T04:29:08.038287800Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E4412","vendorDetail":"X_FIXTURE_E4412","errorHint":"정지 코드를 벤더 문서에서 조회한 뒤 해당 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:32Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E4412 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:32Z","kind":"CELL_SIGNAL","detail":"RACK-204.S07: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S07"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S07","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S07","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S07","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"84b3764fd025cc6c2b202a44c10675ed2e29373619ee34f603ea66fd6ae6622e"}
+{"incidentId":"incident-8","jobOrderId":"SEQ-8","executionId":"exec-10","robotId":"hum-09","unitId":"RACK-204.S08","at":"2026-09-06T00:02:33Z","wallClockAt":"2026-10-08T04:29:08.097465100Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E2075","vendorDetail":"X_FIXTURE_E2075","errorHint":"정지 코드를 기록하고 벤더 문서의 조치 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:33Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E2075 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:33Z","kind":"CELL_SIGNAL","detail":"RACK-204.S08: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S08"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S08","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S08","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S08","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"929dc613f85cf6c26d6e8c8988be619a149c498505f1822fe96be5086e931430"}
+{"incidentId":"incident-9","jobOrderId":"SEQ-9","executionId":"exec-11","robotId":"hum-10","unitId":"RACK-204.S09","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-10-08T04:29:08.145088500Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E6130","vendorDetail":"X_FIXTURE_E6130","errorHint":"정지 코드를 벤더 문서에서 조회하고 현장을 확인한 뒤 재개하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:34Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E6130 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:34Z","kind":"CELL_SIGNAL","detail":"RACK-204.S09: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S09"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S09","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S09","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S09","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"9a7be8d8fee33a648f114efc78027a947a0f43d59ec5454728ef640991b33195"}
diff --git a/handoff/narrator/run-1/manifest.json b/handoff/narrator/run-1/manifest.json
index e2b4c39..baf1aa0 100644
--- a/handoff/narrator/run-1/manifest.json
+++ b/handoff/narrator/run-1/manifest.json
@@ -1 +1 @@
-{"schemaVersion":"5","runId":"run-2026-09-22T16:47:37.854173400Z-1","writtenAt":"2026-09-22T16:47:37.854173400Z","virtualNow":"2026-09-06T00:02:34Z","contractSemver":"0.9.0","counts":{"incidents":9,"remedySearches":4}}
\ No newline at end of file
+{"schemaVersion":"6","runId":"run-2026-10-08T04:29:08.150947600Z-1","writtenAt":"2026-10-08T04:29:08.216948600Z","virtualNow":"2026-09-06T00:02:34Z","contractSemver":"0.9.0","counts":{"incidents":9,"remedySearches":4}}
\ No newline at end of file
diff --git a/handoff/narrator/run-1/remedy-searches.jsonl b/handoff/narrator/run-1/remedy-searches.jsonl
index a15d505..7b38bae 100644
--- a/handoff/narrator/run-1/remedy-searches.jsonl
+++ b/handoff/narrator/run-1/remedy-searches.jsonl
@@ -1,4 +1,4 @@
-{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-1","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-09-22T16:47:30.473263200Z","outcome":"FOUND","steps":[{"skillType":"pick_place","requires":[],"expectedHold":"HOLD_KIND_EMPTY","onFailureHold":"HOLD_KIND_HOLDING"}]}
-{"searchId":"search-2","robotId":"hum-03","jobOrderId":"PATROL-2","at":"2026-09-06T00:00:50Z","wallClockAt":"2026-09-22T16:47:32.868341Z","outcome":"NONE","cause":"NO_CAPABILITY","unmet":[{"subject":"PRECONDITION_SUBJECT_HOLD","required":"HOLD_KIND_EMPTY","observed":"HOLD_KIND_HOLDING","detail":"요구=EMPTY 관측=HOLDING"}]}
-{"searchId":"search-3","robotId":"hum-04","jobOrderId":"PATROL-3","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-09-22T16:47:35.162195700Z","outcome":"WITHHELD"}
-{"searchId":"search-4","robotId":"hum-03","jobOrderId":"SEQ-RELOCATE","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-09-22T16:47:37.751685200Z","outcome":"SOURCE_MISSING","material":"ENGINE-COVER-B","source":"SEQ-IN-03.BIN-A","observed":null,"alternatives":["SEQ-IN-03.BIN-B"]}
+{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-1","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-10-08T04:29:00.884911600Z","outcome":"FOUND","steps":[{"skillType":"pick_place","requires":[],"expectedHold":"HOLD_KIND_EMPTY","onFailureHold":"HOLD_KIND_HOLDING"}]}
+{"searchId":"search-2","robotId":"hum-03","jobOrderId":"PATROL-2","at":"2026-09-06T00:00:50Z","wallClockAt":"2026-10-08T04:29:03.226894200Z","outcome":"NONE","cause":"NO_CAPABILITY","unmet":[{"subject":"PRECONDITION_SUBJECT_HOLD","required":"HOLD_KIND_EMPTY","observed":"HOLD_KIND_HOLDING","detail":"요구=EMPTY 관측=HOLDING"}]}
+{"searchId":"search-3","robotId":"hum-04","jobOrderId":"PATROL-3","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-10-08T04:29:05.522722600Z","outcome":"WITHHELD"}
+{"searchId":"search-4","robotId":"hum-03","jobOrderId":"SEQ-RELOCATE","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-10-08T04:29:08.147115300Z","outcome":"SOURCE_MISSING","material":"ENGINE-COVER-B","source":"SEQ-IN-03.BIN-A","observed":null,"alternatives":["SEQ-IN-03.BIN-B"]}
diff --git a/handoff/narrator/run-2/incidents.jsonl b/handoff/narrator/run-2/incidents.jsonl
index e9f7575..b18d3a2 100644
--- a/handoff/narrator/run-2/incidents.jsonl
+++ b/handoff/narrator/run-2/incidents.jsonl
@@ -1,9 +1,9 @@
-{"incidentId":"incident-1","jobOrderId":"PATROL-1","executionId":"exec-2","robotId":"hum-02","unitId":"remedy-1-pick_place","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-09-22T16:47:37.999677100Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["remedy-1-pick_place","PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":null,"destination":null,"expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":{"id":"narrator-1","kind":"AGENT"},"review":null,"resolution":null,"digest":"d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143"}
-{"incidentId":"incident-2","jobOrderId":"SEQ-3","executionId":"exec-4","robotId":"hum-04","unitId":"RACK-204.S03","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-09-22T16:47:42.547778600Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"X_FIXTURE_GRIPPER_SLIP","vendorDetail":"X_FIXTURE_GRIPPER_SLIP","errorHint":"그리퍼 패드의 마모를 점검하고 대상 자세를 다시 잡으십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-3#RACK-204.S03"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:39Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_GRIPPER_SLIP class=GRASP_FAILED can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:01:39Z","kind":"CELL_SIGNAL","detail":"RACK-204.S03: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S03"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S03","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S03","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S03","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"af2b5726d50290366458080e071bc297b258668ffc7dac77d1fd7c66d46dac75"}
-{"incidentId":"incident-3","jobOrderId":"PATROL-4","executionId":"exec-5","robotId":"hum-05","unitId":"PUMP-01.travel","at":"2026-09-06T00:01:40Z","wallClockAt":"2026-09-22T16:47:42.593754200Z","failureClass":"LOCALIZATION_LOST","fault":{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:40Z","kind":"FAULT_RAISED","detail":"LOCALIZATION_LOST class=LOCALIZATION_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"navigate_to","unitParameters":{"location":"PUMP-ROOM-1"},"source":null,"destination":"PUMP-ROOM-1","expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"1b2afa127508ad837ab7599d00901e53c29ba50832c67b7928b42dfa1dde2b4a"}
-{"incidentId":"incident-4","jobOrderId":"SEQ-4","executionId":"exec-6","robotId":"hum-06","unitId":"RACK-204.S05","at":"2026-09-06T00:02:29Z","wallClockAt":"2026-09-22T16:47:44.902891100Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E9001","vendorDetail":"X_FIXTURE_E9001","errorHint":"정지 코드를 벤더 문서에서 조회하십시오. 코드만으로 갈리지 않으면 현장 점검이 필요합니다.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":9,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S04 pick_place SKILL_STATE_RUNNING->SKILL_STATE_READY","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S04 TASK_STATE_RUNNING->TASK_STATE_SUCCEEDED rev=17 attempt=0","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S04: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:29Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E9001 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":16,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":17,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":18,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S05: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":true,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":2,"plan":["RACK-204.S04","RACK-204.S05"],"completed":["RACK-204.S04"]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":2}],"equipment":[{"id":"RACK-204.S04","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"RACK-204.S05","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S05","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S05","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"510bb77136e44e58e1e495c606846e0d0481da309149fd7a12103813cd501502"}
-{"incidentId":"incident-5","jobOrderId":"WT-781","executionId":"exec-7","robotId":"hum-03","unitId":"HU-1042","at":"2026-09-06T00:02:30Z","wallClockAt":"2026-09-22T16:47:44.948852100Z","failureClass":"SOURCE_CONTAINER_MISMATCH","fault":null,"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["HU-1042"],"completed":[]},"route":"FLEET","intent":{"workMasterId":"DeliverContainer","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"OUT-07","equipmentUse":"source","properties":{"container":"HU-1042"}},{"id":"SEQ-IN-02","equipmentUse":"destination","properties":{}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"transport","unitParameters":{"container":"HU-1042","destination":"SEQ-IN-02","source":"OUT-07"},"source":"OUT-07","destination":"SEQ-IN-02","expectedIdentity":"HU-1042"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":null,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"c5ffd423581ebff9d8dd5f529268e985e017294b9d2469849fdefb0f04e73cf6"}
-{"incidentId":"incident-6","jobOrderId":"SEQ-6","executionId":"exec-8","robotId":"hum-07","unitId":"RACK-204.S06","at":"2026-09-06T00:02:31Z","wallClockAt":"2026-09-22T16:47:44.997119Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-6#RACK-204.S06"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"RESYNC","detail":"snapshot: next sequence=6, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:31Z","kind":"LINK_BROKEN","detail":"CONNECTION_STATE_CONNECTION_BROKEN while RACK-204.S06 is running — result unconfirmed","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:31Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:31Z","kind":"CELL_SIGNAL","detail":"RACK-204.S06: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S06"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S06","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S06","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S06","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":true,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"7aebacbd7e2ed96eb1760ed27f8d2773a0126ff099ea1253751f2f68d1449869"}
-{"incidentId":"incident-7","jobOrderId":"SEQ-7","executionId":"exec-9","robotId":"hum-08","unitId":"RACK-204.S07","at":"2026-09-06T00:02:32Z","wallClockAt":"2026-09-22T16:47:45.044831100Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E4412","vendorDetail":"X_FIXTURE_E4412","errorHint":"정지 코드를 벤더 문서에서 조회한 뒤 해당 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:32Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E4412 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:32Z","kind":"CELL_SIGNAL","detail":"RACK-204.S07: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S07"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S07","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S07","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S07","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"a67f01ae896df2ed3bcdb13dbcc8c0229ccdcba911e11c033925d6cf3d1b3dae"}
-{"incidentId":"incident-8","jobOrderId":"SEQ-8","executionId":"exec-10","robotId":"hum-09","unitId":"RACK-204.S08","at":"2026-09-06T00:02:33Z","wallClockAt":"2026-09-22T16:47:45.092241100Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E2075","vendorDetail":"X_FIXTURE_E2075","errorHint":"정지 코드를 기록하고 벤더 문서의 조치 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:33Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E2075 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:33Z","kind":"CELL_SIGNAL","detail":"RACK-204.S08: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S08"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S08","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S08","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S08","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"f3cbb5b6b3e5f61e09d8d7dbd99bb92f3359dc64c1f4e4f33235fca69975fe71"}
-{"incidentId":"incident-9","jobOrderId":"SEQ-9","executionId":"exec-11","robotId":"hum-10","unitId":"RACK-204.S09","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-09-22T16:47:45.138495100Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E6130","vendorDetail":"X_FIXTURE_E6130","errorHint":"정지 코드를 벤더 문서에서 조회하고 현장을 확인한 뒤 재개하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:34Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E6130 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:34Z","kind":"CELL_SIGNAL","detail":"RACK-204.S09: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S09"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S09","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S09","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S09","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"a920a52519d946038da042ae03dd72ca5ac9355542da196be588592eb724f47c"}
+{"incidentId":"incident-1","jobOrderId":"PATROL-1","executionId":"exec-2","robotId":"hum-02","unitId":"remedy-1-pick_place","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-10-08T04:29:08.376115200Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"PATROL-1#remedy-1-pick_place"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"PATROL-1#remedy-1-pick_place pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"PATROL-1#remedy-1-pick_place TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["remedy-1-pick_place","PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":null,"destination":null,"expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":{"id":"narrator-1","kind":"AGENT"},"review":null,"resolution":null,"digest":"3e0e655dd7176552ad3b85741e57008f35716cb33dc3ac786271a7553911e656"}
+{"incidentId":"incident-2","jobOrderId":"SEQ-3","executionId":"exec-4","robotId":"hum-04","unitId":"RACK-204.S03","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-10-08T04:29:12.951699600Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"X_FIXTURE_GRIPPER_SLIP","vendorDetail":"X_FIXTURE_GRIPPER_SLIP","errorHint":"그리퍼 패드의 마모를 점검하고 대상 자세를 다시 잡으십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-3#RACK-204.S03"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:38Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:39Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_GRIPPER_SLIP class=GRASP_FAILED can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:39Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:01:39Z","kind":"CELL_SIGNAL","detail":"RACK-204.S03: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S03"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S03","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S03","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S03","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"5446fc07bcc0cfd9e437818019f92843714d50c42bcb13d44269e64ed33f94ef"}
+{"incidentId":"incident-3","jobOrderId":"PATROL-4","executionId":"exec-5","robotId":"hum-05","unitId":"PUMP-01.travel","at":"2026-09-06T00:01:40Z","wallClockAt":"2026-10-08T04:29:12.999331500Z","failureClass":"LOCALIZATION_LOST","fault":{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"RESYNC","detail":"snapshot: next sequence=5, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":5,"occurredAt":"2026-09-06T00:01:39Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:01:40Z","kind":"FAULT_RAISED","detail":"LOCALIZATION_LOST class=LOCALIZATION_LOST can_accept_new_task=false","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:01:40Z","kind":"SKILL_TRANSITION","detail":"PATROL-4#PUMP-01.travel navigate_to SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:01:40Z","kind":"TASK_TRANSITION","detail":"PATROL-4#PUMP-01.travel TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"navigate_to","unitParameters":{"location":"PUMP-ROOM-1"},"source":null,"destination":"PUMP-ROOM-1","expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"1a78ed1b648d35d52e9d978534fd2d584de86c88663cc8b6c6211cfa34aa5e59"}
+{"incidentId":"incident-4","jobOrderId":"SEQ-4","executionId":"exec-6","robotId":"hum-06","unitId":"RACK-204.S05","at":"2026-09-06T00:02:29Z","wallClockAt":"2026-10-08T04:29:15.306790100Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E9001","vendorDetail":"X_FIXTURE_E9001","errorHint":"정지 코드를 벤더 문서에서 조회하십시오. 코드만으로 갈리지 않으면 현장 점검이 필요합니다.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":9,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S04 pick_place SKILL_STATE_RUNNING->SKILL_STATE_READY","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S04 TASK_STATE_RUNNING->TASK_STATE_SUCCEEDED rev=17 attempt=0","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S04: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:29Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E9001 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":16,"occurredAt":"2026-09-06T00:02:29Z","kind":"SKILL_TRANSITION","detail":"SEQ-4#RACK-204.S05 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":17,"occurredAt":"2026-09-06T00:02:29Z","kind":"TASK_TRANSITION","detail":"SEQ-4#RACK-204.S05 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":18,"occurredAt":"2026-09-06T00:02:29Z","kind":"CELL_SIGNAL","detail":"RACK-204.S05: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":true,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":2,"plan":["RACK-204.S04","RACK-204.S05"],"completed":["RACK-204.S04"]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":2}],"equipment":[{"id":"RACK-204.S04","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"RACK-204.S05","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S05","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S05","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"4e0a3fb0af7c2fed0c992172584cab959873d91911d4dd38cb791d55188d4b48"}
+{"incidentId":"incident-5","jobOrderId":"WT-781","executionId":"exec-7","robotId":"hum-03","unitId":"HU-1042","at":"2026-09-06T00:02:30Z","wallClockAt":"2026-10-08T04:29:15.351389400Z","failureClass":"SOURCE_CONTAINER_MISMATCH","fault":null,"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["HU-1042"],"completed":[]},"route":"FLEET","intent":{"workMasterId":"DeliverContainer","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"OUT-07","equipmentUse":"source","properties":{"container":"HU-1042"}},{"id":"SEQ-IN-02","equipmentUse":"destination","properties":{}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"transport","unitParameters":{"container":"HU-1042","destination":"SEQ-IN-02","source":"OUT-07"},"source":"OUT-07","destination":"SEQ-IN-02","expectedIdentity":"HU-1042"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":null,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"b3831e67626fc65fb24c8c474ec7b670b3ac105654f736a490841fce3fa94eb5"}
+{"incidentId":"incident-6","jobOrderId":"SEQ-6","executionId":"exec-8","robotId":"hum-07","unitId":"RACK-204.S06","at":"2026-09-06T00:02:31Z","wallClockAt":"2026-10-08T04:29:15.399776400Z","failureClass":"PAYLOAD_LOST","fault":{"failureClass":"PAYLOAD_LOST","errorType":"PAYLOAD_LOST","vendorDetail":"","errorHint":"떨어뜨린 대상을 회수해 원위치에 놓고 재시도하십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-6#RACK-204.S06"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"RESYNC","detail":"snapshot: next sequence=6, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":6,"occurredAt":"2026-09-06T00:02:30Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:31Z","kind":"LINK_BROKEN","detail":"CONNECTION_STATE_CONNECTION_BROKEN while RACK-204.S06 is running — result unconfirmed","local":true},{"sequence":11,"occurredAt":"2026-09-06T00:02:31Z","kind":"FAULT_RAISED","detail":"PAYLOAD_LOST class=PAYLOAD_LOST can_accept_new_task=false","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:31Z","kind":"SKILL_TRANSITION","detail":"SEQ-6#RACK-204.S06 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-6#RACK-204.S06 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:02:31Z","kind":"CELL_SIGNAL","detail":"RACK-204.S06: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_EMPTY","effectMismatch":"PAYLOAD_LOST","requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S06"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S06","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S06","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S06","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":true,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"313aa4a64f286f6501fee05a3f6bdf046c4f23af3a5eb739c397708f386aa33c"}
+{"incidentId":"incident-7","jobOrderId":"SEQ-7","executionId":"exec-9","robotId":"hum-08","unitId":"RACK-204.S07","at":"2026-09-06T00:02:32Z","wallClockAt":"2026-10-08T04:29:15.446348500Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E4412","vendorDetail":"X_FIXTURE_E4412","errorHint":"정지 코드를 벤더 문서에서 조회한 뒤 해당 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:31Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:32Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E4412 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:32Z","kind":"SKILL_TRANSITION","detail":"SEQ-7#RACK-204.S07 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-7#RACK-204.S07 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:32Z","kind":"CELL_SIGNAL","detail":"RACK-204.S07: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S07"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S07","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S07","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S07","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"84b3764fd025cc6c2b202a44c10675ed2e29373619ee34f603ea66fd6ae6622e"}
+{"incidentId":"incident-8","jobOrderId":"SEQ-8","executionId":"exec-10","robotId":"hum-09","unitId":"RACK-204.S08","at":"2026-09-06T00:02:33Z","wallClockAt":"2026-10-08T04:29:15.493947700Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E2075","vendorDetail":"X_FIXTURE_E2075","errorHint":"정지 코드를 기록하고 벤더 문서의 조치 절차를 따르십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:32Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:33Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E2075 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:33Z","kind":"SKILL_TRANSITION","detail":"SEQ-8#RACK-204.S08 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-8#RACK-204.S08 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:33Z","kind":"CELL_SIGNAL","detail":"RACK-204.S08: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S08"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S08","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S08","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S08","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"929dc613f85cf6c26d6e8c8988be619a149c498505f1822fe96be5086e931430"}
+{"incidentId":"incident-9","jobOrderId":"SEQ-9","executionId":"exec-11","robotId":"hum-10","unitId":"RACK-204.S09","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-10-08T04:29:15.541023700Z","failureClass":"UNCLASSIFIED","fault":{"failureClass":"UNCLASSIFIED","errorType":"X_FIXTURE_E6130","vendorDetail":"X_FIXTURE_E6130","errorHint":"정지 코드를 벤더 문서에서 조회하고 현장을 확인한 뒤 재개하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"RESYNC","detail":"snapshot: next sequence=7, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":7,"occurredAt":"2026-09-06T00:02:33Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:02:34Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_E6130 class=UNCLASSIFIED can_accept_new_task=false","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:02:34Z","kind":"SKILL_TRANSITION","detail":"SEQ-9#RACK-204.S09 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:02:34Z","kind":"TASK_TRANSITION","detail":"SEQ-9#RACK-204.S09 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:02:34Z","kind":"CELL_SIGNAL","detail":"RACK-204.S09: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S09"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S09","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S09","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S09","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"9a7be8d8fee33a648f114efc78027a947a0f43d59ec5454728ef640991b33195"}
diff --git a/handoff/narrator/run-2/manifest.json b/handoff/narrator/run-2/manifest.json
index 7fe2cb6..3aacdbc 100644
--- a/handoff/narrator/run-2/manifest.json
+++ b/handoff/narrator/run-2/manifest.json
@@ -1 +1 @@
-{"schemaVersion":"5","runId":"run-2026-09-22T16:47:45.148967500Z-2","writtenAt":"2026-09-22T16:47:45.148967500Z","virtualNow":"2026-09-06T00:02:34Z","contractSemver":"0.9.0","counts":{"incidents":9,"remedySearches":4}}
\ No newline at end of file
+{"schemaVersion":"6","runId":"run-2026-10-08T04:29:15.541602800Z-2","writtenAt":"2026-10-08T04:29:15.550143200Z","virtualNow":"2026-09-06T00:02:34Z","contractSemver":"0.9.0","counts":{"incidents":9,"remedySearches":4}}
\ No newline at end of file
diff --git a/handoff/narrator/run-2/remedy-searches.jsonl b/handoff/narrator/run-2/remedy-searches.jsonl
index 1fb01e1..c514d55 100644
--- a/handoff/narrator/run-2/remedy-searches.jsonl
+++ b/handoff/narrator/run-2/remedy-searches.jsonl
@@ -1,4 +1,4 @@
-{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-1","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-09-22T16:47:37.951799300Z","outcome":"FOUND","steps":[{"skillType":"pick_place","requires":[],"expectedHold":"HOLD_KIND_EMPTY","onFailureHold":"HOLD_KIND_HOLDING"}]}
-{"searchId":"search-2","robotId":"hum-03","jobOrderId":"PATROL-2","at":"2026-09-06T00:00:50Z","wallClockAt":"2026-09-22T16:47:40.241295200Z","outcome":"NONE","cause":"NO_CAPABILITY","unmet":[{"subject":"PRECONDITION_SUBJECT_HOLD","required":"HOLD_KIND_EMPTY","observed":"HOLD_KIND_HOLDING","detail":"요구=EMPTY 관측=HOLDING"}]}
-{"searchId":"search-3","robotId":"hum-04","jobOrderId":"PATROL-3","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-09-22T16:47:42.546782200Z","outcome":"WITHHELD"}
-{"searchId":"search-4","robotId":"hum-03","jobOrderId":"SEQ-RELOCATE","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-09-22T16:47:45.138495100Z","outcome":"SOURCE_MISSING","material":"ENGINE-COVER-B","source":"SEQ-IN-03.BIN-A","observed":null,"alternatives":["SEQ-IN-03.BIN-B"]}
+{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-1","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-10-08T04:29:08.329192400Z","outcome":"FOUND","steps":[{"skillType":"pick_place","requires":[],"expectedHold":"HOLD_KIND_EMPTY","onFailureHold":"HOLD_KIND_HOLDING"}]}
+{"searchId":"search-2","robotId":"hum-03","jobOrderId":"PATROL-2","at":"2026-09-06T00:00:50Z","wallClockAt":"2026-10-08T04:29:10.641675Z","outcome":"NONE","cause":"NO_CAPABILITY","unmet":[{"subject":"PRECONDITION_SUBJECT_HOLD","required":"HOLD_KIND_EMPTY","observed":"HOLD_KIND_HOLDING","detail":"요구=EMPTY 관측=HOLDING"}]}
+{"searchId":"search-3","robotId":"hum-04","jobOrderId":"PATROL-3","at":"2026-09-06T00:01:39Z","wallClockAt":"2026-10-08T04:29:12.950488400Z","outcome":"WITHHELD"}
+{"searchId":"search-4","robotId":"hum-03","jobOrderId":"SEQ-RELOCATE","at":"2026-09-06T00:02:34Z","wallClockAt":"2026-10-08T04:29:15.541602800Z","outcome":"SOURCE_MISSING","material":"ENGINE-COVER-B","source":"SEQ-IN-03.BIN-A","observed":null,"alternatives":["SEQ-IN-03.BIN-B"]}
diff --git a/handoff/narrator/run-3/incidents.jsonl b/handoff/narrator/run-3/incidents.jsonl
index aee9674..138fe8c 100644
--- a/handoff/narrator/run-3/incidents.jsonl
+++ b/handoff/narrator/run-3/incidents.jsonl
@@ -1,4 +1,4 @@
-{"incidentId":"incident-1","jobOrderId":"SEQ-1","executionId":"exec-1","robotId":"hum-04","unitId":"RACK-204.S01","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-09-22T16:47:45.246616900Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-1#RACK-204.S01"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"RESYNC","detail":"snapshot: next sequence=1, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":2,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S01","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"REWORK","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-09-22T16:47:45.247976300Z"},"digest":"fdbbfd555ba836fe0a35cbf0ac2a35a5f26fd3f8ea5cb059d0e4876b0d261046"}
-{"incidentId":"incident-2","jobOrderId":"SEQ-1","executionId":"exec-1","robotId":"hum-04","unitId":"RACK-204.S01","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-09-22T16:47:45.293203100Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-1#RACK-204.S01@r1"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"RESYNC","detail":"snapshot: next sequence=1, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":2,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_CLEARED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":16,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":17,"occurredAt":"2026-09-06T00:00:02Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S01","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"CONFIRM_DONE","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-09-22T16:47:45.294694800Z"},"digest":"02c44ca84d890ec076339f45df8dc320e19d8f7e6913af799f2f55c2007f4e95"}
-{"incidentId":"incident-3","jobOrderId":"SEQ-2","executionId":"exec-2","robotId":"hum-04","unitId":"RACK-204.S02","at":"2026-09-06T00:00:03Z","wallClockAt":"2026-09-22T16:47:45.341566400Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"X_FIXTURE_GRIPPER_SLIP","vendorDetail":"X_FIXTURE_GRIPPER_SLIP","errorHint":"그리퍼 패드의 마모를 점검하고 대상 자세를 다시 잡으십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-2#RACK-204.S02"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":17,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_CLEARED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":18,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":19,"occurredAt":"2026-09-06T00:00:03Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":20,"occurredAt":"2026-09-06T00:00:03Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":21,"occurredAt":"2026-09-06T00:00:03Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_GRIPPER_SLIP class=GRASP_FAILED can_accept_new_task=false","local":false},{"sequence":22,"occurredAt":"2026-09-06T00:00:03Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":23,"occurredAt":"2026-09-06T00:00:03Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":24,"occurredAt":"2026-09-06T00:00:03Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":25,"occurredAt":"2026-09-06T00:00:03Z","kind":"CELL_SIGNAL","detail":"RACK-204.S02: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S02"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S02","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S02","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"66efa92f896837bc7d89d58df9260df55adab5f24473a0582cff9222a9e345ec"}
-{"incidentId":"incident-4","jobOrderId":"PATROL-9","executionId":"exec-3","robotId":"hum-05","unitId":"PUMP-01.travel","at":"2026-09-06T00:00:04Z","wallClockAt":"2026-09-22T16:47:45.387818100Z","failureClass":"LOCALIZATION_LOST","fault":{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":2,"occurredAt":"2026-09-06T00:00:03Z","kind":"RESYNC","detail":"snapshot: next sequence=2, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":2,"occurredAt":"2026-09-06T00:00:03Z","kind":"TASK_TRANSITION","detail":"PATROL-9#PUMP-01.travel TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:04Z","kind":"TASK_TRANSITION","detail":"PATROL-9#PUMP-01.travel TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":4,"occurredAt":"2026-09-06T00:00:04Z","kind":"SKILL_TRANSITION","detail":"PATROL-9#PUMP-01.travel navigate_to SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:04Z","kind":"FAULT_RAISED","detail":"LOCALIZATION_LOST class=LOCALIZATION_LOST can_accept_new_task=false","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:04Z","kind":"SKILL_TRANSITION","detail":"PATROL-9#PUMP-01.travel navigate_to SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:04Z","kind":"SKILL_TRANSITION","detail":"PATROL-9#PUMP-01.travel navigate_to SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:04Z","kind":"TASK_TRANSITION","detail":"PATROL-9#PUMP-01.travel TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"navigate_to","unitParameters":{"location":"PUMP-ROOM-1"},"source":null,"destination":"PUMP-ROOM-1","expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"a0dabadae4d75b30cfd6f480d5a410f3bcc24609723107922956d82fb22b3783"}
+{"incidentId":"incident-1","jobOrderId":"SEQ-1","executionId":"exec-1","robotId":"hum-04","unitId":"RACK-204.S01","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-10-08T04:29:15.650176800Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-1#RACK-204.S01"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"RESYNC","detail":"snapshot: next sequence=1, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":2,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S01","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"REWORK","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-10-08T04:29:15.652183700Z"},"digest":"a2906609a1f7c31ee594e83c4f48642cfad7c1e7f814d49bd492253f68eeba0a"}
+{"incidentId":"incident-2","jobOrderId":"SEQ-1","executionId":"exec-1","robotId":"hum-04","unitId":"RACK-204.S01","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-10-08T04:29:15.696019400Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-1#RACK-204.S01@r1"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"RESYNC","detail":"snapshot: next sequence=1, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":2,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_CLEARED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":10,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":11,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":12,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":13,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":14,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":15,"occurredAt":"2026-09-06T00:00:02Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":16,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01@r1 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":17,"occurredAt":"2026-09-06T00:00:02Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S01","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"CONFIRM_DONE","at":"2026-09-06T00:00:02Z","wallClockAt":"2026-10-08T04:29:15.697097500Z"},"digest":"ebb626aedaa7f064895c41e18e6b7f4211675a12c9ca1c6e2b3029c9555bc28f"}
+{"incidentId":"incident-3","jobOrderId":"SEQ-2","executionId":"exec-2","robotId":"hum-04","unitId":"RACK-204.S02","at":"2026-09-06T00:00:03Z","wallClockAt":"2026-10-08T04:29:15.741947800Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"X_FIXTURE_GRIPPER_SLIP","vendorDetail":"X_FIXTURE_GRIPPER_SLIP","errorHint":"그리퍼 패드의 마모를 점검하고 대상 자세를 다시 잡으십시오.","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-2#RACK-204.S02"}],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":17,"occurredAt":"2026-09-06T00:00:02Z","kind":"FAULT_CLEARED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":18,"occurredAt":"2026-09-06T00:00:02Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":19,"occurredAt":"2026-09-06T00:00:03Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":20,"occurredAt":"2026-09-06T00:00:03Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":21,"occurredAt":"2026-09-06T00:00:03Z","kind":"FAULT_RAISED","detail":"X_FIXTURE_GRIPPER_SLIP class=GRASP_FAILED can_accept_new_task=false","local":false},{"sequence":22,"occurredAt":"2026-09-06T00:00:03Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":23,"occurredAt":"2026-09-06T00:00:03Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":24,"occurredAt":"2026-09-06T00:00:03Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=17 attempt=0","local":false},{"sequence":25,"occurredAt":"2026-09-06T00:00:03Z","kind":"CELL_SIGNAL","detail":"RACK-204.S02: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S02"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S02","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S02","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"96e82d4e67be2ba82fda98152402ee88c82c9866c87a2a712f160a69d3db5286"}
+{"incidentId":"incident-4","jobOrderId":"PATROL-9","executionId":"exec-3","robotId":"hum-05","unitId":"PUMP-01.travel","at":"2026-09-06T00:00:04Z","wallClockAt":"2026-10-08T04:29:15.788503900Z","failureClass":"LOCALIZATION_LOST","fault":{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""},"blockedBy":[{"failureClass":"LOCALIZATION_LOST","errorType":"LOCALIZATION_LOST","vendorDetail":"","errorHint":"로봇을 알려진 랜드마크 앞으로 옮기고 재측위를 실행하십시오.","references":[],"canContinueCurrentTask":false,"canAcceptNewTask":false,"activeUntilKind":"KIND_UNTIL_CLEARED","activeUntilTime":""}],"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"unresolved":false,"preconditionSubjects":[],"evidenceWindow":[{"sequence":2,"occurredAt":"2026-09-06T00:00:03Z","kind":"RESYNC","detail":"snapshot: next sequence=2, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":2,"occurredAt":"2026-09-06T00:00:03Z","kind":"TASK_TRANSITION","detail":"PATROL-9#PUMP-01.travel TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=1 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:04Z","kind":"TASK_TRANSITION","detail":"PATROL-9#PUMP-01.travel TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=1 attempt=0","local":false},{"sequence":4,"occurredAt":"2026-09-06T00:00:04Z","kind":"SKILL_TRANSITION","detail":"PATROL-9#PUMP-01.travel navigate_to SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:04Z","kind":"FAULT_RAISED","detail":"LOCALIZATION_LOST class=LOCALIZATION_LOST can_accept_new_task=false","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:04Z","kind":"SKILL_TRANSITION","detail":"PATROL-9#PUMP-01.travel navigate_to SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:04Z","kind":"SKILL_TRANSITION","detail":"PATROL-9#PUMP-01.travel navigate_to SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:04Z","kind":"TASK_TRANSITION","detail":"PATROL-9#PUMP-01.travel TASK_STATE_RUNNING->TASK_STATE_NEEDS_INTERVENTION rev=1 attempt=0","local":false}],"windowTruncated":false,"expectedHold":null,"observedHold":"HOLD_KIND_EMPTY","effectMismatch":null,"requiredEvidence":"E0","reachedEvidence":"E0","verification":"NOT_REQUESTED","step":{"at":1,"plan":["PUMP-01.travel","PUMP-01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"InspectAsset","orderVersion":1,"orderParameters":{},"materials":[],"equipment":[{"id":"PUMP-01","equipmentUse":"inspection_target","properties":{"location":"PUMP-ROOM-1"}}],"capabilityMaxEvidence":"E0","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"navigate_to","unitParameters":{"location":"PUMP-ROOM-1"},"source":null,"destination":"PUMP-ROOM-1","expectedIdentity":null},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"6c5ca90ce741ad93846465cc1a4dffc80388e07857a6d56cd60496755664f8d9"}
diff --git a/handoff/narrator/run-3/manifest.json b/handoff/narrator/run-3/manifest.json
index ef76f2d..521cd73 100644
--- a/handoff/narrator/run-3/manifest.json
+++ b/handoff/narrator/run-3/manifest.json
@@ -1 +1 @@
-{"schemaVersion":"5","runId":"run-2026-09-22T16:47:45.395007300Z-3","writtenAt":"2026-09-22T16:47:45.395007300Z","virtualNow":"2026-09-06T00:00:04Z","contractSemver":"0.9.0","counts":{"incidents":4,"remedySearches":0}}
\ No newline at end of file
+{"schemaVersion":"6","runId":"run-2026-10-08T04:29:15.789032800Z-3","writtenAt":"2026-10-08T04:29:15.794363100Z","virtualNow":"2026-09-06T00:00:04Z","contractSemver":"0.9.0","counts":{"incidents":4,"remedySearches":0}}
\ No newline at end of file
diff --git a/handoff/narrator/run-4/incidents.jsonl b/handoff/narrator/run-4/incidents.jsonl
index 93e5c3b..64ebbad 100644
--- a/handoff/narrator/run-4/incidents.jsonl
+++ b/handoff/narrator/run-4/incidents.jsonl
@@ -1,3 +1,3 @@
-{"incidentId":"incident-1","jobOrderId":"SEQ-1","executionId":"exec-1","robotId":"hum-02","unitId":"RACK-204.S01","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-09-22T16:47:45.467446200Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-1#RACK-204.S01"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"RESYNC","detail":"snapshot: next sequence=1, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":2,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S01","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"REWORK","at":"2026-09-06T00:00:04Z","wallClockAt":"2026-09-22T16:47:45.606355100Z"},"digest":"4825e9e2e616f284bff1b81aee4780ce349ff51512cd51a30efcec2f58de01b8"}
-{"incidentId":"incident-2","jobOrderId":"SEQ-2","executionId":"exec-2","robotId":"hum-04","unitId":"RACK-204.S02","at":"2026-09-06T00:00:06Z","wallClockAt":"2026-09-22T16:47:45.702913Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-2#RACK-204.S02"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":2,"occurredAt":"2026-09-06T00:00:05Z","kind":"RESYNC","detail":"snapshot: next sequence=2, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":2,"occurredAt":"2026-09-06T00:00:05Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:06Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":4,"occurredAt":"2026-09-06T00:00:06Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:06Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:06Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:06Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:06Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:06Z","kind":"CELL_SIGNAL","detail":"RACK-204.S02: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S02"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S02","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S02","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"REWORK","at":"2026-09-06T00:00:09Z","wallClockAt":"2026-09-22T16:47:45.840155200Z"},"digest":"6dc37836b8b89151f2ea468f48d74eb236a74150230c80bd2009be135e4050f6"}
-{"incidentId":"incident-3","jobOrderId":"SEQ-3","executionId":"exec-3","robotId":"hum-03","unitId":"RACK-204.S03","at":"2026-09-06T00:00:11Z","wallClockAt":"2026-09-22T16:47:45.936374500Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-3#RACK-204.S03"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":2,"occurredAt":"2026-09-06T00:00:10Z","kind":"RESYNC","detail":"snapshot: next sequence=2, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":2,"occurredAt":"2026-09-06T00:00:10Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:11Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":4,"occurredAt":"2026-09-06T00:00:11Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:11Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:11Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:11Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:11Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:11Z","kind":"CELL_SIGNAL","detail":"RACK-204.S03: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S03"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S03","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S03","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S03","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"b5322aa85b5cbae61692ec3daccc3c22fa3ec93660fd0171242824338f372acd"}
+{"incidentId":"incident-1","jobOrderId":"SEQ-1","executionId":"exec-1","robotId":"hum-02","unitId":"RACK-204.S01","at":"2026-09-06T00:00:01Z","wallClockAt":"2026-10-08T04:29:15.865974200Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-1#RACK-204.S01"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"RESYNC","detail":"snapshot: next sequence=1, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":1,"occurredAt":"2026-09-06T00:00:00Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":2,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:01Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:01Z","kind":"SKILL_TRANSITION","detail":"SEQ-1#RACK-204.S01 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:01Z","kind":"TASK_TRANSITION","detail":"SEQ-1#RACK-204.S01 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:01Z","kind":"CELL_SIGNAL","detail":"RACK-204.S01: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S01"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S01","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S01","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S01","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"REWORK","at":"2026-09-06T00:00:04Z","wallClockAt":"2026-10-08T04:29:16.004948Z"},"digest":"827a6936944afb4a3e3273459fd0483948843c3216058c87a2dfd4074dac5407"}
+{"incidentId":"incident-2","jobOrderId":"SEQ-2","executionId":"exec-2","robotId":"hum-04","unitId":"RACK-204.S02","at":"2026-09-06T00:00:06Z","wallClockAt":"2026-10-08T04:29:16.100396300Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-2#RACK-204.S02"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":2,"occurredAt":"2026-09-06T00:00:05Z","kind":"RESYNC","detail":"snapshot: next sequence=2, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":2,"occurredAt":"2026-09-06T00:00:05Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:06Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":4,"occurredAt":"2026-09-06T00:00:06Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:06Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:06Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:06Z","kind":"SKILL_TRANSITION","detail":"SEQ-2#RACK-204.S02 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:06Z","kind":"TASK_TRANSITION","detail":"SEQ-2#RACK-204.S02 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:06Z","kind":"CELL_SIGNAL","detail":"RACK-204.S02: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S02"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S02","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S02","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":{"decision":"REWORK","at":"2026-09-06T00:00:09Z","wallClockAt":"2026-10-08T04:29:16.239404600Z"},"digest":"9baf4a9071efa8eaa5f60948acb846e79cc2d8336f6723afadcf0882b7613ddc"}
+{"incidentId":"incident-3","jobOrderId":"SEQ-3","executionId":"exec-3","robotId":"hum-03","unitId":"RACK-204.S03","at":"2026-09-06T00:00:11Z","wallClockAt":"2026-10-08T04:29:16.335259Z","failureClass":"GRASP_FAILED","fault":{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","vendorDetail":"","errorHint":"","references":[{"key":"KEY_SKILL_ID","value":"pick_place"},{"key":"KEY_TASK_ID","value":"SEQ-3#RACK-204.S03"}],"canContinueCurrentTask":false,"canAcceptNewTask":true,"activeUntilKind":"KIND_UNTIL_NEW_TASK","activeUntilTime":""},"blockedBy":[],"residualHold":{"kind":"HOLD_KIND_HOLDING","objectRef":"SEQ-IN-02.BIN-A","reason":""},"unresolved":true,"preconditionSubjects":[],"evidenceWindow":[{"sequence":2,"occurredAt":"2026-09-06T00:00:10Z","kind":"RESYNC","detail":"snapshot: next sequence=2, faults=0, connection=CONNECTION_STATE_ONLINE","local":true},{"sequence":2,"occurredAt":"2026-09-06T00:00:10Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_UNSPECIFIED->TASK_STATE_ACCEPTED rev=17 attempt=0","local":false},{"sequence":3,"occurredAt":"2026-09-06T00:00:11Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_ACCEPTED->TASK_STATE_RUNNING rev=17 attempt=0","local":false},{"sequence":4,"occurredAt":"2026-09-06T00:00:11Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_READY->SKILL_STATE_RUNNING","local":false},{"sequence":5,"occurredAt":"2026-09-06T00:00:11Z","kind":"FAULT_RAISED","detail":"SKILL_EXECUTION_FAILED class=GRASP_FAILED can_accept_new_task=true","local":false},{"sequence":6,"occurredAt":"2026-09-06T00:00:11Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_RUNNING->SKILL_STATE_HALTED","local":false},{"sequence":7,"occurredAt":"2026-09-06T00:00:11Z","kind":"SKILL_TRANSITION","detail":"SEQ-3#RACK-204.S03 pick_place SKILL_STATE_HALTED->SKILL_STATE_READY","local":false},{"sequence":8,"occurredAt":"2026-09-06T00:00:11Z","kind":"TASK_TRANSITION","detail":"SEQ-3#RACK-204.S03 TASK_STATE_RUNNING->TASK_STATE_RETRIABLE rev=17 attempt=0","local":false},{"sequence":9,"occurredAt":"2026-09-06T00:00:11Z","kind":"CELL_SIGNAL","detail":"RACK-204.S03: occupied=true identity=ENGINE-COVER-A at=(read now)","local":true}],"windowTruncated":false,"expectedHold":"HOLD_KIND_HOLDING","observedHold":"HOLD_KIND_HOLDING","effectMismatch":null,"requiredEvidence":"E2","reachedEvidence":"E0","verification":"MATCHED","step":{"at":1,"plan":["RACK-204.S03"],"completed":[]},"route":"ROBOT","intent":{"workMasterId":"PrepareSequencedRack","orderVersion":17,"orderParameters":{},"materials":[{"materialDefinitionId":"ENGINE-COVER-A","quantity":1}],"equipment":[{"id":"RACK-204.S03","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-A"}},{"id":"SEQ-IN-02.BIN-A","equipmentUse":"source","properties":{"material":"ENGINE-COVER-A"}}],"capabilityMaxEvidence":"E2","evidenceWindowBefore":"PT30S","evidenceWindowAfter":"PT15S","skillType":"pick_place","unitParameters":{"destination":"RACK-204.S03","object_id":"SEQ-IN-02.BIN-A"},"source":"SEQ-IN-02.BIN-A","destination":"RACK-204.S03","expectedIdentity":"ENGINE-COVER-A"},"observation":{"linkBroken":false,"lateEvents":[],"progressObservable":true,"progressStalled":false},"profileRevision":1,"contractSemver":"0.9.0","approvedBy":null,"review":null,"resolution":null,"digest":"4a0c2ffe6657e86c02daa786ccf628b736113f66807c78ae377ae90e5783e465"}
diff --git a/handoff/narrator/run-4/manifest.json b/handoff/narrator/run-4/manifest.json
index a08ae3e..b9614df 100644
--- a/handoff/narrator/run-4/manifest.json
+++ b/handoff/narrator/run-4/manifest.json
@@ -1 +1 @@
-{"schemaVersion":"5","runId":"run-2026-09-22T16:47:45.943169500Z-4","writtenAt":"2026-09-22T16:47:45.943169500Z","virtualNow":"2026-09-06T00:00:11Z","contractSemver":"0.9.0","counts":{"incidents":3,"remedySearches":2}}
\ No newline at end of file
+{"schemaVersion":"6","runId":"run-2026-10-08T04:29:16.337259100Z-4","writtenAt":"2026-10-08T04:29:16.341265100Z","virtualNow":"2026-09-06T00:00:11Z","contractSemver":"0.9.0","counts":{"incidents":3,"remedySearches":2}}
\ No newline at end of file
diff --git a/handoff/narrator/run-4/remedy-searches.jsonl b/handoff/narrator/run-4/remedy-searches.jsonl
index a412db4..33ef733 100644
--- a/handoff/narrator/run-4/remedy-searches.jsonl
+++ b/handoff/narrator/run-4/remedy-searches.jsonl
@@ -1,2 +1,2 @@
-{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-AFTER-1","at":"2026-09-06T00:00:05Z","wallClockAt":"2026-09-22T16:47:45.654726400Z","outcome":"FOUND","steps":[{"skillType":"pick_place","requires":[],"expectedHold":"HOLD_KIND_EMPTY","onFailureHold":"HOLD_KIND_HOLDING"}]}
-{"searchId":"search-2","robotId":"hum-04","jobOrderId":"PATROL-AFTER-2","at":"2026-09-06T00:00:10Z","wallClockAt":"2026-09-22T16:47:45.887779900Z","outcome":"WITHHELD"}
+{"searchId":"search-1","robotId":"hum-02","jobOrderId":"PATROL-AFTER-1","at":"2026-09-06T00:00:05Z","wallClockAt":"2026-10-08T04:29:16.052529300Z","outcome":"FOUND","steps":[{"skillType":"pick_place","requires":[],"expectedHold":"HOLD_KIND_EMPTY","onFailureHold":"HOLD_KIND_HOLDING"}]}
+{"searchId":"search-2","robotId":"hum-04","jobOrderId":"PATROL-AFTER-2","at":"2026-09-06T00:00:10Z","wallClockAt":"2026-10-08T04:29:16.287542300Z","outcome":"WITHHELD"}
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt
index 337f344..ef90beb 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt
@@ -43,6 +43,14 @@ data class Intent(
     val destination: String?,
     /** 설비가 그 자리에서 읽어야 할 것. 대조의 기대값이다. */
     val expectedIdentity: String?,
+    /**
+     * 이 실행을 계획한 **임무 버전**. 코드 케이퍼빌리티로 돈 실행이면 널이다. 작업 지시의 버전([orderVersion])과 다른 축이다 —
+     * 같은 작업 지시라도 어느 정의로 펼쳤는지가 «무엇을 하려 했나» 를 바꾼다.
+     *
+     * **해시에 든다** — 다른 버전으로 펼친 같은 모양의 인시던트는 다른 인시던트다. **내보내기에는 안 실린다** — 읽는 쪽이
+     * 아직 없다(ADR 9). 읽는 쪽이 생기는 날 내보내기 버전을 올려 싣는다.
+     */
+    val missionVersion: Int? = null,
 )
 
 /**
@@ -294,7 +302,8 @@ data class IncidentBundle(
                     intent.equipment.joinToString(";") { "${it.id}/${it.equipmentUse}/${canonicalMap(it.properties)}" } + "|" +
                     "${intent.capabilityMaxEvidence.name}|${intent.evidenceWindowBefore}|${intent.evidenceWindowAfter}|" +
                     "${intent.skillType}|${canonicalMap(intent.unitParameters)}|" +
-                    "${intent.source.orEmpty()}|${intent.destination.orEmpty()}|${intent.expectedIdentity.orEmpty()}",
+                    "${intent.source.orEmpty()}|${intent.destination.orEmpty()}|${intent.expectedIdentity.orEmpty()}|" +
+                    intent.missionVersion?.toString().orEmpty(),
             )
             appendLine(
                 "${observation.linkBroken}|${observation.progressObservable}|${observation.progressStalled}|" +
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt b/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt
index b01df3b..544071f 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt
@@ -129,6 +129,8 @@ internal class IncidentLog(
                     source = unit.source,
                     destination = unit.destination,
                     expectedIdentity = unit.expectedIdentity,
+                    // **실행이 쥔 버전이다** — 지금 활성인 버전이 아니다. 활성화가 실행 도중에 끼어도 이 실행은 옛 버전으로 돈다.
+                    missionVersion = execution.missionVersion,
                 ),
                 observation = ObservationTrust(
                     linkBroken = execution.linkBroken,
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/LedgerExport.kt b/picasso/src/main/kotlin/dev/picasso/middleware/LedgerExport.kt
index 98c3fb8..e769e2b 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/LedgerExport.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/LedgerExport.kt
@@ -39,8 +39,16 @@ object LedgerExport {
      *
      * `4` 에서 탐색 줄의 `outcome` 에 **갈래가 하나 늘었다**(`SOURCE_MISSING`, §15.183). 사건 줄은 안 바뀌었다.
      * **칸이 는 것과 다르다** — `outcome` 으로 분기하는 읽는 쪽은 모르는 값을 만나므로 판을 봐야 한다.
+     *
+     * `5` 에서 인시던트 줄에 `resolution`(사람이 그 단위에 낸 판단) 한 칸이 붙었다(§15.188). 칸이 는 것뿐이고, 해시에서
+     * 빠지는 칸이라 요약은 안 바뀌었다.
+     *
+     * `6` 에서 인시던트 줄의 `route` 에 **하위 범주가 하나 늘었다**(`SIGNAL`, 설비 대기). 칸은 안 늘었다. `4` 와 같은 이유로
+     * 버전을 올린다 — `route` 로 분기하는 읽는 쪽은 모르는 값을 만난다. 의도의 임무 버전(`Intent.missionVersion`)은
+     * 싣지 않는다 — 읽는 쪽이 아직 없다(ADR 9). 다만 그 값이 해시에 들므로 **요약(`digest`)은 모든 줄에서 바뀐다** —
+     * 코드 케이퍼빌리티로 돈 줄(임무 버전 널)도 해시의 모양이 바뀌었기 때문이다.
      */
-    const val SCHEMA_VERSION: String = "5"
+    const val SCHEMA_VERSION: String = "6"
 
     const val INCIDENTS: String = "incidents.jsonl"
     const val REMEDY_SEARCHES: String = "remedy-searches.jsonl"
@@ -184,7 +192,11 @@ object LedgerExport {
         .str("value", r.value)
         .done()
 
-    /** 무엇을 하려던 일이었나 — 상류가 적은 것과 이 층이 편 것을 함께. */
+    /**
+     * 무엇을 하려던 일이었나 — 상류가 적은 것과 이 층이 편 것을 함께.
+     *
+     * **임무 버전(`missionVersion`)은 안 싣는다.** 프로세스 안의 인시던트 번들에만 있다 — 읽는 쪽이 생기면 버전을 올려 싣는다.
+     */
     private fun intent(i: Intent): String = Obj()
         .str("workMasterId", i.workMasterId)
         .num("orderVersion", i.orderVersion)
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/LogicalCapability.kt b/picasso/src/main/kotlin/dev/picasso/middleware/LogicalCapability.kt
index 70866b4..a4f9000 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/LogicalCapability.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/LogicalCapability.kt
@@ -227,7 +227,8 @@ class DeliverContainer : LogicalCapability {
  * 점검 결과를 실을 자리는 계약에 `partial_result` 문자열 하나뿐이고 미믹은 채우지 않는다(§15.76·§15.87).
  * 그래서 이 능력이 낸 `JobResponse.results` 는 지금 비어 있으며, 그 사실을 시험이 고정한다.
  *
- * 공통 엔진은 손대지 않았다 — 이 클래스와 [EquipmentUse] 의 낱말 셋이 확장의 전부다(17장 10번).
+ * 이 능력을 들일 때 공통 엔진은 손대지 않았다 — 이 클래스와 [EquipmentUse] 의 용어 셋이 그때 확장의 전부였다(17장 10번).
+ * 단위의 **종류**가 늘면 이야기가 다르다 — 설비 대기는 엔진에 경로 하나([Route.SIGNAL])와 그 진행을 더했다(ADR 50).
  */
 class InspectAsset : LogicalCapability {
 
diff --git a/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt b/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt
index 03c6152..76e397b 100644
--- a/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt
+++ b/picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt
@@ -33,7 +33,8 @@ import java.time.format.DateTimeParseException
  *
  * [Route.ROBOT] 은 계약(④)의 원자 스킬이고 [Route.FLEET] 은 D 수준 위임이다.
  * 둘을 가르는 것은 하류의 **종류**이지 기종이 아니다 — 여기에 기종 이름은 없고
- * 게이트 7번이 그것을 지킨다.
+ * 게이트 7번이 그것을 지킨다. 셋째 경로 [Route.SIGNAL] 은 하위가 아니라 설비 대기다 —
+ * 요청을 보내지 않고 이름 있는 설비 신호를 기다린다.
  *
  * ## 기체
  *
@@ -44,7 +45,11 @@ class Middleware(
     private val robots: RobotPort,
     private val cell: CellSignals = CellSignals.None,
     private val fleet: AmrFleetPort = AmrFleetPort.None,
-    capabilities: List<LogicalCapability> = listOf(PrepareSequencedRack(), DeliverContainer(), InspectAsset()),
+    /**
+     * 코드로 정의한 케이퍼빌리티 목록. 널이면 [MissionCatalog.codeCapabilities] 다. [missions] 를 주면 쓰지 않으므로
+     * 둘을 함께 주면 생성이 실패한다 — 한쪽이 조용히 무시되는 길을 두지 않는다.
+     */
+    capabilities: List<LogicalCapability>? = null,
     private val now: () -> Instant = { Instant.now() },
     /**
      * `IN_DOUBT` 에서 같은 참조로 다시 묻는 횟수의 상한(13.2 ①). 이만큼 물어도 답이 없으면 하류가 지금은 조회 불가인
@@ -96,8 +101,21 @@ class Middleware(
      * 같은 시드로 다시 띄운 인스턴스가 같은 값을 내면 앞 구동의 통보가 이번 시도의 것으로 읽힌다.
      */
     val instanceId: String = "mw-${java.util.UUID.randomUUID()}",
+    /**
+     * 작업 지시를 계획할 케이퍼빌리티의 출처(임무 버전). 널이면 [capabilities] 로 만든 카탈로그다(버전 없음).
+     *
+     * **새 작업 지시만 읽는다.** 실행은 생성 때 읽은 케이퍼빌리티와 버전을 쥐고 리비전까지 그것으로 돈다 —
+     * 그래서 활성 버전이 바뀌어도 도는 실행은 옛 버전으로 끝난다.
+     */
+    missions: MissionCatalog? = null,
 ) {
-    private val capabilities = capabilities.associateBy { it.workMasterId }
+    // ⚠ 생성자 인자 `missions`(널 허용)가 이 속성을 가린다 — 다른 속성의 초기화식에서 `missions` 는 인자다. 거기서는 `this.missions` 로 쓴다.
+    private val missions: MissionCatalog = run {
+        require(capabilities == null || missions == null) {
+            "케이퍼빌리티 목록과 임무 카탈로그를 함께 줬다 — 카탈로그를 주면 목록은 쓰지 않는다"
+        }
+        missions ?: MissionCatalog.of(capabilities ?: MissionCatalog.codeCapabilities())
+    }
     private val executions = linkedMapOf<String, Execution>()
     private val views = mutableMapOf<String, RobotView>()
 
@@ -147,6 +165,11 @@ class Middleware(
         /** 지금 이 일을 든 기체. **재할당으로 바뀐다** — 옮긴 이력은 자취에 남는다. */
         var robotId: String,
         val capability: LogicalCapability,
+        /**
+         * 이 실행을 계획한 임무 버전. 코드 케이퍼빌리티면 `null` 이다. **생성 때 정해지고 바뀌지 않는다** —
+         * 리비전도 [capability] 로 계획하므로, 활성 버전이 바뀌어도 이 실행은 이 버전으로 끝난다.
+         */
+        val missionVersion: Int?,
         val units: MutableList<ExecutionUnit>,
     ) {
         var physicalState: PhysicalState = PhysicalState.REQUESTED
@@ -197,6 +220,12 @@ class Middleware(
         var linkBroken: Boolean = false
             internal set
 
+        /**
+         * 설비 대기가 마지막으로 자취에 남긴 신호 값 — 태스크 id(시도마다 다르다)로 단다. 값이 바뀔 때만 남기기 위한
+         * 표시다. 재작업하면 새 시도라 첫 값을 다시 남긴다.
+         */
+        internal val signalSeen: MutableMap<String, String> = mutableMapOf()
+
         /** 지연 이벤트(15.1) — 옛 버전의 종착. 폐기하지 않는다. */
         val lateEvents: MutableList<LateEvent> = mutableListOf()
         private val seenLate = mutableSetOf<Triple<String, Int, String>>()
@@ -259,20 +288,24 @@ class Middleware(
      * @param prefix 승인된 조치 열(설계안 §6.4). **[approveRemedy] 만 채운다** — 밖에서 부를 길이 없으므로
      *   승인 없이 조치가 실행되는 경로가 생기지 않는다.
      */
-    private fun submit(order: JobOrder, robotId: String, prefix: List<ExecutionUnit>, approvedBy: Approver? = null): Submission {
-        val capability = capabilities[order.workMasterId]
-            ?: return Submission.Rejected("모르는 논리적 능력이다: ${order.workMasterId}")
-        // 보고서 11.3 — 능력은 최고 등급을 선언하고 요청은 요구 등급을 지정한다. 확인 수단이 없으면 제공 불가다.
-        // 받아 놓고 UNVERIFIED 로 끝내는 것은 상류에 "될지도 모른다" 고 말한 셈이다.
-        if (order.requiredEvidence > capability.maxEvidence) {
-            return Submission.Rejected(
-                "요구 근거 등급 ${order.requiredEvidence} 은 ${capability.workMasterId} 의 최고 등급 ${capability.maxEvidence} 를 넘는다 — 확인 수단이 없다",
-            )
-        }
-
+    private fun submit(
+        order: JobOrder,
+        robotId: String,
+        prefix: List<ExecutionUnit>,
+        approvedBy: Approver? = null,
+        /** [adopt] 가 관문에 대려고 이미 읽은 쌍. 널이면 지금 카탈로그를 읽는다 — 한 작업 수락이 카탈로그를 두 번 읽지 않게 한다. */
+        mission: ActiveMission? = null,
+    ): Submission {
+        // **이미 있는 실행이면 카탈로그를 안 본다.** 리비전은 그 실행이 쥔 케이퍼빌리티로 계획한다 — 여기서 카탈로그를
+        // 읽으면 활성 버전이 바뀐 뒤의 리비전이 새 버전의 단위를 옛 실행에 들인다.
         val existing = executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }
         if (existing != null) return revise(existing, order)
 
+        val active = mission ?: missions.active(order.workMasterId)
+            ?: return Submission.Rejected("모르는 논리적 능력이다: ${order.workMasterId}")
+        val capability = active.capability
+        evidenceRefusal(order, capability)?.let { return it }
+
         val planned = prefix + capability.plan(order)
         when (val admission = admits(order, robotId, planned)) {
             is Admission.Refused -> return desk.record(robotId, order, admission.rejection, admission.sourceMissing)
@@ -284,6 +317,7 @@ class Middleware(
             order = order,
             robotId = robotId,
             capability = capability,
+            missionVersion = active.missionVersion,
             units = planned.toMutableList(),
         )
         execution.units.forEach { it.revision = order.version }
@@ -293,6 +327,19 @@ class Middleware(
         return Submission.Accepted(execution)
     }
 
+    /**
+     * 보고서 11.3 — 능력은 최고 등급을 선언하고 요청은 요구 등급을 지정한다. 확인 수단이 없으면 제공 불가다.
+     * 받아 놓고 UNVERIFIED 로 끝내는 것은 상류에 "될지도 모른다" 고 말한 셈이다.
+     */
+    private fun evidenceRefusal(order: JobOrder, capability: LogicalCapability): Submission.Rejected? =
+        if (order.requiredEvidence > capability.maxEvidence) {
+            Submission.Rejected(
+                "요구 근거 등급 ${order.requiredEvidence} 은 ${capability.workMasterId} 의 최고 등급 ${capability.maxEvidence} 를 넘는다 — 확인 수단이 없다",
+            )
+        } else {
+            null
+        }
+
     /**
      * **배정 관문**(설계안 §7)의 공개 창구. 판정은 [AdmissionGate.admits] 가 한다.
      *
@@ -312,13 +359,15 @@ class Middleware(
      * 전부 떨어지면 [Unassigned] 다. **재계산을 요청하지 않는다** — 요청하면 이 층이 중재자가 된다.
      */
     fun adopt(order: JobOrder, ranked: List<String>): Submission {
-        val capability = capabilities[order.workMasterId]
+        // **카탈로그를 한 번 읽고 그 쌍을 작업 수락까지 넘긴다.** 관문에 댄 계획과 실행이 쥘 계획이 같은 버전이어야
+        // 한다 — 그 사이에 활성화가 끼면 관문이 본 적 없는 단위가 나간다.
+        val mission = missions.active(order.workMasterId)
             ?: return Submission.Rejected("모르는 논리적 능력이다: ${order.workMasterId}")
-        val planned = capability.plan(order)
+        val planned = mission.capability.plan(order)
         val refusals = linkedMapOf<String, String>()
         for (robotId in ranked) {
             when (val admission = admits(order, robotId, planned)) {
-                Admission.Passed -> return submit(order, robotId)
+                Admission.Passed -> return submit(order, robotId, prefix = emptyList(), mission = mission)
                 is Admission.Refused -> refusals[robotId] = admission.rejection.reason
             }
         }
@@ -395,6 +444,14 @@ class Middleware(
     }
 
     private fun revise(execution: Execution, order: JobOrder): Submission {
+        // **실행이 쥔 케이퍼빌리티로 판정한다**(카탈로그가 아니다). 다른 WorkMaster 로 바꾸는 리비전은 그 케이퍼빌리티로
+        // 계획할 수 없으므로 받지 않는다 — 새 작업 지시다.
+        if (order.workMasterId != execution.order.workMasterId) {
+            return Submission.Rejected(
+                "리비전이 WorkMaster 를 바꾼다: 받은 값=${order.workMasterId}, 현재=${execution.order.workMasterId} — 새 작업 지시로 낸다",
+            )
+        }
+        evidenceRefusal(order, execution.capability)?.let { return it }
         if (order.version == execution.order.version) return Submission.Idempotent(execution)
         if (order.version < execution.order.version) {
             return Submission.Rejected("이미 지난 버전이다: 받은 값=${order.version}, 현재=${execution.order.version}")
@@ -432,7 +489,13 @@ class Middleware(
                 // task_id: 접수돼 있었으면 갱신, 아니었으면 새 접수. 어느 쪽이든 핸들이 돌아오면 이제 추적한다).
                 UnitState.RUNNING, UnitState.IN_DOUBT -> {
                     val fresh = replanned[unit.unitId]
-                    if (fresh != null && unit.route == Route.ROBOT) {
+                    // 경로마다 가른다. 로봇 단위만 계약의 갱신 규칙을 탄다 — 플릿에 맡긴 운반은 아래 이유로, 설비 대기는
+                    // 같은 실행이 쥔 같은 정의의 같은 노드라 바꿀 것이 없어 그대로 둔다(기다림이 이어진다).
+                    val updatable = when (unit.route) {
+                        Route.ROBOT -> true
+                        Route.FLEET, Route.SIGNAL -> false
+                    }
+                    if (fresh != null && updatable) {
                         val previous = unit.revision
                         unit.revision = order.version
                         // **기대도 새 버전의 것이다.** 옛 기대를 들고 있으면 옛 버전의 완료가 옛 기대에 맞아 새 버전의 완료로 적힌다.
@@ -607,8 +670,11 @@ class Middleware(
             val settled = when {
                 active.state == UnitState.IN_DOUBT -> resolveDoubt(execution, active)
                 active.state == UnitState.VERIFYING -> checkEvidence(execution, active)
-                active.route == Route.ROBOT -> pumpRobotUnit(execution, active)
-                else -> pumpFleetUnit(execution, active)
+                else -> when (active.route) {
+                    Route.ROBOT -> pumpRobotUnit(execution, active)
+                    Route.FLEET -> pumpFleetUnit(execution, active)
+                    Route.SIGNAL -> pumpSignalUnit(execution, active)
+                }
             }
             if (!settled) return
             execution.active = null
@@ -619,6 +685,9 @@ class Middleware(
                 if (execution.physicalState != PhysicalState.ABORTED) abort(execution, inProgress = active, hold = active.hold, cleanup = "not_applicable")
                 return
             }
+            // **설비 대기의 기한이 실행을 중단했다**(`onDeadline = ABORTED`). 취소 요청이 아니라서 위 분기에 안 걸리고,
+            // 여기서 돌아가지 않으면 아래가 다음 단위를 찾아 출발시킨다 — 신호를 못 본 채 로봇이 움직인다.
+            if (execution.physicalState == PhysicalState.ABORTED) return
         }
 
         if (execution.cancelRequested) {
@@ -825,7 +894,7 @@ class Middleware(
      * @return 단위가 이 펌프에서 종착(운영자 보류 포함)했는가.
      */
     private fun resolveDoubt(execution: Execution, unit: ExecutionUnit): Boolean {
-        val lookup = if (unit.route == Route.ROBOT) robots.executionLookup else fleet.executionLookup
+        val lookup = lookupOf(unit.route)
         if (lookup == ExecutionLookup.CLIENT_REFERENCE && unit.lookups < lookupRetries) {
             unit.lookups += 1
             val found = try {
@@ -920,8 +989,93 @@ class Middleware(
                 false
             }
         }
+
+        // 설비 대기는 하위에 보낼 요청이 없다 — 시작이 곧 기다림의 시작이고, 읽기는 [pumpSignalUnit] 이 한다.
+        Route.SIGNAL -> true
     }
 
+    /**
+     * 경로마다 하위가 클라이언트 참조로 기존 실행을 찾아 주는가(13.2 ①).
+     *
+     * 설비 대기는 요청을 보내지 않으므로 미확정이 생기지 않는다 — 실행의 미확정 자동 해소를 막지 않는 쪽으로 응답한다.
+     */
+    private fun lookupOf(route: Route): ExecutionLookup = when (route) {
+        Route.ROBOT -> robots.executionLookup
+        Route.FLEET -> fleet.executionLookup
+        Route.SIGNAL -> ExecutionLookup.CLIENT_REFERENCE
+    }
+
+    /**
+     * 설비 대기 단위를 한 단계 민다([Route.SIGNAL]) — 이름 있는 신호를 당겨 읽고, 기대 값이면 끝낸다.
+     *
+     * 규칙 넷이다.
+     *
+     * 1. **취소가 걸렸으면 기다림을 끝낸다.** 하위 손잡이가 없어 취소가 하위로 가지 않으므로 여기서 본다. 대기 단위는
+     *    `ABORTED`, 실행은 취소 경로(정리 «해당 없음»)로 끝난다.
+     * 2. **지금 값이 기대 값이면 `DONE` 이다.** 설비가 관측한 사실이라 근거 등급은 E2 다. 관측 시각은 자취에만 남긴다.
+     * 3. **못 읽으면(`null`) 계속 기다린다.** 못 읽은 것을 «기대 값이 아님» 으로 접지 않는다 — 기한이 판정한다.
+     * 4. **기한(시작 시각 + 기한)이 지나면** [WaitSpec.onDeadline] 으로 간다. 마지막으로 읽은 값을 자취에 한 번 남기고
+     *    인시던트를 연다(`SIGNAL_DEADLINE`).
+     *
+     * 신호는 **값이 바뀔 때만** 자취에 남긴다. 진행마다 남기면 자취가 넘쳐 근거 윈도우가 그 줄로 찬다.
+     *
+     * @return 단위가 이 진행에서 종료(운영자 보류 포함)했는가.
+     */
+    private fun pumpSignalUnit(execution: Execution, unit: ExecutionUnit): Boolean {
+        val wait = unit.wait ?: error("설비 대기 단위에 대기 사양이 없다: ${unit.unitId}")
+        if (execution.cancelRequested) {
+            unit.state = UnitState.ABORTED
+            unit.annotate("cancelled while waiting for ${wait.signal}")
+            abort(execution, inProgress = unit, hold = null, cleanup = "not_applicable")
+            return true
+        }
+
+        val reading = cell.signal(wait.signal)
+        val seen = reading?.value ?: NO_SIGNAL
+        if (execution.signalSeen[unit.taskId] != seen) {
+            execution.signalSeen[unit.taskId] = seen
+            execution.trail("CELL_SIGNAL", "signal ${wait.signal}: ${describe(reading)}")
+        }
+
+        if (reading != null && reading.value == wait.expect) {
+            unit.reached = Evidence.E2
+            unit.verification = Verification.MATCHED
+            unit.evidenceAt = reading.observedAt ?: now()
+            unit.state = UnitState.DONE
+            return true
+        }
+
+        val startedAt = unit.requestedAt!!
+        if (!now().isAfter(startedAt.plus(wait.deadline))) {
+            execution.physicalState = PhysicalState.RUNNING
+            return false
+        }
+
+        // 기한이 지났다. 마지막으로 읽은 값을 **기한 시점의 관측으로** 한 번 더 남긴다 — 값이 안 바뀌었으면 그 줄은 시작
+        // 무렵에 한 번 적혔을 뿐이라 인시던트의 근거 윈도우 밖에 있다.
+        execution.trail("CELL_SIGNAL", "signal ${wait.signal} at deadline: ${describe(reading)}")
+        unit.failureClass = WaitSpec.SIGNAL_DEADLINE
+        unit.annotate("signal ${wait.signal} did not read '${wait.expect}' within ${wait.deadline} — ${wait.onDeadline.name}")
+        execution.markIncident(unit)
+        when (wait.onDeadline) {
+            DeadlineOutcome.OPERATOR_HOLD -> unit.state = UnitState.OPERATOR_HOLD
+            DeadlineOutcome.ABORTED -> {
+                unit.state = UnitState.FAILED
+                // **남은 단위는 내보내지 않는다** — 먼저 중단으로 적고 실행을 끝낸다. `PENDING` 으로 두면 그 단위가
+                // «아직 안 시작함» 으로 남아, 진행 루프가 출발시킬 자리와 정산 규칙이 부분 완료로 읽을 자리가 남는다.
+                execution.units.filter { it.state == UnitState.PENDING }.forEach {
+                    it.state = UnitState.ABORTED
+                    it.annotate("not started: ${wait.signal} deadline aborted the execution")
+                }
+                abort(execution, inProgress = unit, hold = null, cleanup = "not_applicable", requested = false)
+            }
+        }
+        return true
+    }
+
+    private fun describe(reading: NamedSignal?): String =
+        if (reading == null) NO_SIGNAL else "value=${reading.value} at=${reading.observedAt?.toString() ?: "(read now)"}"
+
     private fun transportOrderOf(unit: ExecutionUnit) = TransportOrder(
         reference = unit.taskId,
         containerId = unit.expectedIdentity ?: unit.unitId,
@@ -1452,7 +1606,23 @@ class Middleware(
 
     fun lastCancel(executionId: String): CancelReport? = executions[executionId]?.lastCancel
 
-    private fun abort(execution: Execution, inProgress: ExecutionUnit?, hold: HoldState?, cleanup: String) {
+    /**
+     * @param requested 상위의 취소 요청([cancel])으로 멈췄는가. 거짓이면 이 계층이 정해 둔 규칙(설비 대기의 기한 뒤
+     *   `ABORTED`)으로 멈춘 것이고, **취소 응답([CancelReport])을 남기지 않는다** — 아무도 취소를 안 했는데 취소 응답이
+     *   있으면 «누가 멈췄나» 에 거짓으로 응답한다.
+     */
+    private fun abort(
+        execution: Execution,
+        inProgress: ExecutionUnit?,
+        hold: HoldState?,
+        cleanup: String,
+        requested: Boolean = true,
+    ) {
+        if (!requested) {
+            execution.physicalState = PhysicalState.ABORTED
+            notify(execution)
+            return
+        }
         // 단위가 끝까지 갔으면(하류가 중단을 거절했거나, 취소가 닿기 전에 끝났거나) 중단된 단위가 아니라 **그 뒤에서 멈춘** 경계다.
         val refused = inProgress != null && (inProgress.state == UnitState.DONE || inProgress.state == UnitState.UNVERIFIED)
         val report = CancelReport(
@@ -1501,9 +1671,7 @@ class Middleware(
             results = units.filter { it.state == UnitState.DONE && !it.result.isNullOrBlank() }.associate { it.unitId to it.result!! },
             blockedBy = execution.blockedBy.map { canonicalClassOf(it) },
             connection = (views[execution.robotId]?.takeIf { it.observable }?.connection ?: ConnectionState.CONNECTION_STATE_UNSPECIFIED).name,
-            autoResolvesInDoubt = units.all {
-                (if (it.route == Route.ROBOT) robots.executionLookup else fleet.executionLookup) == ExecutionLookup.CLIENT_REFERENCE
-            },
+            autoResolvesInDoubt = units.all { lookupOf(it.route) == ExecutionLookup.CLIENT_REFERENCE },
         )
         outbox += response
         execution.upstreamAck = UpstreamAck.SENT_UNACKED
@@ -1562,5 +1730,8 @@ class Middleware(
 
         /** 계약 `FailureClass.UNCLASSIFIED` 의 이름 — 하류가 분류를 안 실었을 때의 값. 지어낸 분류가 아니다. */
         const val UNCLASSIFIED = "UNCLASSIFIED"
+
+        /** 설비 대기가 이름 있는 신호를 못 읽었다 — 자취의 값 자리. «기대 값이 아님» 과 다르다. */
+        private const val NO_SIGNAL = "no signal"
     }
 }
diff --git a/picasso/src/test/kotlin/dev/picasso/middleware/EvidenceWindowTest.kt b/picasso/src/test/kotlin/dev/picasso/middleware/EvidenceWindowTest.kt
index 66affc2..90857b7 100644
--- a/picasso/src/test/kotlin/dev/picasso/middleware/EvidenceWindowTest.kt
+++ b/picasso/src/test/kotlin/dev/picasso/middleware/EvidenceWindowTest.kt
@@ -255,14 +255,14 @@ class EvidenceWindowTest {
 
     @Test
     fun `판단자는 내보내는 사건 줄에 실리지 않는다`() {
-        // 읽는 쪽이 아직 없다(ADR 9). 실리면 판이 오르고 인계본이 다시 산출된다 — 판 5 그대로여야 한다.
+        // 읽는 쪽이 아직 없다(ADR 9). 실리면 판이 오르고 인계본이 다시 산출된다 — 판 6 그대로여야 한다.
         World().use { w ->
             val exec = w.held()
             w.mw.resolve(exec.executionId, SLOT, OperatorDecision.REWORK, OPERATOR)
             val line = LedgerExport.incidents(w.mw.incidents())
             assertTrue("\"resolution\":{\"decision\":\"REWORK\"" in line, line)
             assertTrue("decidedBy" !in line && OPERATOR.id !in line, line)
-            assertEquals("5", LedgerExport.SCHEMA_VERSION)
+            assertEquals("6", LedgerExport.SCHEMA_VERSION)
         }
     }
 
diff --git a/picasso/src/test/kotlin/dev/picasso/middleware/LedgerExportTest.kt b/picasso/src/test/kotlin/dev/picasso/middleware/LedgerExportTest.kt
index 863e15c..bddfaca 100644
--- a/picasso/src/test/kotlin/dev/picasso/middleware/LedgerExportTest.kt
+++ b/picasso/src/test/kotlin/dev/picasso/middleware/LedgerExportTest.kt
@@ -239,6 +239,7 @@ class LedgerExportTest {
             "막는 결함" to b.copy(blockedBy = listOf(FAULT)),
             "걸음 위치" to b.copy(step = b.step.copy(at = 1)),
             "의도" to b.copy(intent = b.intent.copy(destination = "다른-자리")),
+            "임무 버전" to b.copy(intent = b.intent.copy(missionVersion = 2)),
             "관측 신뢰" to b.copy(observation = b.observation.copy(linkBroken = true)),
         )
 
```

- [ ] **Step 3: picasso 시험(백그라운드)**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" && ./gradlew :picasso:test -q
```
Expected: picasso testcase 343(기존 297 + 새 46, XML 파일 40), 실패 0.

이어서 커밋된 인계 번들이 지금 코드의 산출과 같은지 본다(`ExportFixtureTest` 가 `picasso/build/export/run-*` 를 만들었다):

```bash
PYTHONUTF8=1 python "C:/Users/Eisen/AppData/Local/Temp/p3-tools/handoff_cmp.py" "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission"
```
Expected: 8줄 모두 `같음`(실제 시계 칸과 런 식별자는 가리고 대조한다).

- [ ] **Step 4: 커밋과 묶음 대조**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission"
git add picasso/src/main/kotlin/dev/picasso/middleware/Middleware.kt picasso/src/main/kotlin/dev/picasso/middleware/Incident.kt picasso/src/main/kotlin/dev/picasso/middleware/IncidentLog.kt picasso/src/main/kotlin/dev/picasso/middleware/LedgerExport.kt picasso/src/main/kotlin/dev/picasso/middleware/LogicalCapability.kt picasso/src/test/kotlin/dev/picasso/middleware/EvidenceWindowTest.kt picasso/src/test/kotlin/dev/picasso/middleware/LedgerExportTest.kt picasso/src/test/kotlin/dev/picasso/middleware/EquipmentWaitTest.kt picasso/src/test/kotlin/dev/picasso/middleware/MissionVersionScenarioTest.kt handoff/narrator
git status --short
git commit -q -F - <<'EOF'
feat(p3): 설비 대기 단위와 실행의 임무 버전 고정, 내보내기 버전 6 추가

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash "C:/Users/Eisen/AppData/Local/Temp/p3-cmp.sh" $(git -C "C:/Users/Eisen/AppData/Local/Temp/p3" diff --name-only 41beedb HEAD -- picasso/src handoff)
```
Expected: 대조 줄 35개가 모두 `같음`(코드·시험·인계 번들).

---

## Chunk 2: 문서, 결함 주입, PR

### Task 3: 문서

**Files:**
- Create: `docs/adr/0050-mission-definition-is-versioned-data.md`
- Modify: `CLAUDE.md`(시험 수), `README.md`, `picasso/README.md`, `docs/adr/README.md`, `docs/architecture.md`, `docs/glossary.md`, `docs/limits.md`, `docs/orchestration.md`, `docs/scenarios.md`, `docs/seams.md`, `docs/superpowers/specs/2026-09-05-picasso-design.md`(§14 행, §15.209), `docs/verification.md`, `gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt`(주장 문서 수 66)

- [ ] **Step 1: ADR 50 복사**

```markdown
# ADR 50 — 임무 정의를 검증을 지난 데이터 버전으로 두고 실행은 생성 때 쥔 임무 버전으로 끝낸다

- 상태: 확정 (2026-10-08)
- 관련: [ADR 9](0009-no-declaration-without-consumer.md) 소비자 존재 원칙, [ADR 32](0032-safety-boundary.md) 안전 경계, [ADR 36](0036-work-first-assignment-vs-execution.md), [ADR 38](0038-mission-layer-schema-is-ours.md)
- 변경 이력: 설계 문서 §15.209

## 맥락

41beedb 까지 임무 정의는 코드였다. `PrepareSequencedRack.plan()`(`LogicalCapability.kt` 101~127행)이 작업 지시의 destination 마다 `pick_place` 단위 하나를 내고, 제시 자리는 material 로 짝을 지어 같은 material 이 둘이면 뒤엣것이 이기며, 짝이 없으면 계획 때 `FAILED`(`NO_SOURCE_FOR_MATERIAL`)로 끝냈다. `Middleware` 는 케이퍼빌리티 목록을 workMasterId 로 묶은 맵을 생성 때 쥐었고 실행 중에 바꿀 길이 없었다. 임무 버전 칸은 없었다. 실행은 생성 때 케이퍼빌리티 객체를 쥐었으나 `submit` 이 리비전으로 가기 전에 그 맵을 다시 읽고 최고 근거 등급을 검사했다.

경로는 `ROBOT`·`FLEET` 둘이었고 진행 루프의 분기는 «로봇이 아니면 플릿» 이었다. 셀 신호 포트는 자리별 점유(`observe`)와 `holding` 뿐이라 이름으로 읽는 신호가 없었다. 안전 신호라는 개념은 코드에 없었다(ADR 32 는 안전 기능이 이 소프트웨어 계약을 거치지 않는다고 정한다).

바깥 소비자는 picasso-ops(운영 화면 PoC)다. 운영 관리 화면 설계 제안(`docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`) §10 의 입증 항목 3은 임무 하나를 데이터 버전으로 두고 모의 실행 → 활성화 → 도는 실행 중 새 버전 전환 → 옛 실행은 옛 버전으로 끝남을 보이는 것이며 «이것이 핵심» 이라 적었다. 항목 4는 잘못된 변경 하나(신호 사양에 없는 신호 참조)가 활성화에서 거부되는 것이다. §6 은 노드 2종(단위·설비 대기), 검사 5가지, 직선만, 단위 안 9상태는 엔진이 고정, 실행이 임무 버전 번호를 드는 것을 정했다. §9 는 인시던트 기록에 버전 번호를 적게 했다. 2026-10-08 사용자 결정은 임무 버전을 먼저 picasso 에 짓고 picasso 시험 위에서 입증하며, 노드형 스키마와 설비 대기까지 엔진에 구현하는 것이다. 소비자가 생겨서 지었다(ADR 9).

## 결정

**임무 정의는 노드형 JSON 이다.** 최상위 5칸(`schemaVersion` 1, `workMasterId`, `maxEvidence` E0~E2, `preferredOptionals`, `steps` 1개 이상)은 모두 필수다. 노드는 `kind` 로 `unit`·`wait` 를 나눈다. 단위 노드는 `id`·`skill`·`forEach`·`unitId`·`parameters` 가 필수이고 `expectedIdentity`·`source`·`destination` 과 `pairWith`+`whenUnpaired`(둘이 함께)가 선택이다. 값 출처는 `{"from":"ITEM_ID"}`·`{"from":"ITEM_PROPERTY","property":…}`·`{"from":"PAIRED_ID"}` 셋이고 선택 대체값 `otherwise` 를 둘 수 있다. `unitId` 는 `ITEM_ID` 만 받는다. 속성이 없으면 null 이고(코드의 `expectedIdentity` 와 같다) 값이 없는 파라미터는 빠진다. 대기 노드는 `id`·`signal`·`expect`·`deadlineSeconds`·`onDeadline` 이다. 표기는 `MissionDefinitionParser` 의 KDoc 과 시험 `MissionFixtures` 에 있다.

**파서는 모양과 형만 본다.** 모르는 키, 빠진 칸, 형 틀림, 중복 키, 문법 오류를 모두 모아 한 번에 낸다. 새 의존은 없다. protobuf-java-util 의 `JsonFormat` 으로 `Struct` 를 만들고 엄격 매핑은 손으로 한다. `StrictJson` 이 문법과 중복 키를 본다(`JsonFormat` 은 중복 키에 말없이 뒤엣것을 쓴다). 기한 값과 기한 뒤 상태의 판정은 검증기의 일이다.

**해석기 `DefinedCapability` 가 정의를 `LogicalCapability` 로 바꾼다.** 실행·리비전·인시던트는 지금 경로를 그대로 탄다. 데이터로 옮긴 것은 `PrepareSequencedRack` 하나이고 코드와 같은 계획을 칸마다 낸다(동등성 시험). 코드 클래스는 동등성 기준으로 남는다. `DeliverContainer`·`InspectAsset` 은 코드 정의로 남아 같은 카탈로그에 함께 선다.

**설비 대기는 셋째 경로 `Route.SIGNAL` 이다.** 판정은 신호의 지금 값이다. 기대 값이면 `DONE`·E2 로 끝나고 못 읽으면 계속 기다린다. 기한을 넘으면 인시던트(`SIGNAL_DEADLINE`)를 내고 `onDeadline` 대로 간다. `OPERATOR_HOLD` 는 대기 단위가 운영자 보류로 서서 라인이 멈추고 확인(`CONFIRM_DONE`)·재작업(`REWORK`)·취소로 풀린다. `ABORTED` 는 대기 단위가 `FAILED` 로 남고 남은 단위를 `ABORTED` 로 적은 뒤 실행을 중단하며 취소 응답은 남기지 않는다. 두 경우 모두 인시던트다. 진행 루프의 경로 분기를 «로봇이 아니면 플릿» 에서 경로별 `when` 으로 바꿨다.

**셀 신호 포트에 `signal(name): NamedSignal?` 을 더한다.** 기본 구현은 null 이라 기존 구현체는 깨지지 않는다. 어느 주소가 어느 신호인지는 드라이버의 일이다.

**검증기는 라이브러리다.** 화면과 활성화가 같은 규칙으로 막는다. 노드 id 중복과 기한·신호·자원·스킬·안전 다섯 검사다. 거부는 운영 관리 화면 설계 제안 §8.4 의 6칸(부족한 조건은 종류로, 관측값·기대값, 마지막 확인 시각은 활성화 시도 시각, 근거 버전은 해당 없음, 해결 담당, 바로 갈 작업)에 노드 id 를 더한 것이다. 종류는 `UNREADABLE`·`DUPLICATE_NODE_ID`·`DEADLINE_INVALID`·`SIGNAL_NOT_IN_SPEC`·`SIGNAL_VALUE_INVALID`·`FLOOR_UNOWNED`·`SKILL_NOT_IN_CONTRACT`·`SKILL_NOT_ON_SITE`·`SAFETY_SIGNAL_WAIT` 아홉이고, 해결 담당은 바닥 소유만 화면 밖(`OUTSIDE_CONSOLE`)이다.

**포트 `MissionCatalog` 와 메모리 구현을 둔다.** 포트는 middleware 패키지에, `InMemoryMissionCatalog` 는 mission 패키지에 있어 의존은 한 방향이다. 활성화는 검증을 통과하면 그 WorkMaster 의 다음 버전(1부터)이 되고 거부면 활성 버전이 그대로이며 거부된 시도는 번호를 쓰지 않는다. 검증기 입력(신호 사양·바닥 소유·현장 기체 스킬)은 활성화 호출의 인자다. 구현은 `synchronized` 로 스레드 안전하다. `Middleware` 생성자 맨 뒤에 `missions` 를 두었다. 기본은 코드 케이퍼빌리티 셋이고 버전이 없다. `capabilities` 와 함께 주면 생성이 실패한다.

**실행은 생성 때 임무 버전을 쥔다.** `Execution.missionVersion` 이다. 이미 있는 실행이면 `submit` 은 카탈로그를 보지 않고 리비전으로 간다. 리비전은 실행이 쥔 케이퍼빌리티로 계획하고 근거 등급을 검사하며, WorkMaster 를 바꾸는 리비전은 거부한다. `adopt` 는 카탈로그를 한 번 읽은 쌍(케이퍼빌리티·버전)을 작업 수락까지 넘긴다.

**인시던트는 실행이 쥔 버전을 싣는다.** `Intent.missionVersion` 이고 해시에 든다(코드 케이퍼빌리티 실행은 null 이라 빈 문자열). 내보내기는 싣지 않는다. 조회 버전 5→6 은 `route` 의 하위 범주 `SIGNAL` 때문이다. 로봇 계약과 작업 응답에는 임무 버전 칸을 더하지 않는다.

## 왜 이 모양인가

- 파서와 검증기를 나눴다. 기한 검사를 파서가 하면 그 검사를 빼는 결함이 검증기 시험에 안 잡힌다. 결함 주입 I4a 는 검증기 시험이 잡았다.
- 판정은 지금 값이다. P3 의 신호는 상태 신호뿐이다. 시작 전부터 그 값이면 그 상태는 이미 성립한 것이다. 이벤트형 신호는 범위 밖이다(한계 §15.209).
- 기한 뒤 `ABORTED` 를 `FAILED` 단위 하나로 두지 않았다. 엔진은 `FAILED` 단위 뒤에도 다음 단위를 내보낸다. 신호를 못 본 채 로봇이 움직이면 대기를 둔 뜻이 없다. 정산을 기존 규칙에 맡기면 `[FAILED, …]` 가 부분 완료(`PARTIAL`)가 되어 리비전으로 다시 열린다. 남은 단위를 먼저 `ABORTED` 로 적고 같은 라운드에서 반환해야 다음 단위가 출발하지 않는다(결함 주입 I3a·I3b). 취소 응답은 남기지 않는다. 아무도 취소하지 않았는데 취소 응답이 있으면 «누가 멈췄나» 에 거짓으로 응답한다.
- 자원 검사는 관문과 같은 규칙이다(`Unowned` 만 거부). 레지스터를 안 붙인 배치에서는 모든 자리가 `NotDeclared` 라, 선언 없음을 거부로 읽으면 자리가 적힌 신호를 쓰는 정의가 모두 거부된다.
- 스킬 검사는 둘이다. 계약에 있는 스킬인가와 현장이 제공하는 스킬인가는 다른 물음이고 후속 행동이 다르다.
- 리비전은 실행이 쥔 케이퍼빌리티로 계획한다. 카탈로그를 다시 보면 활성 버전이 바뀐 뒤의 리비전이 새 버전의 단위(예: 대기 단위)를 옛 실행에 들인다(결함 주입 I5 를 시나리오 시험이 잡았다). `adopt` 가 읽은 쌍을 넘기는 것도 같은 이유다. 관문에 댄 계획과 실행이 쥘 계획이 같은 버전이어야 한다.
- 임무 버전은 해시에 든다. `Incident.kt` KDoc 의 해시 원칙 «새 칸은 전부 든다» 를 따른다. `review`·`resolution` 이 빠지는 것은 나중의 사람 단계라서다.
- 모듈을 떼지 않고 패키지로 두었다. 변경의 절반이 엔진 안쪽이라 떼면 한 변경이 둘로 갈라진다. ADR 38 과 `LogicalCapability` KDoc 의 «조합은 미션 계층의 일이고 그것은 이 모듈 안이다» 와 같다. 엔진 없이 스키마·검증기만 필요한 둘째 소비자가 생기면 다시 본다.

## 고르지 않은 것

- 세 정의를 다 데이터로 옮기는 길. 반복 한 번에 노드 여럿, 단위 id 접미사, 작업 지시 파라미터 값, 조건부 포함, 플릿 경로가 스키마에 더 있어야 한다.
- 분기·병렬. 운영 관리 화면 설계 제안 §6 이 5가지 의미(동시 전이 우선순위, 반복 횟수, 자원 획득·반납, 취소·재시작, 재시도 때 부작용 중복)를 먼저 정한 뒤 연다.
- JSON 에 Jackson·kotlinx 를 들이는 길. picasso main 의존은 계약과 계약 소비자뿐이다(P3 스펙의 결정 (차)).
- 작업 응답·로봇 계약에 임무 버전 칸을 두는 길과 `missionVersion` 을 내보내기에 싣는 길. 읽는 쪽이 없다(ADR 9, §15.202 와 같은 처리).
- 별도 저장소·모듈(`picasso-mission`). 위 «왜 이 모양인가» 의 마지막 항목이다.

## 대가

- 엔진이 바뀌었다. `Route` 의 셋째 값, 진행·리비전·미확정 해소·작업 응답의 경로 분기, 중단 반환 가드다. `InspectAsset` 때의 «엔진 무수정» 은 단위의 종류가 늘면 성립하지 않는다(scenarios·README·architecture 의 문장을 고쳤다).
- 조회 버전이 6 이 되어 인계 번들 네 세트를 다시 산출했고 모든 인시던트 줄의 `digest` 가 바뀌었다. 읽는 쪽이 `digest` 를 멱등성 키로 들면 같은 인시던트가 새로 들어온다. 알림이 필요하다.
- 저장과 이력은 메모리 구현뿐이다. picasso-ops 가 S3 에서 붙인다.
- 작업 지시의 설비 id 가 대기 노드 id 와 같으면 단위 id 가 겹친다. 검증기는 정의만 보므로 못 잡는다(한계 §15.209 · id 겹침). 파라미터 이름 오타도 검증기가 못 잡는다(실행 때 `PARAMETER_INVALID`).
- 설비 대기를 사람이 `CONFIRM_DONE` 하면 근거가 E0 로 남아 작업 응답의 근거 등급이 E0 로 내려간다.
- 기한 뒤 `ABORTED` 의 작업 응답은 `operatorRequired=false` 다. 정해 둔 중단이라 사람의 판단을 기다리지 않는다.
- ADR 47 은 «실으면 조회 버전이 6 으로 오르고» 라고 적었으나 6 은 이번에 `route` 의 `SIGNAL` 이 썼다. 결정자를 실을 때는 7 이 된다. ADR 47 본문은 그때의 기록이라 고치지 않는다.

## 대는 것

| 주장 | 시험 |
|---|---|
| 작업 지시 모양마다 데이터 정의가 코드 `PrepareSequencedRack` 과 같은 계획을 칸마다 낸다 | `DefinedCapabilityEquivalenceTest` · `작업 지시 모양마다 데이터 정의가 코드와 같은 계획을 칸마다 낸다` |
| 신호가 기대 값을 읽으면 대기가 E2 로 끝나고 그 뒤에 로봇 단위가 출발한다 | `EquipmentWaitTest` · `신호가 기대 값을 읽으면 대기가 E2 로 끝나고 그 뒤에 로봇 단위가 출발한다` |
| 신호를 못 읽으면 계속 기다리고 기한 시각에는 아직 서 있다가 넘으면 운영자 보류로 선다 | `EquipmentWaitTest` · `신호를 못 읽으면 계속 기다리고 기한 시각에는 아직 서 있다가 넘으면 운영자 보류로 선다` |
| 기한 뒤 `ABORTED` 면 대기 단위는 `FAILED` 로 남고 실행이 중단되며 남은 단위는 나가지 않는다 | `EquipmentWaitTest` · `기한 뒤 ABORTED 면 대기 단위는 FAILED 로 남고 실행이 중단되며 남은 단위는 안 나간다` |
| 보류를 재작업하면 기한이 다시 시작하고 확인하면 근거 E0 로 진행한다 | `EquipmentWaitTest` · `보류를 재작업하면 기한이 다시 시작하고 확인하면 근거 E0 로 진행한다` |
| 대기 중에 취소하면 대기 단위는 `ABORTED` 이고 취소 경로로 끝난다 | `EquipmentWaitTest` · `대기 중에 취소하면 대기 단위는 ABORTED 이고 취소 경로로 끝난다` |
| 대기 중의 리비전은 기다림을 그대로 잇는다 | `EquipmentWaitTest` · `대기 중의 리비전은 기다림을 그대로 잇는다` |
| 검증기는 거부를 전부 모으고 각 거부가 여섯 칸과 노드 id 를 든다 | `MissionValidatorTest` · `거부를 전부 모으고 각 거부가 여섯 칸과 노드를 든다` |
| 정의 안에서 노드 id 가 겹치면 거부한다 | `MissionValidatorTest` · `노드 id 가 겹치면 거부한다` |
| 버전 1 실행 중 버전 2 를 활성화하면 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행은 버전 1 로 끝난다 | `MissionVersionScenarioTest` · `버전 1 실행 중 버전 2 를 활성화하면 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행은 버전 1 로 끝난다` |
| 인시던트는 실행이 쥔 임무 버전을 싣고 그 값이 해시에 들며 내보내기에는 실리지 않는다 | `MissionVersionScenarioTest` · `버전 2 의 대기 기한 인시던트가 그 버전을 싣고 해시에 들며 내보내기에는 안 실린다` |
| 배정 사이에 활성화가 끼어도 실행은 관문에 댄 버전을 쥔다 | `MissionVersionScenarioTest` · `배정 사이에 활성화가 끼어도 실행은 관문에 댄 버전을 쥔다` |
| WorkMaster 를 바꾸는 리비전은 거부하고 실행은 그대로다 | `MissionVersionScenarioTest` · `리비전이 WorkMaster 를 바꾸면 거부하고 실행은 그대로다` |
| 활성화를 통과하면 1 부터 오르는 버전이 활성이 되고 거부된 시도는 번호를 쓰지 않는다 | `InMemoryMissionCatalogTest` · `통과하면 1 부터 오르는 버전이 활성이 되고 거부된 시도는 번호를 쓰지 않는다` |
| 파서는 모르는 키와 빠진 칸과 형이 틀린 값을 한 번에 모은다 | `MissionDefinitionParserTest` · `모르는 키와 빠진 칸과 형이 틀린 값을 한 번에 모은다` |

이 표는 게이트가 이름으로 대조하지 않는다. 이름은 시험과 손으로 맞춘다.

> 마지막 대조: 2026-10-08 · sha256:bd788fcc05b8 · 열림: §15.209 · 정의 둘, §15.209 · 분기, §15.209 · 스키마 파일, §15.209 · 이벤트형 신호, §15.209 · 임무 버전 내보내기, §15.209 · id 겹침, §15.209 · 주소
```

- [ ] **Step 2: 문서 패치**

`C:/Users/Eisen/AppData/Local/Temp/p3-patches/task3.patch`(아래 블록과 같다)를 `git apply --check` 로 본 뒤 `git apply` 한다. 스탬프는 이 패치에 이미 들어 있다.

````diff
diff --git a/CLAUDE.md b/CLAUDE.md
index a3b66bf..07b2fec 100644
--- a/CLAUDE.md
+++ b/CLAUDE.md
@@ -8,7 +8,7 @@
 ## 1. 빌드 및 테스트 명령
 
 ```bash
-# 전체 빌드 및 테스트 실행 (총 1,931개 테스트)
+# 전체 빌드 및 테스트 실행 (총 1,977개 테스트)
 ./gradlew build
 
 # 아키텍처 및 품질 게이트 검증만 실행
@@ -72,4 +72,4 @@
 - **저장소 경계 준수**: 본 저장소 밖의 다른 저장소 파일을 직접 생성하거나 수정하지 않습니다. 계층이 서로 다른 저장소에 위치하고 상호 참조하지 않는다는 사실 자체가 아키텍처 경계의 증명이며, 편의를 이유로 한 번 넘어가면 그 증명이 소멸합니다. 다른 저장소로 넘길 산출물은 `handoff/<받는 쪽>/` 에 두고 경로만 전달하며, 무엇을 반입할지는 받는 쪽이 결정합니다.
 - **한계점 및 히스토리 관리**: 미결 과제는 [`docs/limits.md`](docs/limits.md)에 기록하며, 설계 문서 `§15`의 변경 이력은 기존 항목을 삭제하지 않고 정정 내용을 누적 기록합니다.
 
-> 마지막 대조: 2026-10-08 · sha256:9cc3575296eb · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:2f8b69eb87b3 · 열림: 없음
diff --git a/README.md b/README.md
index 0fc9722..cd7e13b 100644
--- a/README.md
+++ b/README.md
@@ -91,7 +91,7 @@ docs/vendors/             로봇이 아닌 벤더 표면의 측정 노트 (플
 
 저장소 내 대외 문서 57종은 자동화 대조 검증을 완료한 상태입니다. 문서에 명시된 모든 기술적 주장은 자동화 테스트로 증명되거나, [`docs/limits.md`](docs/limits.md)의 오픈 항목 레지스터에 등록되어 추적 관리됩니다. 각 문서 하단의 대조 스탬프(Hash Stamp)은 본문 내용과 연결되어 있어, `CompletionCriterionTest`를 통해 임의 변경 시 스탬프 갱신을 요구합니다.
 
-한계 레지스터(`limits.md`)에 등록된 미결 항목은 **63개**(내부 28개 · 소비자 대기 10개 · 외부 25개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
+한계 레지스터(`limits.md`)에 등록된 미결 항목은 **70개**(내부 29개 · 소비자 대기 15개 · 외부 26개, 의도적 제외 13개 제외)이며, 그 상세 목록과 해결 조건은 `limits.md`에 명시되어 있습니다. 특히 실물 어댑터가 넷 있다(기체 셋, 플릿 하나). 다만, 어댑터 넷 중 어느 것도 실물에 붙여 보지 못했다(C-3)는 물리적 검증 한계가 존재하며, 이는 SDK 라이선스, JVM 바인딩 부재, 플릿 실기체 인스턴스 부재 등에 기인합니다.
 
 실물 넷이 계약에 얼마나 닿나 확인한 정량 분석 결과는 [`profile/distance/`](profile/distance)에서 확인할 수 있습니다. 계약 개정판은 **0.9.0** 이다.
 
@@ -140,7 +140,7 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 | **벤더 인터페이스** | [`profile/vendors/`](profile/vendors) · [`docs/vendors/orbit.md`](docs/vendors/orbit.md) | 벤더 API 표면 분석 및 플릿 관리 인터페이스 측정 노트 |
 | **현장 전제조건** | [`docs/environment-preconditions.md`](docs/environment-preconditions.md) | 로봇 도입 현장의 인프라(도어, 바닥, 조명 등) 엔지니어링 전제조건 |
 | **벤더 매니페스트** | [`tools/vendor-manifest/README.md`](tools/vendor-manifest/README.md) | 어댑터의 사우스바운드 포트 벤더 심볼 인용 대조 검증 도구 |
-| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 63개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
+| **오픈 항목 과제 레지스터** | [`docs/limits.md`](docs/limits.md) | 미결 한계 항목 70개(내부·소비자 대기·외부) 및 해결 조건 관리 레지스터 |
 
 ## 핵심 엔지니어링 규율
 
@@ -151,4 +151,4 @@ client --target <host:port> --robot <id> --requirements <file> --skill <type> [-
 - **결함 주입(Mutation Testing)**: 테스트 케이스 작성 시 의도적 결함을 주입하여 검증 유효성을 선행 확인합니다.
 - **엄격한 실패 정책**: 사전 선언된 요구 검사 목록(`--require`)을 충족하지 못하는 경우 조용한 통과를 허용하지 않습니다.
 
-> 마지막 대조: 2026-10-08 · sha256:0e0ebe16bb61 · 열림: C-3, §15.81
+> 마지막 대조: 2026-10-08 · sha256:8df3ff12389b · 열림: C-3, §15.81
diff --git a/docs/adr/README.md b/docs/adr/README.md
index 99ed77f..9c129f7 100644
--- a/docs/adr/README.md
+++ b/docs/adr/README.md
@@ -44,6 +44,7 @@
 | **47** | **운영자 판단(`resolve`)은 사람만 내고 누가 냈는지 인시던트에 남김. 에이전트는 거절하며, 결정자는 읽는 쪽이 생길 때까지 내보내지 않음** | [`0047`](0047-operator-decision-is-made-by-a-person.md) |
 | **48** | **작업 응답에 버전이 있는 바깥 형식을 두고(쓰기는 담는 쪽), 승인 응답과 실행 · 단계 단위 · 인스턴스 식별자로 이음. 승인 창구 버전 4** | [`0048`](0048-result-notice-has-an-outside-shape.md) |
 | **49** | **개정판 시험 3종(`CONTRACT` · `NEGATIVE` · `DETERMINISM`)의 뜻을 정하고, 실행기를 harness 운영 코드에 두어 registry 의 문 3개(요청 · 집기 · 보고)에 HTTP 로만 닿게 함. 보고는 집은 시각까지 대고, 검사 0개는 통과가 아님** | [`0049`](0049-revision-tests-have-a-runner.md) |
+| **50** | **임무 정의를 검증을 지나야 활성화되는 노드형 데이터로 두고, 실행은 생성 때 그 임무 버전을 쥐어 리비전까지 그 버전으로 끝냄. 설비 대기를 셋째 경로(`SIGNAL`)로 둠** | [`0050`](0050-mission-definition-is-versioned-data.md) |
 
 ---
 
@@ -60,4 +61,4 @@
 
 본 결정 기록들을 관통하는 핵심 엔지니어링 원칙은 **"조용한 통과(Silent Pass)는 명시적 실패보다 치명적이다"**라는 점입니다. 검증 도구가 자원 누락이나 스키마 불일치를 조용히 묵인하면 런타임 장애로 전이되므로, 모든 거버넌스 규칙은 결함 주입 시 즉각적이고 명시적으로 실패하도록 설계되었습니다.
 
-> 마지막 대조: 2026-10-08 · sha256:4c7125ee6bf1 · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:f4750c671704 · 열림: 없음
diff --git a/docs/architecture.md b/docs/architecture.md
index 2c0abaf..0f4a1ca 100644
--- a/docs/architecture.md
+++ b/docs/architecture.md
@@ -57,7 +57,7 @@ TaskHandle
 벤더 API 호출 (Orbit: POST dispatch · Spot: LoadMission+PlayMission · Digit: action-sequential · G1: SetVelocity)
 ```
 
-- **원자적 태스크 매핑**: 분해된 각 작업 단위(`ExecutionUnit`)는 1개의 계약 태스크(`Task`)로 일대일 매핑됩니다. 따라서 각 태스크의 완료 상태 자체가 공정 진행 상황을 반영하므로, 계약 내에 별도의 모호한 부분 완료 상태를 둘 필요가 없습니다.
+- **원자적 태스크 매핑**: 분해된 작업 단위(`ExecutionUnit`) 가운데 로봇 경로(`ROBOT`)의 단위는 1개의 계약 태스크(`Task`)로 일대일 매핑됩니다. 따라서 각 태스크의 완료 상태 자체가 공정 진행 상황을 반영하므로, 계약 내에 별도의 모호한 부분 완료 상태를 둘 필요가 없습니다. 플릿 경로(`FLEET`)의 단위는 운반 하나를 플릿에 위임하고, 설비 대기(`SIGNAL`)의 단위는 하위 요청 없이 이름 있는 신호를 기다리므로 둘 다 계약 태스크가 아닙니다.
 
 ### 2.2 계약 스킬과 벤더 API 표면 간의 매핑 구조
 
@@ -185,4 +185,4 @@ uplink                         → contracts
 5. [`commissioning.md`](commissioning.md) — 현장 시운전 절차 및 운영 설정 REST API 명세
 6. [공식 설계 문서](superpowers/specs/2026-09-05-picasso-design.md) — 시스템 전체 설계 정본 스펙
 
-> 마지막 대조: 2026-10-08 · sha256:18296c0982f2 · 열림: 시나리오 §8, §15.34, §15.5, ADR 32 · 시나리오 5, §1.3 B-1, §15.126
+> 마지막 대조: 2026-10-08 · sha256:1447e0042e80 · 열림: 시나리오 §8, §15.34, §15.5, ADR 32 · 시나리오 5, §1.3 B-1, §15.126
diff --git a/docs/glossary.md b/docs/glossary.md
index 7811df3..2edc859 100644
--- a/docs/glossary.md
+++ b/docs/glossary.md
@@ -75,6 +75,12 @@
 - `NEEDS_INTERVENTION`: 작업자의 물리적 현장 개입이 필수적인 오류.
 - `TERMINAL`: 재시도가 불가능한 영구적 실패.
 
+### 임무 정의 (Mission Definition) 와 임무 버전 (Mission Version)
+**임무 정의**는 WorkMaster 하나를 실행 단위로 펼치는 규칙을 노드형 JSON 으로 적은 것입니다. 노드는 단위 노드와 설비 대기 노드 둘이고 직선으로만 잇습니다. 검증(노드 id · 기한 · 신호 · 자원 · 스킬 · 안전)을 지나야 활성화됩니다. **임무 버전**(`missionVersion`)은 활성화마다 그 WorkMaster 안에서 1부터 오르는 번호입니다. 코드 케이퍼빌리티는 버전이 없습니다(null). 작업 지시의 버전(`JobOrder.version`, 단위의 `revision`)과는 다른 차원입니다. 실행은 생성 때 그 버전을 쥐고 리비전까지 그것으로 돌며, 인시던트 번들의 의도에 실리지만 내보내지는 않습니다. 이것을 주는 포트가 **임무 카탈로그**(`MissionCatalog`)이며, 계약의 `SkillCatalog`, registry 의 `GET /catalog`, 사이트 카탈로그와는 다른 것입니다.
+
+### 설비 대기 (Equipment Wait) 와 신호 사양 (Signal Specification)
+**설비 대기**는 이름 있는 설비 신호가 기대 값을 읽을 때까지 기다리는 노드이자 단위입니다(경로 `SIGNAL`). 기한과 기한 뒤 상태(`OPERATOR_HOLD` · `ABORTED`)를 들며, 판정은 신호의 지금 값으로 합니다. **신호 사양**은 현장 데이터로, 신호마다 이름 · 자리(선택) · 종류(`BOOLEAN` · `TEXT`) · 안전 여부를 적습니다. 주소 매핑은 없으며 드라이버의 일입니다. 안전 신호를 기다리는 대기는 검증기가 거부합니다(ADR 32). **이벤트형 신호**(짧게 켜졌다 꺼지는 신호)는 다루지 않으며 상태 신호만 다룹니다.
+
 ---
 
 ## 3. 상태머신 및 태스크 수명주기 (State Machines & Task Lifecycle)
@@ -283,4 +289,4 @@ OPC UA 기반의 단위 스킬 상태 모델:
 | 인계 | 공정 간 인계 | 실행기(로봇, 설비) 사이에서 작업물을 넘기는 것 |
 | 인계 | 핸드오프 | 다른 시스템이나 저장소로 산출물을 넘기는 것 |
 
-> 마지막 대조: 2026-10-08 · sha256:66574742c455 · 열림: §15.185
+> 마지막 대조: 2026-10-08 · sha256:35da170bd721 · 열림: §15.185
diff --git a/docs/limits.md b/docs/limits.md
index b2fd9a8..78534d0 100644
--- a/docs/limits.md
+++ b/docs/limits.md
@@ -2,7 +2,7 @@
 
 본 문서는 `picasso` 미들웨어 아키텍처 및 구현 상에 존재하는 **알려진 한계(Known Limitations), 스코프 외 제외 항목, 기술 부채 및 해소 조건**을 체계적으로 추적 관리하기 위한 엔지니어링 레지스터입니다.
 
-설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 208 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
+설계 문서의 §15는 전체 변경 이력과 배경을 누적 기록하는 변경 이력이며 (번호가 209 까지 갔고), 본 문서는 현재 시점에서 유효한 오픈 항목 항목만을 분류하여 제공합니다. 해결 완료된 항목은 변경 이력에 `(닫힘)` 처리되고 본 레지스터에서는 정리됩니다.
 
 ---
 
@@ -71,6 +71,7 @@
 | §15.185 | **새로 지은 용어가 용어집을 비켜 가는 것을 기계로 못 막음** — 대조는 *이미 이름 지은 것*(승인 창구)이 부르는 자리마다 같은 말을 쓰는지, 그리고 옛 이름이 그 자리에 남았는지만 본다. 텍스트에서 조어를 알아볼 방법이 없어 **다음 용어의 첫 등장은 여전히 사람이 잡는다** — 이번 것도 밖에서 사람이 물어서 나왔다 | 밖을 향하는 문서가 쓰는 조어를 목록으로 들고 대조가 그 목록을 읽을 때. 다만 목록을 사람이 채우는 한 같은 구멍이 남으므로, 실제 해소는 텍스트가 용어를 정의에서 끌어 쓰는 구조가 설 때다 |
 | §15.190 | **공정 간 인계 안내문이 CI 의 대조 밖에 있음** — 공개 대상이 아니라 추적하지 않으므로 체크아웃에 없고, 안내문이 대는 런 식별자의 대조가 **고치는 기계에서만 돈다**. 없을 때는 무시 목록이 그것을 이름으로 드는지만 보므로 「실수로 지웠다」는 막지만 「낡았다」는 CI 가 못 잡음 | 안내문을 다시 추적하거나, 그 값을 추적되는 파일이 함께 들 때 |
 | §15.194 | **레지스터의 하위 범주를 기계가 반만 댄다** — 해소 조건이 밖의 주어를 용어로 대면 대조가 잡으나, 용어 없이 밖에 기대는 행(다른 항목의 계열만 가리키는 행이 그렇다)은 사람이 읽어야 하위 범주를 안다. §15.185 가 용어집에서 적은 것과 같은 모양임 | 행이 스스로 주어를 선언하고 대조가 그 선언을 읽을 때. 그때까지는 용어 대조가 시끄러운 쪽만 막음 |
+| §15.209 · id 겹침 | **작업 지시의 설비 id 와 대기 노드 id 가 같으면 단위 id 가 겹침**: 대기 단위의 id 는 노드 id 이고 반복 단위의 id 는 작업 지시의 설비 id 다. 둘이 같으면 리비전(단위 id 로 접음) · 인시던트(단위 id 로 찾음) · 작업 응답에서 한쪽이 가려진다. 검증기는 정의만 보므로 설비 id 를 모르고, 정의 안의 노드 id 중복만 거부함(`DUPLICATE_NODE_ID`) | 계획 시점(해석기 또는 작업 수락)에 단위 id 겹침을 거부하는 규칙을 지을 때. 설비 id 는 작업 지시가 와야 알므로 그 자리에서만 막을 수 있음 |
 
 ---
 
@@ -78,6 +79,11 @@
 
 | 출처 | 오픈 항목 과제 내용 | 해소 필요 조건 |
 |---|---|---|
+| §15.209 · 정의 둘 | **`DeliverContainer` · `InspectAsset` 이 코드 정의로 남음**: 데이터로 옮긴 것은 `PrepareSequencedRack` 하나이고 둘은 코드 케이퍼빌리티로 같은 카탈로그에 함께 선다. 옮기려면 반복 한 번에 노드 여럿, 단위 id 접미사(`.travel`), 작업 지시 파라미터 값(`mode`), 조건부 포함, 플릿 경로가 스키마에 더 있어야 함 | 그 둘을 데이터로 바꿔야 하는 소비자가 생길 때(ADR 9). 그때 스키마를 넓히고 코드 정의와의 동등성 시험을 같은 방식으로 붙임 |
+| §15.209 · 분기 | **임무 정의는 직선만**: 분기와 병렬이 없다. 운영 관리 화면 설계 제안 §6 · §11 결정 1 대로 5가지 의미(동시 전이 우선순위, 반복 횟수, 자원 획득 · 반납, 취소 · 재시작, 재시도 때 부작용 중복)를 먼저 정한 뒤에 연다 | 분기나 병렬을 실제로 쓰는 소비자가 생길 때(ADR 9). 그때 5가지 의미부터 정함 |
+| §15.209 · 스키마 파일 | **임무 정의의 JSON Schema 파일이 없음**: 정의의 모양과 형 규칙은 `MissionDefinitionParser` 가 소유한다. 편집기용 스키마 파일은 S3 의 picasso-ops JSON 편집기에서 정함 | 소비자인 picasso-ops JSON 편집기가 S3 에서 스키마 파일을 요구할 때(ADR 9). 그때 파서와 같은 규칙인지 대조하는 시험을 함께 둠 |
+| §15.209 · 이벤트형 신호 | **이벤트형 신호를 못 기다림**: 설비 대기의 판정은 신호의 지금 값이라 상태 신호만 다룬다. 짧게 켜졌다 꺼지는 이벤트형 신호는 폴링 사이에 놓치며, PLC 쪽 래치 비트나 카운터가 있어야 폴링이 놓치지 않는다(`CellMimic` KDoc, 보고서 12.2). 관측 시각은 자취에만 쓰고 판정에는 안 씀 | 이벤트형 신호를 기다려야 하는 정의를 쓰는 소비자가 생길 때(ADR 9). 그때 관측 시각으로 시작 전 신호를 거르는 규칙과 래치 전제를 함께 정함 |
+| §15.209 · 임무 버전 내보내기 | **임무 버전이 밖으로 안 나감**: `Intent.missionVersion` 은 프로세스 안 인시던트 번들에만 있고 해시에 들지만 내보내는 인시던트 줄에는 싣지 않음(§15.202 의 결정자와 같은 처리). narrator 에는 읽을 자리를 묻지 않았음 | 읽는 쪽(narrator)이 임무 버전을 읽을 자리를 요청할 때(ADR 9). 그때 조회 버전을 올리고 의도(`intent`)의 칸으로 실으며 인계 번들을 다시 산출 |
 | §15.208 | **퇴역 기체가 `GET /catalog` 의 가용 수에 세어짐** — 퇴역은 바인딩을 풀지 않고, 카탈로그는 활성 바인딩으로 센다. 첫 소비자(picasso-ops S1d)는 `/catalog` 를 읽지 않는다 | `/catalog` 로 배정을 판단하는 소비자가 생길 때(ADR 9). 그때 퇴역 기체를 셈에서 빼거나 퇴역이 바인딩을 풀게 한다 |
 | §15.202 | **운영자 판단의 결정자가 밖으로 안 나감** — `resolve` 가 결정자를 받아 인시던트의 판단 기록에 남기지만(ADR 47), 내보내는 인시던트 줄에는 싣지 않음. 인시던트 번들을 읽는 쪽은 사람이 판단했다는 것과 무엇을 언제 판단했는지는 알지만 누구인지는 모름 | 읽는 쪽이 결정자를 쓰겠다고 할 때(ADR 9). 그때 조회 버전을 올리고 인시던트의 `approvedBy` 와 같은 `{id, kind}` 모양으로 실으며 인계 번들을 다시 산출 |
 | §15.181 | **골든셋의 설계가 코퍼스에 적재된 설계 일지에 그대로 있음** — 읽는 쪽은 코퍼스를 검색하므로 벤더 문서를 안 읽고 설계 일지를 읽어도 같은 답이 나옴. 근거로 원인을 추린 답과 설계 문서를 되읽은 답이 구별되지 않으며, 그 둘을 가르려고 만든 사건에서 그 구분이 사라짐. 실측으로는 지금 점수에 영향이 없으나(일지를 인용한 판들이 오히려 못 맞힘) 거짓 통과를 만들 수 있는 자리임. **일지는 삭제하지 않는 기록이라 뺄 수 없음**. **2026-09-23 에 한 번 넓어졌다** — §15.188 이 「사건 뒤의 탐색」 짝을 서술하면서 그 짝을 재는 항목의 정답을 그대로 적었고, 같은 문구가 용어집에도 들어갔다가 빠졌음. **2026-09-23 실측으로 좁혀짐** — 읽는 쪽의 사건 질의는 `spec`·`design_doc` 을 **필터로 빼고 있었고** 기록 다섯 판에서 그 필터가 실제로 먹었음. 일지가 근거에 든 것은 도메인 밖 질의뿐임. 그러므로 **채점 경로에서는 이 누수가 열려 있지 않았고**, 「설계 명세를 문서 단위로 빼는 것이 유일한 수단」이라고 적었던 앞 판의 문장은 과했음. 남는 위험은 그 필터가 없는 경로임 | 받는 쪽이 설계 일지를 근거로 든 답을 해당 지표에서 제외. 출처를 보고 하는 판정이므로 본 저장소에서는 막을 수 없고, 문서가 하나 더 새는 것만 시험이 막음 |
@@ -120,6 +126,7 @@
 | §15.163 | **작업 구역의 판정이 성김** — 같은 구역의 두 기체를 동시에 통과시키지 않으나(§15.163), 구역 안에서 실제로 겹치는지는 보지 않음. 세밀하게 가르려면 기하가 필요하고 이 계층에는 없음 | 실제 하드웨어 두 기종이 붙고 좌표를 다루는 소비자가 생길 때. 그전까지는 구역을 잘게 나누는 것이 현장의 수단 |
 | §15.106 · 기종 | 발견된 전체 기체에 단일 프로파일 일괄 적용 | 플릿 API가 기체별 기종 정보를 반환하도록 연동 확장 |
 | §15.203 | **작업 응답을 나르는 실제 하드웨어 담는 측이 없음** — 바깥 형식(`ResultExport`, 버전 1)과 승인 응답의 잇는 칸은 섰고(ADR 48), 자리는 바깥 루프가 정함(현장 운영 측이 소유하는 별도 배치 저장소, `picasso` 는 라이브러리, 2026-10-04). 지금 소비자가 받는 것은 실행기의 참조 한 벌뿐이며 그것은 상위 확인을 안 함 | 실제 하드웨어 현장이 생겨 그 배치 저장소가 담는 측을 세울 때. 그때 `pending()` 으로 꺼내 나르고 `ack` 로 닫는 주기와 안내 파일을 정함. 작업 응답 파일은 앞을 자르지 않는 전체 이력(orchestration §8) |
+| §15.209 · 주소 | **신호 사양이 주소를 모름**: 신호 사양은 이름과 성질(자리 · 종류 · 안전 여부)까지이고, 어느 PLC 주소가 어느 신호인지의 매핑은 드라이버(`CellSignals` 구현체)의 일이라 저장소에 없다. 실제 하드웨어 PLC 와의 신호 대조도 없음(운영 관리 화면 설계 제안 §9 «시운전 신호 대조 전까지 주장») | 실제 하드웨어 PLC 에 붙는 드라이버가 생길 때. 그때 현장 시운전에서 신호 사양과 주소를 대조함 |
 
 ---
 
@@ -128,6 +135,6 @@
 - 항목이 해소되면 설계 문서 §15의 해당 번호에 `(닫힘)`을 명시하고 본 레지스터에서 항목을 정리합니다.
 - 신규 발견된 한계는 설계 문서 §15 변경 이력에 우선 등록한 후 본 레지스터에 분류 편입합니다.
 - 하위 범주는 **해소의 주어**로 정합니다. 해소 조건에 적은 주어와 하위 범주가 어긋나면 `DocumentClaimsTest` 가 막습니다. 다만 주어를 용어로 부르지 않는 행은 기계가 못 가리므로 사람이 읽어야 합니다(§15.194).
-- 밖을 향한 문서가 드는 열림은 **132** 개다. 각 문서 하단 스탬프에 기재된 오픈 항목 ID 총합은 본 수치와 엄격히 일치해야 합니다 (`CompletionCriterionTest` 집행).
+- 밖을 향한 문서가 드는 열림은 **139** 개다. 각 문서 하단 스탬프에 기재된 오픈 항목 ID 총합은 본 수치와 엄격히 일치해야 합니다 (`CompletionCriterionTest` 집행).
 
-> 마지막 대조: 2026-10-08 · sha256:afcb6ec1636e · 열림: 없음
+> 마지막 대조: 2026-10-08 · sha256:f9fddbf0b273 · 열림: 없음
diff --git a/docs/orchestration.md b/docs/orchestration.md
index 148f669..8b2be24 100644
--- a/docs/orchestration.md
+++ b/docs/orchestration.md
@@ -38,6 +38,8 @@ ADR 38 은 L2 를 내재화한 결정이며, 밖에 남긴 것은 L3 입니다.
 | 조치의 자동 승인 권한 | 현장 — 선언하는 사람 (ADR 43·44) | `Middleware.attemptApproval` → `scopeRefusal` — 선언 없는 승인 시도를 거절하고, 값도 선언과 관측에서만 채운다. 사람의 문(`Middleware.approveRemedy`)은 값을 들고 오므로 선언을 안 본다 | 있음 (§15.3 — 식별 정보는 위조 가능하며 부인방지가 아니라 조사 단서다) |
 | 상위 작업 순서 | MES | MES 측 | 있음 (계약 밖) |
 | 셀 설비의 상태 관측 | 셀 설비 | `CellSignals.observe` — `null` 은 빈 자리가 아니라 **말이 없음** | 있음 |
+| 셀 설비의 이름 있는 신호 | 셀 설비 | `CellSignals.signal` — `null` 은 기대 값이 아님이 아니라 **못 읽음** 이다. 설비 대기는 기다리고 기한이 판정하며, 주소 매핑은 드라이버의 일 | 있음 |
+| 임무 정의의 활성 버전 | 본 저장소 | `InMemoryMissionCatalog.activate` → `MissionValidator.validate` — 활성화는 검증(노드 id · 기한 · 신호 · 자원 · 스킬 · 안전)을 지나야 서고 거부면 활성 버전이 그대로다. 새 작업 지시부터 적용되며 도는 실행은 쥔 버전으로 끝난다. 소유는 활성화 판정의 소유이고 정의를 쓰는 사람은 시운전 · 통합 엔지니어다 | 있음 |
 | 자리 이름 ↔ 좌표 결속 | 기체 세계 모델 또는 상위 사이트 정본 (ADR 34 §4) | 기체 질의 (ADR 35) 앞에 `SiteBindingCheck` — 지도 버전과 개체 캘리브레이션 버전 두 차원을 대조 | 있음 (§15.156 — 정본 구현체는 배치가 붙인다) |
 
 **관문 칸이 대는 기호는 코드와 대조됩니다** — `DocumentClaimsTest` 의 «자원 소유 대장이 대는 관문이 코드에 실재한다». 이름이 바뀌거나 사라지면 이 표가 아니라 그 시험이 먼저 빨개집니다.
@@ -210,10 +212,10 @@ flowchart TB
 | 인시던트가 드는 것 | 어느 기체(`robotId`) · 근거의 세기(`requiredEvidence`·`reachedEvidence`·`verification`) · 분류의 근거(`fault`) · 단계 위치(`step`)를 함께 적습니다. **읽는 쪽이 되짚지 않도록 하는 것이 기준입니다**(§15.177) |
 | 결함 | `fault` 는 이 단위의 분류가 그 값이 된 근거이고, `blockedBy` 는 **다음 단위를 막는** 결함입니다. 다음 단위가 없으면 후자는 빈 목록이므로 둘은 다른 차원입니다. 정준 분류와 벤더 원문을 함께 실으며, 분류 이름만으로 접지 않습니다 |
 | 의도 | `intent` 가 **상위가 적은 것과 이 계층이 편 것을 함께** 싣습니다. 앞은 작업 마스터·버전·작업 지시 값·자재·설비이고 뒤는 스킬·단위 값·출발·도착·기대 식별자이며, 둘이 갈리는 자리가 곧 이 계층의 번역입니다. 케이퍼빌리티의 최고 등급과 시간 윈도우 폭도 함께 실어 `evidenceWindow` 가 **왜 그 범위였는지**를 답합니다 |
-| 경로 | `route` 가 `ROBOT` 또는 `FLEET` 입니다. **없으면 책임 소재를 가르지 못합니다** — 같은 실패라도 앞은 기체와 어댑터의 일이고 뒤는 플릿의 일이며, 다음에 누구에게 물을지가 그것으로 갈립니다 |
+| 경로 | `route` 가 `ROBOT` · `FLEET` · `SIGNAL` 중 하나입니다. `SIGNAL` 은 설비 대기이며 하위 요청 없이 이름 있는 신호를 기다린 단위이므로 책임 소재는 셀 설비와 현장 쪽입니다(버전 `6` 에서 생긴 하위 범주). **없으면 책임 소재를 가르지 못합니다** — 같은 실패라도 앞은 기체와 어댑터의 일이고 뒤는 플릿의 일이며, 다음에 누구에게 물을지가 그것으로 갈립니다 |
 | 관측 신뢰 | `observation` 이 `linkBroken` · `lateEvents` · `progressObservable` · `progressStalled` 를 싣습니다. **`progressObservable` 은 3값이며 널이 「아직 갱신을 못 봤다」입니다** — 거짓으로 접으면 못 물어본 것이 못 재는 것이 되고, 진행률을 내지 않는 기종이 언제나 정지한 것으로 보입니다 |
 | 단계 위치 | `step` 은 `at`(1부터) · `plan`(순서대로) · `completed` 입니다. **총수는 `plan` 의 길이이며 따로 싣지 않습니다** — 같은 사실을 두 칸에 두면 갈릴 자리가 생깁니다 |
-| 버전 | `schemaVersion` 이 `5` 입니다. `2` 에서 `blockedBy` 가 분류 이름의 배열에서 결함 객체의 배열로 **모양이 바뀌었으므로** 그 버전은 읽는 쪽이 보아야 하며, `3` 은 칸이 는 것뿐입니다. `4` 는 인시던트 줄을 바꾸지 않고 **조치 탐색 기록의 `outcome` 에 하위 범주를 하나 더합니다** — 칸이 느는 것과 달리 `outcome` 으로 분기하는 읽는 쪽은 모르는 값을 만나므로 그 자리만 버전을 보면 됩니다 · `5` 는 인시던트 줄에 `resolution`(사람이 그 단위에 낸 판단) 한 칸을 더합니다 — 칸이 느는 것이라 읽는 쪽이 안 보아도 깨지지 않고, **요약은 안 바뀝니다**(해시에서 빠지는 셋째 칸입니다) |
+| 버전 | `schemaVersion` 이 `6` 입니다. `2` 에서 `blockedBy` 가 분류 이름의 배열에서 결함 객체의 배열로 **모양이 바뀌었으므로** 그 버전은 읽는 쪽이 보아야 하며, `3` 은 칸이 는 것뿐입니다. `4` 는 인시던트 줄을 바꾸지 않고 **조치 탐색 기록의 `outcome` 에 하위 범주를 하나 더합니다** — 칸이 느는 것과 달리 `outcome` 으로 분기하는 읽는 쪽은 모르는 값을 만나므로 그 자리만 버전을 보면 됩니다 · `5` 는 인시던트 줄에 `resolution`(사람이 그 단위에 낸 판단) 한 칸을 더합니다 — 칸이 느는 것이라 읽는 쪽이 안 보아도 깨지지 않고, **요약은 안 바뀝니다**(해시에서 빠지는 셋째 칸입니다) · `6` 은 인시던트 줄의 `route` 에 하위 범주 `SIGNAL`(설비 대기)을 하나 더합니다. 칸은 늘지 않았으나 `route` 로 분기하는 읽는 쪽은 모르는 값을 만나므로 `4` 와 같은 이유로 버전을 보아야 합니다. 인시던트의 임무 버전(`Intent.missionVersion`)은 해시에 들지만 읽는 쪽이 없어 내보내지 않습니다(ADR 9). 그래서 **모든 줄의 요약(`digest`)이 바뀝니다**(코드 케이퍼빌리티의 줄도 포함). 읽는 쪽이 `digest` 를 멱등성 키로 쓰면 다시 산출한 세트를 새 인시던트로 받습니다 |
 | 번들의 해시 | `digest` 를 함께 적습니다. 이미 계산되는 값이며, 읽는 쪽이 같은 인시던트를 두 번 받았는지 가르는 유일한 결정적 조회 키입니다 |
 | 한 세트의 명세 | `manifest.json` 이 `schemaVersion` · `runId` · `writtenAt` · `virtualNow` · `contractSemver` · `counts` 를 싣습니다 |
 | 런 식별자 | `runId` 는 **이 내보내기에서 유일하게 결정적이지 않은 값**입니다. 나머지는 같은 시드면 같은 값이고 그것이 재현의 근거인데, 읽는 쪽이 멱등성 키를 그 값들로 들면 같은 시드의 두 번째 구동이 전부 이미 본 것으로 접혀 **아무 신호 없이** 처리가 사라집니다. 실제 시계에서 나오며 같은 순간에도 겹치지 않습니다. **결정적으로 바꾸면 그 고장이 조용히 돌아옵니다** |
@@ -367,4 +369,4 @@ flowchart TB
 
 **담는 측이 지킬 조건으로, `job-responses.jsonl` 은 그 인스턴스의 처음부터의 전체 이력이어야 하고 확인된 통보를 앞에서 잘라 내면 안 됩니다.** koshei 는 첫 줄의 `instanceId` 로 어느 구동인지를 읽기 때문입니다. 참조 실행기는 이 조건을 지킵니다(확인된 것까지 포함한 작업 응답 전체를, 개수가 늘 때만 원자적으로 다시 씁니다).
 
-> 마지막 대조: 2026-10-06 · sha256:04ced2b8a5a1 · 열림: §15.143, §15.154, §15.162, §15.163, §15.3, §15.166, §15.173, §15.174, §15.175, §15.176, §15.178, §15.183, §15.184, §15.203
+> 마지막 대조: 2026-10-08 · sha256:d5f7fdb15735 · 열림: §15.143, §15.154, §15.162, §15.163, §15.3, §15.166, §15.173, §15.174, §15.175, §15.176, §15.178, §15.183, §15.184, §15.203
diff --git a/docs/scenarios.md b/docs/scenarios.md
index f5a1878..f8c5d11 100644
--- a/docs/scenarios.md
+++ b/docs/scenarios.md
@@ -66,7 +66,7 @@
 </picture>
 
 - **상위 시스템의 결과 중심 지시**: 상위 시스템은 특정 로봇 기종이나 스킬 시퀀스를 지정하지 않고, 작업 목표(`workMasterId`, `EquipmentRequirement`, 요구 근거 등급)만을 전달합니다.
-- **`LogicalCapability` 기반 분해**: 각 케이퍼빌리티 구현체는 수신된 `JobOrder`를 원자적 실행 단위(`ExecutionUnit`) 목록으로 분해하며, 공통 미들웨어 엔진 코드는 신규 케이퍼빌리티 추가 시에도 무수정(Zero-modification) 상태를 유지합니다.
+- **`LogicalCapability` 기반 분해**: 각 케이퍼빌리티 구현체는 수신된 `JobOrder`를 원자적 실행 단위(`ExecutionUnit`) 목록으로 분해합니다. 케이퍼빌리티는 `LogicalCapability.plan()` 으로만 엔진에 닿으므로 ③ `InspectAsset` 처럼 단위 시퀀스로 펼치는 케이퍼빌리티를 더할 때는 엔진 코드를 수정하지 않았고, 임무 정의(데이터)에서 온 케이퍼빌리티도 `DefinedCapability` 가 같은 `LogicalCapability` 로 바꿔 같은 경로를 탑니다. 다만 단위의 종류를 늘리는 것은 다릅니다. 설비 대기(ADR 50)는 셋째 경로 `SIGNAL` 을 더해 진행 · 리비전 · 미확정 해소 · 작업 응답의 경로 분기가 엔진에서 바뀌었습니다.
 - **사전 요구조건 검증**: 요구된 근거 등급이 해당 케이퍼빌리티의 지원 상한을 초과할 경우, 검증 불가능한 작업으로 판정하여 작업 수락 단계에서 즉시 요청을 거절합니다.
 
 ---
@@ -397,4 +397,4 @@ sequenceDiagram
 - 계층 ② 의 어휘(논리적 케이퍼빌리티의 이름). 외부 문서는 이름을 붙였으나 *프로젝트 정의*라고 표시했고, 이 저장소는 ADR 36 에 따라 상위나 벤더가 이미 가진 것만 들인다. `WorkMasterID` 자리에 무엇이 오는지는 실제 상위를 만나야 안다.
 - AMR 플릿의 계약. §1 의 경계 규칙 그대로다.
 
-> 마지막 대조: 2026-10-06 · sha256:c1d1325e6bcd · 열림: ADR 32 · 시나리오 5, 시나리오 §8, §15.126, §15.87, §15.81, §15.8, C-3
+> 마지막 대조: 2026-10-08 · sha256:86e82aa51525 · 열림: ADR 32 · 시나리오 5, 시나리오 §8, §15.126, §15.87, §15.81, §15.8, C-3
diff --git a/docs/seams.md b/docs/seams.md
index 5640bde..5e924e8 100644
--- a/docs/seams.md
+++ b/docs/seams.md
@@ -36,12 +36,13 @@
 
 ### 2. 현장 설비 센서 신호 (PLC/WCS)
 
-**인터페이스**: `CellSignals.observe(location): SlotSignal?` (null 반환 시 '신호 없음'으로 처리)
+**인터페이스**: `CellSignals.observe(location): SlotSignal?` (null 반환 시 '신호 없음'으로 처리) · `CellSignals.holding(material): List<String>?` (기본 null — 그 질문에 응답하지 않는 설비) · `CellSignals.signal(name): NamedSignal?` (기본 null — 이름 있는 신호를 모르는 설비)
 
 - **실제 하드웨어 전환 작업**:
-  - 현장 PLC의 OPC UA 노드 또는 무전압 I/O 접점 상태를 폴링하여 `SlotSignal(identity, observedAt, latched)` 객체로 변환하는 구현체를 제공합니다.
+  - 현장 PLC의 OPC UA 노드 또는 무전압 I/O 접점 상태를 폴링하여 `SlotSignal(occupied, identity, observedAt)` 객체로 변환하는 구현체를 제공합니다.
   - 설비 신호 폴링 주기 및 하드웨어 래치 정책은 현장 환경에 맞춰 구현하며, 시간 윈도우 δ는 논리적 케이퍼빌리티 파라미터로 설정합니다.
-- **불변 유지 대상**: 근거 결합 엔진 규칙 전체 (시간 윈도우 판정, 센서 재확인, `UNVERIFIED`, `VERIFICATION_MISMATCH` 처리 로직).
+  - `CellSignals.signal(name)` 은 신호 사양의 이름으로 그 신호의 지금 값을 돌려주는 구현체를 제공합니다. 어느 PLC 주소가 어느 신호인지의 매핑은 이 구현체(드라이버)의 일이며, 본 계층은 이름으로만 읽습니다. `null` 은 «기대 값이 아님» 이 아니라 «못 읽음» 이므로 설비 대기는 계속 기다리고 기한이 판정합니다. 상태 신호만 다루며, 짧게 켜졌다 꺼지는 이벤트형 신호는 PLC 쪽 래치가 있어야 폴링이 놓치지 않습니다 (한계 §15.209).
+- **불변 유지 대상**: 근거 결합 엔진 규칙 전체 (시간 윈도우 판정, 센서 재확인, `UNVERIFIED`, `VERIFICATION_MISMATCH` 처리 로직), 설비 대기 판정(지금 값 · 기한 · 기한 뒤 상태).
 
 ### 3. AMR 플릿 관리 시스템
 
@@ -112,11 +113,25 @@
 
 ---
 
+## 데이터 접합부 — 임무 정의 카탈로그 (Mission Catalog)
+
+**인터페이스**: `MissionCatalog.active(workMasterId): ActiveMission?` (기본 구현은 코드 케이퍼빌리티 셋, 버전 없음)
+
+지금 구현은 둘입니다. `MissionCatalog.of(codeCapabilities)` 는 기본 구현으로 코드 케이퍼빌리티만 버전 없이 돌려주고, `InMemoryMissionCatalog` 는 코드 케이퍼빌리티와 데이터 정의를 함께 들며 활성화가 검증을 통과하면 그 WorkMaster 의 다음 버전을 활성으로 세웁니다(스레드 안전). 저장과 이력은 picasso-ops 호스트가 붙입니다(S3).
+
+- **데이터 공급 구현 전환 작업**:
+  - 정의 문서와 버전 이력을 영속 저장에 두는 구현체를 제공합니다.
+  - 활성화 전에 `MissionValidator.validate` 를 같은 규칙으로 부릅니다. 검증기 입력(신호 사양, 바닥 소유, 현장 기체가 제공하는 스킬)은 호스트가 줍니다.
+- **불변 유지 대상**: 엔진 규칙(실행은 생성 때 케이퍼빌리티와 임무 버전을 쥐고 리비전도 그것으로 돈다), 해석기(`DefinedCapability`)와 검증기의 규칙.
+
+---
+
 ## 색인 — 자리와 인터페이스
 
 | 자리 | 인터페이스 | 어디 |
 |---|---|---|
 | 설비 | `CellSignals` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
+| 임무 정의 | `MissionCatalog` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
 | 플릿 | `AmrFleetPort` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
 | 로봇(소비자) | `RobotPort` | `picasso/src/main/kotlin/dev/picasso/middleware/Ports.kt` |
 | 벤더 — Spot | `SpotLink` | `adapter-boston-dynamics-spot/src/main/kotlin/dev/picasso/adapter/spot/SpotLink.kt` |
@@ -134,4 +149,4 @@
 
 > **검증 보증:** 본 색인 테이블의 인터페이스명 및 파일 경로는 `DocumentClaimsTest`를 통해 실제 소스 코드와 상시 대조 검증됩니다.
 
-> 마지막 대조: 2026-10-06 · sha256:4a1e0037f3a9 · 열림: §15.34, C-3, §15.5, §15.156
+> 마지막 대조: 2026-10-08 · sha256:38bd25e33bc2 · 열림: §15.34, C-3, §15.5, §15.156
diff --git a/docs/superpowers/specs/2026-09-05-picasso-design.md b/docs/superpowers/specs/2026-09-05-picasso-design.md
index 0601ce0..5909ad9 100644
--- a/docs/superpowers/specs/2026-09-05-picasso-design.md
+++ b/docs/superpowers/specs/2026-09-05-picasso-design.md
@@ -1104,10 +1104,11 @@ mimic/
 | [47](../../adr/0047-operator-decision-is-made-by-a-person.md) | 운영자 판단은 사람만 내고 누가 냈는지 사건에 남기며, 판단자는 읽는 쪽이 생길 때까지 내보내지 않음 | §15.202 |
 | [48](../../adr/0048-result-notice-has-an-outside-shape.md) | 결과 통보에 판이 있는 바깥 형식을 두고, 승인 답과 실행 · 걸음 단위 · 인스턴스 식별자로 이음 | §15.203 |
 | [49](../../adr/0049-revision-tests-have-a-runner.md) | 개정판 시험 3종의 뜻을 정하고, 실행기를 harness 에 두어 registry 의 문 3개(요청 · 집기 · 보고)에 HTTP 로만 닿게 함 | §15.207 |
+| [50](../../adr/0050-mission-definition-is-versioned-data.md) | 임무 정의를 검증을 지난 데이터로 두어 판 번호를 매기고, 실행은 생성 때 쥔 임무 판으로 끝냄. 설비 대기를 셋째 경로로 둠 | §15.209 |
 
 기록은 `docs/adr/`에 있고 번호가 이 표의 행 번호다. **이미 내려서 코드에 박힌 것만 쓴다** — 3a·3b가 만들 것(11~21)은 그때 쓴다. 결정하지 않은 것을 미리 적어 두면 그것이 결정처럼 보인다.
 
-> 마지막 대조: 2026-10-08 · sha256:a0f4bd977d83 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87
+> 마지막 대조: 2026-10-08 · sha256:30af927964c1 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87
 
 ## 15. 알려진 한계
 
@@ -3098,6 +3099,18 @@ mimic/
 
     **검사 9번이 결속을 어댑터 경계 안에 가둔다.** 탐색어는 결속 파일이 선언한 최상위 이름에서 유도하므로 타입을 더하면 금지도 저절로 는다. 훑는 모듈에 `registry` 와 `profile-model` 을 넣었다 — 검사 7의 목록에 그 둘이 없어서, 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.
 
+209. **임무 정의를 검증을 지나야 활성화되는 데이터 판으로 옮기고, 설비 대기를 셋째 경로로 더했으며, 실행이 생성 때 쥔 임무 판으로 개정판까지 끝나게 했다.**
+
+    임무 정의는 코드였다. `PrepareSequencedRack.plan()` 이 주문의 destination 마다 `pick_place` 단위 하나를 내고, 제시 자리는 material 로 짝을 지어 뒤엣것이 이기며, 짝이 없으면 계획 때 `FAILED`(`NO_SOURCE_FOR_MATERIAL`)였다. `Middleware` 는 케이퍼빌리티 목록을 workMasterId 로 묶은 맵을 생성 때 쥐었고 실행 중에 바꿀 길이 없었다. 임무 판 칸은 없었다. 실행은 케이퍼빌리티 객체를 쥐었으나 `submit` 이 개정판으로 가기 전에 그 맵을 다시 읽었다. 경로는 `ROBOT`·`FLEET` 둘이었고 진행 루프의 분기는 «로봇이 아니면 플릿» 이었다. 셀 신호 포트에는 이름으로 읽는 신호가 없었다. 바깥 첫 소비자 picasso-ops 의 운영 관리 화면 설계 제안 §10 입증 항목 3(임무 하나를 데이터 판으로: 모의 실행 → 활성화 → 도는 실행 중 새 판 전환 → 옛 실행은 옛 판으로 끝남)·4(신호 사양에 없는 신호를 참조하는 변경이 활성화에서 거절됨)와 P3 스펙(`docs/superpowers/specs/2026-10-08-p3-mission-definition-versions-design.md`, picasso-ops 저장소)이 이것을 정했다. 2026-10-08 사용자 결정은 임무 판을 먼저 picasso 에 짓고 picasso 시험 위에서 입증하며 노드형 스키마와 설비 대기까지 엔진에 구현하는 것이다. 소비자가 생겨서 지었다(ADR 9, ADR 50).
+
+    패키지 `dev.picasso.middleware.mission` 을 두었다. 정의 스키마 `MissionDefinition`(노드 `unit`·`wait`, 직선만, `schemaVersion` 1), 엄격 파서 `MissionDefinitionParser`(모르는 키·빠진 칸·형 틀림을 모두 모아 한 번에, `JsonFormat` → `Struct`, 수는 정수 검사), `StrictJson`(손으로 쓴 문법·중복 키 검사, `JsonFormat` 은 중복 키에 말없이 뒤엣것을 쓴다), 신호 사양 `SignalSpec`(이름·자리·종류·안전 여부), 해석기 `DefinedCapability`(정의 → `LogicalCapability`), 검증기 `MissionValidator`(노드 id 중복 + 기한·신호·자원·스킬·안전), 거절 `MissionRefusal` 9종(종류가 해결 담당과 바로 갈 작업을 정하고 바닥 소유만 화면 밖), 메모리 카탈로그 `InMemoryMissionCatalog`(코드 케이퍼빌리티와 데이터 정의가 함께, `synchronized`, 활성화는 검증 통과 시 WorkMaster 마다 1부터 오르는 판, 거절은 번호를 안 씀)다. 데이터로 옮긴 것은 `PrepareSequencedRack` 하나이고 코드 클래스는 동등성 기준으로 남는다. 설비 대기는 셋째 경로 `Route.SIGNAL` 이다. `WaitSpec(signal, expect, deadline, onDeadline)` 이 단위에 붙고 `pumpSignalUnit` 이 돈다. 판정은 신호의 지금 값이다. 기대 값이면 `DONE`·E2, null 이면 계속 기다리고, 기한을 넘으면 사건(`SIGNAL_DEADLINE`)을 낸 뒤 `OPERATOR_HOLD` 는 대기 단위가 운영자 보류로 서고 `ABORTED` 는 대기 단위 `FAILED`·남은 단위 `ABORTED`·실행 중단이며 취소 답은 남기지 않는다. 진행 루프의 경로 분기를 경로별 `when` 으로 바꾸고 중단 뒤 같은 라운드 반환 가드를 두었다. `CellSignals.signal(name)` 을 기본 null 로 더했고(기존 구현체 안 깨짐) `CellMimic` 에 `setSignal`·`clearSignal` 을 두었다.
+
+    포트 `MissionCatalog` 를 middleware 패키지에 두고 `Middleware` 생성자 맨 뒤에 `missions` 를 더했다(기본은 코드 케이퍼빌리티 셋, 판 없음, `capabilities` 와 함께 주면 `require` 실패). `Execution.missionVersion` 을 생성 때 고정한다. 이미 있는 실행이면 `submit` 은 카탈로그를 안 보고 개정판으로 가고, `revise` 는 실행이 쥔 케이퍼빌리티로 계획·근거 등급을 검사하며 WorkMaster 를 바꾸면 거절한다. `adopt` 는 카탈로그를 한 번 읽은 쌍을 접수까지 넘긴다. 사건의 `Intent.missionVersion` 은 실행이 쥔 판이고 해시에 든다. 내보내기에는 싣지 않는다. 내보내기 판은 5 에서 6 이 됐고 6 의 변경은 사건 줄 `route` 의 `SIGNAL` 하나다(칸은 안 늘었다). ADR 47 이 «실으면 조회 판이 6 으로 오르고» 라고 적은 6 을 이번에 `route` 가 썼다. 인계본 run-1..4 를 다시 산출했다. manifest `schemaVersion` 6, 새 runId, 해시에 임무 판 자리가 생겨 모든 사건 줄의 digest 가 바뀌었고 run-1·run-2 의 digest 는 서로 같으며 나머지 칸은 `wallClockAt`·`resolution.wallClockAt` 말고 같다.
+
+    시험 46개를 더해 총수가 1,931 에서 1,977 이 됐다. `MissionDefinitionParserTest` 9, `DefinedCapabilityEquivalenceTest` 6(주문 모양 9가지 × 10칸 + 통째), `MissionValidatorTest` 12, `InMemoryMissionCatalogTest` 5, `EquipmentWaitTest` 8, `MissionVersionScenarioTest` 6 다. 기존 시험 중 셋을 고쳤다. `EvidenceWindowTest` 의 «5» 를 «6» 으로, `LedgerExportTest` 의 해시 칸 목록에 «임무 버전» 행을, `CompletionCriterionTest` 의 65 를 66 으로다. 그 밖의 기존 시험은 고치지 않고 통과했다. 결함 주입 22건이 모두 이름 있는 시험으로 잡혔다. 해석기: 짝 짓기 규칙을 앞엣것이 이기게. 설비 대기: 기한 비교를 기한 시각에 넘기게, ABORTED 반환 가드 제거, 남은 PENDING 을 ABORTED 로 안 바꿈, 기한 중단이 취소 답을 남김, 개정판이 설비 대기를 로봇처럼 갱신, 대기 중 취소를 안 봄, 신호 자취를 진행마다 남김. 검증기: 기한·신호·자원·스킬·안전·노드 id 검사를 하나씩 뺌. 판: 개정판이 카탈로그를 다시 봄, 개정판의 WorkMaster 변경 거부를 뺌, 실행이 판을 안 쥠, 번들에 판을 안 실음, 번들이 활성 판을 실음, 해시에서 임무 판 빼기. 파서: 중복 키를 안 봄. 배정: 읽은 쌍을 안 넘김. 처음 돌린 주입에서 하나가 안 잡혔다. 배정이 읽은 쌍을 안 넘겨도 한 스레드 시험에서는 카탈로그를 두 번 읽어 같은 답이 와 등가 변이였다. 첫 읽기와 둘째 읽기에 다른 판을 주는 카탈로그로 시험을 더해 잡았다. 번들이 활성 판을 실게 하는 주입은 처음 코드가 컴파일되지 않았다. 생성자 인자 `missions` 가 같은 이름의 속성을 가려 속성 초기화식 안에서는 인자가 잡히므로 `this@Middleware.missions` 로 넣어 잡았다.
+
+    남긴 것은 한계 §15.209 의 행들이다. 나머지 두 정의(`DeliverContainer`·`InspectAsset`)의 데이터 이전, 분기·병렬, 주소 매핑, 정의 JSON Schema 파일, 이벤트형 신호, 주문 설비 id 와 대기 노드 id 겹침, `missionVersion` 내보내기다. 운영 실행 호스트(§15.176)와 저장·이력은 S3(picasso-ops)다.
+
 208. **계약 스킬 종류를 기동 때 채우고 개정판 제출·활성화와 기체 바인딩의 조작 문 5개를 열었다.**
 
     `SkillTypeSync` 를 부르는 곳이 시험 소스뿐이었다. 운영 registry 의 `skill_type` 은 비어 있었다. `skill_type` 이 비면 개정판 제출(`RevisionService` 의 `insertSkills`)이 선언 스킬을 조용히 건너뛴다. 그러면 사이트 명칭 요구 집합이 비고 바인딩의 계약 semver 검사도 비교할 것이 없다. 주석의 «그 부재는 바인딩이 보고 막는다» 는 사실이 아니었다. 개정판 제출·활성화·바인딩에 REST 가 없었고 `RevisionService` 는 빈(bean)도 없었다. 옛 `BindingService.bind` 는 없는 기체를 FK 위반 500 으로 냈고, 퇴역 기체도 묶었고, 같은 조합을 다시 묶으면 새 행을 만들어 사이트 명칭 등록 기록이 «미등록» 으로 돌아갔다. 같은 기체의 동시 첫 바인딩은 유일 색인 `robot_binding_one_active` 위반 500 이었다. `/diag/bindings` 행은 어댑터 이름과 버전만 내서 «요청한 빌드로 묶였는가» 를 id 로 판정할 수 없었다. mimic CLI 로 띄운 기체에는 기체가 아는 사이트 명칭(`knownSiteNames`)을 넣을 길이 없었다(기본값이 빈 목록이라 명칭을 기록하면 `CONTRADICTED`). 바깥 첫 소비자 picasso-ops 의 P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소) §6 이 이것들을 P2b 로 정했다. 소비자가 생겨서 열었다(ADR 9).
diff --git a/docs/verification.md b/docs/verification.md
index e5c9385..c248748 100644
--- a/docs/verification.md
+++ b/docs/verification.md
@@ -1,6 +1,6 @@
 # 시스템 검증 충실도 및 환경 신뢰도 매트릭스 (Verification & Fidelity Matrix)
 
-본 문서는 `picasso` 미들웨어 시스템의 1,931개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
+본 문서는 `picasso` 미들웨어 시스템의 1,977개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.
 
 ---
 
@@ -45,4 +45,4 @@
 2. **로봇 인터페이스 계층의 격리성**: 어댑터 계층은 벤더 SDK 격리 원칙에 따라 매니페스트 대조를 통해 정합성을 검증하며, 실기체 직접 연동(C-3)은 환경적 제약으로 인해 오픈 항목 상태로 명시 관리됩니다.
 3. **상위 및 설비 연계 계층의 가정 기반성**: 설비(PLC) 및 AMR 플릿과의 연동 규격은 시스템적 일관성을 입증하기 위한 자체 설계 모델이며, 실제 현장 도입 시 대상 설비에 맞춘 Seam 어댑터 구현이 요구됩니다.
 
-> 마지막 대조: 2026-10-08 · sha256:98b68840f576 · 열림: C-3
+> 마지막 대조: 2026-10-08 · sha256:31de97d48e01 · 열림: C-3
diff --git a/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt b/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
index 6e395ec..74723e9 100644
--- a/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
+++ b/gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
@@ -6,11 +6,11 @@ import kotlin.test.assertEquals
 class CompletionCriterionTest {
 
     @Test
-    fun `주장의 자리가 예순다섯이다`() {
+    fun `주장의 자리가 예순여섯이다`() {
         // **세어서 적은 것을 다시 센다.** 자리가 늘거나 줄면 이 수가 먼저 빨개지고,
         // 그때 스펙 §2 를 다시 읽어야 한다.
         val docs = ClaimSurface.documents()
-        assertEquals(65, docs.size, docs.joinToString("\n") { ClaimSurface.relative(it) })
+        assertEquals(66, docs.size, docs.joinToString("\n") { ClaimSurface.relative(it) })
     }
 
     @Test
diff --git a/picasso/README.md b/picasso/README.md
index a8f5eac..cc80975 100644
--- a/picasso/README.md
+++ b/picasso/README.md
@@ -17,14 +17,15 @@
 | `RemedyDesk.kt` | 탐색 기록·제안·보류·진단·승인 장부와 승인 판정(설계안 §6.4, ADR 43·44·45). 승인 뒤의 작업 수락은 `Middleware` 가 조율 |
 | `IncidentLog.kt` | 인시던트 번들의 봉인·조회, 사후 검토의 기록과 지표, 운영자 판단 부착(설계안 §4) |
 | `Canonical.kt` | 작업 응답·인시던트 번들·이벤트 자취가 나눠 쓰는 정준 프로젝션 셋 |
-| `Ports.kt` | 하위 시스템 연동 추상화 포트: `RobotPort` (인터페이스 계약 소비자), `CellSignals` (현장 설비 센서 신호, E2), `AmrFleetPort` (이송 플릿 연동, E1) |
+| `Ports.kt` | 하위 시스템 연동 추상화 포트: `RobotPort` (인터페이스 계약 소비자), `CellSignals` (현장 설비 센서 신호, E2), `AmrFleetPort` (이송 플릿 연동, E1), `MissionCatalog` (WorkMaster 마다 지금 활성인 케이퍼빌리티와 임무 버전을 주는 포트, 새 작업 지시만 읽음) |
+| `mission/` | **임무 정의 패키지** (`dev.picasso.middleware.mission`): 임무 정의 스키마(`MissionDefinition`)와 엄격 파서(`MissionDefinitionParser`), 신호 사양(`SignalSpec`), 해석기(`DefinedCapability`), 검증기(`MissionValidator`, 5검사 + 노드 id), 메모리 카탈로그(`InMemoryMissionCatalog`, 활성화 관문). 설비 대기 진행 · 버전 고정 · 인시던트 칸 등 엔진 쪽 변경은 기존 파일에 있음 (ADR 50) |
 
 ---
 
 ## 2. 아키텍처 경계 및 관심사 분리
 
 - **기종 비의존 원칙 (Vendor-Agnostic)**: 게이트 검사 7번을 통해 본 모듈 소스 코드 내 특정 벤더나 기종 명칭의 유입을 엄격히 차단합니다. 벤더별 고유 코드를 표준 분류로 변환하는 책임은 어댑터 계층(ADR 33)에 있으며, 미들웨어 코어 내에 기종별 분기(`if robot == ...`)가 존재해서는 안 됩니다.
-- **능력군 추가 시 코어 엔진 불변성**: 엔진 내부에 개별 케이퍼빌리티별 하드코딩 분기가 없습니다. `InspectAsset` 능력군 추가 시에도 코어 엔진의 로직 수정 없이 **3대 일반 규칙**(등급 상한 거부, 결과 참조 처리, 취소 거부 노출)의 매개변수화만으로 수용되었습니다 (§15.93).
+- **케이퍼빌리티 추가 시 코어 엔진 불변성**: 엔진 내부에 개별 케이퍼빌리티별 하드코딩 분기가 없습니다. 케이퍼빌리티는 `LogicalCapability.plan()` 으로만 엔진에 닿으며, 임무 정의(데이터)도 `DefinedCapability` 가 같은 길로 들입니다. `InspectAsset` 추가 시에도 코어 엔진의 로직 수정 없이 **3대 일반 규칙**(등급 상한 거부, 결과 참조 처리, 취소 거부 노출)의 매개변수화만으로 수용되었습니다 (§15.93). 단위의 **종류**를 늘리는 것은 다릅니다. 설비 대기(`Route.SIGNAL`)는 엔진에 경로 하나와 그 진행을 더했습니다 (ADR 50).
 - **글로벌 배차/경로 계획의 스코프 외 분리**: 공장 전체 단위의 AMR 라우팅, 배차 최적화, 전역 자원 선점은 상위 플릿 관제 시스템의 책임이며, 본 모듈은 단일 워크셀 내 작업 분해 및 상태 보증에 집중합니다.
 
 ---
@@ -44,11 +45,14 @@
 |---|---|
 | `SequencingRackTest` | 시나리오 ② — 4개 슬롯 순차 적재, 런타임 버전 갱신, 중간 취소 및 설비 신호 결합 · 계획 시점 사전 조건 사슬 검사 · 적재 유실의 세 차원 통보 |
 | `DeliverContainerTest` | 시나리오 ① — 이송 플릿 위임(`E1`) 및 자재 공정 간 인계 신호 검증 |
-| `InspectAssetTest` | 시나리오 ③ — 신규 능력군 추가 시 코어 엔진 코드 무수정 검증 |
+| `InspectAssetTest` | 시나리오 ③ — 순회 · 단계 둘 · E0 상한 검증. 이 케이퍼빌리티를 들일 때 코어 엔진 코드는 수정하지 않았음 (§15.93) |
 | `EvidenceWindowTest` | 센서 응답 유효 시간 윈도우 δ 초과, 재확인 루프, `UNVERIFIED` 및 `VERIFICATION_MISMATCH` 처리 |
 | `InDoubtTest` · `LateEventTest` | 명령 작업 수락 불확실 상태 해소 절차 및 버전 전환 간 지연 이벤트 처리 무결성 |
 | `EventStreamTest` | 상태 스냅샷 권위, 이벤트 재생 및 장애 복구 후 엔진 재동기화 |
 | `ProgressStallTest` | 작업 정체 가시화 — 정량 측정이 불가능한 기체에 대한 허위 판정 배제 (계약 0.8.0 사양) |
+| `EquipmentWaitTest` | 설비 대기(`Route.SIGNAL`): 신호의 지금 값으로 판정해 `DONE`(E2) · 기한 시각까지 대기하고 넘으면 운영자 보류(라인 멈춤) · 재작업과 확인 · 기한 뒤 `ABORTED`(대기 `FAILED`, 남은 단위 미출발, 취소 응답 없음) · 대기 중 취소 · 대기 중 리비전 |
+| `MissionVersionScenarioTest` | 임무 버전 시나리오: 버전 1 실행 중 버전 2 활성화 시 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행과 그 리비전은 버전 1 로 끝남 · 없는 신호를 참조하는 버전 3 은 거부되고 활성 버전 2 유지 · 인시던트는 실행이 쥔 버전을 실음 · 배정은 관문에 댄 버전을 쥠 · WorkMaster 를 바꾸는 리비전은 거부 |
+| `mission/*Test` | 임무 정의 패키지 4종: 파서(모든 오류를 한 번에 모음, 중복 키 거부) · 동등성(작업 지시 모양 9가지에서 코드 `PrepareSequencedRack` 과 칸마다 같음) · 검증기(5검사와 노드 id 각각) · 메모리 카탈로그(버전 번호, 거부, 스레드 겹침) |
 
 ---
 
@@ -56,4 +60,4 @@
 
 본 모듈의 단위/통합 테스트는 외부 상위 시스템과 현장 설비를 모사한 테스트 대역(`CellMimic`, `AmrFleetMimic`)을 기반으로 동작합니다. 따라서 테스트 스위트의 성공은 설계 가정 하에서의 시스템 일관성을 증명하며, 물리 실제 하드웨어 환경과의 실제 연동 검증 등급은 [`docs/verification.md`](../docs/verification.md)의 10·11번 항목에 명시되어 있습니다.
 
-> 마지막 대조: 2026-10-06 · sha256:aad7b07b14bd · 열림: §15.126
+> 마지막 대조: 2026-10-08 · sha256:aa27ad319b93 · 열림: §15.126
````

- [ ] **Step 3: picasso·게이트 시험(백그라운드)**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" && ./gradlew :picasso:test :gate:test -q
```
Expected: picasso testcase 343, gate testcase 276, 실패 0(`DocumentClaimsTest`·`CompletionCriterionTest`·`GroundTruthTest` 포함).

- [ ] **Step 4: 커밋과 묶음 대조**

```bash
cd "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission"
git add docs/adr/0050-mission-definition-is-versioned-data.md CLAUDE.md README.md picasso/README.md docs/adr/README.md docs/architecture.md docs/glossary.md docs/limits.md docs/orchestration.md docs/scenarios.md docs/seams.md docs/superpowers/specs/2026-09-05-picasso-design.md docs/verification.md gate/src/test/kotlin/dev/picasso/gate/CompletionCriterionTest.kt
git status --short
git commit -q -F - <<'EOF'
docs(p3): ADR 50 과 임무 정의·설비 대기·임무 버전 문서 갱신

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
bash "C:/Users/Eisen/AppData/Local/Temp/p3-cmp.sh" $(git -C "C:/Users/Eisen/AppData/Local/Temp/p3" diff --name-only 41beedb HEAD)
```
Expected: 대조 줄 49개가 모두 `같음`.

### Task 4: 결함 주입(시험이 잡는가)

컨트롤러가 한다. `C:/Users/Eisen/AppData/Local/Temp/p3-inject/inject.py` 가 주입 하나를 넣고, 지정 시험만 돌리고, XML 의 실패 시험 이름을 모으고, 되돌린다. 대상 트리는 환경 변수 `P3_ROOT` 로 준다.

| ID | 주입 | 잡아야 하는 시험 |
|---|---|---|
| I1 | 짝 짓기 규칙(뒤엣것 → 앞엣것) | `DefinedCapabilityEquivalenceTest` |
| I2 | 대기 기한 비교(기한 시각에 넘김) | `EquipmentWaitTest` |
| I3a | 기한 뒤 `ABORTED` 의 같은 라운드 가드 제거 | `EquipmentWaitTest` |
| I3b | 남은 `PENDING` 을 `ABORTED` 로 안 바꿈 | `EquipmentWaitTest` |
| I3c | 기한 중단이 취소 응답을 남김 | `EquipmentWaitTest` |
| I4a~f | 검증기의 기한·신호·자원·스킬·안전·노드 id 중복 검사 각각 빼기 | `MissionValidatorTest` |
| I5 | 리비전이 카탈로그를 다시 봄 | `MissionVersionScenarioTest` |
| I6 | 실행이 버전을 안 쥠 | `MissionVersionScenarioTest` |
| I7 | 번들에 버전을 안 실음 | `MissionVersionScenarioTest`, `EquipmentWaitTest` |
| I8 | 번들이 활성 버전을 실음 | `MissionVersionScenarioTest` |
| I9 | 해시에서 임무 버전 빼기 | `LedgerExportTest`, `MissionVersionScenarioTest` |
| I10 | 리비전이 대기 단위를 로봇처럼 갱신 | `EquipmentWaitTest` |
| I11 | 대기 중 취소를 안 봄 | `EquipmentWaitTest` |
| I12 | 중복 키를 안 봄 | `MissionDefinitionParserTest` |
| I13 | 신호 자취를 진행마다 남김 | `EquipmentWaitTest` |
| I14 | 배정이 읽은 (케이퍼빌리티, 버전) 쌍을 안 넘김 | `MissionVersionScenarioTest` |
| I15 | 리비전의 WorkMaster 변경 거부 빼기 | `MissionVersionScenarioTest` |

- [ ] **Step 1:** 스파이크 결과를 남기려고 `C:/Users/Eisen/AppData/Local/Temp/p3-inject/inject-results.json` 을 `inject-results.spike.json` 으로 복사해 둔다(스파이크의 전체 기록은 `spike-run.log` 의 I1~I14 21줄과 이 파일의 I15 를 합친 것이다). 그다음 `PYTHONUTF8=1 P3_ROOT="C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" python "C:/Users/Eisen/AppData/Local/Temp/p3-inject/inject.py"` 를 백그라운드로 돌린다(UTF-8 이 아니면 주입 이름의 em-dash 에서 출력이 죽는다). Expected: 22줄 모두 `failed` 에 위 표의 시험 클래스가 들어 있고 컴파일 오류가 없음. 없는 줄이 있으면 시험이 돌았는가, 주입이 들어갔는가, 등가 변이인가, 시험의 빈 구간인가 순서로 본다.
- [ ] **Step 2:** `git status --short` 가 비었는지 본다(주입이 모두 되돌려졌다).

### Task 5: 전체 빌드, 새 클론, 합치기, PR, 머지, 알림

- [ ] **Step 1:** 새 클론에서 돌린다. `git clone -q -b feat/p3-mission-versions "C:/Users/Eisen/Desktop/Labs/picasso-wt/p3-mission" "C:/Users/Eisen/AppData/Local/Temp/p3-clean"` 뒤 `./gradlew build` 를 백그라운드로 돌려 testcase 를 센다. Expected: 1,977, 실패 0(Docker 필요). 끝나면 `p3-clean` 을 지운다.
- [ ] **Step 2:** 커밋·PR 문장의 초안을 Codex 와 Fable 에서 받아 취합한다(용어집 8절 새 이름). PR 형식 훅: 절은 `## 개요`·`## 주요 변경 사항`·`## 검증 결과` 셋뿐, 겹화살괄호·낫표·em-dash·en-dash 금지, 본문 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. 서술형 종결(«~했다») 금지, 합니다체나 명사형. 소제목을 쓰면 `### 1. 제목` 꼴. 커밋 트레일러는 훅 상수대로 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- [ ] **Step 3:** 백업 브랜치 `p3-pre-squash` 를 둔 뒤 커밋 셋(Task 1~3)을 하나로 합친다: `git reset --soft 41beedb` 뒤 Step 2 의 메시지로 한 번 커밋, 합치기 전과 트리가 같은지 확인. 푸시 전에 `git fetch -q origin` 하고 `origin/main` 이 `41beedb` 가 아니면 멈추고 보고한다(시험 수와 스탬프를 다시 재야 한다). 푸시하고 PR 을 연다(`gh pr create --body-file -`). CI(`build`)를 한 번 본다.
- [ ] **Step 4:** CI 초록이면 머지한다(`gh pr merge --merge`, `--delete-branch` 는 쓰지 않는다. 쓰면 워크트리에서 main 체크아웃을 시도해 실패한다). 근거: 사용자가 2026-10-08 «중대한 의사결정이 필요한게 아니라면 구현 끝까지 자율로 실행해» 라고 지시했다.
- [ ] **Step 5:** 메인 체크아웃을 당기기 전에 khala·narrator 구현 세션(`ListAgents` 로 찾고 `SendMessage` 로 보낸다)에 알리고, 창이 열려 있지 않다는 답을 받은 뒤 메인 체크아웃에서 `git fetch -q origin && git merge --ff-only origin/main` 으로 당긴다. 끝나면 다시 알린다. 워크트리(`picasso-wt/p3-mission`)를 지우고 로컬·원격 브랜치 `feat/p3-mission-versions`·`p3-pre-squash` 를 지운다.
- [ ] **Step 6:** 메인 체크아웃의 로컬 `handoff/narrator/INDEX.txt`(git 미추적)를 고친다: 머리말의 «schemaVersion 5», 런 식별자 넷(`handoff/narrator/run-*/manifest.json` 의 `runId`), run 별 «schemaVersion 5» 자리, 판 5 절 옆에 판 6 절(인시던트 줄 `route` 의 하위 범주 `SIGNAL`, 해시에 임무 버전이 들어가 임무 버전이 없는 줄까지 모든 digest 가 바뀜, `SIGNAL` 은 네 세트 어디에도 나오지 않음). 고친 뒤 메인 체크아웃에서 `./gradlew :picasso:test --tests '*HandoffFixtureTest*'` 로 런 식별자 대조가 통과하는지 본다(이 검사는 INDEX.txt 가 있는 메인에서만 돈다). INDEX 의 판 번호, sha256, 줄 수를 적어 narrator 와 «보강» 세션에 머지 커밋 해시와 새 문장(`SIGNAL`, 모든 digest 가 바뀜, 판 4 로 고정해 둔 요약의 digest 도 바뀜)을 알린다.
- [ ] **Step 7:** 이 계획 끝 «실행 결과» 절을 채워 picasso-ops 의 이 계획 브랜치(`docs/p3-mission-design`, 스펙·계획이 든 PR)에 커밋하고 그 PR 을 머지한다.

---

## 실행 결과

- 수행: picasso 워크트리에서 묶음 둘(Task 0·1·2, Task 3)을 하위 에이전트(Sonnet)가 수행, 블록은 계획에서 기계로 뽑아 둔 파일을 복사·적용, 묶음마다 커밋된 파일을 스파이크와 바이트 대조해 49개 모두 같음, 워크트리 트리가 스파이크(`ca84b58`)의 트리와 동일, Expected 와 다른 곳 없음
- 결함 주입: 22건(미션 패키지·검증기 8, 설비 대기 7, 버전·인시던트·해시 7) 모두 지정 시험 클래스가 탐지, 스파이크와 워크트리에서 두 번 실행
- 새 클론 빌드: picasso 전체 시험 1,977 실패 0(picasso 343, registry 396, mimic 357, gate 276, harness 208 외), 인계 번들 네 세트를 지금 코드의 산출과 대조해 8개 파일 같음
- 병합: 구현 커밋 셋을 `41beedb` 위에서 하나로 합침(`713e664`, 트리 동일), picasso PR #82 로 올림, CI `build` 초록, 2026-10-08 16:09 KST 머지(머지 커밋 `8f0cc04`)
- 머지 뒤 체크아웃: khala·narrator 세션에 예고하고 열린 창이 없다는 답을 받은 뒤 picasso 메인 체크아웃을 `8f0cc04` 로 fast-forward
- 머지 뒤 인계: 메인의 로컬 `handoff/narrator/INDEX.txt`(git 미추적)를 버전 5 로 고침(머리말, 런 식별자 넷, 세트별 `schemaVersion` 6, 버전 6 절, 버전 이력의 4→5·5→6), 메인에서 `HandoffFixtureTest` 10개 통과, narrator·보강 세션에 머지 해시와 새 문장(route 의 `SIGNAL`, 모든 digest 변경) 알림, narrator 는 버전 6 수용 여부를 그쪽 사용자 결정으로 정한다고 답함, 보강 세션은 koshchei 가 내보내기 버전 5 만 읽어 버전 6 번들을 BROKEN 으로 기록한다는 영향 목록을 보냄(코드 변경 없음, 그쪽 오너 결정 대기, picasso 쪽 요청 없음)
- 걸린 것: 스파이크 첫 버전의 '낡은 신호' 규칙이 상태 신호와 모순(스펙 2차 검토), `abort` 만으로는 같은 라운드에 다음 단위가 출발(2차 검토), 임무 버전을 해시에서 빼는 이유가 코드와 맞지 않음(3차 검토, 해시에 넣기로), 배정이 읽은 쌍을 안 넘기는 결함이 한 스레드에서 등가 변이(시험 보강), 리비전의 WorkMaster 변경 거부에 시험이 없음(계획 검토, 시험과 주입 추가), 주입 실행기가 cp949 출력에서 죽음(`PYTHONUTF8=1`), Fable 초안이 브리프 문체 예의 옛 이름을 따라감(브리프에 '낱말은 따르지 말 것' 추가)
- 다음: S3(picasso-ops: 미들웨어 실행 호스트, 임무 버전 저장과 활성화 API, JSON 편집기와 모의 실행, 도는 실행의 버전 표시, 배정 가능), 그 뒤 S3c(미들웨어 시간값 셋과 인시던트의 현장 설정 버전), S4(장애 주입), 설계 문서는 아직 없음
