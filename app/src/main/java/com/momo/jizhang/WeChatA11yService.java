package com.momo.jizhang;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信零钱支付捕获 + 一键自动同步。
 * 零钱/扫码支付不产生系统通知，本服务通过无障碍：
 *  A) 会话列表预览捕获：微信支付对话的预览文字"已支付¥X.XX"是可读的 → 直接入账；
 *  B) 对话内凭证卡片捕获：能读到卡片文字时提取商户与支付方式；
 *  C) 自动同步：App 内点「同步微信零钱」→ 自动打开微信、点进对话、读取、退回。
 */
public class WeChatA11yService extends AccessibilityService {

    private static final Pattern AMT = Pattern.compile("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern PREVIEW_PAID = Pattern.compile("已支付\\s*[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern T_HM   = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final Pattern T_AP   = Pattern.compile("^(早上|上午|中午|下午|晚上)\\s*(\\d{1,2}):(\\d{2})$");
    private static final Pattern T_YES  = Pattern.compile("^昨天\\s*(\\d{1,2}):(\\d{2})$");
    private static final Pattern T_WEEK = Pattern.compile("^星期([一二三四五六日天])\\s*(\\d{1,2}):(\\d{2})$");
    private static final Pattern T_MD   = Pattern.compile("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})$");
    private static final Pattern T_DATE = Pattern.compile("^(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日$");
    private static final String[] BANK_CHANNELS = {"sms", "bankapp"};

    private static long lastScan = 0;
    private static long lastLog = 0;

    /* 自动同步状态机 */
    private static volatile boolean autoMode = false;
    private static long autoStart = 0;
    private static int scrollTries = 0;
    private static int backTries = 0;
    private static double previewAmt = -1;
    private static String previewTime = "";

    public static void requestAutoSync() {
        autoMode = true; autoStart = System.currentTimeMillis();
        scrollTries = 0; backTries = 0; previewAmt = -1; previewTime = "";
    }

    @Override
    public void onServiceConnected() {
        new DbHelper(this).logDebug("a11y-ready", "无障碍服务已启动");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            int type = e.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
            long now = System.currentTimeMillis();
            if (now - lastScan < 800) return;
            lastScan = now;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            List<String> texts = new ArrayList<>();
            collect(root, texts);
            if (texts.isEmpty()) return;
            String all = join(texts);
            DbHelper db = new DbHelper(this);

            if (autoMode) { handleAutoSync(root, texts, all, db, now); return; }

            /* ---------- 被动捕获 ---------- */
            // A. 会话列表：微信支付行的预览"已支付¥X.XX"
            if (tryPreviewCapture(texts, db, now, false)) return;

            // B. 支付成功页 / 凭证卡片
            boolean payPage = all.contains("支付成功") && AMT.matcher(all).find();
            boolean receiptChat = all.contains("微信支付") && all.contains("微信支付凭证");
            if (!payPage && !receiptChat) return;

            if (now - lastLog > 3000) {
                lastLog = now;
                db.logDebug("a11y-page", String.valueOf(e.getClassName()) + " | " +
                        (all.length() > 150 ? all.substring(0, 150) : all));
            }
            processReceipt(texts, db, now);
        } catch (Throwable t) {
            try { new DbHelper(this).logDebug("a11y-error", String.valueOf(t)); } catch (Throwable ignored) {}
        }
    }

    /* ================= 会话列表预览捕获 ================= */
    /** 在微信会话列表找到"微信支付"行，读取预览"已支付¥X.XX"与行内时间。返回是否命中。 */
    private boolean tryPreviewCapture(List<String> texts, DbHelper db, long now, boolean auto) {
        int idx = -1;
        for (int i = 0; i < texts.size(); i++) {
            if ("微信支付".equals(texts.get(i))) { idx = i; break; }
        }
        if (idx < 0) return false;
        // 预览文字与时间都在标题附近 ±3 个节点内
        double amt = -1;
        String timeTxt = "";
        for (int i = Math.max(0, idx - 3); i <= Math.min(texts.size() - 1, idx + 3); i++) {
            String t = texts.get(i);
            Matcher m = PREVIEW_PAID.matcher(t);
            if (amt <= 0 && m.find()) amt = Double.parseDouble(m.group(1));
            if (timeTxt.isEmpty() && looksLikeTime(t)) timeTxt = t;
        }
        if (amt <= 0) return false;

        // 判重签名 = 金额 + 行内时间：同一笔不会重复，连付两笔同金额也能分开
        String sig = amt + "|" + timeTxt;
        if (sig.equals(db.getSetting("a11y_prev", ""))) return false;

        // 已被银行渠道（短信/银行App）今天记录过同额 → 那笔是银行卡支付，跳过
        if (db.existsChannelAmountToday(amt, BANK_CHANNELS)) {
            db.setSetting("a11y_prev", sig);
            db.logDebug("a11y-skip", "预览 ¥" + amt + " 已被银行渠道记录");
            return true;
        }

        long ts = parseRowTime(timeTxt, now);
        long effTs = ts > 0 ? ts : now;
        long id = db.addTx(-amt, "daily", "微信支付", "a11y", "wechat", effTs,
                "a11y|preview|" + sig.replace("|", "-") + "|" + (effTs / 60000));
        if (id != -1) {
            db.setSetting("a11y_prev", sig);
            db.logDebug("ok-a11y", -amt + " 微信支付（列表预览" + (auto ? "/自动同步" : "") + "）");
            if (auto) toast("已入账：微信支付 ¥" + amt);
            return true;
        }
        db.setSetting("a11y_prev", sig);
        db.logDebug("a11y-skip", "预览已存在 ¥" + amt);
        return true;
    }

    /* ================= 自动同步（只在会话列表读预览，不进对话） ================= */
    private void handleAutoSync(AccessibilityNodeInfo root, List<String> texts, String all,
                                DbHelper db, long now) {
        if (now - autoStart > 20000) {
            db.logDebug("a11y-auto", "同步超时");
            notifyDone("同步超时，点我返回 Momo记账");
            autoMode = false;
            return;
        }

        // 微信停在某对话页（含微信支付对话）→ 退回会话列表
        if (all.contains("查看账单详情") || all.contains("查看交易详情") || all.contains("收款方")) {
            if (backTries < 3) {
                backTries++;
                performGlobalAction(GLOBAL_ACTION_BACK);
                db.logDebug("a11y-auto", "在对话页，退回会话列表");
            }
            return;
        }

        // 找"微信支付"行
        int idx = -1;
        for (int i = 0; i < texts.size(); i++) {
            if ("微信支付".equals(texts.get(i))) { idx = i; break; }
        }
        if (idx >= 0) {
            // 行在 → 读预览入账（可能无新支付，也算完成）
            tryPreviewCapture(texts, db, now, true);
            db.logDebug("a11y-auto", "同步完成（列表预览）");
            finishAndReturn(db);
            return;
        }

        // 行不在当前屏 → 滚动查找
        AccessibilityNodeInfo list = findScrollable(root);
        if (list != null && scrollTries < 6) {
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            scrollTries++;
            db.logDebug("a11y-auto", "滚动查找微信支付对话 " + scrollTries);
            return;
        }
        // 没找到
        db.logDebug("a11y-auto", "找不到「微信支付」对话");
        toast("没找到微信支付对话，请先把它置顶");
        notifyDone("未找到微信支付对话，点我返回");
        autoMode = false;
    }

    private void finishAndReturn(DbHelper db) {
        notifyDone("零钱账单已同步，点我返回 Momo记账");
        autoMode = false;
        backToApp();
    }

    /** 同步完成通知：系统自动跳回被拦截时，点通知即可返回 */
    private void notifyDone(String text) {
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    "sync", "同步提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            android.content.Intent it = getPackageManager().getLaunchIntentForPackage(getPackageName());
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 1, it, android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = new android.app.Notification.Builder(this, "sync")
                    .setContentTitle("Momo记账")
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1, n);
        } catch (Throwable t) {
            try { new DbHelper(this).logDebug("a11y-error", "通知失败: " + t); } catch (Throwable ignored) {}
        }
    }

    private void backToApp() {
        try {
            android.content.Intent it = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (it != null) {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(it);
            }
        } catch (Throwable t) {
            try { new DbHelper(this).logDebug("a11y-auto", "自动返回被系统拦截，需手动切回"); } catch (Throwable ignored) {}
        }
    }

    /* ================= 凭证卡片解析（可能读不到，返回是否成功） ================= */
    private boolean processReceipt(List<String> texts, DbHelper db, long now) {
        int anchor = lastIndexOf(texts, "微信支付凭证");
        if (anchor < 0) anchor = firstIndexOf(texts, "支付成功");
        if (anchor < 0) return false;
        List<String> card = texts.subList(anchor, texts.size());
        String cardText = join(card);
        if (!cardText.contains("支付成功")) return false;

        double amt = -1;
        for (String t : card) {
            Matcher m = AMT.matcher(t);
            if (m.find()) { amt = Double.parseDouble(m.group(1)); break; }
        }
        if (amt <= 0 || amt > 1000000) return false;

        String merchant = "微信支付";
        for (int i = 0; i < card.size(); i++) {
            if (card.get(i).contains("收款方") && i + 1 < card.size()) {
                String v = card.get(i + 1).trim();
                if (!v.isEmpty() && !v.contains("¥") && !v.contains("支付") && v.length() <= 12) {
                    merchant = v; break;
                }
            }
        }
        String account = "wechat";
        for (String t : card) {
            if (t.contains("使用") && t.contains("支付")) {
                account = t.contains("银行") ? "bank" : "wechat";
                break;
            }
        }

        long ts = parseDivider(texts, anchor, now);
        long effTs = ts > 0 ? ts : now;

        if (db.hasSimilar(amt, effTs, "a11y")) {
            db.logDebug("dup-a11y", amt + " " + merchant + "（与他渠道同金额，已合并）");
            return true;
        }
        if (ts <= 0 && db.existsA11yToday(amt, merchant)) {
            db.logDebug("a11y-skip", "今日已有相同记录 " + amt + " " + merchant);
            return true;
        }
        String cat = Parser.classify(merchant);
        long id = db.addTx(-amt, cat, merchant, "a11y", account, effTs,
                "a11y|" + amt + "|" + merchant + "|" + (effTs / 60000));
        if (id == -1) {
            db.logDebug("a11y-skip", "已存在 " + amt + " " + merchant);
        } else {
            db.logDebug("ok-a11y", -amt + " " + merchant + ("bank".equals(account) ? "（银行卡）" : "（零钱）"));
            if (autoMode) toast("已入账：" + merchant + " ¥" + amt);
        }
        return true;
    }

    /* ================= 节点工具 ================= */
    private static void collect(AccessibilityNodeInfo n, List<String> out) {
        if (n == null) return;
        CharSequence t = n.getText();
        if (t != null && t.toString().trim().length() > 0) out.add(t.toString().trim());
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) { collect(c, out); c.recycle(); }
        }
    }

    private static AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo root, String text) {
        if (root == null) return null;
        CharSequence t = root.getText();
        if (t != null && text.equals(t.toString().trim())) {
            AccessibilityNodeInfo n = root;
            while (n != null && !n.isClickable()) n = n.getParent();
            if (n != null) return n;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo c = root.getChild(i);
            if (c != null) {
                AccessibilityNodeInfo r = findClickableByText(c, text);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        String cls = root.getClassName() == null ? "" : root.getClassName().toString();
        if (root.isScrollable() && (cls.contains("ListView") || cls.contains("RecyclerView"))) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo c = root.getChild(i);
            if (c != null) {
                AccessibilityNodeInfo r = findScrollable(c);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void toast(String msg) {
        new android.os.Handler(getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    /* 会话列表行内时间样式："晚上8:25" / "15:06" / "昨天" / "星期三" / "10月30日" */
    private static boolean looksLikeTime(String t) {
        t = t.trim();
        return T_HM.matcher(t).matches() || T_AP.matcher(t).matches()
                || T_YES.matcher(t).matches() || T_WEEK.matcher(t).matches()
                || T_MD.matcher(t).matches() || T_DATE.matcher(t).matches()
                || t.equals("昨天") || t.startsWith("星期");
    }

    /** 把行内时间文字解析成时间戳；解析不出返回 0 */
    private static long parseRowTime(String t, long now) {
        if (t == null || t.isEmpty()) return 0;
        t = t.trim();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        Matcher m = T_AP.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(2));
            String ap = m.group(1);
            if (("下午".equals(ap) || "晚上".equals(ap)) && h < 12) h += 12;
            if ("中午".equals(ap) && h < 12) h += 12;
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, Integer.parseInt(m.group(3)));
            if (c.getTimeInMillis() > now + 60000) c.add(Calendar.DAY_OF_YEAR, -1);
            return c.getTimeInMillis();
        }
        m = T_HM.matcher(t);
        if (m.matches()) {
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
            c.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
            if (c.getTimeInMillis() > now + 60000) c.add(Calendar.DAY_OF_YEAR, -1);
            return c.getTimeInMillis();
        }
        m = T_YES.matcher(t);
        if (m.matches()) {
            c.add(Calendar.DAY_OF_YEAR, -1);
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
            c.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
            return c.getTimeInMillis();
        }
        if (t.equals("昨天")) {
            c.add(Calendar.DAY_OF_YEAR, -1);
            return c.getTimeInMillis();
        }
        m = T_WEEK.matcher(t);
        if (m.matches()) {
            int wd = "一二三四五六日天".indexOf(m.group(1).charAt(0));
            if (wd >= 0) {
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(2)));
                c.set(Calendar.MINUTE, Integer.parseInt(m.group(3)));
                int curWd = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                c.add(Calendar.DAY_OF_YEAR, -((curWd - wd + 7) % 7));
                return c.getTimeInMillis();
            }
        }
        if (t.startsWith("星期")) return c.getTimeInMillis();
        m = T_MD.matcher(t);
        if (m.matches()) {
            int y = m.group(1) != null ? Integer.parseInt(m.group(1)) : c.get(Calendar.YEAR);
            c.set(y, Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), 0);
            return c.getTimeInMillis();
        }
        m = T_DATE.matcher(t);
        if (m.matches()) {
            int y = m.group(1) != null ? Integer.parseInt(m.group(1)) : c.get(Calendar.YEAR);
            c.set(y, Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)), 12, 0, 0);
            return c.getTimeInMillis();
        }
        return 0;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(' ').append(s);
        return sb.toString();
    }

    private static int lastIndexOf(List<String> l, String key) {
        for (int i = l.size() - 1; i >= 0; i--) if (key.equals(l.get(i))) return i;
        return -1;
    }

    private static int firstIndexOf(List<String> l, String key) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).contains(key)) return i;
        return -1;
    }

    private static long parseDivider(List<String> texts, int anchor, long now) {
        for (int i = anchor - 1; i >= 0; i--) {
            String t = texts.get(i).trim();
            Matcher m = T_MD.matcher(t);
            if (m.matches()) {
                Calendar c = Calendar.getInstance();
                int y = m.group(1) != null ? Integer.parseInt(m.group(1)) : c.get(Calendar.YEAR);
                c.set(y, Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), 0);
                c.set(Calendar.MILLISECOND, 0);
                return c.getTimeInMillis();
            }
            m = T_YES.matcher(t);
            if (m.matches()) {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.DAY_OF_YEAR, -1);
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
                c.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                return c.getTimeInMillis();
            }
            m = T_WEEK.matcher(t);
            if (m.matches()) {
                int wd = "一二三四五六日天".indexOf(m.group(1).charAt(0));
                if (wd >= 0) {
                    Calendar c = Calendar.getInstance();
                    c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(2)));
                    c.set(Calendar.MINUTE, Integer.parseInt(m.group(3)));
                    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                    int curWd = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                    c.add(Calendar.DAY_OF_YEAR, -((curWd - wd + 7) % 7));
                    return c.getTimeInMillis();
                }
            }
            m = T_HM.matcher(t);
            if (m.matches()) {
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1)));
                c.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
                c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() > now + 60000) c.add(Calendar.DAY_OF_YEAR, -1);
                return c.getTimeInMillis();
            }
        }
        return 0;
    }

    @Override
    public void onInterrupt() {}
}
