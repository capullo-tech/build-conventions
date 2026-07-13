# build-conventions

The **shared toolchain single-source-of-truth** for the
[Capullo Audio Platform](https://github.com/capullo-tech). One repo publishes one
`libs.versions.toml`; every other repo imports it, so a toolchain bump happens
**once here** and rolls out everywhere instead of drifting across ~9 repos
(the "six repos drift on versions" risk).

## What's published

A [Gradle version catalog](https://docs.gradle.org/current/userguide/version_catalogs.html)
artifact (`packaging=toml`) built by the `version-catalog` plugin from
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). It carries the org
toolchain (AGP · Kotlin · KSP · Hilt · Spotless/ktlint) plus every library used
by two or more repos (coroutines, ktor, media3, room, compose BOM + compose,
navigation3, junit/espresso …) and the union of all `[plugins]` aliases.

## How a repo consumes it

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // serves build-conventions + the other org libs
    }
    versionCatalogs {
        create("libs") { from("com.github.capullo-tech:build-conventions:<tag>") }
    }
}
```

Then build files reference `libs.<alias>` / `libs.plugins.<alias>` exactly as
with a local catalog.

### The two-catalog rule

`from()` **cannot** be combined with inline `version()`/`library()` entries in the
same catalog, and some versions legitimately can't be shared. So each repo keeps a
**second, local catalog** - `pins` - for:

- **inter-repo capullo pins** (`capulloAudio`, `capullo-audio-ui`, `contracts`,
  `capulloSource*`, `libSnapcast`, `libTdlibAndroid`, `libMedia3Ffmpeg`) - these
  are pinned **per-app, per-release** and must move independently; and
- **single-repo deps** (newpipe, reorderable, coil, mockk, tdlib …).

```kotlin
// settings.gradle.kts (a repo that has local pins)
versionCatalogs {
    create("libs") { from("com.github.capullo-tech:build-conventions:<tag>") }
    create("pins") { from(files("gradle/pins.versions.toml")) }
}
```

Rule of thumb: **any shared *version* goes in `libs`** (unused entries are free);
**pins and one-repo libraries go in `pins`.** `capullo-audio-contracts` is the
pure case - all commons, no pins, so it needs no `pins` catalog at all.

## Bumping the toolchain

1. Edit [`gradle/libs.versions.toml`](gradle/libs.versions.toml) here, commit, push.
2. `./gradlew publishToMavenLocal` locally (or let jitpack build the new commit).
3. In each consuming repo, re-pin the `from(...:<tag>)` coordinate to the new
   commit/tag and build-verify. (Consumers pin an **immutable commit**, not
   `main-SNAPSHOT` - jitpack caches `-SNAPSHOT` unreliably; see the other org libs.)

## Build / verify (no Android SDK needed - pure metadata)

```bash
./gradlew generateCatalogAsToml   # regenerate build/version-catalog/libs.versions.toml
./gradlew publishToMavenLocal     # what jitpack runs; installs the .toml to ~/.m2
```

## Docs

- [Composite-build ↔ jitpack toggle](docs/composite-vs-jitpack.md) - the dev-vs-release switch every ship relies on.
- [Playbook: add a new app / add a new source](docs/playbook-add-app-or-source.md).

## Coordinates

`com.github.capullo-tech:build-conventions:<tag>` (single-artifact, jitpack;
same shape as `lib-media3-ffmpeg-android`).

## License

Copyright 2026 capullo-tech. Licensed under GPLv3 - see [`LICENSE`](LICENSE).
