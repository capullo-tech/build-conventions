// build-conventions - publishes the capullo-tech SHARED version catalog.
//
// This project has NO source and NO dependencies: it exists only to package
// gradle/libs.versions.toml as a consumable version-catalog artifact and publish
// it so every repo can import it with
//   create("libs") { from("com.github.capullo-tech:build-conventions:<tag>") }.
//
// Build/verify locally (no Android SDK needed - pure metadata):
//   ./gradlew generateCatalogAsToml   # regenerates build/version-catalog/libs.versions.toml
//   ./gradlew publishToMavenLocal     # what jitpack runs; installs the .toml to ~/.m2
plugins {
    `version-catalog`
    `maven-publish`
}

// jitpack rewrites the group to com.github.capullo-tech on publish (same as the
// other single-artifact org repos, e.g. lib-media3-ffmpeg-android).
group = "tech.capullo.buildconventions"
version = "0.1.0"

catalog {
    // The published catalog IS gradle/libs.versions.toml verbatim.
    versionCatalog {
        from(files("gradle/libs.versions.toml"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["versionCatalog"])
            artifactId = "build-conventions"
        }
    }
}
