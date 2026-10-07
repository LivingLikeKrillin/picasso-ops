package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/** `site/robots.json` 의 한 줄. 현장으로 치면 기체 명판이다(스펙 §6). [profile] 은 저장소 루트 기준 경로다. */
data class RosterEntry(val robotId: String, val serial: String, val profile: String)

/** 기체 명부. 런처가 이것으로 mimic 을 띄우고, 운영자와 시험이 이것을 보고 기체를 선언한다(스펙 §6). */
object RobotRoster {

    fun read(file: Path): List<RosterEntry> = parse(Files.readString(file))

    fun parse(json: String): List<RosterEntry> {
        val root = ObjectMapper().readTree(json)
        require(root.isArray) { "robots.json 은 배열이어야 한다" }
        val entries = root.mapIndexed { index, node ->
            fun field(name: String): String {
                val value = node.get(name)?.takeIf { it.isTextual }?.asText()?.trim()
                require(!value.isNullOrEmpty()) { "robots.json ${index}번째 기체에 '$name' 이 없거나 비었다" }
                return value
            }
            RosterEntry(field("robot_id"), field("serial"), field("profile"))
        }
        require(entries.isNotEmpty()) { "robots.json 에 기체가 없다" }
        val duplicated = entries.groupBy { it.robotId }.filterValues { it.size > 1 }.keys
        // 조용히 덮으면 기체 하나가 사라진 채 기동한다(mimic CLI 의 --robot 과 같은 이유).
        require(duplicated.isEmpty()) { "robots.json 에 같은 robot_id 가 두 번 있다: $duplicated" }
        return entries
    }
}
