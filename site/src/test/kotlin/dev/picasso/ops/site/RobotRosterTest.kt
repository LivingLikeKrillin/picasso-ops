package dev.picasso.ops.site

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RobotRosterTest {

    @Test
    fun `저장소의 robots_json 은 기체 2대이고 프로파일 파일이 실재한다`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER))
        assertEquals(listOf("humanoid-01", "quadruped-01"), roster.map { it.robotId })
        roster.forEach { assertTrue(Files.isRegularFile(root.resolve(it.profile)), "프로파일이 없다: ${it.profile}") }
    }

    @Test
    fun `칸 값의 앞뒤 공백을 지우고 읽는다`() {
        val roster = RobotRoster.parse("""[{"robot_id":" r1 ","serial":"S1","profile":"p.json"}]""")
        assertEquals(listOf(RosterEntry("r1", "S1", "p.json")), roster)
    }

    @Test
    fun `빈 일련번호를 거절한다`() {
        val e = assertFailsWith<IllegalArgumentException> {
            RobotRoster.parse("""[{"robot_id":"r1","serial":"  ","profile":"p.json"}]""")
        }
        assertTrue("'serial'" in e.message!!, e.message)
    }

    @Test
    fun `같은 robot_id 두 번을 거절한다`() {
        val e = assertFailsWith<IllegalArgumentException> {
            RobotRoster.parse(
                """[{"robot_id":"r1","serial":"S1","profile":"p"},{"robot_id":"r1","serial":"S2","profile":"p"}]""",
            )
        }
        assertTrue("r1" in e.message!!, e.message)
    }

    @Test
    fun `빈 명부를 거절한다`() {
        assertFailsWith<IllegalArgumentException> { RobotRoster.parse("[]") }
    }
}
