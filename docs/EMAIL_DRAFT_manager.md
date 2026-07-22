# Email draft — CallBot test-automation dialer

**To:** [Manager]
**Subject:** CallBot — in-house call-automation tool for panel VoIP/alarm testing (status + a small hardware request)

**Attachments:** callbot_dialer.png, callbot_incall.png, callbot_settings.png

---

Hi [Manager],

I want to share a tool I built this week and get a go-ahead on a small piece of hardware
that would unlock the rest of it.

## The problem

Our C8000 call testing depends on driving a real phone that answers the panel's
emergency calls. Until now we drove an Android phone with raw ADB key presses
("press 5 to answer, 6 to hang up"). That approach is fragile and, critically,
**could not send DTMF tones or control call audio** — so a whole class of call
scenarios (far-end disconnect codes, simulated conversation, call recording) was
simply not testable in an automated way.

## What I built — "CallBot"

CallBot is a small Android dialer app we install as the phone's default dialer. It is
fully controllable over ADB (so it scripts cleanly into our test harness) **and** has a
normal dialer UI for manual use. Everything a tester or a script does shows up in a
live, timestamped event log on screen (see screenshots).

Today it gives us, on the existing bench phone:

- **Full call control with no fragile key-presses**: answer / reject / hang up, mute,
  speaker/earpiece, configurable auto-answer.
- **Real DTMF tones** — the capability we were missing. This immediately produced a
  useful result (below).
- **Simulated "person talking"** into the call (plays an audio clip so the panel hears
  speech during the call).
- **Live status + event log**, and a Python driver so it plugs straight into our
  automated test scripts.

**First concrete win:** using real DTMF, I ran an unattended overnight campaign of
**161 panel emergency calls** with varying call length, pauses and end-methods. Result:
**161/161 passed, no failures**, and I confirmed that **DTMF "9" reliably ends the
panel's call (58/58)** while "0" does not — a detail that matters for reproducing the
open field bug and that we could not test before.

## What it can and cannot do — and why hardware matters

Some things are blocked by Android itself on a normal (non-rooted) phone, for security
reasons — not by our code:

| Capability | Current phone (stock) | Rooted Pixel | Rooted Pixel + small USB audio adapter |
|---|---|---|---|
| Answer/reject/hang-up, mute, routing | ✅ | ✅ | ✅ |
| Real DTMF tones | ✅ | ✅ | ✅ |
| Live status / event log / scripting | ✅ | ✅ | ✅ |
| Play speech so the panel hears it | ⚠️ only via external PC speaker | ⚠️ same | ✅ clean, digital |
| **Record both sides of the call to a file** | ❌ blocked by Android | ✅ | ✅ |
| **Automated "conversation" simulator** (talk to the panel, it replies) | ❌ | ❌ | ✅ |

The two ❌ items are exactly what QA and I need for the harder call scenarios (proving
audio quality, and reproducing the intermittent field bug with realistic two-way audio).

## The request

1. **A Google Pixel (7a or 8a), unlocked model** — ~$250 (7a) to ~$400 (8a). Rooting a
   Pixel is officially supported and stable, and it unlocks **two-direction call
   recording** using a well-known, proven method (no risky custom code). The 7a is
   perfectly sufficient for our use; the 8a mainly buys longer software support.
2. *(Optional, ~$30)* a small **USB audio adapter** that lets us inject audio cleanly into
   the call and build an automated conversation simulator (see below). This is the only
   way to do it reliably — it is a hardware limitation of all modern phones, rooted or not.

Total is a modest, one-time bench cost, and it turns CallBot into a complete,
unattended call-testing rig.

## One idea I'd like to add

A "smart conversation" mode: a tester (or a script) talks to the panel, and when they
pause, the tool automatically feeds audio back into the call with a short, adjustable
delay — simulating a natural back-and-forth conversation without a person needing to
stay on the line. This is genuinely useful for stress-testing the panel's live-audio
behavior. It needs the small USB adapter above; I've scoped the design and it's ready to
build once the hardware is here.

Happy to give a live demo whenever convenient. Screenshots attached: the dialer, the
in-call controls (including the DTMF keypad and record button), and the settings screen.

Thanks,
Dmitry
