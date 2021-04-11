plugins {
    java
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "1.0.0"
}

group = "com.faendir.proguard"
version = "1.3"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.register<Jar>("sourcesJar") {
    group = "documentation"
    from(sourceSets["main"].allSource)
    archiveClassifier.set("sources")
}

tasks.register<Jar>("javadocJar") {
    group = "documentation"
    from(tasks["javadoc"])
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components.findByName("java"))
            artifact(tasks.findByName("sourcesJar"))
            artifact(tasks.findByName("javadocJar"))

            pom {
                name.set("Retrace")
                description.set("Retraces proguard obfuscated stacktraces.")
                url.set("https://github.com/F43nd1r/Retrace")
                scm {
                    connection.set("scm:git:https://github.com/F43nd1r/Retrace.git")
                    developerConnection.set("scm:git:git@github.com:F43nd1r/Retrace.git")
                    url.set("https://github.com/F43nd1r/Retrace.git")
                }
                licenses {
                    license {
                        name.set("GNU General Public License v2.0")
                        url.set("https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("f43nd1r")
                        name.set("Lukas Morawietz")
                    }
                }
            }
        }
    }
}

signing {
    val signingKey = project.findProperty("signingKey") as? String ?: System.getenv("SIGNING_KEY")
    val signingPassword = project.findProperty("signingPassword") as? String ?: System.getenv("SIGNING_PASSWORD")
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}

nexusPublishing {
    repositories {
        sonatype {
            username.set(project.findProperty("ossrhUser") as? String ?: System.getenv("OSSRH_USER"))
            password.set(project.findProperty("ossrhPassword") as? String ?: System.getenv("OSSRH_PASSWORD"))
        }
    }
}

tasks.named("publish") {
    dependsOn("publishToSonatype")
    dependsOn("closeAndReleaseSonatypeStagingRepository")
}
