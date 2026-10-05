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
    // External camera-widget boundary; normal execution always uses the bundled ZXing scanner.
    static java.util.function.Function<android.content.Context, DecoratedBarcodeView> scannerFactory = DecoratedBarcodeView::new;
    private DecoratedBarcodeView scanner;
    private boolean foreground, delivered;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        android.content.Context themed = new androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_TalkToAgent_NativeC);
        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(NativeCStyle.SURFACE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(themed);
        scroll.setFillViewport(true);
        LinearLayout content = NativeCStyle.card(themed, NativeCStyle.SURFACE);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView title = new TextView(themed);
        title.setText("掃描連線邀請"); title.setTextSize(24); title.setTextColor(NativeCStyle.GREEN);
        title.setAccessibilityHeading(true); content.addView(title);
        LinearLayout explanation = NativeCStyle.card(themed, NativeCStyle.LOW);
        LinearLayout.LayoutParams cardBounds = new LinearLayout.LayoutParams(-1, -2);
        cardBounds.setMargins(0, NativeCStyle.dp(this, 16), 0, NativeCStyle.dp(this, 16));
        content.addView(explanation, cardBounds);
        TextView instructions = new TextView(themed);
        instructions.setTextSize(16); instructions.setTextColor(NativeCStyle.TEXT);
        instructions.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        instructions.setText("掃描 Windows 終端的 TalkToAgent 有效邀請。不要分享 QR；不會儲存 QR 圖片。掃描成功不等於應用授權或系統藍牙配對完成。若無法辨識，請在電腦放大終端或重顯示邀請。");
        explanation.addView(instructions);
        scanner = scannerFactory.apply(themed); scanner.setSaveEnabled(false);
        scanner.setContentDescription("連線邀請 QR 相機取景區");
        scanner.setStatusText("將有效連線邀請置於框內");
        scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        content.addView(scanner, new LinearLayout.LayoutParams(-1, NativeCStyle.dp(this, 280)));
        Button cancel = (Button) android.view.LayoutInflater.from(themed).inflate(R.layout.native_c_outlined_button, root, false);
        cancel.setText("取消掃描"); cancel.setContentDescription("取消掃描並返回裝置連線");
        cancel.setMinHeight(NativeCStyle.dp(this, 48)); cancel.setOnClickListener(v -> finish()); root.addView(cancel);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(r - l - root.getPaddingLeft() - root.getPaddingRight(), NativeCStyle.dp(this, 560));
            for (android.view.View child : new android.view.View[]{scroll, cancel})
                if (child.getLayoutParams().width != width) { child.getLayoutParams().width = width; child.requestLayout(); }
        });
        setContentView(root);
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
