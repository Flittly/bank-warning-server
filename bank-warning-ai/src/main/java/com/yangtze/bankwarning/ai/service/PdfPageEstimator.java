package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.SkillSandboxExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 粗估 PDF 页数，用来给 Docling 那一次执行算超时预算。
 *
 * <p><b>它不是 PDF 解析器。</b>只扫原始字节里的页对象与页树计数，压缩对象流
 * （ObjStm）里的页扫不到，所以结果只用于「决定等多久」，不参与任何正确性判断：
 * 估少了最坏退回配置里的固定值（等于没做这件事），估多了只是多给一点时间。
 *
 * <p><b>为什么必须按页算：</b>Docling 的耗时几乎与页数线性相关，而扫描件要以
 * OCR 速率走（实测约 20 秒/页）。旧做法是对所有 PDF 给同一个固定超时，结果是
 * 几页的电子版等待富余、几十页的扫描件必然超时——超时后回落 pypdf，表格与层级
 * 全丢，而且用户要等到最后一刻才知道。
 */
final class PdfPageEstimator {

    /** 单页 OCR 的边际耗时（实测 1 页 46s、3 页 86s，差值 40s 对应 2 页）。 */
    static final int PER_PAGE_SECONDS = 20;

    /** 一次性开销：解释器启动 + torch 与版面/表格模型加载（实测约 26 秒）。 */
    static final int FIXED_OVERHEAD_SECONDS = 25;

    /** 文件再大就不全文扫了：读上百 MB 只为数页不值当，直接退回配置值。 */
    private static final long MAX_SCAN_BYTES = 128L * 1024 * 1024;

    /** 页对象：{@code /Type /Page}，用负向断言排除 {@code /Pages}。 */
    private static final Pattern PAGE_OBJECT = Pattern.compile("/Type\\s*/Page(?![a-zA-Z])");

    /** 页树节点上的子页数：{@code /Count 42}。 */
    private static final Pattern COUNT_ENTRY = Pattern.compile("/Count\\s+(\\d{1,6})");

    private PdfPageEstimator() {
    }

    /**
     * 估页数；{@code -1} 表示估不出来（文件读不了、太大、或结构里没有可识别的页标记）。
     */
    static int estimate(Path pdf) {
        if (pdf == null) {
            return -1;
        }
        try {
            long size = Files.size(pdf);
            if (size <= 0 || size > MAX_SCAN_BYTES) {
                return -1;
            }
            // ISO-8859-1 是字节到字符的一一映射：不会因非法 UTF-8 序列丢字节，
            // 等价于"把文件当文本扫一遍"，不会踩坏二进制里的偏移。
            String raw = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
            // 两个信号取大：压缩过的 PDF 里页对象可能全在对象流内（数不到），
            // 而未压缩的 PDF 可能只有页对象、没有可靠的 /Count。任一能数出来就够用。
            int best = Math.max(countPageObjects(raw), maxDeclaredCount(raw));
            return best > 0 ? best : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 按页数给超时预算。
     *
     * @param configured 配置里的固定超时（秒），{@code <=0} 视为未配置
     * @param pages      估出的页数，{@code <=0} 表示估不出
     * @return 实际要传给沙箱的超时（秒）
     */
    static int budgetSeconds(int configured, int pages) {
        if (pages <= 0) {
            return configured;
        }
        long need = expectedSeconds(pages);
        long base = Math.max(configured, need);
        return (int) Math.min(base, SkillSandboxExecutor.MAX_TIMEOUT_OVERRIDE_SECONDS);
    }

    /** 按扫描件速率估算处理这么多页要多久（秒）。 */
    static int expectedSeconds(int pages) {
        if (pages <= 0) {
            return FIXED_OVERHEAD_SECONDS;
        }
        long need = FIXED_OVERHEAD_SECONDS + (long) PER_PAGE_SECONDS * pages;
        return need > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) need;
    }

    /** 预算是否被上限截断——截断了就说明"这份文件注定超时"，值得提前告警。 */
    static boolean exceedsLimit(int pages) {
        return pages > 0 && expectedSeconds(pages) > SkillSandboxExecutor.MAX_TIMEOUT_OVERRIDE_SECONDS;
    }

    private static int countPageObjects(String raw) {
        Matcher matcher = PAGE_OBJECT.matcher(raw);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 页树里声明的最多子页数。
     *
     * <p>取最大值而不是第一个：{@code /Count} 在每个中间节点上都有，根节点的那个才是总页数，
     * 而子节点的值更小。页数上千的文档会超过六位，正则直接不匹配，退回按页对象计数。
     */
    private static int maxDeclaredCount(String raw) {
        Matcher matcher = COUNT_ENTRY.matcher(raw);
        int max = 0;
        while (matcher.find()) {
            try {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 六位以内不会溢出，这里只是防御
            }
        }
        return max;
    }
}
