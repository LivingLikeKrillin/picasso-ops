package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * `site/robots.json` 의 한 줄. 현장으로 치면 기체 명판이다(스펙 §6). [profile] 은 저장소 루트 기준 경로다.
 *
 * @param siteNames 현장에서 이 기체에 티칭한 사이트 명칭(P2·S1d 스펙 §7). 런처가 기동 때 기체에 넣는다. 칸이 없으면
 *   빈 목록이다 — 티칭하지 않은 기체다
 */
data class RosterEntry(val robotId: String, val serial: String, val profile: String, val siteNames: List<String> = emptyList())

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
            RosterEntry(field("robot_id"), field("serial"), field("profile"), siteNames(node, index))
        }
        require(entries.isNotEmpty()) { "robots.json 에 기체가 없다" }
        val duplicated = entries.groupBy { it.robotId }.filterValues { it.size > 1 }.keys
        // 조용히 덮으면 기체 하나가 사라진 채 기동한다(mimic CLI 의 --robot 과 같은 이유).
        require(duplicated.isEmpty()) { "robots.json 에 같은 robot_id 가 두 번 있다: $duplicated" }
        return entries
    }

    /** 빈 이름은 받지 않는다. registry 는 개수만 대조하므로(P2·S1d 스펙 §1) 빈 문자열이 들어가면 티칭한 것으로 세어진다. */
    private fun siteNames(node: com.fasterxml.jackson.databind.JsonNode, index: Int): List<String> {
        val names = node.get("site_names") ?: return emptyList()
        require(names.isArray) { "robots.json ${index}번째 기체의 'site_names' 는 배열이어야 한다" }
        return names.map { name ->
            val value = name.takeIf { it.isTextual }?.asText()?.trim()
            require(!value.isNullOrEmpty()) { "robots.json ${index}번째 기체의 'site_names' 에 빈 이름이 있다" }
            value
        }
    }
}
