package com.momo.jizhang;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 支付通知 / 银行短信解析 + 本地关键词分类 */
public class Parser {

    public static class Parsed {
        public double amount;        // 负=支出 正=收入
        public String merchant = "";
        public String category = "daily";
        public double balance = -1;  // 短信中解析到的余额，-1 = 无
    }

    private static final Pattern BAL = Pattern.compile("余额[:：]?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

    /** 语序一：动作词在前，金额在后（"支出5.40元"/"扣款人民币100元"） */
    private static final Pattern AMT_AFTER = Pattern.compile(
            "(?:支出|付款|支付|扣款|消费|收款|退款|收入|转入|存入|缴费|还款|取现|到账)" +
            "[^0-9¥￥]{0,8}[¥￥]?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|块)?");
    /**
     * 语序二：金额在前，动作词在后（"你有一笔5.40元的支出"）。
     * 负数向回顾排除"10:01支出"这类时间数字误匹配。
     */
    private static final Pattern AMT_BEFORE = Pattern.compile(
            "(?<![:：.0-9])([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|块)?\\s*的?" +
            "(?:支出|付款|支付|扣款|消费|收款|退款|收入|转入|存入|缴费|还款|取现|到账)");
    private static final Pattern AMT_YUAN = Pattern.compile("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern AMT_PLAIN = Pattern.compile("(?<![:：.0-9])([0-9]+(?:\\.[0-9]{1,2})?)\\s*(?:元|块)");

    private static final String[] EXPENSE_WORDS = {"支出", "付款", "支付", "扣款", "消费", "缴费", "取现", "还款"};
    private static final String[] INCOME_WORDS  = {"退款", "收款", "收入", "转入", "到账", "存入", "工资"};

    /**
     * 排除词：只放"绝不会出现在真实扣款通知里"的词。
     * 进站/刷码/上车 = 乘车过程通知；验证码/登录等 = 系统通知；优惠券等 = 营销。
     * 注意：不可放"乘车码""点击查看"这类可能出现在真实扣款通知里的词。
     */
    private static final String[] EXCLUDE = {
            "进站", "刷码", "上车", "扫码成功", "出行提醒", "行程单", "开门",
            "验证码", "登录", "注册", "优惠券", "红包封面", "小程序"
    };

    /** 银行类渠道（短信/银行App通知）额外排除营销内容 */
    private static final String[] EXCLUDE_BANK_MSG = {
            "券", "待使用", "待领取", "退订", "拒收", "回复T", "回复R", "回复TD", "抽奖"
    };

    /** @param source wechat / alipay / sms / bankapp */
    public static Parsed parse(String source, String title, String text) {
        String all = ((title == null ? "" : title) + " " + (text == null ? "" : text)).trim();
        if (all.isEmpty()) return null;

        if (containsAny(all, EXCLUDE)) return null;
        // 银行类渠道额外过滤营销内容（营销短信必带"退订/拒收"，真实动账永不含这些词）
        if (("sms".equals(source) || "bankapp".equals(source)) && containsAny(all, EXCLUDE_BANK_MSG))
            return null;

        boolean isIncome = containsAny(all, INCOME_WORDS);
        boolean isExpense = containsAny(all, EXPENSE_WORDS);
        if (!isIncome && !isExpense) return null;
        // 同时含收支词时以支出优先
        if (isIncome && isExpense) {
            isIncome = all.contains("退款");
            isExpense = !isIncome;
        }

        Double amt = findAmount(all);
        if (amt == null || amt <= 0 || amt > 10000000) return null;

        Parsed p = new Parsed();
        p.amount = isExpense && !isIncome ? -amt : amt;
        p.merchant = findMerchant(all, source);
        p.category = classify(p.merchant + " " + all);

        Matcher bm = BAL.matcher(all);
        if (bm.find()) {
            try { p.balance = Double.parseDouble(bm.group(1).replace(",", "")); } catch (Exception ignored) {}
        }
        return p;
    }

    private static Double findAmount(String s) {
        Matcher m;
        // 优先"金额在前"语序：避免把"支出，点击领取2元红包"里的营销数字当成金额
        m = AMT_BEFORE.matcher(s);
        if (m.find()) { try { return Double.parseDouble(m.group(1)); } catch (Exception ignored) {} }
        m = AMT_AFTER.matcher(s);
        if (m.find()) { try { return Double.parseDouble(m.group(1)); } catch (Exception ignored) {} }
        m = AMT_YUAN.matcher(s);
        if (m.find()) { try { return Double.parseDouble(m.group(1)); } catch (Exception ignored) {} }
        m = AMT_PLAIN.matcher(s);
        if (m.find()) { try { return Double.parseDouble(m.group(1)); } catch (Exception ignored) {} }
        return null;
    }

    private static String findMerchant(String s, String source) {
        // 财付通渠道 = 微信支付
        if (s.contains("财付通")) return "微信支付";
        // "XX-消费-李文轩快捷支付" 模式：取快捷支付前最后一个 "-" 之后的内容
        int idx = s.indexOf("快捷支付");
        if (idx > 0) {
            String before = s.substring(0, idx);
            int dash = before.lastIndexOf('-');
            if (dash >= 0) {
                String v = before.substring(dash + 1).trim();
                if (v.length() >= 2 && v.length() <= 12
                        && !containsAny(v, "支付", "付款", "消费", "快捷", "二维码", "银行", "扫"))
                    return v;
            }
        }
        String[] patterns = {
                "向[\\\"「]?(.{1,20}?)[\\\"」]?付款",
                "收款方[:：]?\\s*(.{1,20}?)[\\s，,。]",
                "在[\\\"「]?(.{1,20}?)[\\\"」]?(?:消费|付款)(?:成功)?",
                "商户(?:名称)?[:：]\\s*(.{1,20}?)[\\s，,。]",
                "交易对象[:：]\\s*(.{1,20}?)[\\s，,。]",
        };
        for (String p : patterns) {
            try {
                Matcher m = Pattern.compile(p).matcher(s);
                while (m.find()) {
                    String v = m.group(1).trim();
                    if (v.length() >= 2 && !containsAny(v, "支付", "付款", "消费", "快捷", "二维码", "银行", "金额", "扫", "财付通", "-"))
                        return v.length() > 12 ? v.substring(0, 12) : v;
                }
            } catch (Exception ignored) {}
        }
        switch (source) {
            case "wechat": return "微信支付";
            case "alipay": return "支付宝";
            default: return "银行卡";
        }
    }

    /** 本地关键词分类引擎（离线可用） */
    public static String classify(String s) {
        if (containsAny(s, "地铁", "公交", "打车", "滴滴", "出租", "加油", "停车", "火车", "机票", "高铁",
                "12306", "单车", "乘车", "出行", "过路费", "航空", "巴士")) return "trans";
        if (containsAny(s, "餐", "饭", "面", "粉", "咖啡", "奶茶", "茶", "外卖", "小吃", "烤", "火锅",
                "水果", "菜", "超市", "便利", "肯德基", "麦当劳", "星巴克", "瑞幸", "食堂", "美团", "饿了么", "饮")) return "food";
        if (containsAny(s, "衣", "裤", "鞋", "裙", "外套", "优衣库", "服饰", "帽", "袜")) return "cloth";
        if (containsAny(s, "口红", "化妆", "护肤", "面膜", "美甲", "香水", "美妆")) return "beauty";
        if (containsAny(s, "电影", "游戏", "会员", "KTV", "票", "演出", "剧本杀", "娱", "视频")) return "fun";
        if (containsAny(s, "药", "医院", "门诊", "挂号", "诊所", "体检", "牙")) return "med";
        if (containsAny(s, "房租", "水电", "物业", "燃气", "宽带", "公寓")) return "house";
        return "daily";
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }
}
