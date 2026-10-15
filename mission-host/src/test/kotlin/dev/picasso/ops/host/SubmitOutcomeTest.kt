package dev.picasso.ops.host

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.RemedyOutcome
import dev.picasso.middleware.SourceUnobserved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 미들웨어 거부와 미배정 사유를 제출 결과로 옮기는 자리. 실행 호스트의 `assign` 경로는 출발 결품과 연쇄 거부를 UNASSIGNED 로 내므로
 * (MissionHostTest 의 출발 결품 시험) 구조가 실린 REJECTED 는 HTTP 로 만들 수 없고, 미배정의 셀 무응답(`alternatives` null)도 호스트
 * 셀 대역으로는 만들 수 없다. 그래서 picasso 의 값을 직접 세운다.
 */
class SubmitOutcomeTest {

    private val json = jacksonObjectMapper()
    private val source = RemedyOutcome.SourceMissing(material = "ENGINE-COVER-A", source = "SEQ-IN-02.BIN-A", observed = null, alternatives = null)

    private fun body(outcome: SubmitOutcome) = json.readTree(json.writeValueAsString(outcome))

    @Test
    fun `출발 결품 거부는 대체 자리와 그 자리를 싣고 널과 빈 목록을 접지 않는다`() {
        val listed = body(SubmitOutcome.rejected(
            Middleware.Submission.Rejected("출발 자리 비었다", alternativeLocations = listOf("SEQ-IN-03.BIN-B")),
            source.copy(alternatives = listOf("SEQ-IN-03.BIN-B")), emptyList(),
        ))
        assertEquals("REJECTED", listed["result"].asText())
        assertEquals("출발 자리 비었다", listed["rejectionReason"].asText())
        assertEquals(listOf("SEQ-IN-03.BIN-B"), listed["alternativeLocations"].map { it.asText() })
        assertEquals("SEQ-IN-02.BIN-A", listed["sourceMissing"]["source"].asText())
        assertEquals("ENGINE-COVER-A", listed["sourceMissing"]["material"].asText())
        assertTrue(listed["sourceMissing"].has("observed") && listed["sourceMissing"]["observed"].isNull, listed.toString())

        val none = body(SubmitOutcome.rejected(
            Middleware.Submission.Rejected("출발 자리 비었다", alternativeLocations = emptyList()), source.copy(alternatives = emptyList()), emptyList(),
        ))
        assertTrue(none["alternativeLocations"].isArray && none["alternativeLocations"].isEmpty, none.toString())

        val silent = body(SubmitOutcome.rejected(Middleware.Submission.Rejected("출발 자리 비었다"), source, emptyList()))
        assertTrue(silent.has("alternativeLocations") && silent["alternativeLocations"].isNull, silent.toString())
        assertTrue(silent["sourceMissing"].isObject, silent.toString())
    }

    @Test
    fun `picasso 가 놓은 작업 지시로 거부하면 놓은 작업 지시 칸이 참이고 그 밖의 거부는 거짓이다`() {
        val forgotten = body(SubmitOutcome.rejected(
            Middleware.Submission.Rejected("${Middleware.FORGOTTEN_JOB_ORDER}: JO-1 · exec-1 · 판 1 · PHYSICALLY_DONE — 다시 할 일은 새 작업 지시 id 로 낸다"),
            null, emptyList(),
        ))
        assertEquals(listOf("REJECTED", "true"), listOf(forgotten["result"].asText(), forgotten["forgottenJobOrder"].asText()), forgotten.toString())
        assertTrue(forgotten["rejectionReason"].asText().startsWith(Middleware.FORGOTTEN_JOB_ORDER))
        val other = body(SubmitOutcome.rejected(Middleware.Submission.Rejected("요구 근거 등급이 넘는다"), null, emptyList()))
        assertFalse(other["forgottenJobOrder"].asBoolean(), other.toString())
    }

    @Test
    fun `미배정의 기체별 사유는 출발 결품일 때만 대체 자리와 그 자리를 싣고 널과 빈 목록을 접지 않는다`() {
        fun refusal(missing: RemedyOutcome.SourceMissing?) = json.readTree(json.writeValueAsString(Refusal.of("humanoid-01", "출발 자리 비었다", missing)))

        val listed = refusal(source.copy(alternatives = listOf("SEQ-IN-03.BIN-B")))
        assertEquals(listOf("SEQ-IN-03.BIN-B"), listed["alternativeLocations"].map { it.asText() }, listed.toString())
        assertEquals("SEQ-IN-02.BIN-A", listed["sourceMissing"]["source"].asText())
        assertEquals("ENGINE-COVER-A", listed["sourceMissing"]["material"].asText())
        assertTrue(listed["sourceMissing"].has("observed") && listed["sourceMissing"]["observed"].isNull, listed.toString())

        val none = refusal(source.copy(alternatives = emptyList()))
        assertTrue(none["alternativeLocations"].isArray && none["alternativeLocations"].isEmpty, none.toString())

        // 셀이 답하지 않았다. 빈 목록으로 접지 않고, 출발 결품이라는 것은 sourceMissing 이 말한다.
        val silent = refusal(source)
        assertTrue(silent.has("alternativeLocations") && silent["alternativeLocations"].isNull, silent.toString())
        assertTrue(silent["sourceMissing"].isObject, silent.toString())

        val other = refusal(null)
        assertTrue(other.has("alternativeLocations") && other["alternativeLocations"].isNull, other.toString())
        assertTrue(other.has("sourceMissing") && other["sourceMissing"].isNull, other.toString())
    }

    @Test
    fun `출발 미관측 거부는 그 자리와 설비가 말한 것을 싣고 결품과 대체 자리를 싣지 않는다`() {
        fun rejected(occupied: Boolean?) = body(SubmitOutcome.rejected(
            Middleware.Submission.Rejected("출발 자리를 확인하지 못했다", sourceUnobserved = SourceUnobserved("ENGINE-COVER-A", "SEQ-IN-02.BIN-A", occupied)),
            null, emptyList(),
        ))
        val silent = rejected(null)
        assertEquals("REJECTED", silent["result"].asText())
        assertEquals("SEQ-IN-02.BIN-A", silent["sourceUnobserved"]["source"].asText(), silent.toString())
        assertEquals("ENGINE-COVER-A", silent["sourceUnobserved"]["material"].asText())
        assertTrue(silent["sourceUnobserved"].has("occupied") && silent["sourceUnobserved"]["occupied"].isNull, silent.toString())
        assertTrue(silent.has("sourceMissing") && silent["sourceMissing"].isNull, silent.toString())
        assertTrue(silent.has("alternativeLocations") && silent["alternativeLocations"].isNull, silent.toString())
        assertEquals(true, rejected(true)["sourceUnobserved"]["occupied"].asBoolean())

        // 출발 결품 거부와 다른 거부는 출발 미관측 칸이 비었다.
        val missing = body(SubmitOutcome.rejected(Middleware.Submission.Rejected("출발 자리 비었다"), source, emptyList()))
        assertTrue(missing.has("sourceUnobserved") && missing["sourceUnobserved"].isNull, missing.toString())
    }

    @Test
    fun `미배정의 기체별 사유는 출발 미관측일 때만 그 자리를 싣고 결품 칸과 대체 자리는 비었다`() {
        fun refusal(missing: RemedyOutcome.SourceMissing?, unobserved: SourceUnobserved?) =
            json.readTree(json.writeValueAsString(Refusal.of("humanoid-01", "출발 자리를 확인하지 못했다", missing, unobserved)))

        val unobserved = refusal(null, SourceUnobserved(null, "SEQ-IN-02.BIN-A", true))
        assertEquals("SEQ-IN-02.BIN-A", unobserved["sourceUnobserved"]["source"].asText(), unobserved.toString())
        assertTrue(unobserved["sourceUnobserved"].has("material") && unobserved["sourceUnobserved"]["material"].isNull, unobserved.toString())
        assertEquals(true, unobserved["sourceUnobserved"]["occupied"].asBoolean())
        assertTrue(unobserved["sourceMissing"].isNull && unobserved["alternativeLocations"].isNull, unobserved.toString())

        val missing = refusal(source, null)
        assertTrue(missing["sourceMissing"].isObject, missing.toString())
        assertTrue(missing.has("sourceUnobserved") && missing["sourceUnobserved"].isNull, missing.toString())
    }

    @Test
    fun `조치 탐색 응답은 하위 범주 이름으로 싣고 보류한 응답은 단계 없이 보류했다고만 싣는다`() {
        val step = RemedyStep(
            "release", listOf(Precondition.newBuilder().setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD).setRequires(HoldKind.HOLD_KIND_HOLDING).build()),
            HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING,
        )
        val found = body(SubmitOutcome.rejected(Middleware.Submission.Rejected("사전 조건", remedy = Remedy.Found(listOf(step))), null, emptyList()))
        assertEquals("FOUND", found["remedy"]["kind"].asText(), found.toString())
        assertEquals("release", found["remedy"]["steps"][0]["skillType"].asText())
        assertEquals("HOLD_KIND_EMPTY", found["remedy"]["steps"][0]["expectedHold"].asText())
        assertEquals("PRECONDITION_SUBJECT_HOLD", found["remedy"]["steps"][0]["requires"][0]["subject"].asText())
        assertFalse(found["remedyWithheld"].asBoolean())
        // 출발 결품이 아니면 그 칸은 비고, 대체 자리도 널이다.
        assertTrue(found["sourceMissing"].isNull && found["alternativeLocations"].isNull, found.toString())

        val violation = PreconditionViolation(PreconditionSubject.PRECONDITION_SUBJECT_HOLD, HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING, "든 채다")
        val none = body(SubmitOutcome.rejected(
            Middleware.Submission.Rejected("사전 조건", remedy = Remedy.None(listOf(violation), Remedy.None.Cause.DEPTH_LIMIT)), null, emptyList(),
        ))
        assertEquals("NONE", none["remedy"]["kind"].asText(), none.toString())
        assertEquals("DEPTH_LIMIT", none["remedy"]["cause"].asText())
        assertEquals("든 채다", none["remedy"]["unmet"][0]["detail"].asText())
        assertFalse(none["remedy"].has("steps"), none.toString())

        val withheld = body(SubmitOutcome.rejected(Middleware.Submission.Rejected("사전 조건", remedy = null, remedyWithheld = true), RemedyOutcome.Withheld, emptyList()))
        assertTrue(withheld["remedyWithheld"].asBoolean(), withheld.toString())
        assertTrue(withheld["remedy"].isNull && withheld["sourceMissing"].isNull, withheld.toString())
    }
}
