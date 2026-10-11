import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("kr.dogfoot:hwplib:1.1.11")
    implementation("kr.dogfoot:hwpxlib:1.0.9")

    testImplementation(kotlin("test"))
    // The corpus test uses hwplib's own text extractor as a reference.
    testImplementation("kr.dogfoot:hwplib:1.1.11")
}

tasks.test {
    useJUnitPlatform()
}
