plugins {
    id("java")
    id("java-library")
    id("io.freefair.lombok") version "8.14.2"
    id("maven-publish")
}

group = "ru.yuraender"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
    api("jakarta.validation:jakarta.validation-api:3.1.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.javadoc {
    options.encoding = "UTF-8"
    if (JavaVersion.current().isJava9Compatible) {
        (options as CoreJavadocOptions).addBooleanOption("html5", true)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "crpt-api"
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name.set("CrptApi")
                description.set("Java-клиент для API Честного знака")
                url.set("https://github.com/yuraender/crpt-api")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("yuraender")
                        name.set("YuraEnder")
                        email.set("yuraender@yandex.ru")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/yuraender/crpt-api.git")
                    developerConnection.set("scm:git:ssh://github.com/yuraender/crpt-api.git")
                    url.set("https://github.com/yuraender/crpt-api")
                }
            }
        }
    }
}
