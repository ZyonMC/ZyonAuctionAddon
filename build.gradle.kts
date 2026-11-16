plugins {
    `java-library`
}

group = "net.zyonmc"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.groupez.dev/releases")
    maven("https://repo.tcoded.com/releases")
    maven("https://repo.extendedclip.com/releases/")
}

// El plugin se distribuye con sarah / FoliaLib / currencies RELOCADOS (fr.maxlego08.zauctionhouse.libs.*). Compilar
// contra el proyecto :API "crudo" produce firmas sin relocar que NO existen en runtime (NoSuchMethodError). Por eso
// se compila contra el jar sombreado de la API, que es exactamente lo que el plugin expone.
val apiShadowJar = gradle.includedBuild("zAuctionHouse").task(":API:shadowJar")

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly(fileTree("../zAuctionHouse/target-api") {
        include("zAuctionHouse-API-*.jar")
        builtBy(apiShadowJar)
    })
    compileOnly("fr.maxlego08.menu:zmenu-api:1.1.1.7")

    // Paper la descarga en runtime (plugin.yml `libraries`); no se empaqueta en el jar.
    compileOnly("io.lettuce:lettuce-core:6.5.5.RELEASE")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

// Los tests (incluidos los de integracion con Redis real) ven las mismas librerias que el codigo.
configurations.testImplementation.get().extendsFrom(configurations.compileOnly.get())

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    // Redis de pruebas: -Dzah.test.redis=host:puerto (o env ZAH_TEST_REDIS). Sin el, los tests de integracion se omiten.
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("zah.test.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
    environment("ZAH_TEST_REDIS", System.getenv("ZAH_TEST_REDIS") ?: "")
}
