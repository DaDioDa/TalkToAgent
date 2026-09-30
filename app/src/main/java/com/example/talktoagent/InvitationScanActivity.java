package com.example.talktoagent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import java.util.Collections;

/** Private bundled camera scanner: no external intent, deep link, image import/export or URI opening. */
public final class InvitationScanActivity extends Activity {
    static final String RESULT = "invitation";
    private DecoratedBarcodeView scanner;
    private boolean foreground, delivered;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        TextView instructions = new TextView(this);
        instructions.setText("掃描 Windows 終端的 TalkToAgent 有效邀請。不要分享 QR；不會儲存 QR 圖片。若無法辨識，請在電腦放大終端或重顯示邀請。");
        content.addView(instructions);
        scanner = new DecoratedBarcodeView(this); scanner.setSaveEnabled(false);
        scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        content.addView(scanner, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = new Button(this); cancel.setText("取消掃描"); cancel.setOnClickListener(v -> finish()); content.addView(cancel);
        setContentView(content);
        scanner.decodeSingle(result -> {
            if (!foreground || delivered) return;
            String uri = result.getText();
            try { Invitation.parse(uri); }
            catch (Exception failure) { instructions.setText("不是合法 TalkToAgent 邀請；請取消並重新掃描電腦上的有效 QR"); return; }
            delivered = true; scanner.pause();
            setResult(RESULT_OK, new Intent().putExtra(RESULT, uri)); finish();
        });
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { setResult(RESULT_CANCELED); finish(); return; }
        try { if (!delivered) scanner.resume(); }
        catch (SecurityException failure) { setResult(RESULT_CANCELED); finish(); }
    }
    @Override protected void onPause() { foreground = false; scanner.pause(); super.onPause(); }
}
