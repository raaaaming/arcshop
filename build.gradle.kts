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
}

kotlin {
	jvmToolchain(21)
}

tasks.test {
	useJUnitPlatform()
}
