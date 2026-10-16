package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.SkillPathGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 报告导出打包。
 *
 * <p>一次报告生成会落一个同名输出目录 {@code report_<taskId>_<ts>/}，里面同时有
 * 子图（png）与**分区断面详情 CSV**。导出时把两者打进一个 zip：
 * {@code 报告.docx + 分区1_断面详情.csv + … + 分区N_断面详情.csv}，
 * 这样「报告」与「各分区断面详情」是同一次导出的产物，不会各下载一次而对不上。
 */
@Service
public class ReportPackageService {

    private static final Logger log = LoggerFactory.getLogger(ReportPackageService.class);

    /** 报告文件名契约：{@code report_<taskId>_<yyyyMMdd_HHmmss>.md}（与 ReportsController 一致）。 */
    private static final Pattern REPORT_NAME_PTN =
            Pattern.compile("^report_(.+?)_(\\d{8}_\\d{6})\\.md$");

    /** CSV 文件名里的分区序号：{@code 分区<序号>_断面详情.csv}。 */
    private static final Pattern CSV_INDEX_PTN = Pattern.compile("^分区(\\d+)_");

    /** 报告 md 文件名 → 同名输出目录名；不匹配（非法文件名）返回 null。 */
    public static String reportDirName(String reportFileName) {
        if (reportFileName == null) {
            return null;
        }
        Matcher m = REPORT_NAME_PTN.matcher(reportFileName);
        return m.matches() ? "report_" + m.group(1) + "_" + m.group(2) : null;
    }

    /**
     * 报告对应的输出目录（含子图与分区 CSV）。
     *
     * <p>目录名由正则白名单字符拼出，再用 {@link SkillPathGuard#isWithin} 兜一层
     * 目录包含校验，确保不会越出 outputDir。
     *
     * @return 存在且为目录时返回 File，否则 null
     */
    public File resolveReportDir(File outputDir, String reportFileName) {
        if (outputDir == null) {
            return null;
        }
        String dirName = reportDirName(reportFileName);
        if (dirName == null) {
            return null;
        }
        File dir = new File(outputDir, dirName);
        if (!SkillPathGuard.isWithin(outputDir.toPath(), dir.toPath())) {
            return null;
        }
        return dir.exists() && dir.isDirectory() ? dir : null;
    }

    /** 目录内的分区断面详情 CSV，按分区序号排序（「未落入分区」排最后）。无目录时返回空列表。 */
    public List<File> listPartitionCsvs(File reportDir) {
        if (reportDir == null) {
            return List.of();
        }
        File[] files = reportDir.listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".csv"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<File> csvs = new ArrayList<>(Arrays.asList(files));
        csvs.sort(Comparator.comparingInt(ReportPackageService::csvOrder)
                .thenComparing(File::getName));
        return csvs;
    }

    private static int csvOrder(File file) {
        Matcher m = CSV_INDEX_PTN.matcher(file.getName());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /**
     * 把 docx 与分区 CSV 打成 zip。
     *
     * @return zip 字节；**没有 CSV 时返回 null**（调用方按历史行为直接返回 docx）
     */
    public byte[] zipWithCsvs(String docxName, byte[] docx, List<File> csvs) {
        if (csvs == null || csvs.isEmpty()) {
            return null;
        }
        String safeDocxName = (docxName == null || docxName.isBlank()) ? "report.docx" : docxName;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(safeDocxName));
            zos.write(docx);
            zos.closeEntry();
            for (File csv : csvs) {
                // 中文文件名靠 UTF-8 字符集写 EFS 标志位，Windows 资源管理器 / 7-Zip 均可正确解出
                zos.putNextEntry(new ZipEntry(csv.getName()));
                zos.write(Files.readAllBytes(csv.toPath()));
                zos.closeEntry();
            }
        } catch (IOException e) {
            log.error("[package] 打包报告导出失败: {}", e.getMessage(), e);
            return null;
        }
        log.info("[package] 报告导出包已生成：{} + {} 个分区 CSV", safeDocxName, csvs.size());
        return bos.toByteArray();
    }

    /**
     * 构造 Content-Disposition：同时给出 ASCII 回退名与 RFC 5987 的 UTF-8 名，
     * 避免中文文件名在部分客户端下变成乱码或被截断。
     */
    public static String contentDisposition(String fileName) {
        String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String encoded = java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
