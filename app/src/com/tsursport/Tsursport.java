package com.tsursport;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MockRun v3 -- 免 root 模拟定位：内置地图点选路线 / 配速 / 往返或环形循环。
 *
 * UI 是一个全屏 WebView（assets/index.html + 自带 leaflet，底图用高德栅格瓦片），
 * 原生层只做两件事：
 *   1) 被 JavaScript 调用后把路线交给前台服务；
 *   2) 用 LocationManager.addTestProvider 按配速推进 GPS 点。
 */
public class Tsursport extends Activity {

    WebView web;
    android.webkit.ValueCallback<Uri[]> filePathCallback;
    static final int REQ_FILE = 41;

    WindowManager wm;
    double cadenceStride = 1.05;
    boolean cadenceFollow = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBlockNetworkLoads(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.i("TsursportJS", m.message() + " @" + m.lineNumber());
                return true;
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                                       FileChooserParams params) {
                filePathCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "选择 GPX 文件"), REQ_FILE);
                    return true;
                } catch (Throwable t) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");

        if (getIntent() != null && getIntent().getBooleanExtra("ball", false)) {
            web.postDelayed(new Runnable() { public void run() { toggleBall(true); } }, 900);
        } else if (getSharedPreferences("tsursport", MODE_PRIVATE).getBoolean("ballOn", false)) {
            web.postDelayed(new Runnable() {
                public void run() { Ball.show(getApplicationContext()); }
            }, 600);
        }

        setContentView(web);

        // 调试/兜底：原生直接开始（与悬浮窗 ▶ 开始 同一路径）
        if (getIntent() != null && getIntent().getBooleanExtra("ballStart", false)) {
            getIntent().removeExtra("ballStart");
            web.postDelayed(new Runnable() {
                public void run() {
                    Ball.app = getApplicationContext();
                    Ball.askUiStart();
                }
            }, 1200);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("openSettings", false)) {
            getIntent().removeExtra("openSettings");
            web.postDelayed(new Runnable() {
                public void run() { web.evaluateJavascript("openModal ? openModal() : 0", null); }
            }, 900);
        }
        handleStartExtra(getIntent());

        // 申请通知权限（Android 13+），前台服务通知才可见
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACTIVITY_RECOGNITION"}, 1);
        }
    }


    void handleStartExtra(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("startRun", false)) return;
        intent.removeExtra("startRun");
        if (web != null) web.postDelayed(new Runnable() {
            public void run() { web.evaluateJavascript("if(window.startRun) window.startRun();", null); }
        }, 500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStartExtra(intent);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
            if (filePathCallback != null) { filePathCallback.onReceiveValue(out); filePathCallback = null; }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript("(window.closeTopOverlay && window.closeTopOverlay()) ? '1' : '0'",
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            if (v == null || !v.contains("1")) Tsursport.super.onBackPressed();
                        }
                    });
            return;
        }
        super.onBackPressed();
    }

    void unusedBack() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    public class Bridge {

        @JavascriptInterface
        public void saveRoute(String routeJson, double speed, String loop, double stride, boolean follow) {
            try {
                getSharedPreferences("tsursport", MODE_PRIVATE).edit()
                        .putString("route", routeJson)
                        .putString("speed", String.valueOf(speed))
                        .putString("loop", loop)
                        .putString("stride", String.valueOf(stride))
                        .putBoolean("follow", follow)
                        .apply();
                android.util.Log.i("TsursportRoute", "saved len=" + (routeJson == null ? -1 : routeJson.length())
                        + " speed=" + speed + " loop=" + loop);
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public boolean start(String routeJson, double speed, String loop) {
            if (!mockAllowed()) {
                toast("还没把 MockRun 设为模拟位置信息应用：设置 → 开发者选项 → 选择模拟位置信息应用");
                return false;
            }
            if (Build.VERSION.SDK_INT >= 29
                    && checkSelfPermission("android.permission.ACTIVITY_RECOGNITION")
                       != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.ACTIVITY_RECOGNITION"}, 3);
                // 不阻断：没有这个权限只是读不到步频，位置照样能模拟
            }
            if (!hasLocationPermission()) {
                toast("缺少定位权限，请允许后再试");
                requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, 2);
                return false;
            }
            getSharedPreferences("tsursport", MODE_PRIVATE).edit()
                    .putString("route", routeJson)
                    .putString("speed", String.valueOf(speed))
                    .putString("loop", loop)
                    .putString("stride", String.valueOf(cadenceStride))
                    .putBoolean("follow", cadenceFollow)
                    .apply();

            Intent i = new Intent(Tsursport.this, RunService.class);
            i.setAction(RunService.ACTION_START);
            i.putExtra(RunService.EX_ROUTE, routeJson);
            i.putExtra(RunService.EX_SPEED, speed);
            i.putExtra(RunService.EX_LOOP, loop);
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                i.putExtra("stride", cadenceStride);
                i.putExtra("followCadence", cadenceFollow);
            } catch (Throwable ignored) { }
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } catch (Throwable t) {
                android.util.Log.w("Tsursport", "start failed: " + t);
                toast("启动失败：" + t.getMessage());
                return false;
            }
            return true;
        }

        @JavascriptInterface
        public void setRealism(int level, boolean jitter, boolean sat) {
            RunService.realism = Math.max(0, Math.min(2, level));
            RunService.jitterOn = jitter;
            RunService.satOn = sat;
        }

        @JavascriptInterface
        public void setCadenceMode(boolean follow, double stride) {
            cadenceStride = stride <= 0 ? 1.05 : stride;
            cadenceFollow = follow;
        }

        @JavascriptInterface
        public boolean mockReady() {
            return mockAllowed() && hasLocationPermission();
        }

        @JavascriptInterface
        public void stop() {
            Intent i = new Intent(Tsursport.this, RunService.class);
            i.setAction(RunService.ACTION_STOP);
            startService(i);
        }

        @JavascriptInterface
        public String state() {
            return RunService.stateJson();
        }

        @JavascriptInterface
        public void setPaused(boolean p) {
            try {
                Intent i = new Intent(Tsursport.this, RunService.class);
                i.setAction(p ? RunService.ACTION_PAUSE : RunService.ACTION_RESUME);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void setTheme(String mode) {
            getSharedPreferences("tsursport", MODE_PRIVATE).edit().putString("theme", mode).apply();
            runOnUiThread(new Runnable() { public void run() { refreshBallTheme(); } });
        }

        @JavascriptInterface
        public void openDevSettings() {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                toast("打不开开发者选项，请手动进入设置");
            }
        }

        @JavascriptInterface
        public void showBall() {
            runOnUiThread(new Runnable() { public void run() { toggleBall(true); } });
        }

        @JavascriptInterface
        public void hideBall() {
            runOnUiThread(new Runnable() { public void run() { toggleBall(false); } });
        }

        @JavascriptInterface
        public String saveTrack(String dataUrl) {
            try {
                String b64 = dataUrl.substring(dataUrl.indexOf(",") + 1);
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                java.io.File dir = new java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Tsursport");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, "tsursport-" + System.currentTimeMillis() + ".png");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(bytes); fo.close();
                toast("轨迹图已保存：" + f.getAbsolutePath());
                return f.getAbsolutePath();
            } catch (Throwable t) {
                return "ERR " + t.getMessage();
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            Toast.makeText(Tsursport.this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    /** 是否已被选为「模拟位置信息应用」 */    boolean mockAllowed() {
        try {
            android.app.AppOpsManager aom = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow("android:mock_location",
                    android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    boolean hasLocationPermission() {
        return checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    // ---------------------------------------------------------------- 悬浮窗（进程级，退出界面不消失）
    static class Ball {
        static WindowManager wm;
        static LinearLayout root;
        static boolean miniMode = false, showing = false;
        static final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        static Runnable tick;
        static TextView tvDist, tvPace, tvSpm, tvState, tvSpeed, tvPlay;
        static Context app;

        // 主题调色板（跟随 App 主题）
        static boolean light = true;
        static int cPanel, cStroke, cTitle, cLabel, cValue, cSub, cGhostBg, cGhostTxt, cClose;

        static void applyTheme(Context c) {
            try {
                light = !"dark".equals(c.getSharedPreferences("tsursport", Context.MODE_PRIVATE)
                        .getString("theme", "light"));
            } catch (Throwable ignored) { light = true; }
            if (light) {
                cPanel = 0xF7FFFFFF; cStroke = 0x1F0F141E; cTitle = 0xFF0D1117;
                cLabel = 0xFF6B7688; cValue = 0xFF0D1117; cSub = 0xFF6B7688;
                cGhostBg = 0x140F141E; cGhostTxt = 0xFF0D1117; cClose = 0xFF98A2B3;
            } else {
                cPanel = 0xF2141A24; cStroke = 0x33FFFFFF; cTitle = 0xFFFFFFFF;
                cLabel = 0xFF8B95A7; cValue = 0xFFFFFFFF; cSub = 0xFF93A0B4;
                cGhostBg = 0x26FFFFFF; cGhostTxt = 0xFFFFFFFF; cClose = 0xFF8B95A7;
            }
        }

        static int dp(float v) {
            return (int) (v * app.getResources().getDisplayMetrics().density + .5f);
        }

        static GradientDrawable round(int radiusDp, int color, int strokeDp, int strokeColor) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(radiusDp));
            g.setColor(color);
            if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
            return g;
        }

        static GradientDrawable oval(int[] colors) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
            g.setShape(GradientDrawable.OVAL);
            return g;
        }

        static TextView pill(String text, int wDp, int hDp, int bgColor, int textColor, float sizeSp) {
            TextView tv = new TextView(app);
            tv.setText(text);
            tv.setTextSize(sizeSp);
            tv.setTextColor(textColor);
            tv.setGravity(Gravity.CENTER);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            tv.setBackground(round(hDp / 2, bgColor, 0, 0));
            tv.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
            return tv;
        }

        static LinearLayout statBox(String label) {
            LinearLayout box = new LinearLayout(app);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            TextView l = new TextView(app);
            l.setText(label);
            l.setTextSize(9);
            l.setTextColor(cLabel);
            TextView v = new TextView(app);
            v.setText("--");
            v.setTextSize(13);
            v.setTextColor(cValue);
            v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
            box.addView(l);
            box.addView(v);
            box.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.setTag(v);
            return box;
        }

        static void paintPlay() {
            if (tvPlay == null) return;
            if (!RunService.active) {
                tvPlay.setText("▶ 开始");
                tvPlay.setTextColor(Color.parseColor("#04140D"));
                tvPlay.setBackground(round(22, Color.parseColor("#3DDC97"), 0, 0));
            } else if (RunService.paused) {
                tvPlay.setText("▶ 继续");
                tvPlay.setTextColor(Color.parseColor("#04140D"));
                tvPlay.setBackground(round(22, Color.parseColor("#3DDC97"), 0, 0));
            } else {
                tvPlay.setText("❚❚ 暂停");
                tvPlay.setTextColor(Color.parseColor("#1A1200"));
                tvPlay.setBackground(round(22, Color.parseColor("#FFB020"), 0, 0));
            }
        }

        /** 待机时直接用保存的路线启动（不依赖界面） */
        static void askUiStart() {
            try {
                android.content.SharedPreferences sp =
                        app.getSharedPreferences("tsursport", Context.MODE_PRIVATE);
                String route = sp.getString("route", "");
                if (route == null || route.length() < 12) {
                    // 没有保存过路线：把界面拉起来让用户选点
                    Intent ui = new Intent(app, Tsursport.class);
                    ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    app.startActivity(ui);
                    android.widget.Toast.makeText(app, "请先在地图上点至少两个路线点",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                android.util.Log.i("TsursportBall", "start with route len=" + route.length());
                Intent i = new Intent(app, RunService.class);
                i.setAction(RunService.ACTION_START);
                i.putExtra(RunService.EX_ROUTE, route);
                i.putExtra(RunService.EX_SPEED, Double.parseDouble(sp.getString("speed", "2.6")));
                i.putExtra(RunService.EX_LOOP, sp.getString("loop", "pingpong"));
                i.putExtra("stride", Double.parseDouble(sp.getString("stride", "1.05")));
                i.putExtra("followCadence", sp.getBoolean("follow", false));
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                else app.startService(i);
                android.util.Log.i("TsursportBall", "service requested");
            } catch (Throwable t) {
                android.util.Log.w("TsursportBall", "ball start failed: " + t);
            }
        }

        static void svc(String action) {
            try {
                Intent i = new Intent(app, RunService.class);
                i.setAction(action);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                else app.startService(i);
            } catch (Throwable ignored) { }
        }

        static String paceText(double secPerKm) {
            int m = (int) (secPerKm / 60), sec = (int) Math.round(secPerKm % 60);
            if (sec == 60) { m++; sec = 0; }
            return m + "'" + (sec < 10 ? "0" + sec : String.valueOf(sec)) + "\"";
        }

        static LinearLayout buildExpanded() {
            LinearLayout box = new LinearLayout(app);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(12), dp(10), dp(12), dp(12));
            box.setBackground(round(20, cPanel, 1, cStroke));
            box.setElevation(dp(10));
            box.setLayoutParams(new LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout title = new LinearLayout(app);
            title.setOrientation(LinearLayout.HORIZONTAL);
            title.setGravity(Gravity.CENTER_VERTICAL);

            TextView logo = new TextView(app);
            logo.setText("T");
            logo.setTextSize(10);
            logo.setTextColor(Color.parseColor("#05120C"));
            logo.setGravity(Gravity.CENTER);
            logo.setTypeface(logo.getTypeface(), android.graphics.Typeface.BOLD);
            logo.setBackground(oval(new int[]{Color.parseColor("#38A3FF"), Color.parseColor("#3DDC97")}));
            title.addView(logo, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView name = new TextView(app);
            name.setText("  Tsursport");
            name.setTextSize(12);
            name.setTextColor(cTitle);
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            title.addView(name);

            tvState = new TextView(app);
            tvState.setText("运行中");
            tvState.setTextSize(9);
            tvState.setTextColor(Color.parseColor("#3DDC97"));
            tvState.setPadding(dp(8), 0, dp(6), 0);
            title.addView(tvState);

            TextView close = new TextView(app);
            close.setText("✕");
            close.setTextSize(13);
            close.setTextColor(cClose);
            close.setPadding(dp(8), 0, dp(2), 0);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Ball.hide(); }
            });
            title.addView(close);

            title.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setMini(true); }
            });
            attachDrag(title);
            box.addView(title);

            LinearLayout stats = new LinearLayout(app);
            stats.setOrientation(LinearLayout.HORIZONTAL);
            stats.setPadding(0, dp(10), 0, dp(4));
            LinearLayout b1 = statBox("距离 m");
            LinearLayout b2 = statBox("配速");
            LinearLayout b3 = statBox("步频");
            tvDist = (TextView) b1.getTag();
            tvPace = (TextView) b2.getTag();
            tvSpm = (TextView) b3.getTag();
            stats.addView(b1); stats.addView(b2); stats.addView(b3);
            box.addView(stats);

            tvSpeed = new TextView(app);
            tvSpeed.setText("-- m/s");
            tvSpeed.setTextSize(10);
            tvSpeed.setTextColor(cSub);
            tvSpeed.setGravity(Gravity.CENTER);
            tvSpeed.setPadding(0, 0, 0, dp(8));
            box.addView(tvSpeed);

            LinearLayout btns = new LinearLayout(app);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER_VERTICAL);

            TextView minus = pill("－", 44, 40, cGhostBg, cGhostTxt, 16);
            minus.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { svc(RunService.ACTION_SLOWER); }
            });
            tvPlay = pill("❚❚ 暂停", 88, 44, Color.parseColor("#FFB020"), Color.parseColor("#1A1200"), 14);
            tvPlay.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!RunService.active) { askUiStart(); return; }
                    svc(RunService.ACTION_TOGGLE);
                    paintPlay();
                }
            });
            TextView plus = pill("＋", 44, 40, cGhostBg, cGhostTxt, 16);
            plus.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { svc(RunService.ACTION_FASTER); }
            });

            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(44), dp(40));
            mlp.rightMargin = dp(8);
            btns.addView(minus, mlp);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(88), dp(44));
            plp.rightMargin = dp(8);
            btns.addView(tvPlay, plp);
            btns.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(40)));
            box.addView(btns);
            return box;
        }

        static LinearLayout buildMini() {
            LinearLayout box = new LinearLayout(app);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackground(oval(new int[]{Color.parseColor("#38A3FF"), Color.parseColor("#3DDC97")}));
            box.setElevation(dp(10));
            box.setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(54)));

            TextView t = new TextView(app);
            t.setText("T");
            t.setTextSize(18);
            t.setTextColor(Color.parseColor("#05120C"));
            t.setGravity(Gravity.CENTER);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            box.addView(t);

            box.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setMini(false); }
            });
            attachDrag(box);
            return box;
        }

        static void attachDrag(View handle) {
            handle.setOnTouchListener(new View.OnTouchListener() {
                int downX, downY, startX, startY;
                boolean moved;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = (int) e.getRawX(); downY = (int) e.getRawY();
                            startX = lp.x; startY = lp.y; moved = false;
                            return false;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                            if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                                moved = true;
                                lp.x = startX + dx; lp.y = startY + dy;
                                try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) { }
                            }
                            return moved;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                            return moved;
                    }
                    return false;
                }
            });
        }

        static void setMini(boolean mini) {
            if (root == null) return;
            miniMode = mini;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            lp.width = mini ? dp(54) : dp(210);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            root.removeAllViews();
            root.addView(mini ? buildMini() : buildExpanded());
            try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) { }
        }

        static void startTick() {
            if (tick != null) handler.removeCallbacks(tick);
            tick = new Runnable() {
                @Override public void run() {
                    try {
                        if (showing && !miniMode) {
                            if (tvDist != null) tvDist.setText(String.valueOf((int) RunService.curDist));
                            if (tvSpeed != null) tvSpeed.setText(String.format("%.1f m/s", RunService.curSpeed));
                            double sp = RunService.curSpeed;
                            if (tvPace != null) tvPace.setText(sp > 0.1 ? paceText(1000 / sp) : "--");
                            if (tvSpm != null) tvSpm.setText(RunService.cadence > 0
                                    ? String.valueOf((int) RunService.cadence) : "-");
                            paintPlay();
                            if (tvState != null) {
                                if (!RunService.active) {
                                    tvState.setText("待机");
                                    tvState.setTextColor(Color.parseColor(light ? "#6B7688" : "#8B95A7"));
                                } else if (RunService.paused) {
                                    tvState.setText("已暂停");
                                    tvState.setTextColor(Color.parseColor("#FFB020"));
                                } else {
                                    tvState.setText("运行中");
                                    tvState.setTextColor(Color.parseColor("#3DDC97"));
                                }
                            }
                        }
                    } catch (Throwable ignored) { }
                    handler.postDelayed(this, 1000);
                }
            };
            handler.post(tick);
        }

        static boolean canDraw(Context c) {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
        }

        static void show(Context ctx) {
            try {
                app = ctx.getApplicationContext();
                applyTheme(app);
                if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (!canDraw(app)) {
                    android.widget.Toast.makeText(app,
                            "请先允许「显示在其他应用上层」", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                if (root != null) { showing = true; startTick(); return; }

                root = new LinearLayout(app);
                root.setOrientation(LinearLayout.VERTICAL);
                root.addView(buildExpanded());
                miniMode = false;

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        dp(210),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= 26
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = dp(12);
                lp.y = dp(120);
                wm.addView(root, lp);
                showing = true;
                startTick();
            } catch (Throwable t) {
                android.util.Log.w("Tsursport", "ball: " + t);
            }
        }

        static void hide() {
            showing = false;
            if (tick != null) handler.removeCallbacks(tick);
            if (root != null) {
                try { if (wm != null) wm.removeView(root); } catch (Throwable ignored) { }
                root = null;
            }
        }
    }

    static void refreshBallTheme() {
        if (Ball.showing && Ball.root != null) {
            try { Ball.applyTheme(Ball.app); Ball.setMini(Ball.miniMode); } catch (Throwable ignored) { }
        }
    }

    void toggleBall(boolean show) {
        if (show) {
            Ball.show(getApplicationContext());
            getSharedPreferences("tsursport", MODE_PRIVATE).edit().putBoolean("ballOn", true).apply();
        } else {
            Ball.hide();
            getSharedPreferences("tsursport", MODE_PRIVATE).edit().putBoolean("ballOn", false).apply();
        }
    }

    void sendSvc(String action) {
        try {
            Intent i = new Intent(this, RunService.class);
            i.setAction(action);
            startService(i);
        } catch (Throwable t) { android.util.Log.w("Tsursport", "svc: " + t); }
    }

    // ---------------------------------------------------------------- service
    public static class RunService extends Service {

        static final String ACTION_START = "com.tsursport.START";
        static final String ACTION_STOP = "com.tsursport.STOP";
        static final String ACTION_TOGGLE = "com.tsursport.TOGGLE";
        static final String ACTION_FASTER = "com.tsursport.FASTER";
        static final String ACTION_SLOWER = "com.tsursport.SLOWER";
        static final String ACTION_PAUSE = "com.tsursport.PAUSE";
        static final String ACTION_RESUME = "com.tsursport.RESUME";
        static volatile boolean paused = false;
        static final String EX_ROUTE = "route";
        static final String EX_SPEED = "speed";
        static final String EX_LOOP = "loop";

        static volatile int realism = 1;      // 0 稳定 / 1 标准 / 2 高仿真
        static volatile boolean jitterOn = true;
        static volatile boolean satOn = true;

        static volatile boolean active = false;
        static volatile double curLat = 0, curLng = 0, curDist = 0, curSpeed = 2.6, curBearing = 0;

        static String stateJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("active", active);
                o.put("paused", paused);
                o.put("lat", curLat);
                o.put("lng", curLng);
                o.put("dist", curDist);
                o.put("speed", curSpeed);
                o.put("bearing", curBearing);
                o.put("pace", curSpeed > 0 ? (1000.0 / curSpeed) : 0);
                o.put("cadence", cadence);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        LocationManager lm;
        SensorManager sm;
        Sensor stepSensor;
        final java.util.ArrayDeque<Long> stepTimes = new java.util.ArrayDeque<Long>();
        static volatile double cadence = 0;
        double strideM = 1.05;
        boolean followCadence = false;

        final SensorEventListener stepListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                long now = SystemClock.elapsedRealtime();
                stepTimes.addLast(now);
                while (!stepTimes.isEmpty() && now - stepTimes.peekFirst() > 20000) stepTimes.removeFirst();
                int n = stepTimes.size();
                cadence = n <= 1 ? 0 : (n - 1) * 60000.0 / (now - stepTimes.peekFirst());
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };

        Handler h;
        double[] lats = new double[0], lngs = new double[0];
        double speed = 2.6;
        boolean pingpong = true;
        int seg = 0, dir = 1;
        double segProgress = 0, totalMeters = 0;
        long lastTick;

        @Override public void onCreate() {
            super.onCreate();
            h = new Handler(Looper.getMainLooper());
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        }

        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                stopMock(); stopSelf(); return START_NOT_STICKY;
            }
            try {
                if (getSharedPreferences("tsursport", MODE_PRIVATE).getBoolean("ballOn", false)
                        && !Ball.showing) {
                    Ball.show(getApplicationContext());
                }
            } catch (Throwable ignored) { }

            if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
                paused = !paused; return START_STICKY;
            }
            if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
                paused = true; return START_STICKY;
            }
            if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
                paused = false; return START_STICKY;
            }
            if (intent != null && ACTION_FASTER.equals(intent.getAction())) {
                speed = Math.min(15.0, speed + 0.3); return START_STICKY;
            }
            if (intent != null && ACTION_SLOWER.equals(intent.getAction())) {
                speed = Math.min(15.0, Math.max(0.8, speed - 0.3)); return START_STICKY;
            }
            if (intent != null) {
                parse(intent.getStringExtra(EX_ROUTE));
                speed = intent.getDoubleExtra(EX_SPEED, 2.6);
                strideM = intent.getDoubleExtra("stride", 1.05);
                followCadence = intent.getBooleanExtra("followCadence", false);
                pingpong = !"circle".equals(intent.getStringExtra(EX_LOOP));
                // 环形：把起点补到末尾，最后一段就是"从最后一个点回到起点"，不是瞬移
                if (!pingpong && lats.length >= 3) {
                    double[] la2 = new double[lats.length + 1];
                    double[] ln2 = new double[lngs.length + 1];
                    System.arraycopy(lats, 0, la2, 0, lats.length);
                    System.arraycopy(lngs, 0, ln2, 0, lngs.length);
                    la2[lats.length] = lats[0];
                    ln2[lngs.length] = lngs[0];
                    lats = la2; lngs = ln2;
                }
            }
            if (lats.length < 2) { stopSelf(); return START_NOT_STICKY; }

            startForegroundNotice();
            acquireWake();
            installTestProvider();

            try {
                if (sm != null) {
                    if (stepSensor == null) stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
                    if (stepSensor == null) stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
                    if (stepSensor != null) {
                        sm.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
                    }
                }
            } catch (Throwable t) { android.util.Log.w("Tsursport", "sensor: " + t); }

            lastTick = System.currentTimeMillis();
            h.removeCallbacks(tick);
            h.post(tick);
            active = true;
            return START_STICKY;
        }

        void parse(String json) {
            try {
                JSONArray arr = new JSONArray(json);
                int n = arr.length();
                lats = new double[n]; lngs = new double[n];
                for (int i = 0; i < n; i++) {
                    JSONArray p = arr.getJSONArray(i);
                    lats[i] = p.getDouble(0);
                    lngs[i] = p.getDouble(1);
                }
            } catch (Exception e) {
                lats = new double[0]; lngs = new double[0];
            }
            seg = 0; dir = 1; segProgress = 0; totalMeters = 0;
        }

        final java.util.LinkedHashSet<String> providers = new java.util.LinkedHashSet<String>();

        /** 把系统里现存的 provider 全部纳入接管范围（不同机型会有 vendor 自定义 provider） */
        void collectProviders() {
            providers.clear();
            providers.add(LocationManager.GPS_PROVIDER);
            providers.add(LocationManager.NETWORK_PROVIDER);
            // passive 不允许被 mock，跳过（系统会自动镜像其他 provider）
            providers.add("fused");
            try {
                java.util.List<String> all = lm.getAllProviders();
                if (all != null) {
                    for (String n : all) {
                        if (n != null && !n.isEmpty()) providers.add(n);
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("Tsursport", "getAllProviders: " + t);
            }
        }

        void addOne(String name) {
            boolean requiresNetwork = !LocationManager.GPS_PROVIDER.equals(name);
            try {
                lm.addTestProvider(name, false, requiresNetwork, false, false, true, true, true, 1, 1);
            } catch (IllegalArgumentException exists) {
                try { lm.removeTestProvider(name); } catch (Exception ignored) { }
                try {
                    lm.addTestProvider(name, false, requiresNetwork, false, false, true, true, true, 1, 1);
                } catch (Exception e) {
                    android.util.Log.w("Tsursport", "addTestProvider(" + name + ") failed: " + e);
                }
            } catch (Throwable t) {
                android.util.Log.w("Tsursport", "addTestProvider(" + name + ") denied: " + t);
            }
            try { lm.setTestProviderEnabled(name, true); } catch (Throwable ignored) { }
        }

        void installTestProvider() {
            try {
                if (lm == null) { return; }
                collectProviders();
                for (String name : providers) addOne(name);
            } catch (SecurityException se) {
                Toast.makeText(this, "没有模拟位置权限：开发者选项 → 选择模拟位置信息应用 → 选 MockRun",
                        Toast.LENGTH_LONG).show();
            }
        }

        double segLength(int i) {
            if (i < 0) i = 0;
            if (i > lats.length - 2) i = lats.length - 2;
            double dLat = (lats[i + 1] - lats[i]) * 111320.0;
            double dLng = (lngs[i + 1] - lngs[i]) * 111320.0 * Math.cos(Math.toRadians(lats[i]));
            return Math.sqrt(dLat * dLat + dLng * dLng);
        }

        double[] at(int i, double meters) {
            double len = Math.max(0.001, segLength(i));
            double t = Math.min(1.0, meters / len);
            return new double[]{
                    lats[i] + (lats[i + 1] - lats[i]) * t,
                    lngs[i] + (lngs[i + 1] - lngs[i]) * t
            };
        }

        final Runnable tick = new Runnable() {
            @Override public void run() {
              try {
                long now = System.currentTimeMillis();
                double dt = Math.max(0.2, (now - lastTick) / 1000.0);
                lastTick = now;

                if (paused) {
                    h.postDelayed(this, 1000);
                    return;
                }
                double effSpeed = speed;
                if (followCadence && cadence > 30) {
                    effSpeed = Math.max(0.8, Math.min(15.0, cadence * strideM / 60.0));
                }
                curSpeed = effSpeed;
                double budget = effSpeed * dt;
                int guard = 0;
                while (budget > 0 && guard++ < 128) {
                    double remain = segLength(seg) - segProgress;
                    if (budget < remain) { segProgress += budget; totalMeters += budget; budget = 0; }
                    else {
                        totalMeters += remain; budget -= remain; segProgress = 0;
                        if (pingpong) {
                            if (seg < 0) seg = 0;
                            if (seg > lats.length - 2) seg = lats.length - 2;
                            seg += dir;
                            if (seg >= lats.length - 1) { seg = lats.length - 2; dir = -1; }
                            else if (seg < 0) { seg = 0; dir = 1; }
                        } else {
                            seg++;
                            if (seg >= lats.length - 1) seg = 0;
                        }
                    }
                }

                double[] p = at(seg, segProgress);
                double[] q = at(seg, Math.min(segProgress + 1.0, segLength(seg)));
                double bearing = Math.toDegrees(Math.atan2(
                        (q[1] - p[1]) * Math.cos(Math.toRadians(p[0])), (q[0] - p[0])));
                if (bearing < 0) bearing += 360;

                curLat = p[0] + (Math.random() - .5) * 0.0000025;
                curLng = p[1] + (Math.random() - .5) * 0.0000025;
                curDist = totalMeters; curSpeed = speed; curBearing = bearing;

                // —— 让数值更像真机：精度/卫星/速度都带自然抖动 ——
                float hBase = realism >= 2 ? 8f : (realism == 1 ? 12f : 20f);
                float hSpan = realism >= 2 ? 22f : (realism == 1 ? 16f : 10f);
                float hAcc = hBase + (float) (Math.random() * hSpan);
                float vAcc = 8f + (float) (Math.random() * 7f);
                float sAcc = 0.6f + (float) (Math.random() * 0.9f);
                float bAcc = 5f + (float) (Math.random() * 10f);
                double jit = jitterOn ? (realism >= 2 ? 0.16 : 0.10) : 0.0;
                double spd = speed * (1.0 - jit / 2 + Math.random() * jit);
                double brg = bearing + (jitterOn ? (Math.random() - 0.5) * 6.0 : 0.0);
                int sats = 10 + (int) (Math.random() * 7);                 // 10~16 颗
                int meanCn0 = 28 + (int) (Math.random() * 11);             // 28~38
                int maxCn0 = meanCn0 + 8 + (int) (Math.random() * 8);

                Location loc = new Location(LocationManager.GPS_PROVIDER);
                loc.setLatitude(curLat);
                loc.setLongitude(curLng);
                loc.setAltitude(7.0 + Math.random() * 3.0);
                loc.setAccuracy(hAcc);
                loc.setSpeed((float) spd);
                loc.setBearing((float) brg);
                loc.setTime(System.currentTimeMillis());
                if (Build.VERSION.SDK_INT >= 26) {
                    loc.setVerticalAccuracyMeters(vAcc);
                    loc.setSpeedAccuracyMetersPerSecond(sAcc);
                    loc.setBearingAccuracyDegrees(bAcc);
                }
                if (satOn) {
                    android.os.Bundle ex = new android.os.Bundle();
                    ex.putInt("satellites", sats);
                    ex.putInt("meanCn0", meanCn0);
                    ex.putInt("maxCn0", maxCn0);
                    loc.setExtras(ex);
                }
                if (Build.VERSION.SDK_INT >= 17)
                    loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                for (String name : providers) {
                    Location copy = new Location(loc);
                    copy.setProvider(name);
                    try { lm.setTestProviderLocation(name, copy); } catch (Throwable ignored) { }
                }

                h.postDelayed(this, 1000);
              } catch (Throwable t) {
                android.util.Log.e("MockRunTick", "tick failed", t);
                h.postDelayed(this, 1000);
              }
            }
        };

        void stopMock() {
            active = false;
            try { if (sm != null) sm.unregisterListener(stepListener); } catch (Throwable ignored) { }
            h.removeCallbacks(tick);
            releaseWake();
            for (String name : providers) {
                try { lm.setTestProviderEnabled(name, false); } catch (Throwable ignored) { }
                try { lm.removeTestProvider(name); } catch (Throwable ignored) { }
            }
        }

        android.os.PowerManager.WakeLock wake;

        void acquireWake() {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (wake == null) {
                    wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Tsursport:run");
                    wake.setReferenceCounted(false);
                }
                wake.acquire(60 * 60 * 1000L);
            } catch (Throwable t) {
                android.util.Log.w("Tsursport", "wake: " + t);
            }
        }

        void releaseWake() {
            try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) { }
        }

        void startForegroundNotice() {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        "mockrun", "Tsursport", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, "mockrun")
                    : new Notification.Builder(this);
            nb.setContentTitle("Tsursport 模拟中")
              .setContentText(String.format("%.1f m/s · 已跑 %.0f m", speed, totalMeters))
              .setSmallIcon(android.R.drawable.ic_menu_mylocation)
              .setOngoing(true);
            try {
                startForeground(1001, nb.build());
            } catch (Throwable t) {
                android.util.Log.w("Tsursport", "startForeground failed: " + t);
            }
        }

        @Override public IBinder onBind(Intent i) { return null; }
        @Override public void onDestroy() { stopMock(); super.onDestroy(); }
    }
}
