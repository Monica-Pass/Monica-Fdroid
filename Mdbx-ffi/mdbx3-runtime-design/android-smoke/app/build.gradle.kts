plugins {
    id("com.android.application")
}

val repoRoot = rootProject.projectDir.resolve("../..")
val selectedAbi = providers.gradleProperty("mdbxAbi").orElse("x86_64")
val libraryRoot = providers.gradleProperty("mdbxLibraryRoot")
    .map { project.file(it) }
    .orElse(repoRoot.resolve("target/mdbx3-android-final"))

android {
    namespace = "com.monica.mdbx3.smoke"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.monica.mdbx3.smoke"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += selectedAbi.get()
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
            }
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/mdbx3-jniLibs"))

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.register<Sync>("stageMdbx3Library") {
    val abi = selectedAbi.get()
    from(libraryRoot.map { it.resolve("android-jniLibs/$abi") })
    into(layout.buildDirectory.dir("generated/mdbx3-jniLibs/$abi"))
    include("libmdbx_ffi.so")
    doFirst {
        require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            "Unsupported mdbxAbi=$abi"
        }
    }
}

tasks.named("preBuild") {
    dependsOn("stageMdbx3Library")
}
