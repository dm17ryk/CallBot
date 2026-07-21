package com.essence.callbot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings: auto-answer, recording mode, recordings folder (SAF picker),
 * soundtrack file (SAF picker), talk/pause cadence defaults.
 */
public class SettingsActivity extends Activity {
    private static final int REQ_REC_DIR = 10;
    private static final int REQ_TRACK = 11;
    private static final String[] REC_MODES =
            {"auto", "voicecall", "downlink", "uplink", "voicereco", "voicecomm", "mic", "acoustic"};

    private Switch mAutoAnswer;
    private EditText mDelay, mTalk, mPause;
    private Spinner mRecMode;
    private TextView mRecDir, mTrack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mAutoAnswer = findViewById(R.id.sw_autoanswer);
        mDelay = findViewById(R.id.edit_delay);
        mTalk = findViewById(R.id.edit_talk);
        mPause = findViewById(R.id.edit_pause);
        mRecMode = findViewById(R.id.spin_recmode);
        mRecDir = findViewById(R.id.txt_recdir);
        mTrack = findViewById(R.id.txt_track);

        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, REC_MODES);
        mRecMode.setAdapter(ad);

        // current values
        mAutoAnswer.setChecked(Prefs.autoAnswerOn());
        mDelay.setText(String.valueOf(Prefs.autoAnswerDelayMs()));
        mTalk.setText(String.valueOf(Prefs.talkMs()));
        mPause.setText(String.valueOf(Prefs.pauseMs()));
        for (int i = 0; i < REC_MODES.length; i++) {
            if (REC_MODES[i].equals(Prefs.recMode())) { mRecMode.setSelection(i); break; }
        }
        refreshPaths();

        findViewById(R.id.btn_pick_dir).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_REC_DIR);
        });
        findViewById(R.id.btn_reset_dir).setOnClickListener(v -> {
            Prefs.setRecDirUri("");
            Prefs.setRecDirPath("");
            refreshPaths();
        });
        findViewById(R.id.btn_pick_track).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i, REQ_TRACK);
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            Prefs.setAutoAnswer(mAutoAnswer.isChecked(), parse(mDelay, 0));
            Prefs.setRecMode(REC_MODES[mRecMode.getSelectedItemPosition()]);
            Prefs.setCadence(parse(mTalk, 0), parse(mPause, 0));
            EventLog.log("GUI", "settings saved: autoAnswer=" + mAutoAnswer.isChecked()
                    + "/" + parse(mDelay, 0) + "ms recMode="
                    + REC_MODES[mRecMode.getSelectedItemPosition()]
                    + " cadence=" + parse(mTalk, 0) + "/" + parse(mPause, 0));
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void refreshPaths() {
        String dir = !Prefs.recDirUri().isEmpty() ? Prefs.recDirUri()
                : !Prefs.recDirPath().isEmpty() ? Prefs.recDirPath()
                : getExternalFilesDir("rec") + " (default)";
        mRecDir.setText(dir);
        mTrack.setText(Prefs.soundtrack().isEmpty() ? "(none)" : Prefs.soundtrack());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_REC_DIR) {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Prefs.setRecDirUri(uri.toString());
            EventLog.log("GUI", "recordings folder set: " + uri);
        } else if (requestCode == REQ_TRACK) {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Prefs.setSoundtrack(uri.toString());
            EventLog.log("GUI", "soundtrack set: " + uri);
        }
        refreshPaths();
    }

    private int parse(EditText e, int dflt) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (NumberFormatException ex) { return dflt; }
    }
}
