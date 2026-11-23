package com.momo.jizhang;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

/** 接收银行扣款短信，解析后自动入账；如短信含余额则同步更新银行卡余额；含诊断日志 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            DbHelper db = new DbHelper(ctx);
            if (!db.getSetting("channels", "{}").contains("\"sms\":true")) return;

            Bundle b = intent.getExtras();
            if (b == null) return;
            Object[] pdus = (Object[]) b.get("pdus");
            if (pdus == null) return;
            StringBuilder sb = new StringBuilder();
            String format = b.getString("format");
            String from = "";
            for (Object pdu : pdus) {
                SmsMessage msg = format != null
                        ? SmsMessage.createFromPdu((byte[]) pdu, format)
                        : SmsMessage.createFromPdu((byte[]) pdu);
                if (msg != null) {
                    if (msg.getMessageBody() != null) sb.append(msg.getMessageBody());
                    if (from.isEmpty() && msg.getOriginatingAddress() != null)
                        from = msg.getOriginatingAddress();
                }
            }
            String body = sb.toString();
            if (body.isEmpty()) return;

            // 用短信自身的时间戳，保证与收件箱扫描的去重键一致
            long ts = System.currentTimeMillis();
            if (pdus.length > 0) {
                SmsMessage first = format != null
                        ? SmsMessage.createFromPdu((byte[]) pdus[0], format)
                        : SmsMessage.createFromPdu((byte[]) pdus[0]);
                if (first != null && first.getTimestampMillis() > 0) ts = first.getTimestampMillis();
            }

            // 诊断：带金额/资金词的短信都记录（含发送方尾4位），未通过银行特征判断也能看到
            boolean moneyish = body.matches(".*[¥￥].*") || body.matches(".*[0-9]+(\\.[0-9]+)?元.*")
                    || body.contains("支出") || body.contains("扣款") || body.contains("消费")
                    || body.contains("到账") || body.contains("余额");
            if (moneyish) {
                String tail = from.length() > 4 ? from.substring(from.length() - 4) : from;
                db.logDebug("sms-seen", "[" + tail + "] " + body);
            }

            // 只处理像银行发来的短信
            if (!(body.contains("银行") || body.contains("储蓄卡") || body.contains("信用卡")
                    || body.contains("账户") || body.contains("尾号") || body.contains("卡号"))) {
                if (moneyish) db.logDebug("sms-skip", "不像银行短信: " + body);
                return;
            }

            Parser.Parsed p = Parser.parse("sms", "", body);
            if (p == null) {
                if (moneyish) db.logDebug("sms-skip", "解析失败: " + body);
                return;
            }

            if (db.hasSimilar(p.amount, ts, "sms")) {
                // 同一笔已被其他渠道记账，但短信里的余额仍可用来校准
                db.logDebug("dup-sms", p.amount + "（与他渠道同金额，已合并）");
                if (p.balance >= 0) db.setAccount("bank", p.balance);
                return;
            }
            String dedup = "sms|" + p.amount + "|" + p.merchant + "|" + (ts / 60000);
            long id = db.addTx(p.amount, p.category, p.merchant, "sms", "", ts, dedup);
            if (id != -1) db.logDebug("sms-ok", p.amount + " " + p.merchant);

            if (p.balance >= 0) db.setAccount("bank", p.balance);
        } catch (Throwable ignored) {}
    }
}
