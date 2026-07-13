# Playbook - add a new source, or a new app

The platform is a layered DAG: Layer-0 native/prebuilt
forks → **L1 `capullo-audio-contracts`** (SPI) → **L2 `capullo-audio`** (engine) →
**L3 `capullo-source-*`** (ingress) → **L4 apps**. Adding a source or an app is
"fill in one layer against fixed contracts," so both are turnkey. Dependencies point
**one way only** (L0→L4); no library may depend on an app or a sibling source.

---

## A. Add a new source (`capullo-source-<name>`)

A source answers "*where does audio + metadata come from*." It implements the SPI and
nothing more - it is Media3-free, DI-free, and knows nothing about Snapcast or delivery.

### 1. Scaffold the repo (mirror `capullo-source-radiobrowser`)

```
capullo-source-<name>/
  settings.gradle.kts      # catalog import + contracts composite/jitpack toggle (below)
  build.gradle.kts         # root: alias(libs.plugins.android.library) etc.
  gradle/pins.versions.toml # local pins catalog: contracts pin + any one-repo deps
  src/main/…               # the source implementation
  src/test/…               # the fake-driven contract test 
  app/                     # minimal :app harness that wires the source to CapulloAudioEngine
  .github/workflows/       # build.yml, codeql.yml, Release.yml (copy from a source sibling)
  jitpack.yml
  LICENSE (GPLv3) · README.md
```

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        create("libs") { from("com.github.capullo-tech:build-conventions:<tag>") } // shared toolchain
        create("pins") { from(files("gradle/pins.versions.toml")) }                // local pins + one-offs
    }
}
rootProject.name = "capullo-source-<name>"
include(":capullo-source-<name>", ":app")

// contracts: composite when checked out as a sibling, jitpack otherwise.
if (file("../capullo-audio-contracts").exists()) {
    includeBuild("../capullo-audio-contracts") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-audio-contracts")).using(project(":"))
        }
    }
}
```

`gradle/pins.versions.toml` (only what isn't in the shared catalog):

```toml
[versions]
contracts = "main-SNAPSHOT"   # or an immutable commit for a pinned release
# … any deps unique to THIS source (an HTTP client, a parser, a prebuilt L0 fork pin) …

[libraries]
capullo-audio-contracts = { module = "com.github.capullo-tech:capullo-audio-contracts", version.ref = "contracts" }
# … the one-off libraries …
```

Build files then use `libs.*` for toolchain/commons and `pins.*` for the pins/one-offs.

### 2. Implement the contract

- `class <Name>Source : MediaSourceProvider, NowPlayingSource` (implement `NowPlayingSource`
  only if the source enriches now-playing - most do).
- `mediaRequestFor(id)`: resolve a logical id → `MediaRequest(uri, mimeType?, headers?)`.
  **For a fetch-based source** (download-then-play, like telegram) the now-playing side
  effect (ID3 tags, art) lives **here**, because it's only readable post-download - the
  engine calls `mediaRequestFor(idAt(i))` *before* `onQueueAdvanced(i)`.
- `queue()`: expose ordering; set `isRotating = true` for an endless single-station
  radio stream, `false` for a finite playlist (the engine auto-advances on track-end only
  when `!isRotating`).
- `onQueueAdvanced(i)`: **non-blocking, cancellable** prefetch (the source owns a
  `CoroutineScope` + a cancellable `Job`; a re-advance cancels the stale prefetch).
- App-facing entry (`setQueue(...)` / `loadStation(...)`) is **not** in the contract - it's
  the seam the app calls to populate the source.

### 3. Keep it neutral

No `androidx.media3.*` imports (inline the one `MimeTypes` constant if you need it, as
radiobrowser does). No Hilt/`@Inject` - plain constructors; the app's Hilt module `@Provides`
it. Push app-specific now-playing fields into `NowPlaying.extras`.

### 4. Validate the contract behaviourally (the real test)

Write a **fake-driven JVM driver test** (`<Name>SourceContractTest`, `testDebugUnitTest`,
runs on CI with no emulator) with fakes for the source's I/O (network client,
DAO). Drive the real loop: resolve → enrich now-playing → advance → prefetch lookahead →
cached-fast-path → re-advance-cancels-prefetch → unresolvable-track throws + self-purges.
A green compile is **not** proof the contract fits - this test is.

### 5. Wire the harness `:app`

Minimal app that constructs `CapulloAudioEngine` with the new source + fake app-side SPI
impls, and `assembleDebug`s. Proves the source is consumable and packages any L0 `.so` it
brings transitively.

### 6. CI + publish

Copy `build.yml` (`spotlessCheck` if configured → `testDebugUnitTest` → `assembleDebug`),
`codeql.yml`, `Release.yml` from a source sibling; add `jitpack.yml`
(`./gradlew publishToMavenLocal`). Push (org visibility needs the owner's OK). Coordinate:
`com.github.capullo-tech:capullo-source-<name>:<commit>`.

### 7. An app adopts it

The app adds the source's pin to *its* `pins` catalog, adds a composite toggle for it, and a
Hilt `@Provides` binding the source into its playback wiring.

---

## B. Add a new app

An app is **UI + navigation + DI + adapter wiring** only. All audio/transport/broadcast is
`capullo-audio`; all ingress is a `capullo-source-*`.

### 1. Scaffold to the org standard

Namespace `tech.capullo.<app>`. `settings.gradle.kts` imports the shared catalog as `libs`,
a local `pins` catalog, and composite/jitpack toggles for **each** first-party lib it
consumes (`capullo-audio` - remember the **nested** multi-module group for `-ui` - and the
source). Toolchain comes entirely from `libs` (AGP 9.1.0 / Kotlin 2.3.10 / Gradle 9.6.1 /
Compose BOM / Hilt / Nav3 / Spotless). Apply Hilt + Spotless.

### 2. Consume the engine (choose an integration strategy)

- **Facade** (`CapulloAudioEngine`): simplest - the engine drives the
  `queue()→mediaRequestFor→play→onQueueAdvanced` loop for you.
- **Direct transport** (what QC/TC do today, "Strategy 1"): the app keeps its own
  `PlaybackService`/`SnapcastManager` orchestration and consumes the engine's public transport
  classes (`SnapserverProcess`, `SnapclientProcess`, `SnapcastControlClient`, `SnapcontrolPlugin`,
  NSD/discovery, FIFO). More control; the app owns focus + service lifecycle.

Either way: give each app instance its own identity for multi-instance coexistence -
`SnapserverProcess(context, streamName = "<App>", ports = SnapserverPorts.free())` (dynamic
ports) and the per-app abstract control socket `snapcontrol.<packageName>` (fix A). Advertise
the resolved `httpPort` via NSD so listen-in/web-player URLs read the real port.

### 3. Shared client UI

Consume `capullo-audio-ui` for the Snapcast control sheet + listen-in QR dialog rather than
copying them. App-specific now-playing/nav stays in the app.

### 4. DI

A Hilt `@Module @InstallIn(SingletonComponent)` `@Provides` the source (it's DI-free) and any
app-side SPI impls (artwork provider, `PlaybackController` → your transport). Contracts + engine
are DI-agnostic by design; the app binds.

### 5. Security defaults

If the source persists sensitive local state (e.g. a Telegram session), require encryption at
rest (`databaseEncryptionKey` from Keystore/EncryptedSharedPrefs) and set `allowBackup=false`.
The snapserver web player / `/jsonrpc` control surface is unauthenticated and LAN-scoped - see
[SECURITY.md](../SECURITY.md).

### 6. CI + verify

The three workflows (`Build`/`codeql`/`Release`) adapted for the app's assemble task. **Verify
on device**, not just green: broadcast to a snapclient, listen-in, web-player now-playing +
transport, and (for a playlist source) next/prev. A green build compiles; it does not prove it
plays.

---

## Quick reference - what goes where

| Concern | Lives in |
|---|---|
| Toolchain + any ≥2-repo version | shared `libs` catalog (this repo) |
| Inter-repo capullo pins, one-repo deps | each repo's local `pins` catalog |
| SPI (contracts) | `capullo-audio-contracts` - changes rarely, SemVer |
| Player, FIFO, Snapcast, NSD, web player, control | `capullo-audio` |
| Shared client Compose (control sheet, QR) | `capullo-audio-ui` |
| "Where audio comes from" | a `capullo-source-*` |
| UI / nav / DI / focus / service lifecycle | the app |
