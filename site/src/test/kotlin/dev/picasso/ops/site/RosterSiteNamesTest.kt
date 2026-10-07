package dev.picasso.ops.site

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 명부의 `site_names`(P2·S1d 스펙 §7). 현장에서 티칭한 명칭이며 런처가 기동 때 기체에 넣는다. */
class RosterSiteNamesTest {

    @Test
    fun `저장소의 명부는 humanoid-01 만 명칭을 티칭했다`() {
        val root = Path.of("..").toAbsolutePath().normalize()
        val names = RobotRoster.read(root.resolve(SiteConfig.ROSTER)).associate { it.robotId to it.siteNames }
        assertEquals(mapOf("humanoid-01" to listOf("dock-3", "bay-7"), "quadruped-01" to emptyList()), names)
    }

    @Test
    fun `칸이 없으면 티칭하지 않은 기체다`() {
        val roster = RobotRoster.parse("""[{"robot_id":"r1","serial":"S1","profile":"p.json"}]""")
        assertEquals(emptyList(), roster.single().siteNames)
    }

    @Test
    fun `빈 이름과 배열 아닌 값을 거절한다`() {
        listOf(""""site_names":["dock-3"," "]""", """"site_names":"dock-3"""").forEach { field ->
            val e = assertFailsWith<IllegalArgumentException> {
                RobotRoster.parse("""[{"robot_id":"r1","serial":"S1","profile":"p.json",$field}]""")
            }
            assertTrue("'site_names'" in e.message!!, e.message)
        }
    }
}
