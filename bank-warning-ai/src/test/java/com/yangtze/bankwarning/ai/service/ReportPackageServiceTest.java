package com.yangtze.bankwarning.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告导出打包的契约测试。
 *
 * <p>导出报告时，本次任务输出目录里的分区断面详情 CSV 必须与 docx 打进同一个
 * 压缩包（否则「报告」与「各分区详情」是两次各自的下载，用户很难对上）。
 * 没有 CSV 时必须退回原行为——直接返回 docx。
 */
@DisplayName("报告导出打包")
class ReportPackageServiceTest {

    private final ReportPackageService service = new ReportPackageService();

    // ------------------------------------------------------------------
    // 1. 报告文件名 → 输出目录
    // ------------------------------------------------------------------

    @Test
    @DisplayName("报告文件名反解输出目录名；非法文件名返回 null")
    void reportDirName() {
        assertEquals("report_t1_20261015_143000",
                ReportPackageService.reportDirName("report_t1_20261015_143000.md"));
        // taskId 自带下划线时也要能整体取出
        assertEquals("report_a_b_20261015_143000",
                ReportPackageService.reportDirName("report_a_b_20261015_143000.md"));
        assertNull(ReportPackageService.reportDirName("foo.md"));
        assertNull(ReportPackageService.reportDirName(null));
    }

    @Test
    @DisplayName("目录不存在时返回 null（老报告 / 已被清理）")
    void resolveMissingDir(@TempDir Path outputDir) {
        assertNull(service.resolveReportDir(outputDir.toFile(), "report_t1_20261015_143000.md"));
    }

    // ------------------------------------------------------------------
    // 2. CSV 清单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CSV 按分区序号排序，「未落入分区」排最后，非 CSV 文件被排除")
    void listPartitionCsvsOrdersByIndex(@TempDir Path dir) throws Exception {
        // 故意用会暴露字符串排序问题的 2 与 10
        Files.writeString(dir.resolve("分区10_断面详情.csv"), "x");
        Files.writeString(dir.resolve("分区2_断面详情.csv"), "x");
        Files.writeString(dir.resolve("分区1_断面详情.csv"), "x");
        Files.writeString(dir.resolve("未落入分区_断面详情.csv"), "x");
        Files.writeString(dir.resolve("risk_distribution_map_r0c0.png"), "x");

        List<String> names = service.listPartitionCsvs(dir.toFile()).stream()
                .map(File::getName).toList();

        assertEquals(List.of("分区1_断面详情.csv", "分区2_断面详情.csv",
                "分区10_断面详情.csv", "未落入分区_断面详情.csv"), names);
    }

    @Test
    @DisplayName("无目录 / 无 CSV 时返回空列表")
    void listPartitionCsvsEmpty(@TempDir Path dir) {
        assertTrue(service.listPartitionCsvs(null).isEmpty());
        assertTrue(service.listPartitionCsvs(dir.toFile()).isEmpty());
    }

    // ------------------------------------------------------------------
    // 3. 打包
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无 CSV 时不打包（返回 null）→ 调用方按原样返回 docx")
    void noCsvMeansNoZip() {
        assertNull(service.zipWithCsvs("report.docx", new byte[] {1, 2, 3}, List.of()));
        assertNull(service.zipWithCsvs("report.docx", new byte[] {1, 2, 3}, null));
    }

    @Test
    @DisplayName("zip 内含 docx 与全部分区 CSV，中文条目名可原样读回")
    void zipContainsDocxAndCsvs(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("分区1_断面详情.csv"), "断面ID\nS1\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("分区2_断面详情.csv"), "断面ID\nS2\n", StandardCharsets.UTF_8);

        byte[] docx = "FAKE-DOCX".getBytes(StandardCharsets.UTF_8);
        byte[] zip = service.zipWithCsvs("report_t1_20261015_143000.docx", docx,
                service.listPartitionCsvs(dir.toFile()));

        assertNotNull(zip);
        List<String> names = new ArrayList<>();
        String docxContent = null;
        String csv1 = null;
        try (ZipInputStream zin = new ZipInputStream(
                new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                names.add(entry.getName());
                String content = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                if (entry.getName().endsWith(".docx")) {
                    docxContent = content;
                } else if (entry.getName().equals("分区1_断面详情.csv")) {
                    csv1 = content;
                }
            }
        }

        assertEquals(List.of("report_t1_20261015_143000.docx",
                "分区1_断面详情.csv", "分区2_断面详情.csv"), names);
        assertEquals("FAKE-DOCX", docxContent);
        assertTrue(csv1 != null && csv1.contains("S1"), String.valueOf(csv1));
    }

    // ------------------------------------------------------------------
    // 4. 响应头
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Content-Disposition 同时给 ASCII 回退名与 RFC 5987 的 UTF-8 名")
    void contentDispositionCarriesUtf8Name() {
        String header = ReportPackageService.contentDisposition("report_t1_20261015_143000_导出包.zip");
        assertTrue(header.startsWith("attachment; filename=\""), header);
        assertTrue(header.contains("filename*=UTF-8''"), header);
        assertTrue(header.contains("%E5%AF%BC%E5%87%BA%E5%8C%85.zip"), "中文应做百分号编码：" + header);
        assertFalse(header.contains("导出包"), "头部不应出现裸中文：" + header);
    }
}
