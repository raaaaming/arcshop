plugins {
	kotlin("jvm") version "2.3.21"
	id("com.google.devtools.ksp") version "2.3.7"
}

group = "kr.acda"
version = "1.0.0"

repositories {
	mavenCentral()
	mavenLocal()
	maven("https://repo.papermc.io/repository/maven-public/")
	maven("https://repo.acda.kr/repository/maven-releases/")
	maven("https://repo.acda.kr/repository/maven-snapshots/")
}

dependencies {
	testImplementation(kotlin("test"))
	testImplementation(kotlin("test-junit5"))

	compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

	compileOnly("kr.acda.arccore:arc-core:1.1.0-SNAPSHOT")
	ksp("kr.acda.arccore:arc-ksp:1.1.0-SNAPSHOT")

	compileOnly("org.jetbrains.exposed:exposed-core:1.0.0-rc-4")
	compileOnly("org.jetbrains.exposed:exposed-jdbc:1.0.0-rc-4")

	compileOnly("kr.acda:arceconomy:1.1.0")

	// ARCLink 클라이언트 모드가 연결된 플레이어에게 MCEF 브라우저 상점 UI를 띄우기 위한 선택적
	// 연동 (ArcLinkService 인터페이스만 씀, arclink 모듈이 없어도 arcshop 자체는 정상 동작 —
	// ARCShopModule.onEnable()의 try/catch 참고). 이 좌표(ARClinkPlugin 프로젝트의 group/artifactId,
	// build.gradle.kts 기준)가 실제로 repo.acda.kr 에 publish 돼 있는지는 이 세션에서 확인하지
	// 못했다 — 안 돼 있다면 먼저 ARClinkPlugin에서 `./gradlew publish` 가 필요하다 (TODO.md 참고).
	compileOnly("kr.acda:ARClinkPlugin:1.0.0")
}

kotlin {
	jvmToolchain(21)
}

tasks.test {
	useJUnitPlatform()
}
