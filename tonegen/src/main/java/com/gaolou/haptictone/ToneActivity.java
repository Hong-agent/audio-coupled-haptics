package com.gaolou.haptictone;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 自检工具：以 USAGE_MEDIA 播放一段「低频鼓点 + 中高频」测试音。
 *
 * <p>存在的意义：验证「外部应用声音 → 音频耦合触觉播放器 → 马达」整条链路。
 * 播放本应用的音频时，本应用自己不会被采集（Android 的 playback capture 只抓其它应用），
 * 所以必须用它来当"另一个 App"的声音源。
 */
public final class ToneActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF05070B);

        TextView title = new TextView(this);
        title.setText("触觉测试音");
        title.setTextColor(0xFFE8EFF9);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF5EEAD4);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dip(12), 0, dip(24));
        root.addView(status);

        TextView hint = new TextView(this);
        hint.setText("低频鼓点 55Hz + 440/1200Hz\n播放走前台媒体服务，可后台继续（通知栏可停止）\n最多 120 秒；也可以直接切回主应用观察电平");
        hint.setTextColor(0xFF8A9BB4);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dip(4), 1f);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        status.setText("已开始播放（可切后台）");
        ToneService.start(this);
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
