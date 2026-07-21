package com.essence.callbot;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialer home screen: dialpad + live status panel + timestamped event log.
 * Every ADB command, GUI tap and Telecom transition shows up in the log.
 */
public class MainActivity extends Activity
        implements StatusStore.Listener, EventLog.Listener {

    private static final int REQ_PERMS = 1;
    private static final int REQ_ROLE = 2;
    private static final String[] DIGITS =
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};

    private TextView mStatus;
    private EditText mNumber;
    private Button mRoleBtn;
    private ArrayAdapter<String> mEvents;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatus = findViewById(R.id.txt_status);
        mNumber = findViewById(R.id.edit_number);
        mRoleBtn = findViewById(R.id.btn_role);

        GridLayout pad = findViewById(R.id.grid_dialpad);
        buildDialpad(pad);

        Button call = findViewById(R.id.btn_call);
        call.setOnClickListener(v -> placeCall());
        Button del = findViewById(R.id.btn_del);
        del.setOnClickListener(v -> {
            String t = mNumber.getText().toString();
            if (!t.isEmpty()) mNumber.setText(t.substring(0, t.length() - 1));
            mNumber.setSelection(mNumber.getText().length());
        });
        del.setOnLongClickListener(v -> { mNumber.setText(""); return true; });

        findViewById(R.id.btn_settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_incall).setOnClickListener(
                v -> startActivity(new Intent(this, InCallActivity.class)));
        mRoleBtn.setOnClickListener(v -> requestDialerRole());

        ListView list = findViewById(R.id.list_events);
        mEvents = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>(EventLog.snapshot()));
        list.setAdapter(mEvents);

        handleDialIntent(getIntent());
        requestRuntimePermissions();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDialIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatusStore.get().addListener(this);
        EventLog.addListener(this);
        onStatusChanged();
        mRoleBtn.setVisibility(isDefaultDialer() ? Button.GONE : Button.VISIBLE);
        if (!isDefaultDialer()) requestDialerRole();
    }

    @Override
    protected void onPause() {
        StatusStore.get().removeListener(this);
        EventLog.removeListener(this);
        super.onPause();
    }

    // --- listeners ---------------------------------------------------------

    @Override
    public void onStatusChanged() {
        StatusStore s = StatusStore.get();
        String line = "call: " + s.callState()
                + (s.number().isEmpty() ? "" : " (" + s.number() + ")")
                + "\nroute: " + s.route() + "   muted: " + s.muted()
                + "\nauto-answer: " + (Prefs.autoAnswerOn() ? Prefs.autoAnswerDelayMs() + " ms" : "off")
                + "   rec: " + s.recStateStr()
                + "   play: " + s.playbackState();
        mStatus.setText(line);
    }

    @Override
    public void onEvent(String line) {
        mEvents.add(line);
        while (mEvents.getCount() > 400) mEvents.remove(mEvents.getItem(0));
    }

    // --- actions -----------------------------------------------------------

    private void placeCall() {
        String num = mNumber.getText().toString().trim();
        if (num.isEmpty()) { toast("enter a number"); return; }
        EventLog.log("GUI", "place call: " + num);
        try {
            TelecomManager tm = getSystemService(TelecomManager.class);
            tm.placeCall(Uri.fromParts("tel", num, null), new Bundle());
        } catch (SecurityException e) {
            toast("CALL_PHONE not granted / not default dialer");
            StatusStore.get().setLastError("placeCall: " + e);
        }
    }

    private void buildDialpad(GridLayout pad) {
        pad.setColumnCount(3);
        for (String d : DIGITS) {
            Button b = new Button(this);
            b.setText(d);
            b.setTextSize(22);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setGravity(Gravity.FILL_HORIZONTAL);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                mNumber.append(d);
                mNumber.setSelection(mNumber.getText().length());
            });
            pad.addView(b);
        }
    }

    private boolean isDefaultDialer() {
        RoleManager rm = getSystemService(RoleManager.class);
        return rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
    }

    private void requestDialerRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE);
        }
    }

    private void requestRuntimePermissions() {
        List<String> need = new ArrayList<>();
        String[] wanted = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.RECORD_AUDIO,
                               Manifest.permission.READ_PHONE_STATE,
                               Manifest.permission.CALL_PHONE,
                               Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO,
                               Manifest.permission.READ_PHONE_STATE,
                               Manifest.permission.CALL_PHONE};
        for (String p : wanted) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE) {
            EventLog.log("GUI", "dialer role " +
                    (resultCode == RESULT_OK ? "GRANTED" : "denied (result=" + resultCode + ")"));
        }
    }

    private void handleDialIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null && "tel".equals(data.getScheme())) {
            mNumber.setText(data.getSchemeSpecificPart());
            mNumber.setSelection(mNumber.getText().length());
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
