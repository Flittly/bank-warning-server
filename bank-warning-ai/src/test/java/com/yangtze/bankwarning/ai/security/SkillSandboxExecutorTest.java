package com.yangtze.bankwarning.ai.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段二沙箱执行器测试。
 *
 * 集成用例依赖本机 python（无 python 时自动跳过）；环境白名单等纯逻辑用例不依赖 python。
 */
class SkillSandboxExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> TEST_ALLOWLIST = List.of(
            "PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "LANG", "LC_ALL", "PYTHONIOENCODING");

    /** 沙箱内部约定变量（白名单之外由执行器显式注入的键） */
    private static final Set<String> INTERNAL_KEYS = Set.of(
            "PYTHONIOENCODING", "PYTHONHASHSEED", "TMP", "TEMP", "TMPDIR",
            "SKILL_SANDBOX_WORKDIR", "SKILL_SANDBOX_MEMORY_MB", "SKILL_SANDBOX_CPU_SECONDS",
            "SKILL_SANDBOX_READ_ROOTS", "UV_CACHE_DIR", "UV_NO_PROGRESS");

    @TempDir
    Path fixture;

    @Test
    void buildEnv_keepsOnlyAllowlistedAndInternalKeys() throws IOException {
        SkillSandboxExecutor executor = executor(120, 1024 * 1024);
        Path workdir = fixture.resolve("work");
        Files.createDirectories(workdir);

        Map<String, String> env = executor.buildEnv(
                fixture.resolve("skill"), List.of(fixture), workdir, Map.of("PYTHONIOENCODING", "utf-8"));

        Set<String> allowed = env.keySet().stream()
                .filter(k -> !INTERNAL_KEYS.contains(k))
                .collect(Collectors.toSet());
        assertTrue(TEST_ALLOWLIST.containsAll(allowed),
                "环境变量泄露了白名单之外的键: " + allowed);
        // 宿主常见密钥绝不该出现在子进程环境里
        assertFalse(env.containsKey("DATABASE_URL"));
        assertFalse(env.containsKey("NACOS_PASSWORD"));
        assertFalse(env.containsKey("DEEPSEEK_API_KEY"));
        assertFalse(env.containsKey("JWT_SECRET"));
    }

    @Test
    void executesBenignScript() throws Exception {
        assumePython();
        Path script = writeScript("print('hello-from-sandbox')");

        SkillSandboxExecutor.SandboxResult result = run(script, 10, 2048);

        assertTrue(result.isSuccess(), "stderr=" + result.getStderr());
        assertTrue(result.getStdout().contains("hello-from-sandbox"));
        assertEquals("PROCESS", result.getMode());
    }

    @Test
    void childEnvContainsNoHostSecrets() throws Exception {
        assumePython();
        Path script = writeScript(
                "import json, os\nprint(json.dumps(sorted(os.environ.keys())))\n");

        SkillSandboxExecutor.SandboxResult result = run(script, 10, 8192);

        assertTrue(result.isSuccess(), "stderr=" + result.getStderr());
        Set<String> keys = JSON.readValue(result.getStdout(), new TypeReference<Set<String>>() {});
        Set<String> allowed = new HashSet<>();
        INTERNAL_KEYS.forEach(k -> allowed.add(normalize(k)));
        TEST_ALLOWLIST.forEach(k -> allowed.add(normalize(k)));
        uncontrollableEnvKeys().forEach(k -> allowed.add(normalize(k)));
        Set<String> unexpected = keys.stream()
                .filter(k -> !allowed.contains(normalize(k)))
                .collect(Collectors.toSet());
        assertTrue(unexpected.isEmpty(),
                "子进程环境出现了未授权键（宿主继承 / 运行器注入之外的意外键）: " + unexpected);
        // 宿主必须无一泄漏：这几个键一旦出现就是真实的沙箱逃逸
        for (String secret : List.of("DATABASE_URL", "NACOS_PASSWORD", "DEEPSEEK_API_KEY", "JWT_SECRET")) {
            assertFalse(keys.contains(secret), "宿主密钥泄漏进子进程环境: " + secret);
        }
    }

    /**
     * 探测「父进程无论如何都清不掉的键」—— 用 <b>清空环境</b> 的方式启动一个 python，看还剩什么。
     *
     * <p>本机装了 LiteSandbox（对子进程做审计的工具）。实测结论：
     * <ol>
     *   <li>宿主 shell 的环境里<b>没有</b> {@code LSBOX_*}；</li>
     *   <li>但由宿主 shell 启动的 python，{@code os.environ} 里<b>有</b> {@code LSBOX_*}。</li>
     * </ol>
     * 一个进程不可能把自己都没有的变量传给子进程，因此唯一的解释是：
     * 注入发生在 {@code CreateProcess} 这一层（进程创建钩子），
     * {@code LSBOX_*} 是审计共享内存的句柄。它绕过了父进程的环境块，
     * 所以 {@code ProcessBuilder.environment().clear()} 也拦不住，{@code python -I} 同样拦不住。
     *
     * <p>这类键既不是宿主继承、也不是沙箱放行，断言里必须单独减掉；
     * 否则测试会因为"这台机器上恰好装了某个审计工具"而误报，
     * 真正值得盯的泄漏（宿主密钥被整份继承）反而被淹没。
     * {@code buildEnv} 自身的正确性由 {@link #buildEnv_keepsOnlyAllowlistedAndInternalKeys} 直接验证。
     */
    private static Set<String> uncontrollableEnvKeys() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python", "-c",
                "import os,json;print(json.dumps(sorted(os.environ.keys())))");
        pb.environment().clear();
        // 只补回两个"即使不放也一定会被宿主搜到"的启动必需项（它们本来就在白名单内，不影响判断力）
        for (String key : List.of("PATH", "SYSTEMROOT")) {
            String value = System.getenv(key);
            if (value != null) {
                pb.environment().put(key, value);
            }
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        String json = out.lines()
                .map(String::trim)
                .filter(l -> l.startsWith("["))
                .reduce((a, b) -> b)
                .orElse("[]");
        return JSON.readValue(json, new TypeReference<Set<String>>() {});
    }

    /** Windows 环境变量名不区分大小写，比较前统一 */
    private static String normalize(String key) {
        return key.toUpperCase(Locale.ROOT);
    }

    @Test
    void auditBlocksSocket() throws Exception {
        assumePython();
        Path script = writeScript("import socket\nsocket.socket()\nprint('unreachable')\n");

        SkillSandboxExecutor.SandboxResult result = run(script, 10, 8192);

        assertFalse(result.isSuccess());
        assertNotEquals(0, result.getExitCode());
        assertTrue(result.getStderr().contains("blocked by skill sandbox"), result.getStderr());
        assertFalse(result.getStdout().contains("unreachable"));
    }

    @Test
    void auditBlocksSubprocess() throws Exception {
        assumePython();
        Path script = writeScript("import subprocess\nsubprocess.run(['echo', 'hi'])\n");

        SkillSandboxExecutor.SandboxResult result = run(script, 10, 8192);

        assertFalse(result.isSuccess());
        assertTrue(result.getStderr().contains("blocked by skill sandbox"), result.getStderr());
    }

    @Test
    void auditBlocksWriteOutsideWorkdir() throws Exception {
        assumePython();
        Path evil = fixture.resolve("evil.txt");
        Path script = writeScript("open(r'" + evil + "', 'w').write('owned')\n");

        SkillSandboxExecutor.SandboxResult result = run(script, 10, 8192);

        assertFalse(result.isSuccess());
        assertTrue(result.getStderr().contains("blocked write outside workdir"), result.getStderr());
        assertFalse(Files.exists(evil), "宿主文件被越界写入");
    }

    @Test
    void allowsWriteInsideWorkdirAndReadOfReadRoots() throws Exception {
        assumePython();
        Path input = fixture.resolve("input.txt");
        Files.writeString(input, "pdf-bytes");
        Path script = writeScript(
                "import os\n"
                        + "p = os.path.join(os.environ['SKILL_SANDBOX_WORKDIR'], 'out.txt')\n"
                        + "open(p, 'w').write('ok')\n"
                        + "data = open(r'" + input + "', 'r').read()\n"
                        + "print(data)\n");

        SkillSandboxExecutor.SandboxRequest request = SkillSandboxExecutor.SandboxRequest.builder()
                .script(script)
                .skillDir(script.getParent())
                .readRoots(List.of(fixture))
                .args(List.of())
                .build();
        SkillSandboxExecutor.SandboxResult result = newExecutor(10, 8192).execute(request);

        assertTrue(result.isSuccess(), "stderr=" + result.getStderr());
        assertTrue(result.getStdout().contains("pdf-bytes"));
    }

    @Test
    void outputCapTruncates() throws Exception {
        assumePython();
        Path script = writeScript("print('x' * 5000)\n");

        SkillSandboxExecutor.SandboxResult result = run(script, 10, 1024);

        assertTrue(result.isSuccess());
        assertTrue(result.isOutputTruncated());
        assertTrue(result.getStdout().length() <= 4096,
                "stdout 未按上限截断: " + result.getStdout().length());
    }

    @Test
    void timeoutKillsProcessTree() throws Exception {
        assumePython();
        Path script = writeScript("import time\ntime.sleep(30)\n");

        SkillSandboxExecutor.SandboxResult result = run(script, 1, 8192);

        assertTrue(result.isTimedOut());
        assertFalse(result.isSuccess());
    }

    @Test
    void argvIsPreservedForScript() throws Exception {
        assumePython();
        Path script = writeScript("import sys\nprint('|'.join(sys.argv[1:]))\n");

        SkillSandboxExecutor.SandboxResult result = run(script, 10, 8192, List.of("a b", "c.txt"));

        assertTrue(result.isSuccess(), "stderr=" + result.getStderr());
        assertTrue(result.getStdout().contains("a b|c.txt"), result.getStdout());
    }

    // ---------- helpers ----------

    private void assumePython() {
        Assumptions.assumeTrue(pythonAvailable(), "本机无 python，跳过集成用例");
    }

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "-V").redirectErrorStream(true).start();
            boolean ok = p.waitFor(10, TimeUnit.SECONDS);
            return ok && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private SkillSandboxExecutor executor(int timeoutSeconds, int maxOutputBytes) throws IOException {
        return newExecutor(timeoutSeconds, maxOutputBytes);
    }

    private SkillSandboxExecutor newExecutor(int timeoutSeconds, int maxOutputBytes) throws IOException {
        Path cache = fixture.resolve("cache");
        Files.createDirectories(cache);
        return new SkillSandboxExecutor(
                () -> SkillSecuritySettings.withSandboxPolicy(
                        SkillSecurityProfile.STANDARD, "PROCESS", timeoutSeconds, maxOutputBytes),
                0.5, "", TEST_ALLOWLIST, cache.toString());
    }

    private Path writeScript(String body) throws IOException {
        Path dir = fixture.resolve("scripts");
        Files.createDirectories(dir);
        Path script = dir.resolve("test_" + System.nanoTime() + ".py");
        Files.writeString(script, body);
        return script;
    }

    private SkillSandboxExecutor.SandboxResult run(Path script, int timeout, int maxOutput) throws Exception {
        return run(script, timeout, maxOutput, List.of());
    }

    private SkillSandboxExecutor.SandboxResult run(Path script, int timeout, int maxOutput, List<String> args)
            throws Exception {
        SkillSandboxExecutor.SandboxRequest request = SkillSandboxExecutor.SandboxRequest.builder()
                .script(script)
                .skillDir(script.getParent())
                .args(args)
                .build();
        return newExecutor(timeout, maxOutput).execute(request);
    }
}
