# CallBot — bench call-debug dialer for the Essence CP benches

Fully ADB-controllable Android dialer app (`com.essence.callbot`) used to debug
CP (C8000/C7000) emergency voice calls. Replaces fragile `adb input keyevent`
control with real Telecom APIs, adds in-call audio injection ("person talking"
simulation), call recording, and a live GUI event log.

**Target bench phone:** POCO X5 Pro 5G (`22101320G`), Android 14 / HyperOS 2.0,
adb serial `f65b39ae`. Works on any Android 10+ phone (minSdk 29, targetSdk 34).

## Capabilities

| Area | What it does |
|---|---|
| Call control | answer / reject / hangup / DTMF sequences (`0-9A-D*#`, `p`=1s pause) via `InCallService` — no keyevents, works on MIUI/HyperOS |
| Audio | real Telecom uplink mute (`setMuted`), route speaker/earpiece/wired/BT |
| Auto-answer | on/off + delay ms, configurable via GUI or ADB |
| Soundtrack | plays MP3/AAC/OGG/FLAC/WAV into the **call uplink** (`USAGE_VOICE_COMMUNICATION`), loop + talk/pause cadence to simulate a person talking |
| Recording | layered source chain: `voicecall` → `voicereco` → `voicecomm` → `acoustic` (MIC+speaker, guaranteed floor). WAV 16 kHz mono, adb-pullable. Custom folder via Settings (SAF) |
| Telemetry | `status` command returns full JSON synchronously; `files/status.json`; logcat tag `CALLBOT`; per-session event log file with timestamps |
| GUI | dialer home (dialpad + status + live event log), in-call screen (timer, buttons, DTMF pad), settings. GUI taps and ADB commands share one code path and one log |

## ADB command surface

All commands are ordered broadcasts — the JSON result prints synchronously:

```
adb -s f65b39ae shell am broadcast -p com.essence.callbot -a com.essence.callbot.CMD --es cmd status
adb ... --es cmd answer
adb ... --es cmd reject
adb ... --es cmd hangup
adb ... --es cmd dtmf --es digits 13p9 --ei gap_ms 250 --ei tone_ms 250
adb ... --es cmd mute --ez on true
adb ... --es cmd route --es to speaker            # speaker|earpiece|wired|bluetooth
adb ... --es cmd autoanswer --ez on true --ei delay_ms 1500
adb ... --es cmd play --es file /sdcard/talk.mp3 --ez loop true --ei talk_ms 4000 --ei pause_ms 1500
adb ... --es cmd stopplay
adb ... --es cmd recstart --es mode auto --es name run1
       # modes: auto|voicecall|downlink|uplink|voicereco|voicecomm|mic|acoustic
adb ... --es cmd recstop
adb ... --es cmd setrecdir --es path /sdcard/CallBot
adb ... --es cmd settrack --es file /sdcard/callbot_talk.mp3   # default track for `play` with no file
adb ... --es cmd reset
```

Python driver (in the CP_ESP32 repo): `Tools/callbot_driver.py`
(`CallBot` class + CLI; `ensure-ready` does install/role/permission setup).

## Recordings & logs

- Recordings: `/sdcard/Android/data/com.essence.callbot/files/rec/*.wav`
- Event logs: `/sdcard/Android/data/com.essence.callbot/files/logs/events_*.log`
- Status file: `/sdcard/Android/data/com.essence.callbot/files/status.json`
- A custom recordings folder can be chosen in Settings (copied there on stop).

## Audio reality on the unrooted POCO X5 Pro (verified live 2026-07-22)

**Recording: impossible unrooted.** All app-accessible mic sources are zero-filled
by Android 14's concurrent-capture policy during cellular calls (668 s capture,
RMS 0.0, permissions all granted). `voicecall`/`downlink`/`uplink` need
`CAPTURE_AUDIO_OUTPUT` (signature|privileged) — kept in the fallback chain so a
future **rooted** phone works with zero code change.

**Uplink "talker" injection: also impossible from the phone itself.**
Telephony-device routing is HAL-refused (error -19); `USAGE_VOICE_COMMUNICATION`
plays locally only; acoustic self-playback is cancelled from the TX by the
in-call AEC (the phone's own speaker output is the AEC reference).
**Working path: play the soundtrack from the bench PC speakers next to the
phone** — external audio passes the AEC like a real person:
`python Tools/callbot_driver.py play-pc talk.mp3 --talk 4000 --pause 1500` / `stop-pc`.

Full matrix: CP_ESP32 `docs/findings/CALLBOT-AUDIO-PATHS-2026-07-22.md`.

## Build

Toolchain (already provisioned on the bench PC):
- JDK 17+ (Temurin 21 on PATH works)
- Android SDK at `D:\Essence_SC\dev\android-sdk` (platforms;android-34, build-tools;34.0.0)
- Gradle 8.7 at `D:\Essence_SC\dev\gradle-8.7` (wrapper committed after first `gradle wrapper`)

```powershell
cd D:\Essence_SC\src\CallBot
.\gradlew.bat assembleDebug
# -> app\build\outputs\apk\debug\app-debug.apk
```

`local.properties` (git-ignored) must contain `sdk.dir=D:\\Essence_SC\\dev\\android-sdk`.

## Install & one-time phone setup (HyperOS)

```powershell
python Tools/callbot_driver.py ensure-ready --apk app\build\outputs\apk\debug\app-debug.apk
```

That script does, idempotently:
1. `adb install -t -r app-debug.apk`
2. `cmd role add-role-holder --user 0 android.app.role.DIALER com.essence.callbot`
   (falls back to showing the RoleManager dialog — tap "Set as default" once)
3. `pm grant` RECORD_AUDIO / READ_PHONE_STATE / CALL_PHONE / POST_NOTIFICATIONS
4. doze whitelist + `appops RUN_IN_BACKGROUND allow`

Manual one-time taps that may still be needed on HyperOS:
- Security app → Autostart → enable CallBot
- Battery saver → No restrictions for CallBot

## Bench notes

- **LTE call end rule:** hanging up on the phone does NOT cancel the CP alarm —
  also send `ALARM OFF:;` on the CP TestShell (cilogg action 224).
- Muting the uplink (`mute`) does NOT block the injected soundtrack — injection
  enters the TX path after the mic mute (proven on the dtmf_apk predecessor).
- The old `com.essence.dtmftester` APK (Tools/dtmf_apk in CP_ESP32) is
  superseded by CallBot but kept intact for the legacy Poco F1 phone.
