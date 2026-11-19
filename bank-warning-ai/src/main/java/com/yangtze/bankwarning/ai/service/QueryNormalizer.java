package com.yangtze.bankwarning.ai.service;

/**
 * 阶段 5：查询归一化——把「看起来一样、机器读起来不一样」的写法折成同一种。
 *
 * <p><b>为什么这一步值得做</b>：关键词路是<b>字面</b>匹配。同一个意思写成全角「２０３」、
 * 罗马数字「Ⅲ 级」、或者带不可见字符的复制粘贴文本，倒排索引里就是另一个词项，
 * 于是「明明资料里有，就是查不到」。归一化的收益不需要评测证明——它是把噪声消掉，
 * 不是改判断逻辑。
 *
 * <p><b>只做无损变换</b>：全角转半角（U+FF01..U+FF5E → U+0021..U+007E）、
 * 罗马数字转阿拉伯数字、去掉零宽字符与多余空白。任何需要「理解语义」的改动
 * 都不属于这一层——那属于查询改写，风险高得多，默认关闭。
 */
public final class QueryNormalizer {

    private QueryNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // 全角 ASCII（！到 ～）折成半角；中文标点不在这个区间，不受影响。
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
                continue;
            }
            // 零宽空格 / 零宽连接符 / BOM：从网页或 Word 复制时最常见，肉眼不可见但会拆词。
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            char roman = romanToDigit(c);
            sb.append(roman == 0 ? c : roman);
        }
        // 连续空白折成一个空格：分词器对空白不敏感，但词项去重要按空白切。
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** Ⅰ~Ⅳ（含小写 ⅰ~ⅳ）转 1~4；不认识的字符返回 0，由调用方保留原字符。 */
    private static char romanToDigit(char c) {
        return switch (c) {
            case 'Ⅰ', 'ⅰ' -> '1';
            case 'Ⅱ', 'ⅱ' -> '2';
            case 'Ⅲ', 'ⅲ' -> '3';
            case 'Ⅳ', 'ⅳ' -> '4';
            default -> 0;
        };
    }
}
