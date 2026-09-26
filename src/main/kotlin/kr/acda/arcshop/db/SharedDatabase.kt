package kr.acda.arcshop.db

import org.jetbrains.exposed.v1.jdbc.Database

/**
 * 프록시(다중 서버) 환경에서 캐시 잔액/개인 구매 한도를 공유하기 위한 중앙 DB 연결 설정.
 * 반드시 모든 백엔드 서버가 접근 가능한 공유 DB(MySQL/MariaDB)를 가리켜야 한다 — SQLite는
 * 파일 하나를 여러 서버가 동시에 못 쓰므로 단일 서버 테스트 용도로만 허용한다 (DECISIONS.md #1).
 */
data class SharedDatabaseConfig(
    val jdbcUrl: String,
    val driver: String,
    val user: String,
    val password: String,
) {
    val isSqlite: Boolean get() = driver.contains("sqlite", ignoreCase = true)

    companion object {
        /** 설정이 없을 때의 안전한 기본값 — 모듈 데이터 폴더 밑 로컬 SQLite (프록시 비호환, 경고 대상). */
        fun localSqliteFallback(dataFolder: java.nio.file.Path): SharedDatabaseConfig {
            val file = dataFolder.resolve("arcshop-local.db")
            return SharedDatabaseConfig(
                jdbcUrl = "jdbc:sqlite:${file.toAbsolutePath()}",
                driver = "org.sqlite.JDBC",
                user = "",
                password = "",
            )
        }
    }
}

object SharedDatabaseFactory {
    fun connect(config: SharedDatabaseConfig): Database =
        Database.connect(url = config.jdbcUrl, driver = config.driver, user = config.user, password = config.password)
}
