# The composite-build ↔ jitpack toggle

Every capullo-tech repo consumes its first-party dependencies (the SPI, the
engine, the source libs) in **one of two modes**, chosen automatically at
configuration time by a one-line file check in `settings.gradle[.kts]`. This is
the switch that makes "edit a library, both apps rebuild instantly" real in
development while keeping CI/release builds reproducible.

## The two modes

| | Composite build (dev) | jitpack coordinate (release / CI) |
|---|---|---|
| **When** | the dependency is checked out as a **sibling directory** | no sibling present (a lone CI checkout, or a store build) |
| **Source of the code** | built from the sibling's source via `includeBuild` | a prebuilt artifact from `jitpack.io`, pinned by commit |
| **Edit-rebuild loop** | instant - no publish/tag cycle | must push + let jitpack build a new commit |
| **Used by** | local co-development; the Windows build host (all repos live side-by-side) | GitHub Actions CI; app store builds |

## How it's wired

The catalog pins an **immutable jitpack coordinate** (a commit hash, never
`main-SNAPSHOT` - jitpack caches `-SNAPSHOT` unreliably and serves stale bytes,
which repeatedly broke CI). Then `settings.gradle.kts` overrides that coordinate
with the sibling project **only when the sibling exists**:

```kotlin
// settings.gradle.kts - engine consuming the SPI (capullo-audio)
if (file("../capullo-audio-contracts").exists()) {
    includeBuild("../capullo-audio-contracts") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-audio-contracts"))
                .using(project(":"))
        }
    }
}
```

```kotlin
// settings.gradle.kts - an app consuming the multi-module engine (quantumcast / telecloud-radio)
if (file("../capullo-audio").exists()) {
    includeBuild("../capullo-audio") {
        dependencySubstitution {
            // NOTE the NESTED group com.github.capullo-tech.capullo-audio: - see the multi-module note below.
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio"))
                .using(project(":capullo-audio"))
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio-ui"))
                .using(project(":capullo-audio-ui"))
        }
    }
}
```

- **Sibling present** → `includeBuild` + `dependencySubstitution` replace the
  coordinate with the local project. The pinned version in the catalog is ignored.
- **No sibling** → the `if` is false, the block is skipped, and the pinned jitpack
  coordinate resolves from `jitpack.io` (which must be in
  `dependencyResolutionManagement.repositories`).

Nothing switches manually. Check the repos out side-by-side and you get composite;
build one alone and you get jitpack.

## Coordinate shapes (must match on both sides of the substitution)

- **Single-module repo** (contracts, a source lib, `lib-media3-ffmpeg-android`,
  `build-conventions`): flat group - `com.github.capullo-tech:<repo>`.
- **Multi-module repo** (`capullo-audio` publishes `capullo-audio` **and**
  `capullo-audio-ui`): only the module whose name equals the repo name is served
  at the flat coordinate; **every other module is served only at the nested group**
  `com.github.capullo-tech.capullo-audio:<module>`. Use the **nested** group for
  *both* modules in the catalog and the substitution, or Gradle sees the engine
  twice (flat direct + nested transitive via `-ui`) and fails on duplicate classes.
- **lib-snapcast-android** (multi-module native): `com.github.capullo-tech.lib-snapcast-android:lib-snapcast-android`.

## The version catalog is different - it is *always* jitpack, never composite

`build-conventions` (this repo) is consumed as a **published version-catalog
artifact**, not as code, and a version catalog **cannot** be composite-substituted
(`from()` resolves a metadata artifact during settings evaluation, before any
`includeBuild` project graph exists). So every repo pins the catalog by jitpack
tag/commit in *all* modes:

```kotlin
versionCatalogs {
    create("libs") { from("com.github.capullo-tech:build-conventions:<tag>") }
}
```

This is fine: the catalog changes rarely (a toolchain bump), and the pinned
artifact is cached after first resolve. To develop the catalog against a repo
locally before publishing, `publishToMavenLocal` here and add `mavenLocal()` to
that repo's catalog repositories temporarily.

## Release flow (how a pinned commit gets there)

1. Land the library change; push; jitpack builds the commit on first request
   (warm it: `curl -s https://jitpack.io/com/github/capullo-tech/<repo>/<commit>/<artifact>.pom`).
2. Bump the consumer's catalog pin to that commit; build-verify (a **no-sibling**
   build with `--refresh-dependencies` proves pure-jitpack resolution before CI).
3. Push the consumer. CI (no siblings) exercises the jitpack path.

> **Multi-module warm-up:** for `capullo-audio`, warm **both** the `capullo-audio`
> and the `capullo-audio-ui` POMs, and ensure the repo's `jitpack.yml` runs
> `publishToMavenLocal` for **both** modules - otherwise `-ui` 401s.
