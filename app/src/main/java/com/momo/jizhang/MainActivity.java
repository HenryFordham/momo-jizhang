package com.momo.jizhang;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.Manifest;
import android.content.pm.PackageManager;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private WebView web;
    private DbHelper db;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DbHelper(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.handleBack && window.handleBack()", null);
    }

    void jsBack() { super.onBackPressed(); }

    @Override
    protected void onResume() {
        super.onResume();
        // 打开/回到 App 时主动扫描最近短信入账（不依赖短信广播送达）
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            new Thread(() -> {
                int n = scanSmsInbox();
                if (n > 0) ui.post(() -> web.evaluateJavascript(
                        "window.toastSyncSms && window.toastSyncSms(" + n + "); window.refreshAll && window.refreshAll()", null));
            }).start();
        }
        if (web != null) web.evaluateJavascript("window.refreshAll && window.refreshAll()", null);
    }

    /** 扫描最近 24 小时短信收件箱，银行扣款自动入账。返回新入账笔数 */
    private int scanSmsInbox() {
        int added = 0;
        try {
            long since = System.currentTimeMillis() - 86400000L;
            android.database.Cursor c = getContentResolver().query(
                    android.net.Uri.parse("content://sms/inbox"),
                    new String[]{"body", "date"}, "date > ?",
                    new String[]{String.valueOf(since)}, "date DESC");
            if (c == null) return 0;
            while (c.moveToNext()) {
                String body = c.getString(0);
                long ts = c.getLong(1);
                if (!(body.contains("银行") || body.contains("储蓄卡") || body.contains("信用卡")
                        || body.contains("账户") || body.contains("尾号") || body.contains("卡号"))) continue;
                Parser.Parsed p = Parser.parse("sms", "", body);
                if (p == null) continue;
                if (db.hasSimilar(p.amount, ts, "sms")) {
                    if (p.balance >= 0) db.setAccount("bank", p.balance);
                    continue;
                }
                String dedup = "sms|" + p.amount + "|" + p.merchant + "|" + (ts / 60000);
                long id = db.addTx(p.amount, p.category, p.merchant, "sms", "", ts, dedup);
                if (id != -1) {
                    added++;
                    db.logDebug("ok-sms", p.amount + " " + p.merchant + "（收件箱扫描）");
                    if (p.balance >= 0) db.setAccount("bank", p.balance);
                }
            }
            c.close();
        } catch (Throwable ignored) {}
        return added;
    }

    /* ================= JS 桥 ================= */
    class Bridge {

        @JavascriptInterface
        public boolean isReady() { return true; }

        @JavascriptInterface
        public String getInitData(int year, int month) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("summary", db.monthSummary(year, month));
                o.put("accounts", db.getAccounts());
                o.put("goals", db.getGoals());
                o.put("salary", Double.parseDouble(db.getSetting("salary", "0")));
                o.put("budget", Double.parseDouble(db.getSetting("budget", "0")));
                o.put("bg", db.getSetting("bg", "bg-cream"));
                o.put("catBudgets", new org.json.JSONObject(db.getSetting("catBudgets", "{}")));
                o.put("channels", new org.json.JSONObject(db.getSetting("channels", "{}")));
                o.put("todaySpent", db.todaySpent());
                o.put("notifGranted", notifGranted());
                o.put("smsGranted", hasSmsPermission());
                o.put("a11yGranted", a11yEnabled());
                return o.toString();
            } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
        }

        @JavascriptInterface
        public String getMonthTx(int year, int month) {
            try { return db.txForMonth(year, month).toString(); }
            catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String getCategoryTx(String cat, int year, int month) {
            try { return db.txForCategory(cat, year, month).toString(); }
            catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String getWeekCompare(String cat) {
            double[] v = db.weekCompare(cat == null || cat.isEmpty() ? null : cat);
            return "[" + v[0] + "," + v[1] + "]";
        }

        @JavascriptInterface
        public long addTx(double amount, String cat, String merchant, String channel, String account, long ts) {
            String dedup = channel + "|" + amount + "|" + merchant + "|" + ts;
            return db.addTx(amount, cat, merchant, channel, account, ts, dedup);
        }

        @JavascriptInterface
        public boolean deleteTx(long id) { return db.deleteTx(id); }

        @JavascriptInterface
        public void updateTx(long id, double amount, String cat, String merchant) {
            db.updateTx(id, amount, cat, merchant);
        }

        @JavascriptInterface
        public void setAccount(String type, double balance) { db.setAccount(type, balance); }

        @JavascriptInterface
        public long addGoal(String type, String name, double target, String link) {
            return db.addGoal(type, name, target, link);
        }

        @JavascriptInterface
        public void updateGoalSaved(long id, double saved) { db.updateGoalSaved(id, saved); }

        @JavascriptInterface
        public void deleteGoal(long id) { db.deleteGoal(id); }

        @JavascriptInterface
        public void setSetting(String key, String value) { db.setSetting(key, value); }

        @JavascriptInterface
        public boolean notifGranted() {
            String flat = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        }

        @JavascriptInterface
        public void openNotifSettings() {
            startActivity(new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        /* ---------- 短信 ---------- */
        @JavascriptInterface
        public boolean hasSmsPermission() {
            return checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public boolean requestSmsPermission() {
            ArrayList<String> need = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECEIVE_SMS);
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_SMS);
            if (need.isEmpty()) return true;
            requestPermissions(need.toArray(new String[0]), 11);
            return false;
        }

        /** 解析测试：把短信原文粘进来，返回解析结果（用于自查格式是否支持） */
        @JavascriptInterface
        public String testParse(String text) {
            Parser.Parsed p = Parser.parse("sms", "", text == null ? "" : text);
            if (p == null) return "null";
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("amount", p.amount);
                o.put("merchant", p.merchant);
                o.put("category", p.category);
                o.put("balance", p.balance);
                return o.toString();
            } catch (Exception e) { return "null"; }
        }

        /* ---------- 诊断日志 ---------- */
        @JavascriptInterface
        public String getDebugLog() {
            try { return db.getDebugLog().toString(); }
            catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public void clearDebugLog() { db.clearDebugLog(); }

        /* ---------- 无障碍（微信零钱） ---------- */
        @JavascriptInterface
        public boolean a11yEnabled() {
            String flat = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_accessibility_services");
            return flat != null && flat.contains(getPackageName());
        }

        @JavascriptInterface
        public void openA11ySettings() {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        /** 一键同步微信零钱：0=已启动 1=需先开无障碍 2=未安装微信 */
        @JavascriptInterface
        public int syncWeChat() {
            if (!a11yEnabled()) return 1;
            android.content.Intent it = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (it == null) return 2;
            // 同步完成提醒需要通知权限（Android 13+）
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12);
            }
            WeChatA11yService.requestAutoSync();
            startActivity(it);
            return 0;
        }

        /** 手动同步银行短信：-1=无权限（已弹申请） 其他=新入账笔数 */
        @JavascriptInterface
        public int syncSms() {
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, 13);
                return -1;
            }
            return scanSmsInbox();
        }

        @JavascriptInterface
        public void exitApp() { jsBack(); }
    }
}
