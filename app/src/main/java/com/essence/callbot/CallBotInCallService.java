package com.essence.callbot;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.VideoProfile;

/**
 * Telecom core. Bound by the system while CallBot holds ROLE_DIALER.
 * All call control (GUI buttons and ADB commands) funnels through the
 * static helpers below — no keyevents anywhere.
 */
public class CallBotInCallService extends InCallService {

    public static volatile CallBotInCallService sInstance = null;
    public static volatile Call sActiveCall = null;

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static HandlerThread sDtmfThread;
    private static Handler sDtmf;

    private final Call.Callback mCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            String name = stateName(state);
            EventLog.log("TELECOM", "call state -> " + name);
            StatusStore.get().setCallState(name, numberOf(call));
            if (state == Call.STATE_ACTIVE) {
                StatusStore.get().setCallStartElapsed(SystemClock.elapsedRealtime());
            } else if (state == Call.STATE_DISCONNECTED) {
                StatusStore.get().setCallStartElapsed(0);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        if (sDtmfThread == null) {
            sDtmfThread = new HandlerThread("callbot-dtmf");
            sDtmfThread.start();
            sDtmf = new Handler(sDtmfThread.getLooper());
        }
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        sActiveCall = call;
        call.registerCallback(mCallback);
        int state = call.getDetails().getState();
        String number = numberOf(call);
        EventLog.log("TELECOM", "call added: " + number + " state=" + stateName(state));
        StatusStore.get().setCallState(stateName(state), number);

        // bring up the in-call screen (also gives us a foreground context for the mic FGS)
        Intent ui = new Intent(this, InCallActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(ui);

        if (state == Call.STATE_RINGING && Prefs.autoAnswerOn()) {
            int delay = Prefs.autoAnswerDelayMs();
            EventLog.log("TELECOM", "auto-answer in " + delay + " ms");
            sMain.postDelayed(() -> {
                Call c = sActiveCall;
                if (c != null && c.getDetails().getState() == Call.STATE_RINGING) {
                    c.answer(VideoProfile.STATE_AUDIO_ONLY);
                }
            }, delay);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        call.unregisterCallback(mCallback);
        if (sActiveCall == call) sActiveCall = null;
        EventLog.log("TELECOM", "call removed");
        StatusStore.get().setCallState("IDLE", "");
        StatusStore.get().setCallStartElapsed(0);
        // call is gone: stop the simulated talker and the recorder
        SoundtrackPlayer.get().stop();
        RecorderService.stopRec(this);
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        String route = routeName(state.getRoute());
        EventLog.log("AUDIO", "muted=" + state.isMuted() + " route=" + route);
        StatusStore.get().setAudio(state.isMuted(), route);
    }

    // --- static control API (returns null on success, error string otherwise) ---

    public static String answerCall() {
        Call c = sActiveCall;
        if (c == null) return "no_active_call";
        c.answer(VideoProfile.STATE_AUDIO_ONLY);
        return null;
    }

    public static String rejectCall() {
        Call c = sActiveCall;
        if (c == null) return "no_active_call";
        c.reject(false, null);
        return null;
    }

    public static String hangupCall() {
        Call c = sActiveCall;
        if (c == null) return "no_active_call";
        c.disconnect();
        return null;
    }

    /**
     * Play a DTMF sequence on the active call, asynchronously.
     * digits: 0-9 A-D * # plus 'p' or ',' = 1 s pause.
     */
    public static String sendDtmf(String digits, int toneMs, int gapMs) {
        Call c = sActiveCall;
        if (c == null) return "no_active_call";
        if (digits == null || digits.isEmpty()) return "no_digits";
        if (sDtmf == null) return "service_not_ready";
        sDtmf.post(() -> {
            for (char raw : digits.toCharArray()) {
                char d = Character.toUpperCase(raw);
                Call call = sActiveCall;
                if (call == null) { EventLog.log("TELECOM", "dtmf aborted: call gone"); return; }
                try {
                    if (d == 'P' || d == ',') {
                        Thread.sleep(1000);
                    } else if ((d >= '0' && d <= '9') || (d >= 'A' && d <= 'D') || d == '*' || d == '#') {
                        call.playDtmfTone(d);
                        Thread.sleep(toneMs);
                        call.stopDtmfTone();
                        EventLog.log("TELECOM", "dtmf sent: " + d);
                        Thread.sleep(gapMs);
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        return null;
    }

    public static String setMute(boolean on) {
        CallBotInCallService svc = sInstance;
        if (svc == null) return "incall_service_not_bound";
        svc.setMuted(on);
        return null;
    }

    public static String setRoute(String to) {
        CallBotInCallService svc = sInstance;
        if (svc == null) return "incall_service_not_bound";
        int route;
        switch (to == null ? "" : to.toLowerCase()) {
            case "speaker":   route = CallAudioState.ROUTE_SPEAKER; break;
            case "earpiece":  route = CallAudioState.ROUTE_EARPIECE; break;
            case "wired":     route = CallAudioState.ROUTE_WIRED_HEADSET; break;
            case "bluetooth": route = CallAudioState.ROUTE_BLUETOOTH; break;
            default: return "bad_route (use speaker|earpiece|wired|bluetooth)";
        }
        svc.setAudioRoute(route);
        return null;
    }

    // --- helpers -----------------------------------------------------------

    static String numberOf(Call call) {
        Uri h = call.getDetails().getHandle();
        return h != null ? h.getSchemeSpecificPart() : "unknown";
    }

    static String routeName(int route) {
        switch (route) {
            case CallAudioState.ROUTE_EARPIECE:      return "earpiece";
            case CallAudioState.ROUTE_SPEAKER:       return "speaker";
            case CallAudioState.ROUTE_WIRED_HEADSET: return "wired";
            case CallAudioState.ROUTE_BLUETOOTH:     return "bluetooth";
            default:                                 return "route_" + route;
        }
    }

    static String stateName(int s) {
        switch (s) {
            case Call.STATE_NEW:                  return "NEW";
            case Call.STATE_DIALING:              return "DIALING";
            case Call.STATE_RINGING:              return "RINGING";
            case Call.STATE_HOLDING:              return "HOLDING";
            case Call.STATE_ACTIVE:               return "ACTIVE";
            case Call.STATE_DISCONNECTED:         return "DISCONNECTED";
            case Call.STATE_SELECT_PHONE_ACCOUNT: return "SELECT_PHONE_ACCOUNT";
            case Call.STATE_CONNECTING:           return "CONNECTING";
            case Call.STATE_DISCONNECTING:        return "DISCONNECTING";
            case Call.STATE_PULLING_CALL:         return "PULLING";
            default:                              return "STATE_" + s;
        }
    }
}
