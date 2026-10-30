# Kurobane for Android

Kurobane (黒羽, "black wing") is a native Android client for a **ZeroClaw gateway** you already run on a server,
homelab, VPS or Tailnet. The app talks to your gateway directly — there is no Kurobane server and no account.
Modeled on the [herald](https://github.com/BookahCoding/herald) Hermes Android client.

## Features

- **Pairing sign-in**: exchange the pairing code (`zeroclaw gateway pair` / the dashboard) for a bearer token;
  the token is stored in the **Android Keystore** (EncryptedSharedPreferences), never in plain files
- **Chat** over the gateway WebSocket (`/ws/chat`, `zeroclaw.v1`): streaming replies, live tool calls/results,
  approval prompts with Approve/Deny, token/cost summary on `done`, abort a running turn
- **Sessions**: list (`GET /api/sessions`), open transcripts, new chat, rename, delete
- **Tailscale-ready**: plain HTTP is allowed only for `*.ts.net` and the `100.64.0.0/10` range
  (see `res/xml/network_security_config.xml`); everything else requires HTTPS
- **Themes**: Hokusai-inspired theme families (Washi & Ink, Great Wave, Red Fuji, Kirifuri Falls) with light/dark
- **In-app updates**: anonymous `releases/latest` check on GitHub; only strict `vX.Y.Z` stable releases with the
  exact `kurobane-android.apk` asset are offered, and never a downgrade. Install prompts come from Android itself
  (`REQUEST_INSTALL_PACKAGES`)

## Requirements

- Android 8.0+ (API 26+)
- A running ZeroClaw gateway reachable from the phone

## Install

Download `kurobane-android.apk` from the [latest release](https://github.com/BookahCoding/Kurobane-for-android/releases/latest)
and install it (allow "install from this source" when Android asks). Open the app, enter your gateway address
(e.g. `http://100.64.0.2:42617` over Tailscale), the agent alias, and a pairing code, then **Pair and connect**.

## Build from source

You need the Android SDK (compileSdk 37) and JDK 17.

```bash
./gradlew :shared:core:allTests     # unit tests
./gradlew :androidApp:assembleDebug  # debug APK
```

## Release

Push a tag `vX.Y.Z` → `.github/workflows/release.yml` builds a signed APK (secrets `ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) and publishes it as a GitHub Release
named `kurobane-android.apk`, which is what the in-app updater looks for. Add plain-text notes at
`docs/release-notes/vX.Y.Z.txt`.

## Protocol notes

- Auth: `POST /api/pair` → `Authorization: Bearer <token>` (WebSocket also accepts the `bearer.<token>` sub-protocol)
- Chat: `GET /ws/chat?agent_alias=…&session_id=…&name=…`; send `{"type":"message","content":"…"}`;
  receive `session_start`, `connected`, `chunk`, `thinking`, `tool_call`, `tool_result`, `approval_request`,
  `done`, `aborted`, `error`; answer approvals with `{"type":"approval_response","request_id":…,"decision":"approve|deny|always"}`
- REST: `GET /api/status`, `GET /api/sessions`, `GET /api/sessions/{id}/messages`, `DELETE`/`PUT /api/sessions/{id}`,
  `POST /api/sessions/{id}/abort`, `GET /api/config`, `PUT /api/config/prop?path=…`

## Privacy

Nothing is sent anywhere except your own gateway and GitHub's release API for the (optional) update check.
