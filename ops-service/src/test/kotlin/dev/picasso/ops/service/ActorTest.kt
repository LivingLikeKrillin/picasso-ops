package dev.picasso.ops.service

import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ActorTest {

    @Test
    fun `X-Actor 는 모드 소문자와 사용자를 사선으로 잇는다`() {
        assertEquals("engineer/kim", Actor(Mode.ENGINEER, "kim").header())
        assertEquals("operator/lee.j", Actor(Mode.OPERATOR, "lee.j").header())
    }

    @Test
    fun `요청 헤더에서 모드와 사용자를 읽는다`() {
        assertEquals(Actor(Mode.OPERATOR, "kim"), Actor.fromHeaders(" Operator ", " kim "))
    }

    @Test
    fun `모르는 모드나 빈 사용자는 널이다`() {
        assertNull(Actor.fromHeaders("admin", "kim"))
        assertNull(Actor.fromHeaders(null, "kim"))
        assertNull(Actor.fromHeaders("engineer", " "))
        assertNull(Actor.fromHeaders("engineer", null))
    }

    @Test
    fun `헤더에 못 싣는 사용자 이름은 받지 않는다`() {
        assertNull(Actor.fromHeaders("engineer", "a/b"))
        assertNull(Actor.fromHeaders("engineer", "김"))
        assertFailsWith<IllegalArgumentException> { Actor(Mode.ENGINEER, "a b") }
    }
}
