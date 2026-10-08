package dev.picasso.ops.site

import java.nio.file.Path

data class DbConfig(val url: String, val user: String, val password: String)

/**
 * 런처 설정. 값은 루트 `.env` 에서 환경 변수로 온다(스펙 §4). 사이트 id 와 기체 명부는 한 출처에서 나온다(스펙 §6 ④).
 *
 * @param registryPort 0 이면 무작위 포트(시험).
 * @param mimicPort mimic gRPC 포트. 0 이면 무작위(시험). 실행 호스트가 이 포트로 붙는다(S3a 스펙 §6.2).
 * @param cellPort 셀 대역 `GET /cell` 의 루프백 포트. 0 이면 무작위(시험).
 * @param cell 셀 대역의 제시 자리와 슬롯. 현장마다 다를 값이라 설정에 두고, 런처는 [CellFixture.STANDARD] 를 쓴다.
 */
data class SiteConfig(
    val root: Path,
    val siteId: String,
    val db: DbConfig,
    val registryPort: Int,
    val operatorToken: String,
    val ingestToken: String,
    val roster: List<RosterEntry>,
    val mimicPort: Int = 0,
    val cellPort: Int = 0,
    val cell: CellFixture = CellFixture.STANDARD,
) {
    val schema: Path get() = root.resolve(PROFILE_SCHEMA)

    fun profile(entry: RosterEntry): Path = root.resolve(entry.profile)

    companion object {
        const val PROFILE_SCHEMA = "picasso/profile/schema/capability-profile.schema.json"
        const val ROSTER = "site/robots.json"

        fun fromEnv(env: Map<String, String>, root: Path): SiteConfig {
            fun need(key: String): String = env[key]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("환경 변수 $key 가 없다(루트 .env 를 확인)")
            fun port(key: String): Int = need(key).let {
                it.toIntOrNull() ?: throw IllegalArgumentException("$key 가 정수가 아니다: $it")
            }
            return SiteConfig(
                root = root,
                siteId = need("SITE_ID"),
                db = DbConfig(need("PICASSO_DB_URL"), need("PICASSO_DB_USER"), need("PICASSO_DB_PASSWORD")),
                registryPort = port("REGISTRY_PORT"),
                operatorToken = need("PICASSO_OPERATOR_TOKEN"),
                ingestToken = need("PICASSO_INGEST_TOKEN"),
                roster = RobotRoster.read(root.resolve(ROSTER)),
                mimicPort = port("MIMIC_GRPC_PORT"),
                cellPort = port("SITE_CELL_PORT"),
            )
        }
    }
}
