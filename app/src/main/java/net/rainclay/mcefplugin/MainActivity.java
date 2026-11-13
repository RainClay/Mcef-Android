package net.rainclay.mcefplugin;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;

/**
 * NativeLibPlugin 协议要求有 LAUNCHER 入口，但插件本体没有交互逻辑。
 * 这个 Activity 只显示版本状态，给用户一个"装没装好"的确认入口。
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String dir = getApplicationInfo().nativeLibraryDir;
        File cef = new File(dir, "libcef.so");
        File mcef = new File(dir, "libmcef.so");
        String cefState = cef.isFile()
                ? "OK (" + cef.length() / 1024 / 1024 + " MB)" : "MISSING";
        String mcefState = mcef.isFile()
                ? "OK (" + mcef.length() / 1024 + " KB)" : "MISSING";
        TextView tv = new TextView(this);
        tv.setText("MCEF Engine Plugin\n"
                + "Cefrium 0.18.0 (Chromium 156.0.8078.80)\n\n"
                + "nativeLibraryDir:\n" + dir + "\n\n"
                + "libcef.so:  " + cefState + "\n"
                + "libmcef.so: " + mcefState + "\n\n"
                + "打开 ZalithLauncher 即可，无需其他操作。");
        tv.setPadding(48, 96, 48, 48);
        setContentView(tv);
    }
}
