package com.example.talktoagent;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Camera-free stand-in only for delivering a synthetic scanner result via Android. */
public final class DiagnosticScanReturnActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Isolated synthetic scan return diagnostic");
        setContentView(label);
    }
}
