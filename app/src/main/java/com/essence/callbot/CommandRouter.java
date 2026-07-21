package com.essence.callbot;

import android.content.Context;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Single dispatch point for every control verb. Both the ADB broadcast
 * receiver and the GUI buttons call execute(), so behavior and event
 * logging are identical regardless of who issued the command.
 */
public final class CommandRouter {
    private CommandRouter() {}

    public static JSONObject execute(Context ctx, String source, String cmd, Bundle b) {
        if (b == null) b = new Bundle();
        if (cmd == null) return err("(null)", "missing --es cmd");
        EventLog.log(source, "cmd: " + cmd + summarize(b));
        try {
            switch (cmd.toLowerCase()) {
                case "status":
                    return StatusStore.get().toJson().put("ack", "ok").put("cmd", "status");

                case "answer":
                    return ack("answer", CallBotInCallService.answerCall());

                case "reject":
                    return ack("reject", CallBotInCallService.rejectCall());

                case "hangup":
                    return ack("hangup", CallBotInCallService.hangupCall());

                case "dtmf": {
                    String digits = b.getString("digits", "");
                    int toneMs = b.getInt("tone_ms", 250);
                    int gapMs = b.getInt("gap_ms", 250);
                    return ack("dtmf", CallBotInCallService.sendDtmf(digits, toneMs, gapMs));
                }

                case "mute":
                    return ack("mute", CallBotInCallService.setMute(b.getBoolean("on", true)));

                case "route":
                    return ack("route", CallBotInCallService.setRoute(b.getString("to", "")));

                case "autoanswer": {
                    boolean on = b.getBoolean("on", true);
                    int delay = b.getInt("delay_ms", Prefs.autoAnswerDelayMs());
                    Prefs.setAutoAnswer(on, delay);
                    StatusStore.get().setLastError(null); // force a publish so GUI refreshes
                    return ack("autoanswer", null);
                }

                case "play": {
                    String file = b.getString("file", "");
                    if (file.isEmpty()) file = Prefs.soundtrack();
                    if (file.isEmpty()) return err("play", "no file (pass --es file or pick one in Settings)");
                    boolean loop = b.getBoolean("loop", false);
                    int talkMs = b.getInt("talk_ms", Prefs.talkMs());
                    int pauseMs = b.getInt("pause_ms", Prefs.pauseMs());
                    String e = SoundtrackPlayer.get().start(ctx, file, loop, talkMs, pauseMs);
                    return ack("play", e);
                }

                case "stopplay":
                    SoundtrackPlayer.get().stop();
                    return ack("stopplay", null);

                case "recstart": {
                    String mode = b.getString("mode", Prefs.recMode());
                    String name = b.getString("name", "call");
                    RecorderService.startRec(ctx, mode, name);
                    return ack("recstart", null);
                }

                case "recstop":
                    RecorderService.stopRec(ctx);
                    return ack("recstop", null);

                case "setrecdir": {
                    String path = b.getString("path", "");
                    Prefs.setRecDirPath(path);
                    return ack("setrecdir", null);
                }

                case "reset":
                    SoundtrackPlayer.get().stop();
                    RecorderService.stopRec(ctx);
                    StatusStore.get().setLastError(null);
                    return ack("reset", null);

                default:
                    return err(cmd, "unknown command");
            }
        } catch (Exception e) {
            StatusStore.get().setLastError(cmd + ": " + e);
            return err(cmd, e.toString());
        }
    }

    private static JSONObject ack(String cmd, String errOrNull) {
        return errOrNull == null ? ok(cmd) : err(cmd, errOrNull);
    }

    private static JSONObject ok(String cmd) {
        JSONObject o = new JSONObject();
        try { o.put("ack", "ok").put("cmd", cmd); } catch (JSONException ignored) {}
        return o;
    }

    private static JSONObject err(String cmd, String reason) {
        JSONObject o = new JSONObject();
        try { o.put("ack", "err").put("cmd", cmd).put("reason", reason); } catch (JSONException ignored) {}
        return o;
    }

    private static String summarize(Bundle b) {
        if (b.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" {");
        boolean first = true;
        for (String k : b.keySet()) {
            if ("cmd".equals(k)) continue;
            if (!first) sb.append(", ");
            sb.append(k).append('=').append(b.get(k));
            first = false;
        }
        return sb.append('}').length() <= 3 ? "" : sb.toString();
    }
}
