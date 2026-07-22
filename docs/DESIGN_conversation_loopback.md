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

Requirements: a **4-pole CTIA headset** connection with a live **mic** pin (a 3-pole
headphone plug leaves uplink on the phone's internal mic — no good). **The POCO X5 Pro has
a real 3.5mm jack**, so we tap it directly with a TRRS splitter — no DAC adapter needed
(only jackless phones like the Pixel 7a/8a would need an active-DAC USB-C headset adapter).
Bench-verify once that the *cellular* uplink follows to the external mic on our phone
(strong indirect evidence, no model-specific doc).

**Consequence:** this rig gives injection **and** two-direction recording with no root, and
is *more* capable than root (Tensor has no rooted uplink-injection path). Root/BCR remains
only a "nice-to-have" for a pristine, fully-digital both-direction on-phone recording.

### Recommended rigs (POCO X5 Pro has a 3.5mm jack — tap it directly)
- **Budget, functionally sufficient (~$20):** 4-pole TRRS mic/headphone **splitter** off the
  phone jack + a cheap PC **USB sound card** (line-in captures downlink; output feeds the
  phone mic). We control uplink content in software, so hardware mix-minus is not required.
  Gotcha: the mic pin carries bias — pad the PC output to mic level or the phone clips.
- **Turnkey, no level-fiddling (~$150):** **Zoom PodTrak P4** — has a dedicated TRRS **phone
  port with mix-minus** made for exactly this (plug the phone's 3.5mm jack straight in); it is
  simultaneously a USB interface to the PC.
- **NOT recommended:** RØDE **Streamer X** (video-capture/streaming console; its TRRS is a
  *headset input* for a person's headset — wrong direction — and it has no phone channel /
  mix-minus). RØDECaster Duo (~$450) works but connects the phone over USB-C, not the jack,
  and is overkill here.

## Status
Scoped, **not yet implemented** — waiting on the audio bridge hardware. Loopback + injection
+ recording all land together on the PC side once the bridge is in hand; the phone (rooted
or not) only needs to hold the call.
