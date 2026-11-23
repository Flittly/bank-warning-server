package com.yangtze.bankwarning.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * PDF 结构化提取（Docling）的启动自检。
 *
 * <p><b>为什么单独一份报告，而不并进 RAG 的那份</b>：这两件事的可观测面完全不同。
 * RAG 那份回答的是「检索按什么配置在跑」；这份回答的是「PDF 能不能被…结构化地读出来」。
 * 混在一起会让一个「开关没通电」的故事盖住另一个。
 *
 * <p><b>要防的具体事故</b>：离线机器上跑了半年 docx，某天有人传了第一份 PDF，
 * 转换报错、静默降级成 pypdf、表格全丢，而日志里只有一行 warn。等到发现「检索不到表里的数」
 * 时，已经没有人会把原因联想到「模型目录没预置」。
 *
 * <p><b>只报事实、不下结论</b>：这里不 import Docling、也不起子进程去探它装没装
 * （一次启动多几秒、还多了失败路径）。能确定的是「路径配了什么、目录里有几个文件」，
 * 就把这些打出来；至于能不能跑，第一次上传时自然会知道。
 *
 * <p><b>绝不阻断启动</b>：任何异常都只打 warn。
 */
@Component
public class DoclingStartupReport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DoclingStartupReport.class);

    /** 模型权重目录；不设则 Docling 首次转换会联网下载。 */
    @Value("${app.ai.pdf.docling-artifacts-path:${DOCLING_ARTIFACTS_PATH:}}")
    private String artifactsPath = "";

    /** 部署环境是否无外网。只有它和「模型未预置」同时成立，首次转换才必然失败。 */
    @Value("${app.ai.pdf.docling-offline:false}")
    private boolean offline;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Path artifacts = artifactsPath == null || artifactsPath.isBlank()
                    ? null : Paths.get(artifactsPath);
            int cached = countModelFiles(defaultCacheDir());

            log.info("[docling] ===== PDF 结构化提取 =====");
            log.info("[docling] 模型路径 : {}", artifacts == null
                    ? "未设置（首次转换联网下载到缓存目录）"
                    : artifacts + (Files.isDirectory(artifacts) ? "（存在）" : "（不存在）"));
            log.info("[docling] 本地缓存 : {}（{} 个文件）", defaultCacheDir(), cached);
            log.info("[docling] 降级策略 : Docling 不可用时回落 pypdf，入库结果标记 degraded=true");
            for (String w : warnings(artifactsPath, artifacts != null && Files.isDirectory(artifacts),
                    cached, offline)) {
                log.warn("[docling] [warn] {}", w);
            }
            log.info("[docling] ============================");
        } catch (Exception e) {
            log.warn("[docling] 启动自检打印失败（不影响服务）: {}", e.getMessage());
        }
    }

    /** Docling 的默认缓存位置，与 {@code docling.datamodel.settings.AppSettings.cache_dir} 一致。 */
    static Path defaultCacheDir() {
        return Paths.get(System.getProperty("user.home", "."), ".cache", "docling", "models");
    }

    /** 缓存目录里的文件数；目录不存在或读不了都算 0（这只是个提示，不是判据）。 */
    private static int countModelFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(dir, 3)) {
            return (int) files.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 组合检查。抽成包级方法是为了能单测「什么情况报什么」，而不是去断言日志字符串。
     *
     * <p>三条断言各自对应一种真实故障：
     * <ul>
     *   <li>离线且没预置 → 第一份 PDF 必然降级，而且**每次**都会试一次再失败；</li>
     *   <li>路径配了但目录不在 → 通常是把别的机器的路径抄过来了；</li>
     *   <li>哪都没模型 → 联网时能自愈，但第一次上传会明显变慢，值得先说一声。</li>
     * </ul>
     */
    static List<String> warnings(String artifactsPath, boolean artifactsExists,
                                 int cachedModelFiles, boolean offline) {
        List<String> out = new ArrayList<>();
        boolean artifactsSet = artifactsPath != null && !artifactsPath.isBlank();
        boolean hasModels = (artifactsSet && artifactsExists) || cachedModelFiles > 0;

        if (artifactsSet && !artifactsExists) {
            out.add("DOCLING_ARTIFACTS_PATH 指向的目录不存在：" + artifactsPath
                    + "；PDF 转换会失败并回落 pypdf（表格与层级丢失）");
        }
        if (offline && !hasModels) {
            out.add("离线环境且未预置 Docling 模型：第一份 PDF 必然回落 pypdf，"
                    + "请把模型目录随交付包一起搬过来并设置 DOCLING_ARTIFACTS_PATH");
        }
        // 「路径配错」已经在上面说清楚了，不再叠一条「尚未预置」——同一件事说两遍
        // 只会让运维以为有两个问题要处理。
        if (!offline && !hasModels && !artifactsSet) {
            out.add("尚未预置 Docling 模型：第一份 PDF 会先联网下载权重（数百 MB 量级），"
                    + "期间该次上传耗时明显偏长");
        }
        return out;
    }
}
