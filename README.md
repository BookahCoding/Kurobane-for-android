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
  messages are shown. After pairing, the alias is checked against `GET /api/config`. An exact enabled match wins.
  If the name is missing or disabled and `kogarashi` is enabled, that agent is selected and stored on this phone,
  with a one-line explanation. No other agent is substituted. A failed config fetch keeps the saved name.
- **Chat**: session list (`GET /api/sessions`) with search, friendly names, relative timestamps, rename and delete
  (`PUT` / `DELETE /api/sessions/{id}`), device-only project labels captioned "On this phone", and a draft kept per
  gateway and session. Open a transcript, start a new chat, and send messages over `/ws/chat` with streamed replies.
  Assistant replies render selectable Markdown (headings, emphasis, lists, inline code, fenced code, links) and can
  be copied. Thinking and tool arguments/results expand in the bubble. Running, error, and cancelled turns look
  different. PNG, JPEG, GIF, and WebP images upload with `POST /api/upload?agent=` and the returned marker is inserted
  into the message; other files are refused, and a local preview is shown before send. Each turn opens a socket and
  closes it when the turn ends. A dropped connection is not sent again.
- **Approvals**: Approve, Deny, and Always. Under Always, the app says the server still asks for every `always_ask`
  tool, including every shell call. One tap sends once. A dropped socket or a stale request id sends nothing.
  The same card stays in the open chat and in **Needs you**. If an approval arrives while the activity is not resumed,
  a notification is posted (Android 13+ asks for notification permission). The text says this is not reliable delivery
  while the app is killed. There is no foreground service and no push.
- **Simple and Advanced**: Simple is chat, sessions, and Needs you. Advanced also opens Dashboard, Agents, Tools,
  Skills, Jobs, Memory, Usage, Plugins, Personality (file names only), and Devices (name and type). Jobs, memory, and
  skills are read only. Empty cron and plugin lists say so. HTTP 401, an unreachable gateway, and a provider error
  use plain sentences.
- **Dashboard**: `GET /api/status` and `GET /api/health` (version, uptime, pairing, memory backend, process model).
- **Agents**: the configured list from `GET /api/config`, plus this agent's model and fallback from the config and
  `GET /api/config/prop`, shown next to the process model from `GET /api/status`. The two labels can differ.
- **Tools**: `GET /api/tools?agent=…` (name and description) for the connected agent.
- **Usage**: `GET /api/cost` shows tokens. When `usd` is 0 and tokens are unpriced, the app says the gateway did not
  price them and does not invent a cost.
- **Appearance**: eight Hokusai families from the Windows catalog (Washi & Ink, Great Wave, Red Fuji, Pine & Mist,
  Chrysanthemums, Peonies, Kirifuri Falls, Thunderstorm), light and dark, plus a light/dark/system choice stored on
  the phone. Text uses the platform font so Greek and large text stay readable.
- **Settings**: the connected gateway, **Forget this gateway**, appearance, Simple/Advanced, the installed version,
  and **Check for updates**.

The signed-in home follows Herald's shape: the open chat fills the screen and a sessions sidebar — a drawer on
phones — carries the chat list and the other destinations.
- **In-app updates** (release builds only): see [Updates](#updates).

Not implemented: voice input (no voice API is used), push or any delivery once the process is dead, creating jobs,
writing skills, and deleting memory. A debug-signed APK will not update over a release-signed install.

Unit tests cover parsing, the alias rule, Markdown blocks, image types, approval gating, and unpriced usage.
**Real-gateway and phone checks are separate** from those tests and from the emulator stand-in described above.

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
- REST used by the app: `GET /api/sessions`, `GET /api/sessions/running`, `GET /api/sessions/{id}/messages`,
  `POST /api/sessions/{id}/abort`, `PUT /api/sessions/{id}` (rename), `DELETE /api/sessions/{id}` (delete),
  `GET /api/config`, `GET /api/config/prop?path=`, `GET /api/status`, `GET /api/health`, `GET /api/tools?agent=…`,
  `GET /api/cron`, `GET /api/memory`, `GET /api/cost`, `GET /api/plugins`, `GET /api/skills/bundles`,
  `GET /api/agents/{alias}/skills`, `GET /api/personality?agent=`, `GET /api/devices`.
  `POST /api/upload?agent=` sends raw PNG, JPEG, GIF, or WebP bytes and returns `{path, marker}`.
- Session ids: `GET /api/sessions` returns both a display `session_id` (`gw_` stripped) and the full `session_key`;
  rename/delete use `session_key`, the WebSocket uses the display id.

## Privacy

Nothing is sent anywhere except your own gateway and GitHub's release API/downloads for the update check, which you
start yourself.

## License

MIT, see [LICENSE](LICENSE).
