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

## Status
Scoped, **not yet implemented** — waiting on the USB Audio Class adapter. Capture-only
(record) lands first with the rooted Pixel + BCR path; loopback follows with the adapter.
