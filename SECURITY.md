# Security policy - Capullo Audio Platform

This is the platform-wide security posture for the capullo-tech audio stack
(`capullo-audio` + `capullo-audio-ui`, the `capullo-source-*` libraries, and the
`quantumcast` / `telecloud-radio` apps). It documents the trust model, the known
exposed surfaces, and how to report an issue. Apps may drop a short `SECURITY.md`
pointing here.

## Reporting a vulnerability

Please report privately - do **not** open a public issue for an unfixed vuln.
Use GitHub **Security Advisories** ("Report a vulnerability") on the affected repo,
or email the maintainer. Include repro steps, affected commit/coordinate, and impact.
No formal SLA (hobby project); reports are triaged best-effort.

## Trust model

The platform assumes a **trusted local network**. A capullo app turns the device
into a Snapcast **broadcaster**: it runs an in-process `snapserver` that streams
audio to snapclients on the LAN and serves a browser web player. This is a LAN
multiroom-audio design, not an internet-facing service. **Do not expose these ports
to the internet** (no port-forwarding / DMZ). On an untrusted network (public Wi-Fi),
broadcasting exposes the surfaces below to everyone on that segment.

## Exposed surfaces

### 1. Snapserver ports (LAN, unauthenticated) - the main surface

`SnapserverProcess` binds three TCP ports (`capullo-audio/.../SnapserverProcess.kt`):

| Port | Purpose | Exposure |
|---|---|---|
| `[stream]` | PCM audio to snapclients | anyone on the LAN can receive the audio stream |
| `[tcp]` | Snapcast JSON-RPC (control) over raw TCP | unauthenticated control |
| `[http]` | web player (`doc_root`) + `/jsonrpc` (control WS) + `/stream` (audio WS) | unauthenticated; a browser is a full client |

- **No authentication.** Upstream Snapcast has no auth/ACL; the embedded server runs
  stock. Anyone who can reach the http/tcp port can read now-playing, enumerate
  clients, issue transport commands (play/pause/next), and set per-client
  volume/latency/channel.
- **Ports are randomized per run** (dynamic ports, `SnapserverPorts.free()`). This is defence-in-*obscurity* only: the resolved http port
  is advertised over mDNS/NSD (as the `http` TXT attribute) so listen-in and the
  web-player QR work, so it is discoverable on the LAN. Randomization prevents fixed-port
  collisions between coexisting apps; it is **not** an access control.
- **Bind interface.** The server binds all interfaces; scope it to the LAN via the
  network, not the app.

### 2. Cooperative stream-lock - UI-enforced, not server-enforced

The "lock" that stops non-broadcaster clients changing volume/latency/channel is
enforced in **both UIs** (the Android control sheet and the bundled web player) but
**not** in the server. It cannot be server-enforced: `Client.SetVolume/SetLatency/SetName`
are snapserver **core** RPCs sent client→server over `/jsonrpc`; they never transit the
`SnapcontrolPlugin` (which only speaks `Plugin.Stream.Player.*`), and stock snapserver has
no ACL and its notifications carry no initiator, so reversion is impossible too. **The lock
defends against cooperative clients only.** A hand-crafted `/jsonrpc` client bypasses it -
undefendable without patching snapserver. Treat the lock as UX, not a security boundary.

### 3. Per-app control socket namespacing - fixed (fix A)

Two capullo apps on one device previously clashed on a device-global abstract socket.
Resolved: the snapserver↔plugin control socket is namespaced per app -
`snapcontrol.<packageName>` (`SnapserverProcess.controlSocketName` /
`SnapcontrolPlugin.socketName`; native `--socket-name=` in `lib-snapcast-android`).
QC + TC broadcast simultaneously with no bind conflict.

**Residual (low severity, self-healing):** the `snapclient_channel` socket (native
snapclient's runtime L/R channel-switch control) is still shared. During an audio-focus
**handoff** the incoming app's snapclient can bind before the outgoing app's `destroy()`
releases it, so the loser logs `Channel control bind failed` and skips its ctrl thread.
**Audio is never interrupted; only the loser's runtime L/R toggle is lost until its next
snapclient restart.** Remedy if it ever bites: a Kotlin-side retry-with-backoff on the bind
in QC `PlaybackService` / TC `SnapcastManager` (the heavier per-app-name native edit in
`oboe_player.cpp` is only warranted if a retry proves insufficient). Not implemented -
parked until observed to matter.

### 4. Telegram (TDLib) session data - encrypted at rest

A Telegram session is account-level access. `capullo-source-telegram` requires an
app-generated `databaseEncryptionKey` (`TelegramCredentials`; 32-byte SecureRandom, stored
in Android Keystore / EncryptedSharedPreferences) - there is **no cleartext path**. Apps set
`allowBackup=false` so the session DB isn't captured by device backups. Fresh installs start
encrypted; a pre-existing cleartext DB must be wiped once on upgrade (the key can't open it).

## Data handling

- No first-party telemetry/analytics; no first-party backend. Apps talk to third-party
  services directly per source (Radio Browser API, Telegram/TDLib, YouTube via NewPipe for
  link enrichment). Audio never leaves the LAN - it's rebroadcast to local snapclients.
- Layer-0 forks (`lib-snapcast-android`, `lib-media3-ffmpeg-android`, `lib-tdlib-android`)
  ship **prebuilt** native binaries mirrored verbatim from pinned upstream commits (provenance
  in each repo's `NOTICE`). The supply-chain trust is in those upstreams; pins are immutable.

## Recommendations for deployers / integrators

- Broadcast only on a trusted LAN; never forward the snapserver ports to the internet.
- Treat any device on the segment as able to control playback while broadcasting.
- New apps/sources: follow the security defaults in the
  [playbook](docs/playbook-add-app-or-source.md) (encryption at rest for sensitive local
  state; `allowBackup=false`; don't assume the web/control surface is private).

## Scope / non-goals

Hardening stock Snapcast with authentication is out of scope (it would fork the protocol and
break interop with standard snapclients/web clients). The platform's security boundary is the
**LAN**, by design.
