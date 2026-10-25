// A module that goes to Maven Central (io.github.aristarkhkenebas:<module>): everything from caulis.library,
// plus its sources, its documentation, a POM that says what it is and under which license,
// and a signature. Examples and tools are not published (they use caulis.library alone).
//
//   ./gradlew publishAllPublicationsToStagingRepository -Pcaulis.version=0.1.0
//   ci/publish-central.sh                       (signs nothing itself: see there)

plugins {
    id("caulis.library")
    `maven-publish`
    signing
}

// What a release is called: -Pcaulis.version=0.1.0 (else the catalog's -SNAPSHOT).
providers.gradleProperty("caulis.version").orNull?.let { version = it }

java {
    withSourcesJar()
}

// Maven Central wants a javadoc jar. A small page saying where the documentation is goes into
// it, not the Dokka pages (megabytes per module, against Central's size limit per month): IDEs
// show the KDoc from the sources jar, and the pages are on the website (./gradlew dokkaGenerate).
val javadocPage by tasks.registering {
    val name = project.name
    val description = provider { project.description.orEmpty() }
    val website = providers.gradleProperty("caulis.website")
    val page = layout.buildDirectory.file("javadoc-page/index.html")
    inputs.property("description", description)
    inputs.property("website", website.orElse(""))
    outputs.file(page)
    doLast {
        page.get().asFile.writeText(
            """
            |<!DOCTYPE html>
            |<html><head><meta charset="utf-8"><title>$name</title></head><body>
            |<h1>$name</h1>
            |<p>${description.get()}</p>
            |<p>The documentation: <a href="${website.getOrElse("")}">${website.getOrElse("")}</a>.
            |The KDoc of every class is in the sources jar (your IDE shows it).</p>
            |</body></html>
            |""".trimMargin(),
        )
    }
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier = "javadoc"
    from(javadocPage)
}

// The license goes into every jar.
val licenseFile = layout.projectDirectory.file("LICENSE").asFile.takeIf { it.exists() } ?: rootProject.file("LICENSE")
tasks.withType<Jar>().configureEach {
    from(licenseFile) { into("META-INF") }
    from(rootProject.file("NOTICE")) { into("META-INF") }
}

// Test helpers (ScreenTestServer, TestClient) stay ours: not part of what is published.
plugins.withId("java-test-fixtures") {
    val component = components["java"] as AdhocComponentWithVariants
    component.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    component.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

// Filled in gradle.properties (or with -P...): where the project lives.
fun setting(name: String) = providers.gradleProperty(name)

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)
            pom {
                name = project.name
                description = provider { project.description ?: error("${project.path} has no description (it goes into the POM)") }
                url = setting("caulis.website")
                licenses {
                    license {
                        name = "MPL-2.0"
                        url = "https://www.mozilla.org/en-US/MPL/2.0/"
                    }
                }
                developers {
                    developer {
                        id = setting("caulis.developer.id")
                        name = setting("caulis.developer.name")
                    }
                }
                scm {
                    url = setting("caulis.scm")
                    connection = setting("caulis.scm").map { "scm:git:$it.git" }
                }
            }
        }
    }
    repositories {
        // Everything for one release, in one folder at the top: ci/publish-central.sh bundles it.
        maven {
            name = "staging"
            url = rootProject.layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

// Signed with your key for a release (gpg's own agent: no key in any file here); snapshots
// are not signed.
signing {
    isRequired = !version.toString().endsWith("-SNAPSHOT")
    useGpgCmd()
    sign(publishing.publications)
}

// A release says where it lives: nothing goes out with an empty POM field.
tasks.withType<PublishToMavenRepository>().configureEach {
    val missing = listOf("caulis.website", "caulis.scm", "caulis.developer.id", "caulis.developer.name").filter { !setting(it).isPresent }
    doFirst {
        check(missing.isEmpty()) { "Set ${missing.joinToString()} in gradle.properties (they go into every POM)" }
    }
}

// The public API of each module is written down (api/<module>.api, kept in the repository):
// `./gradlew checkKotlinAbi` (part of `check`) fails when it changes unnoticed, and
// `./gradlew updateKotlinAbi` writes it anew after a change that is meant.
@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    abiValidation {
        filters {
            exclude {
                // Generated bindings of C libraries, and what is marked internal: not the API.
                byNames.add("dev.caulis.ffi.c.**")
                annotatedWith.add("dev.caulis.log.InternalCaulisApi")
            }
        }
    }
}
