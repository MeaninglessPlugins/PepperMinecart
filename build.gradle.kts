plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.pepperminecart"
version = "1.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.bstats:bstats-bukkit:3.1.0")

    // 测试源集需要显式声明 API（测试运行于纯 JVM，仅使用 API 级类）
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.45.0")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 配置期捕获版本：processResources 执行期访问 project/Task.project 已被 Gradle 弃用
val pluginVersion: String = version.toString()

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    // 交付产物：shadowJar 使用 -all 分类器，避免与普通 jar 同名互相覆盖。
    // 若同名，单独运行 `gradlew jar` 会把缺 bstats 的瘦包覆盖成部署产物，
    // 服务器加载时 NoClassDefFoundError 直接启用失败。
    shadowJar {
        archiveClassifier.set("all")
        archiveFileName.set("PepperMinecart-${pluginVersion}-all.jar")
        relocate("org.bstats", "com.pepperminecart.libs.bstats")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        // BuildArtifactContractTest 断言交付 jar 已生成且包含重定位 bstats
        dependsOn(shadowJar, jar)
    }
}
