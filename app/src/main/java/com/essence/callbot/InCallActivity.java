package com.essence.callbot;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.GridLayout;
import android.widget.TextView;

/**
 * In-call screen: caller id, live state, call timer, and the full control
 * set (answer / reject / hangup / mute / speaker / DTMF keypad / soundtrack /
 * record). Every tap goes through CommandRouter, so it behaves exactly like
 * the equivalent ADB command and lands in the event log.
 *
 * Launched by CallBotInCallService on every incoming/outgoing call; also
 * provides the foreground context that lets the microphone FGS start.
 */
public class InCallActivity extends Activity
        implements StatusStore.Listener, EventLog.Listener {

    private static final String[] DIGITS =
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};

    private TextView mNumber, mState, mTicker;
    private Chronometer mChrono;
    private Button mAnswer, mReject, mHangup, mMute, mSpeaker, mPlay, mRecord;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mSawCall = false;
    private boolean mChronoRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        setContentView(R.layout.activity_incall);

        mNumber = findViewById(R.id.txt_number);
        mState = findViewById(R.id.txt_state);
        mTicker = findViewById(R.id.txt_ticker);
        mChrono = findViewById(R.id.chrono);
        mAnswer = findViewById(R.id.btn_answer);
        mReject = findViewById(R.id.btn_reject);
        mHangup = findViewById(R.id.btn_hangup);
        mMute = findViewById(R.id.btn_mute);
        mSpeaker = findViewById(R.id.btn_speaker);
        mPlay = findViewById(R.id.btn_play);
        mRecord = findViewById(R.id.btn_record);

        mAnswer.setOnClickListener(v -> gui("answer", null, null));
        mReject.setOnClickListener(v -> gui("reject", null, null));
        mHangup.setOnClickListener(v -> gui("hangup", null, null));
        mMute.setOnClickListener(v -> {
            Bundle b = new Bundle();
            b.putBoolean("on", !StatusStore.get().muted());
            gui("mute", b, null);
        });
        mSpeaker.setOnClickListener(v -> {
            Bundle b = new Bundle();
            b.putString("to", "speaker".equals(StatusStore.get().route()) ? "earpiece" : "speaker");
            gui("route", b, null);
        });
        mPlay.setOnClickListener(v -> {
            if (!"stopped".equals(StatusStore.get().playbackState())) {
                gui("stopplay", null, null);
            } else {
                Bundle b = new Bundle();
                b.putBoolean("loop", true);
                gui("play", b, "no soundtrack file set (Settings)");
            }
        });
        mRecord.setOnClickListener(v -> {
            if ("recording".equals(StatusStore.get().recStateStr())) {
                gui("recstop", null, null);
            } else {
                Bundle b = new Bundle();
                b.putString("name", "manual");
                gui("recstart", b, null);
            }
        });

        GridLayout dtmf = findViewById(R.id.grid_dtmf);
        dtmf.setColumnCount(3);
        for (String d : DIGITS) {
            Button b = new Button(this);
            b.setText(d);
            b.setTextSize(18);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setGravity(Gravity.FILL_HORIZONTAL);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                Bundle bb = new Bundle();
                bb.putString("digits", d);
                gui("dtmf", bb, null);
            });
            dtmf.addView(b);
        }
    }

    private void gui(String cmd, Bundle extras, String errToastOverride) {
        try {
            org.json.JSONObject res = CommandRouter.execute(this, "GUI", cmd, extras);
            if ("err".equals(res.optString("ack"))) {
                String msg = errToastOverride != null ? errToastOverride : res.optString("reason");
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusStore.get().addListener(this);
        EventLog.addListener(this);
        onStatusChanged();
    }

    @Override
    protected void onPause() {
        StatusStore.get().removeListener(this);
        EventLog.removeListener(this);
        super.onPause();
    }

    @Override
    public void onStatusChanged() {
        StatusStore s = StatusStore.get();
        String state = s.callState();
        mNumber.setText(s.number().isEmpty() ? "—" : s.number());
        mState.setText(state);

        long base = s.callStartElapsed();
        if (base > 0) {
            if (!mChronoRunning) {
                mChrono.setBase(base);
                mChrono.start();
                mChronoRunning = true;
            }
        } else if (mChronoRunning) {
            mChrono.stop();
            mChronoRunning = false;
        }

        boolean ringing = "RINGING".equals(state);
        boolean inCall = "ACTIVE".equals(state) || "DIALING".equals(state)
                || "CONNECTING".equals(state) || "HOLDING".equals(state);
        mAnswer.setEnabled(ringing);
        mReject.setEnabled(ringing);
        mHangup.setEnabled(ringing || inCall);
        mMute.setText(s.muted() ? "Unmute" : "Mute");
        mSpeaker.setText("speaker".equals(s.route()) ? "Earpiece" : "Speaker");
        mPlay.setText("stopped".equals(s.playbackState()) ? "Play track" : "Stop track");
        mRecord.setText("recording".equals(s.recStateStr()) ? "Stop rec" : "Record");

        if (ringing || inCall) mSawCall = true;
        if (mSawCall && "IDLE".equals(state)) {
            mMain.postDelayed(() -> {
                if ("IDLE".equals(StatusStore.get().callState())) finish();
            }, 1500);
        }
    }

    @Override
    public void onEvent(String line) { mTicker.setText(line); }
}
