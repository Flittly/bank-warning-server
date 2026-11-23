package com.yangtze.bankwarning.ai.service;

import com.yangtze.bankwarning.ai.security.SkillSandboxExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页数估算与超时预算的守门测试。
 *
 * <p>这两个数字直接决定「一份几十页的扫描件会不会被误杀」，而误杀的后果不是报错，
 * 是静默回落 pypdf（表格拍平、层级全丢）。所以边界值都要钉住。
 */
class PdfPageEstimatorTest {

    /** 未压缩的最小单页 PDF：页对象与页树计数都存在。 */
    private static final String ONE_PAGE_PDF = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
            """;

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
        return file;
    }

    @Test
    void countsPageObjectsInPlainPdf() throws IOException {
        Path pdf = write("one.pdf", ONE_PAGE_PDF);
        assertEquals(1, PdfPageEstimator.estimate(pdf));
    }

    @Test
    void pagesNodeIsNotCountedAsPage() throws IOException {
        // 三页文档里 /Type /Pages 出现一次、/Type /Page 三次；
        // 若把 /Pages 也算进去就会报 4 页，超时预算被白白放大。
        String threePages = """
                %PDF-1.4
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >> endobj
                3 0 obj << /Type /Page /Parent 2 0 R >> endobj
                4 0 obj << /Type /Page /Parent 2 0 R >> endobj
                5 0 obj << /Type /Page /Parent 2 0 R >> endobj
                %%EOF
                """;
        assertEquals(3, PdfPageEstimator.estimate(write("three.pdf", threePages)));
    }

    @Test
    void fallsBackToDeclaredCountWhenObjectsAreCompressed() throws IOException {
        // 压缩对象流里的页对象扫不到，只剩页树上的 /Count 可读
        String compressed = "%PDF-1.7\n2 0 obj << /Type /Pages /Count 42 >> endobj\n%%EOF\n";
        assertEquals(42, PdfPageEstimator.estimate(write("compressed.pdf", compressed)));
    }

    @Test
    void takesTheLargerOfTwoSignals() throws IOException {
        // 中间节点 /Count 更小，根节点那个才是总数；取最大值才不会被带偏
        String nested = """
                %PDF-1.4
                2 0 obj << /Type /Pages /Kids [3 0 R] /Count 7 >> endobj
                3 0 obj << /Type /Pages /Kids [] /Count 2 >> endobj
                """;
        assertEquals(7, PdfPageEstimator.estimate(write("nested.pdf", nested)));
    }

    @Test
    void returnsUnknownForUnrecognizableInput() throws IOException {
        assertEquals(-1, PdfPageEstimator.estimate(write("garbage.pdf", "not a pdf at all")));
        assertEquals(-1, PdfPageEstimator.estimate(write("empty.pdf", "")));
        assertEquals(-1, PdfPageEstimator.estimate(null));
        assertEquals(-1, PdfPageEstimator.estimate(tempDir.resolve("missing.pdf")));
    }

    @Test
    void unknownPageCountKeepsConfiguredTimeout() {
        assertEquals(300, PdfPageEstimator.budgetSeconds(300, -1));
        assertEquals(0, PdfPageEstimator.budgetSeconds(0, -1));
    }

    @Test
    void budgetGrowsWithPagesButNeverBelowConfigured() {
        // 1 页按速率只要 45s，但配置已经给了 300s，不能反而缩短
        assertEquals(300, PdfPageEstimator.budgetSeconds(300, 1));
        // 30 页 = 25 + 600 = 625s，超过配置的 300s，按工作量给
        assertEquals(625, PdfPageEstimator.budgetSeconds(300, 30));
    }

    @Test
    void budgetIsCappedBySandboxLimit() {
        assertEquals(SkillSandboxExecutor.MAX_TIMEOUT_OVERRIDE_SECONDS,
                PdfPageEstimator.budgetSeconds(300, 100));
    }

    @Test
    void flagsFilesThatCannotFitInOneRun() {
        // 43 页 = 885s 还能装下；44 页 = 905s 就必然超时
        assertEquals(885, PdfPageEstimator.expectedSeconds(43));
        assertFalse(PdfPageEstimator.exceedsLimit(43));
        assertTrue(PdfPageEstimator.exceedsLimit(44));
        assertFalse(PdfPageEstimator.exceedsLimit(-1));
    }
}
