package com.momo.jizhang;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;

/**
 * 通知监听：
 *  - 微信支付 / 支付宝：全量诊断记录 + 解析入账
 *  - 其他 App（如银行 App 动账推送）：内容含银行特征词时尝试解析入账
 */
public class BillListenerService extends NotificationListenerService {

    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            String source;
            if (PKG_WECHAT.equals(pkg)) source = "wechat";
            else if (PKG_ALIPAY.equals(pkg)) source = "alipay";
            else source = "bankapp";

            Bundle extras = sbn.getNotification().extras;
            // 把所有可能的内容字段拼起来：微信支付凭证的金额在多行扩展字段里
            StringBuilder sb = new StringBuilder();
            append(sb, extras.get(Notification.EXTRA_TITLE));
            append(sb, extras.get(Notification.EXTRA_TITLE_BIG));
            append(sb, extras.get(Notification.EXTRA_TEXT));
            append(sb, extras.get(Notification.EXTRA_BIG_TEXT));
            append(sb, extras.get(Notification.EXTRA_SUB_TEXT));
            append(sb, extras.get(Notification.EXTRA_SUMMARY_TEXT));
            CharSequence[] lines = extras.getCharSequenceArray("android.textLines");
            if (lines != null) for (CharSequence l : lines) append(sb, l);
            String body = sb.toString().trim();

            DbHelper db = new DbHelper(this);
            String channels = db.getSetting("channels", "{}");

            if ("bankapp".equals(source)) {
                handleBankApp(db, channels, pkg, body, sbn.getPostTime());
                return;
            }

            if (!channels.contains("\"" + source + "\":true")) return;

            // 诊断：只记录支付相关通知（支付/收款/转账/红包/凭证/金额字样），过滤聊天消息
            boolean payish = body.matches(".*[¥￥].*") || body.matches(".*[0-9]+(\\.[0-9]+)?元.*")
                    || containsAny(body, "支付", "付款", "支出", "收款", "退款", "消费", "扣款",
                            "转账", "红包", "凭证", "零钱", "银行卡");
            // 微信聊天消息（非支付相关）不记日志、不解析
            if (!body.isEmpty() && !payish && "wechat".equals(source)) return;
            if (body.isEmpty()) {
                StringBuilder keys = new StringBuilder();
                for (String k : extras.keySet()) keys.append(k).append(' ');
                db.logDebug("raw-" + source, "[无文本内容] extras字段: " + keys);
                return;
            }
            db.logDebug("raw-" + source, body);

            if (!payish) return;

            Parser.Parsed p = Parser.parse(source, "", body);
            if (p == null) {
                db.logDebug("skip-" + source, body);
                return;
            }

            long ts = sbn.getPostTime();
            if (db.hasSimilar(p.amount, ts, source)) {
                db.logDebug("dup-" + source, p.amount + " " + p.merchant + "（与他渠道同金额，已合并）");
                return;
            }
            String dedup = source + "|" + p.amount + "|" + p.merchant + "|" + (ts / 60000);
            long id = db.addTx(p.amount, p.category, p.merchant, source, "", ts, dedup);
            if (id != -1) db.logDebug("ok-" + source, p.amount + " " + p.merchant);
        } catch (Throwable ignored) {}
    }

    /** 银行 App 动账推送：内容必须像银行通知（含银行/账户/尾号等特征词 + 资金词） */
    private void handleBankApp(DbHelper db, String channels, String pkg, String body, long ts) {
        if (channels.contains("\"bankapp\":false")) return;
        if (body.isEmpty()) return;
        boolean bankish = containsAny(body, "银行", "储蓄卡", "信用卡", "账户", "尾号", "卡号");
        if (!bankish) return;
        boolean moneyish = body.matches(".*[¥￥].*") || body.matches(".*[0-9]+(\\.[0-9]+)?元.*")
                || containsAny(body, "支出", "扣款", "消费", "到账", "转入", "余额");
        if (!moneyish) return;

        db.logDebug("raw-bankapp", pkg + " | " + body);

        Parser.Parsed p = Parser.parse("sms", "", body);
        if (p == null) {
            db.logDebug("skip-bankapp", body);
            return;
        }
        if (db.hasSimilar(p.amount, ts, "bankapp")) {
            db.logDebug("dup-bankapp", p.amount + " " + p.merchant + "（与他渠道同金额，已合并）");
            if (p.balance >= 0) db.setAccount("bank", p.balance);
            return;
        }
        String dedup = "bankapp|" + p.amount + "|" + p.merchant + "|" + (ts / 60000);
        long id = db.addTx(p.amount, p.category, p.merchant, "bankapp", "", ts, dedup);
        if (id != -1) db.logDebug("ok-bankapp", p.amount + " " + p.merchant);
        if (p.balance >= 0) db.setAccount("bank", p.balance);
    }

    private static void append(StringBuilder sb, Object o) {
        if (o != null && o.toString().trim().length() > 0) sb.append(' ').append(o);
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }
}
