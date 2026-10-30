# Kurobane for Android

Kurobane (黒羽, "black wing") is a native Android client for a **ZeroClaw gateway** you already run on a server,
homelab, VPS or Tailnet. The app talks to your gateway directly; there is no Kurobane server and no account.
Modeled on the [Herald](https://github.com/BookahCoding/herald) Android client.

## What works

Everything below was exercised on an Android 14 emulator against a small stand-in for the gateway that implements the
protocol as read from the ZeroClaw source (`crates/zeroclaw-gateway`, October 2026), plus unit tests for the parsing
and policy code. **It has not yet been run against a real ZeroClaw gateway**; if something differs on yours, please
open an issue.

- **Pairing**: enter the gateway address, agent alias and a pairing code; the app calls `POST /api/pair` and stores
  the bearer token in Keystore-backed `EncryptedSharedPreferences`. Wrong code, rate limit and unreachable-gateway
  messages are shown. After pairing, the alias is checked against `GET /api/config`: the gateway has no default agent,
  so if the alias you typed does not exist, the first enabled agent is used.
- **Chat**: session list (`GET /api/sessions`) with friendly names and relative timestamps, rename and delete
  (`PUT` / `DELETE /api/sessions/{id}`), open a transcript, start a new chat, send messages over `/ws/chat`
  with streamed replies, tool-call status, an Approve/Always/Deny prompt for tool approvals, an Abort button, and a
  token/cost summary. Each turn opens a socket and closes it when the turn ends. The approval round-trip and a 45-second turn were run on the emulator; Abort is covered by
  unit tests and the gateway source but was not exercised live.
- **Dashboard**: gateway health from `GET /api/status` (version, uptime, pairing state, memory backend, resolved
  model/provider, channels).
- **Agents**: the configured agent list from `GET /api/config` (alias, enabled, provider, channels).
- **Tools**: the tool registry from `GET /api/tools?agent=…` (name + description), scoped to the connected agent.
- **Settings**: shows the connected gateway, **Forget this gateway** (deletes the stored token), the installed version
  and **Check for updates**.

The signed-in home follows Herald's shape: the open chat fills the screen and a sessions sidebar — a drawer on
phones — carries the chat list plus Dashboard, Agents, Tools and Settings. Surfaces are colored from the Hokusai
theme catalog in `shared/designsystem` (Washi & Ink by default), the same families as the Windows client.
- **In-app updates** (release builds only): see [Updates](#updates).

Not implemented yet: Markdown rendering, a dark/themed UI (the app uses the default Material 3
light theme; `shared/designsystem` holds an unused theme catalog), tool-argument/result details, notifications.

## Install

1. Download `kurobane-android.apk` from the [latest release](https://github.com/BookahCoding/Kurobane-for-android/releases/latest)
   and install it (Android asks you to allow installing from your browser or file manager the first time).
   *No release has been published yet: the first one needs the signing key described under [Releasing](#releasing).
   Until then, build the debug APK yourself (see [Build from source](#build-from-source)).*
2. Start your gateway and get a pairing code (shown at startup while unpaired, or `zeroclaw gateway get-paircode --new`).
3. In the app enter the gateway address and the code, then **Pair and connect**.

Requires Android 8.0 or newer (API 26+).

## Connecting to the gateway (Tailscale)

The gateway listens on `127.0.0.1:42617` by default, so the phone cannot reach it until you expose it. Two options:

- **Recommended: `tailscale serve`**: proxy the local port over HTTPS on your tailnet and use
  `https://<machine>.<tailnet>.ts.net` in the app. Normal TLS, no cleartext.
- **Direct**: set `[gateway] host` to the machine's Tailscale address (or start the gateway with `--host`) and use
  `http://100.x.y.z:42617`. ZeroClaw logs a warning for any non-loopback bind unless `[gateway] allow_public_bind = true`
  (the setting only silences that warning; keep pairing enabled, and note that `0.0.0.0` exposes every interface).

### Cleartext (`http://`) policy and the trade-off

Android's `network_security_config.xml` can only match host *names*. A `<domain>` entry such as `100.64.0.0/10` is
treated as a literal host name and matches nothing, so the OS cannot restrict cleartext to the Tailscale range. The
app therefore lets the OS permit cleartext and enforces the rule itself, before any connection, in
`GatewayUrl.normalize`:

| Address | `http://` | `https://` |
|---|---|---|
| `100.64.0.0/10`, `fd7a:115c:a1e0::/48` (Tailscale), `*.ts.net` | allowed | allowed |
| `10/8`, `172.16/12`, `192.168/16` (private LAN), loopback, `localhost` | allowed | allowed |
| anything else | **rejected** | required |

A bare `100.64.0.2:42617` defaults to `http://`; any other bare host defaults to `https://`. The pairing code and
bearer token cross the network unencrypted on the `http://` ranges (Tailscale itself encrypts the tunnel; plain LAN
does not), so prefer `tailscale serve` or HTTPS where you can. The cost of this design is that the guarantee lives
in app code and its unit tests (`GatewayUrlTests`) rather than in the OS; all other app traffic (GitHub) uses
hard-coded `https://` URLs.

## Updates

**Settings → Check for updates** asks GitHub for this repository's `releases/latest`. A release is offered only if
it is stable (not draft or pre-release), its tag is `vMAJOR.MINOR.PATCH` and newer than the installed version, and it
has an asset named exactly `kurobane-android.apk` at the expected download address. **Download and install** then:

1. downloads the APK to the app cache and verifies its size and SHA-256 (the digest GitHub reports for the asset),
2. checks that it is this app, has a higher `versionCode`, and is signed with the **same certificate** as the
   installed app,
3. asks Android for the "install unknown apps" permission if needed (a button opens the right setting), and
4. hands the file to Android's package installer, which shows its own confirmation.

Clear messages are shown for no release found, offline/GitHub errors, rate limiting, a damaged download, a different
signing key and a missing install permission. The updater is switched off in builds without a release repository
(local builds, which are signed with the debug key and could not be updated over by a release anyway). If you
installed a debug build, uninstall it once before installing the release APK; after that, updates install in place.

## Build from source

Requirements: JDK 21 (Android Studio's bundled JBR works), the Android SDK with platform 37 and build-tools, and a
`local.properties` containing `sdk.dir=...` (Android Studio creates it).

```bash
./gradlew :shared:core:allTests      # unit tests
./gradlew :androidApp:lintDebug      # Android lint
./gradlew :androidApp:assembleDebug  # debug APK in androidApp/build/outputs/apk/debug/
```

CI (`.github/workflows/ci.yml`) runs the tests, lint, and debug and release builds on every push and pull request.

## Releasing

Versions are `MAJOR.MINOR.PATCH` (minor and patch below 100). The Android `versionCode` is
`MAJOR*10000 + MINOR*100 + PATCH`, so it always increases with the version, and `versionName` is the version itself.
Both come from the tag; the version in `androidApp/build.gradle.kts` is only the default for local builds.

**One-time setup: the signing key.** Every release must be signed with the same key or Android will refuse the
update. Create it once, back it up (losing it means users must uninstall to update), and never commit it:

```bash
keytool -genkeypair -v -keystore kurobane-release.jks -alias kurobane -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 kurobane-release.jks > kurobane-release.jks.b64        # PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("kurobane-release.jks"))
gh secret set ANDROID_KEYSTORE_B64 < kurobane-release.jks.b64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS          # kurobane
gh secret set ANDROID_KEY_PASSWORD
```

**Dry run.** In GitHub, *Actions → Release → Run workflow* with a version such as `0.5.1`. It runs the tests, builds
and signs the APK, verifies the signature, package name and `versionCode`, and attaches the APK as a workflow
artifact; nothing is published. It prints the signing certificate's SHA-256. Commit that fingerprint (public data)
to `release-signing-cert.sha256`; from then on the workflow refuses to publish an APK signed with any other key.

**Release.** Merge to `main`, then tag the merged commit:

```bash
git checkout main && git pull
git tag v0.5.1 && git push origin v0.5.1
```

The workflow checks that the tag is on `main` and newer than the latest release, rebuilds and re-verifies, and
publishes a GitHub Release with `kurobane-android.apk` (the name the updater looks for) and its `.sha256`. Release
notes come from `docs/release-notes/vX.Y.Z.txt` if present, otherwise they are generated.

## Protocol notes

Checked against the ZeroClaw gateway source:

- Pairing: `POST /api/pair` with `{"code","device_name","device_type"}` returns `{"paired":true,"token":…}`;
  failures are HTTP 400/403/429 with an `error` field. Authenticate afterwards with `Authorization: Bearer <token>`.
- Chat: `GET /ws/chat?agent_alias=…&session_id=…` (sub-protocol `zeroclaw.v1`; `agent_alias` is required). Send
  `{"type":"message","content":…}`; receive `session_start`, `chunk`, `thinking`, `tool_call`, `tool_result`,
  `approval_request`, `done`, `aborted`, `error`. Answer approvals with
  `{"type":"approval_response","request_id":…,"decision":"approve|deny|always"}`. The gateway keeps the socket open after
  `done`, so the client closes it.
- REST used by the app: `GET /api/sessions`, `GET /api/sessions/{id}/messages`, `POST /api/sessions/{id}/abort`,
  `PUT /api/sessions/{id}` (rename), `DELETE /api/sessions/{id}` (delete), `GET /api/config` (agent list),
  `GET /api/status` (dashboard), `GET /api/tools?agent=…` (tool registry).
- Session ids: `GET /api/sessions` returns both a display `session_id` (`gw_` stripped) and the full `session_key`;
  rename/delete use `session_key`, the WebSocket uses the display id.

## Privacy

Nothing is sent anywhere except your own gateway and GitHub's release API/downloads for the update check, which you
start yourself.

## License

MIT, see [LICENSE](LICENSE).
