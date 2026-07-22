# Design note — "smart conversation" loopback

## Goal
Simulate a natural two-way conversation with the panel: a tester (or script) talks;
when they pause, the tool feeds audio back into the call after a short, variable delay,
so the panel experiences realistic back-and-forth live audio without a human staying on
the line. Variants: echo the tester's own captured speech, or play scripted clips gated
by voice-activity detection (VAD).

## The two primitives it needs
1. **Capture** call audio (downlink = panel's voice, uplink = tester's voice).
2. **Inject** processed audio back into the call **uplink** so the panel hears it.

## Feasibility per platform (established on the bench, 2026-07-22)
| Primitive | Stock phone | Rooted Pixel (Tensor) | Rooted Pixel + USB-Audio-Class adapter |
|---|---|---|---|
| Capture call audio | ❌ zero-filled by Android policy | ✅ `VOICE_CALL`/`VOICE_DOWNLINK` (BCR-proven) | ✅ (adapter loopback, no root needed) |
| Inject into uplink | ❌ (AEC cancels self-play; telephony HAL refuses) | ❌ **still blocked** (Tensor has no Qualcomm `tinymix` route; framework injection blocked since API 21) | ✅ adapter is the call mic — electrical inject, no AEC |

**Key conclusion:** rooting alone does **not** enable the loopback — the injection half is
unsolved on any Tensor phone even with root. The enabling piece is a **small USB Audio
Class adapter** (~$15–40) that the phone uses as the call headset. This also makes the
loopback **root-independent** and chipset-agnostic.

## Recommended architecture (PC-hosted, through a USB audio adapter)
```
 Panel (CP) ⇄ cellular ⇄ Phone (CallBot, default dialer)
                              │  USB-C
                              ▼
                     USB Audio Class adapter
                    (phone routes call speaker → adapter OUT,
                                 call mic     ← adapter IN)
                              │
                              ▼
                    PC audio pipeline (extends callbot_local_talker.ps1):
                      capture downlink  →  VAD (detect tester pause)
                      →  variable-delay buffer  →  playback into adapter IN (uplink)
```
- No AEC problem: the injected audio enters as the electrical mic signal, not acoustically.
- No root required for the loopback itself (root is still wanted, separately, for on-phone
  call recording via BCR).
- CallBot's role: hold the call (answer/route/keep-alive) and expose status; the PC does the
  DSP. A future `loopback` verb in `callbot_driver.py` would start/stop the PC pipeline and
  set delay/VAD threshold.

## Parameters to expose
- `delay_ms` (variable reply delay), `vad_threshold` / `pause_ms` (when to reply),
  `mode` = echo-user | scripted-clip, `max_reply_ms`.

## Major update (2026-07-22, researched): the audio bridge replaces root entirely
A wired/USB **headset** audio path exposes BOTH call directions to the PC **below the
app/permission layer**, so it needs **no root and no app permission**:
- Downlink (far end) comes out the headset output → PC captures it.
- Uplink is taken from the headset **mic** → PC drives it (inject / delayed loopback).

Requirements: a **4-pole CTIA headset** interface with a live **mic** pin (a 3-pole
headphone adapter leaves uplink on the phone's internal mic — no good), and on jackless
phones (POCO X5 Pro, Pixel 7a/8a) it must be an **active DAC** adapter (passive analog
over USB-C is dead on modern phones). Bench-verify once that the *cellular* uplink follows
to the external mic on our phone (strong indirect evidence, no model-specific doc).

**Consequence:** this rig gives injection **and** two-direction recording with no root, and
is *more* capable than root (Tensor has no rooted uplink-injection path). Root/BCR remains
only a "nice-to-have" for a pristine, fully-digital both-direction on-phone recording.

### Recommended rigs
- **Cleanest (~$465), fully digital, no analog level/bias work:** RØDECaster Duo (~$450) —
  phone connects **USB-C↔USB-C**, appears as a USB headset with mix-minus (kills echo), and
  is simultaneously a USB interface to the PC.
- **Budget (~$65), analog path:** UGREEN USB-C→3.5mm **DAC+Mic** adapter (~$15) + 4-pole TRRS
  mic/headphone splitter (~$6) + Behringer UCA202 or ~$10 USB sound card + inline mic-level
  pad/DC-block on the inject line (~$5). Gotcha: the mic pin carries bias — PC line-out must
  be padded to mic level, or the phone clips/mis-detects.
- **Middle (~$165):** Zoom PodTrak P4 (~$150) + the DAC headset adapter (jackless phone needs
  a jack to plug into the P4's TRRS port); PC gets a stereo mix.

## Status
Scoped, **not yet implemented** — waiting on the audio bridge hardware. Loopback + injection
+ recording all land together on the PC side once the bridge is in hand; the phone (rooted
or not) only needs to hold the call.
