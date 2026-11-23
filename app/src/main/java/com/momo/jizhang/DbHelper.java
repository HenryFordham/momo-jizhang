package com.momo.jizhang;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

public class DbHelper extends SQLiteOpenHelper {

    public DbHelper(Context ctx) { super(ctx, "momo.db", null, 3); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transactions(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "amount REAL NOT NULL," +           // 负=支出 正=收入
                "category TEXT NOT NULL," +
                "merchant TEXT NOT NULL," +
                "channel TEXT NOT NULL," +          // wechat/alipay/sms/manual
                "account TEXT NOT NULL DEFAULT ''," + // 扣款账户 wechat/alipay/bank
                "ts INTEGER NOT NULL," +
                "dedup TEXT UNIQUE)");
        db.execSQL("CREATE TABLE accounts(type TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE goals(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT NOT NULL," +
                "name TEXT NOT NULL," +
                "target REAL NOT NULL," +
                "saved REAL NOT NULL DEFAULT 0," +
                "link TEXT)");
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("INSERT INTO accounts(type,balance) VALUES('wechat',0),('alipay',0),('bank',0)");
        db.execSQL("INSERT INTO settings(key,value) VALUES" +
                "('salary','0'),('budget','0'),('bg','bg-cream')," +
                "('catBudgets','{\"food\":3000,\"trans\":300,\"cloth\":300,\"beauty\":300,\"fun\":300,\"med\":300,\"house\":2000,\"daily\":680}')," +
                "('channels','{\"wechat\":true,\"alipay\":true,\"sms\":true}')");
        db.execSQL("CREATE TABLE debug_log(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "source TEXT NOT NULL, content TEXT NOT NULL, ts INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN account TEXT NOT NULL DEFAULT ''");
        }
        if (o < 3) {
            db.execSQL("CREATE TABLE debug_log(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "source TEXT NOT NULL, content TEXT NOT NULL, ts INTEGER NOT NULL)");
        }
    }

    /* ---------- 诊断日志 ---------- */
    public void logDebug(String source, String content) {
        ContentValues v = new ContentValues();
        v.put("source", source);
        v.put("content", content.length() > 200 ? content.substring(0, 200) : content);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("debug_log", null, v);
        getWritableDatabase().execSQL(
                "DELETE FROM debug_log WHERE id NOT IN (SELECT id FROM debug_log ORDER BY id DESC LIMIT 100)");
    }

    public JSONArray getDebugLog() throws Exception {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT source, content, ts FROM debug_log ORDER BY id DESC LIMIT 50", null);
        JSONArray arr = new JSONArray();
        while (c.moveToNext()) {
            JSONObject o = new JSONObject();
            o.put("source", c.getString(0)); o.put("content", c.getString(1)); o.put("ts", c.getLong(2));
            arr.put(o);
        }
        c.close();
        return arr;
    }

    public void clearDebugLog() {
        getWritableDatabase().delete("debug_log", null, null);
    }

    /* ---------- 余额联动 ---------- */
    private String accountOf(String channel, String account) {
        if (account != null && !account.isEmpty()) return account;
        switch (channel) {
            case "wechat": return "wechat";
            case "alipay": return "alipay";
            case "sms":    return "bank";
            case "bankapp": return "bank";
            default:       return "";
        }
    }

    private void applyBalance(String account, double delta) {
        if (account == null || account.isEmpty()) return;
        getWritableDatabase().execSQL("UPDATE accounts SET balance = balance + ? WHERE type=?",
                new Object[]{delta, account});
    }

    /* ---------- transactions ---------- */
    public long addTx(double amount, String cat, String merchant, String channel, String account, long ts, String dedup) {
        ContentValues v = new ContentValues();
        v.put("amount", amount); v.put("category", cat); v.put("merchant", merchant);
        v.put("channel", channel); v.put("account", accountOf(channel, account));
        v.put("ts", ts); v.put("dedup", dedup);
        long id = getWritableDatabase().insertWithOnConflict("transactions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id != -1) applyBalance(accountOf(channel, account), amount);
        return id;
    }

    public boolean deleteTx(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT amount, account FROM transactions WHERE id=" + id, null);
        if (!c.moveToFirst()) { c.close(); return false; }
        double amt = c.getDouble(0); String acc = c.getString(1);
        c.close();
        boolean ok = getWritableDatabase().delete("transactions", "id=" + id, null) > 0;
        if (ok) applyBalance(acc, -amt);
        return ok;
    }

    public void updateTx(long id, double amount, String cat, String merchant) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT amount, account FROM transactions WHERE id=" + id, null);
        if (!c.moveToFirst()) { c.close(); return; }
        double oldAmt = c.getDouble(0); String acc = c.getString(1);
        c.close();
        ContentValues v = new ContentValues();
        v.put("amount", amount); v.put("category", cat); v.put("merchant", merchant);
        getWritableDatabase().update("transactions", v, "id=" + id, null);
        applyBalance(acc, amount - oldAmt);
    }

    /** 今天指定渠道是否已有同金额记录（用于零钱预览入账时排除已被银行渠道记录的） */
    public boolean existsChannelAmountToday(double amount, String[] channels) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < channels.length; i++) {
            if (i > 0) in.append(',');
            in.append('\'').append(channels[i]).append('\'');
        }
        Cursor cur = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM transactions WHERE ABS(ABS(amount) - ?) < 0.001 AND ts>=? AND channel IN (" + in + ")",
                new String[]{String.valueOf(Math.abs(amount)), String.valueOf(c.getTimeInMillis())});
        boolean exists = false;
        if (cur.moveToFirst()) exists = cur.getInt(0) > 0;
        cur.close();
        return exists;
    }

    /** 防"翻旧账重复记"：今天是否已有同额同商户的无障碍记录 */
    public boolean existsA11yToday(double amount, String merchant) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        Cursor cur = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM transactions WHERE channel='a11y'" +
                        " AND ABS(ABS(amount) - ?) < 0.001 AND merchant=? AND ts>=?",
                new String[]{String.valueOf(Math.abs(amount)), merchant, String.valueOf(c.getTimeInMillis())});
        boolean exists = false;
        if (cur.moveToFirst()) exists = cur.getInt(0) > 0;
        cur.close();
        return exists;
    }

    /** 跨渠道去重：2 分钟内、其他渠道已有相同金额的账单才视为重复（同渠道连付两笔同金额不算） */
    public boolean hasSimilar(double amount, long ts, String channel) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM transactions WHERE ABS(ABS(amount) - ?) < 0.001" +
                        " AND ABS(ts - ?) < 120000 AND channel != ?",
                new String[]{String.valueOf(Math.abs(amount)), String.valueOf(ts), channel});
        boolean dup = false;
        if (c.moveToFirst()) dup = c.getInt(0) > 0;
        c.close();
        return dup;
    }

    private static long[] monthRange(int year, int month) {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, 1, 0, 0, 0); c.set(Calendar.MILLISECOND, 0);
        long start = c.getTimeInMillis();
        c.add(Calendar.MONTH, 1);
        return new long[]{start, c.getTimeInMillis()};
    }

    public JSONObject monthSummary(int year, int month) throws Exception {
        long[] r = monthRange(year, month);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, amount FROM transactions WHERE ts>=? AND ts<?",
                new String[]{String.valueOf(r[0]), String.valueOf(r[1])});
        double income = 0, expense = 0;
        JSONObject byCat = new JSONObject();
        while (c.moveToNext()) {
            String cat = c.getString(0);
            double amt = c.getDouble(1);
            if (amt >= 0) income += amt;
            else { expense += -amt; byCat.put(cat, byCat.optDouble(cat, 0) + (-amt)); }
        }
        c.close();
        JSONObject o = new JSONObject();
        o.put("income", income); o.put("expense", expense); o.put("byCat", byCat);
        return o;
    }

    public JSONArray txForMonth(int year, int month) throws Exception {
        long[] r = monthRange(year, month);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, category, merchant, channel, ts FROM transactions" +
                        " WHERE ts>=? AND ts<? ORDER BY ts DESC LIMIT 800",
                new String[]{String.valueOf(r[0]), String.valueOf(r[1])});
        return cursorToJson(c);
    }

    public JSONArray txForCategory(String cat, int year, int month) throws Exception {
        long[] r = monthRange(year, month);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, amount, category, merchant, channel, ts FROM transactions" +
                        " WHERE category=? AND ts>=? AND ts<? ORDER BY ts DESC LIMIT 500",
                new String[]{cat, String.valueOf(r[0]), String.valueOf(r[1])});
        return cursorToJson(c);
    }

    private JSONArray cursorToJson(Cursor c) throws Exception {
        JSONArray arr = new JSONArray();
        while (c.moveToNext()) {
            JSONObject o = new JSONObject();
            o.put("id", c.getLong(0)); o.put("amount", c.getDouble(1));
            o.put("category", c.getString(2)); o.put("merchant", c.getString(3));
            o.put("channel", c.getString(4)); o.put("ts", c.getLong(5));
            arr.put(o);
        }
        c.close();
        return arr;
    }

    public double[] weekCompare(String cat) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        long thisWeek = c.getTimeInMillis();
        c.add(Calendar.WEEK_OF_YEAR, -1);
        long lastWeek = c.getTimeInMillis();
        double[] out = new double[]{0, 0};
        String sql = "SELECT SUM(-amount) FROM transactions WHERE amount<0 AND ts>=? AND ts<?" +
                (cat == null ? "" : " AND category='" + cat + "'");
        Cursor c1 = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(thisWeek), String.valueOf(Long.MAX_VALUE / 2)});
        if (c1.moveToFirst()) out[0] = c1.isNull(0) ? 0 : c1.getDouble(0);
        c1.close();
        Cursor c2 = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(lastWeek), String.valueOf(thisWeek)});
        if (c2.moveToFirst()) out[1] = c2.isNull(0) ? 0 : c2.getDouble(0);
        c2.close();
        return out;
    }

    public double todaySpent() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        Cursor cur = getReadableDatabase().rawQuery(
                "SELECT SUM(-amount) FROM transactions WHERE amount<0 AND ts>=?",
                new String[]{String.valueOf(c.getTimeInMillis())});
        double v = 0;
        if (cur.moveToFirst() && !cur.isNull(0)) v = cur.getDouble(0);
        cur.close();
        return v;
    }

    /* ---------- accounts ---------- */
    public JSONArray getAccounts() throws Exception {
        Cursor c = getReadableDatabase().rawQuery("SELECT type, balance FROM accounts", null);
        JSONArray arr = new JSONArray();
        while (c.moveToNext()) {
            JSONObject o = new JSONObject();
            o.put("type", c.getString(0)); o.put("balance", c.getDouble(1));
            arr.put(o);
        }
        c.close();
        return arr;
    }

    public void setAccount(String type, double balance) {
        ContentValues v = new ContentValues();
        v.put("balance", balance);
        getWritableDatabase().update("accounts", v, "type=?", new String[]{type});
    }

    /* ---------- goals ---------- */
    public JSONArray getGoals() throws Exception {
        Cursor c = getReadableDatabase().rawQuery("SELECT id,type,name,target,saved,link FROM goals ORDER BY id", null);
        JSONArray arr = new JSONArray();
        while (c.moveToNext()) {
            JSONObject o = new JSONObject();
            o.put("id", c.getLong(0)); o.put("type", c.getString(1)); o.put("name", c.getString(2));
            o.put("target", c.getDouble(3)); o.put("saved", c.getDouble(4));
            o.put("link", c.isNull(5) ? "" : c.getString(5));
            arr.put(o);
        }
        c.close();
        return arr;
    }

    public long addGoal(String type, String name, double target, String link) {
        ContentValues v = new ContentValues();
        v.put("type", type); v.put("name", name); v.put("target", target); v.put("saved", 0); v.put("link", link);
        return getWritableDatabase().insert("goals", null, v);
    }

    public void updateGoalSaved(long id, double saved) {
        ContentValues v = new ContentValues();
        v.put("saved", saved);
        getWritableDatabase().update("goals", v, "id=" + id, null);
    }

    public void deleteGoal(long id) {
        getWritableDatabase().delete("goals", "id=" + id, null);
    }

    /* ---------- settings ---------- */
    public String getSetting(String key, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key});
        String v = def;
        if (c.moveToFirst()) v = c.getString(0);
        c.close();
        return v;
    }

    public void setSetting(String key, String value) {
        getWritableDatabase().execSQL("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)",
                new Object[]{key, value});
    }
}
