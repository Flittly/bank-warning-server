package com.yangtze.bankwarning.ai.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill 脚本执行沙箱（阶段二 · 执行隔离）。
 * 作为 skill 脚本执行的统一入口，替代原先散落在执行链路里的裸 ProcessBuilder。
 * 无论沙箱开关如何，始终执行三项基础执行约束：
 *   1. 环境变量白名单，子进程绝不继承宿主密钥（DATABASE_URL / NACOS_* / API Key / JWT）；
 *   2. 强超时，超时后强杀整棵进程树；
 *   3. 输出上限，stdout/stderr 超限即截断并标记，防止输出洪泛打爆内存。
 * 沙箱强度由 mode 决定：
 *   OFF      —— 阶段一原行为（直接跑脚本，不注入引导脚本）
 *   PROCESS  —— 进程级软沙箱：python -I + sandbox_bootstrap.py（audit hook + rlimit）
 *   DOCKER   —— 容器级硬沙箱：--network none --read-only --cap-drop ALL --no-new-privileges ...
 */
@Component
public class SkillSandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillSandboxExecutor.class);

    public enum Mode {
        OFF, PROCESS, DOCKER
    }

    private final Mode mode;
    private final int timeoutSeconds;
    private final int maxOutputBytes;
    private final int memoryMb;
    private final int cpuSeconds;
    private final int pidsLimit;
    private final double cpus;
    private final String dockerImage;
    private final List<String> envAllowlist;
    private final Path bootstrapFile;
    private final String uvCacheDir;

    public SkillSandboxExecutor(
            @Value("${app.ai.skill.sandbox.mode:process}") String mode,
            @Value("${app.ai.skill.sandbox.timeout-seconds:120}") int timeoutSeconds,
            @Value("${app.ai.skill.sandbox.max-output-bytes:1048576}") int maxOutputBytes,
            @Value("${app.ai.skill.sandbox.memory-mb:512}") int memoryMb,
            @Value("${app.ai.skill.sandbox.cpu-seconds:30}") int cpuSeconds,
            @Value("${app.ai.skill.sandbox.pids-limit:64}") int pidsLimit,
            @Value("${app.ai.skill.sandbox.cpus:0.5}") double cpus,
            @Value("${app.ai.skill.sandbox.docker-image:}") String dockerImage,
            @Value("${app.ai.skill.sandbox.env-allowlist:}") List<String> envAllowlist,
            @Value("${app.ai.skill.cache-dir:${user.dir}/.skills-cache}") String cacheDir) {
        this.mode = parseMode(mode);
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
        this.memoryMb = memoryMb;
        this.cpuSeconds = cpuSeconds;
        this.pidsLimit = pidsLimit;
        this.cpus = cpus;
        this.dockerImage = dockerImage;
        this.envAllowlist = envAllowlist == null || envAllowlist.isEmpty()
                ? List.of("PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT",
                        "LANG", "LC_ALL", "PYTHONIOENCODING")
                : List.copyOf(envAllowlist);
        Path cacheRoot = Paths.get(cacheDir).toAbsolutePath().normalize();
        this.uvCacheDir = cacheRoot.resolve("_uv").toString();
        this.bootstrapFile = extractBootstrap(cacheRoot);
        log.info("[skill-sandbox] mode={}, timeout={}s, max-output={}B, memory={}MB, cpu={}s, pids={}",
                this.mode, timeoutSeconds, maxOutputBytes, memoryMb, cpuSeconds, pidsLimit);
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isSandboxEnabled() {
        return mode != Mode.OFF;
    }

    /**
     * 执行一个沙箱化命令。
     *
     * @param request 执行请求（脚本、skill 目录、参数、只读根、额外环境变量等）
     * @return 结构化执行结果
     * @throws IOException 进程启动失败
     */
    public SandboxResult execute(SandboxRequest request) throws IOException {
        Path script = request.script.toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) {
            throw new IllegalArgumentException("脚本不存在: " + script);
        }
        Path skillDir = request.skillDir == null
                ? script.getParent()
                : request.skillDir.toAbsolutePath().normalize();
        boolean ownWorkDir = request.workDir == null;
        Path workDir = ownWorkDir ? Files.createTempDirectory("skill-sandbox-")
                : request.workDir.toAbsolutePath().normalize();
        Files.createDirectories(workDir);

        Map<String, String> env = buildEnv(skillDir, request.readRoots, workDir, request.extraEnv);
        List<String> command = buildCommand(request, script, skillDir, workDir);
        long started = System.nanoTime();
        Process process = null;
        boolean timedOut = false;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // 工作目录 = 本次执行的临时目录，脚本的相对路径写入都落在这里
            pb.directory(workDir.toFile());
            // 关键：清空继承的环境，只放白名单（防 os.environ 偷密钥）
            pb.environment().clear();
            pb.environment().putAll(env);
            final Process startedProcess = pb.start();
            process = startedProcess;
            writeStdin(startedProcess, request.stdin);

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            boolean[] stdoutTruncated = {false};
            boolean[] stderrTruncated = {false};
            Thread outThread = new Thread(
                    () -> readBounded(startedProcess.getInputStream(), maxOutputBytes, stdout, stdoutTruncated));
            Thread errThread = new Thread(
                    () -> readBounded(startedProcess.getErrorStream(), maxOutputBytes, stderr, stderrTruncated));
            outThread.start();
            errThread.start();

            timedOut = !awaitCompletion(startedProcess, timeoutSeconds);
            if (timedOut) {
                log.warn("[skill-sandbox] 执行超时（>{}s），强杀进程树", timeoutSeconds);
                destroyTree(startedProcess);
                awaitTermination(startedProcess);
            }
            joinQuietly(outThread);
            joinQuietly(errThread);
            destroyDescendants(startedProcess);

            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log.info("[skill-sandbox] done mode={} exit={} timedOut={} truncated={} duration={}ms",
                    mode, process.exitValue(), timedOut, stdoutTruncated[0] || stderrTruncated[0], durationMs);
            return new SandboxResult(
                    startedProcess.exitValue(),
                    stdout.toString(),
                    stderr.toString(),
                    timedOut,
                    stdoutTruncated[0] || stderrTruncated[0],
                    mode.name(),
                    durationMs);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (ownWorkDir) {
                deleteRecursively(workDir);
            }
        }
    }

    /** 构建子进程环境：仅白名单 + 沙箱内部约定变量，绝不继承宿主密钥 */
    Map<String, String> buildEnv(Path skillDir, List<Path> readRoots, Path workDir, Map<String, String> extraEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : envAllowlist) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        env.putIfAbsent("PYTHONIOENCODING", "utf-8");
        // 固定哈希种子，保证同一脚本每次执行行为可复现
        env.put("PYTHONHASHSEED", "0");
        // 临时目录指向工作目录，脚本用 tempfile 建的文件同样落在沙箱内
        env.put("TMP", workDir.toString());
        env.put("TEMP", workDir.toString());
        env.put("TMPDIR", workDir.toString());
        env.put("SKILL_SANDBOX_WORKDIR", workDir.toString());
        env.put("SKILL_SANDBOX_MEMORY_MB", String.valueOf(memoryMb));
        env.put("SKILL_SANDBOX_CPU_SECONDS", String.valueOf(cpuSeconds));
        if (mode == Mode.PROCESS || mode == Mode.OFF) {
            // uv 缓存固定到沙箱自有目录，避免依赖/污染宿主用户目录
            env.put("UV_CACHE_DIR", uvCacheDir);
            env.put("UV_NO_PROGRESS", "1");
        }
        List<Path> roots = new ArrayList<>();
        roots.add(skillDir);
        if (readRoots != null) {
            roots.addAll(readRoots);
        }
        // 只读根列表通过环境变量传给引导脚本：脚本能读这些目录，但不能写
        env.put("SKILL_SANDBOX_READ_ROOTS", roots.stream()
                .filter(p -> p != null)
                .map(p -> p.toAbsolutePath().normalize().toString())
                .distinct()
                .collect(Collectors.joining(File.pathSeparator)));
        if (extraEnv != null) {
            extraEnv.forEach((k, v) -> {
                if (k != null && !k.isBlank()) {
                    env.put(k, v);
                }
            });
        }
        return env;
    }

    private List<String> buildCommand(SandboxRequest request, Path script, Path skillDir, Path workDir) {
        List<String> cmd = new ArrayList<>();
        // docker 硬沙箱：一次性容器，网络、文件系统、内核权限全部收紧，跑完即毁（--rm）
        if (mode == Mode.DOCKER) {
            if (dockerImage == null || dockerImage.isBlank()) {
                throw new IllegalStateException("docker 模式需要配置 app.ai.skill.sandbox.docker-image");
            }
            if (!SkillPathGuard.isWithin(skillDir, script)) {
                throw new IllegalArgumentException("docker 模式要求脚本位于 skillDir 内: " + script);
            }
            String containerScript = "/skill/" + skillDir.relativize(script).toString().replace('\\', '/');
            cmd.add("docker");
            cmd.add("run");
            cmd.add("--rm");
            cmd.add("-i");
            cmd.add("--network");
            cmd.add("none");
            cmd.add("--read-only");
            cmd.add("--cap-drop");
            cmd.add("ALL");
            cmd.add("--security-opt");
            cmd.add("no-new-privileges");
            cmd.add("--pids-limit");
            cmd.add(String.valueOf(pidsLimit));
            cmd.add("--memory");
            cmd.add(memoryMb + "m");
            cmd.add("--cpus");
            cmd.add(String.valueOf(cpus));
            cmd.add("-v");
            cmd.add(skillDir + ":/skill:ro");
            cmd.add("-v");
            cmd.add(bootstrapFile + ":/sandbox/sandbox_bootstrap.py:ro");
            cmd.add("-v");
            cmd.add(workDir + ":/work");
            cmd.add("-w");
            cmd.add("/work");
            cmd.add("-e");
            cmd.add("SKILL_SANDBOX_WORKDIR=/work");
            cmd.add("-e");
            cmd.add("SKILL_SANDBOX_READ_ROOTS=/skill");
            cmd.add("-e");
            cmd.add("SKILL_SANDBOX_MEMORY_MB=" + memoryMb);
            cmd.add("-e");
            cmd.add("SKILL_SANDBOX_CPU_SECONDS=" + cpuSeconds);
            cmd.add("-e");
            cmd.add("PYTHONIOENCODING=utf-8");
            cmd.add("-e");
            cmd.add("PYTHONHASHSEED=0");
            cmd.add(dockerImage);
            cmd.add("python");
            cmd.add("-I");
            cmd.add("/sandbox/sandbox_bootstrap.py");
            cmd.add(containerScript);
        } else {
            // PROCESS / OFF：统一经 uv 解析 skill 项目依赖（保留阶段一执行方式），再注入引导脚本
            if (request.useUvProject) {
                cmd.add("uv");
                cmd.add("run");
                cmd.add("--quiet");
                cmd.add("--project");
                cmd.add(skillDir.toString());
                cmd.add("python");
            } else {
                cmd.add("python");
            }
            if (mode == Mode.PROCESS) {
                cmd.add("-I");
                cmd.add(bootstrapFile.toString());
            }
            cmd.add(script.toString());
        }
        if (request.args != null) {
            cmd.addAll(request.args);
        }
        return cmd;
    }

    private static Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return Mode.PROCESS;
        }
        try {
            return Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[skill-sandbox] 未知 sandbox.mode={}，回退 process", mode);
            return Mode.PROCESS;
        }
    }

    private static Path extractBootstrap(Path cacheRoot) {
        try {
            Path dir = cacheRoot.resolve("_sandbox");
            Files.createDirectories(dir);
            Path target = dir.resolve("sandbox_bootstrap.py");
            try (InputStream in = new ClassPathResource("sandbox/sandbox_bootstrap.py").getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("无法物化沙箱引导脚本 sandbox/sandbox_bootstrap.py", e);
        }
    }

    private static void writeStdin(Process process, byte[] stdin) {
        if (stdin == null) {
            return;
        }
        try (OutputStream out = process.getOutputStream()) {
            out.write(stdin);
        } catch (IOException ignored) {
            // 脚本未读取 stdin 属正常情况
        }
    }

    /** 读取流，超过 maxBytes 的部分丢弃但继续排空管道，避免子进程写阻塞 */
    private static void readBounded(InputStream input, int maxBytes, StringBuilder target, boolean[] truncated) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int total = 0;
            int n;
            while ((n = reader.read(buffer)) != -1) {
                int room = maxBytes - total;
                if (room > 0) {
                    int take = Math.min(n, room);
                    target.append(buffer, 0, take);
                    total += take;
                    if (take < n) {
                        truncated[0] = true;
                    }
                } else {
                    truncated[0] = true;
                }
            }
        } catch (IOException ignored) {
            // 进程被杀后流提前关闭属正常情况
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void destroyDescendants(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
    }

    private static void awaitTermination(Process process) {
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitCompletion(Process process, int timeoutSeconds) {
        try {
            return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 递归删除临时工作目录：脚本可能往 workdir 写入产物，执行完必须清理 */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 沙箱执行请求 */
    public static final class SandboxRequest {
        private final Path script;
        private final Path skillDir;
        private final List<String> args;
        private final List<Path> readRoots;
        private final Map<String, String> extraEnv;
        private final byte[] stdin;
        private final Path workDir;
        private final boolean useUvProject;

        private SandboxRequest(Builder builder) {
            this.script = builder.script;
            this.skillDir = builder.skillDir;
            this.args = builder.args == null ? List.of() : List.copyOf(builder.args);
            this.readRoots = builder.readRoots == null ? List.of() : List.copyOf(builder.readRoots);
            this.extraEnv = builder.extraEnv == null ? Map.of() : Map.copyOf(builder.extraEnv);
            this.stdin = builder.stdin;
            this.workDir = builder.workDir;
            this.useUvProject = builder.useUvProject;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private Path script;
            private Path skillDir;
            private List<String> args;
            private List<Path> readRoots;
            private Map<String, String> extraEnv;
            private byte[] stdin;
            private Path workDir;
            private boolean useUvProject;

            public Builder script(Path script) {
                this.script = script;
                return this;
            }

            public Builder skillDir(Path skillDir) {
                this.skillDir = skillDir;
                return this;
            }

            public Builder args(List<String> args) {
                this.args = args;
                return this;
            }

            public Builder readRoots(List<Path> readRoots) {
                this.readRoots = readRoots;
                return this;
            }

            public Builder extraEnv(Map<String, String> extraEnv) {
                this.extraEnv = extraEnv;
                return this;
            }

            public Builder stdin(byte[] stdin) {
                this.stdin = stdin;
                return this;
            }

            public Builder workDir(Path workDir) {
                this.workDir = workDir;
                return this;
            }

            public Builder useUvProject(boolean useUvProject) {
                this.useUvProject = useUvProject;
                return this;
            }

            public SandboxRequest build() {
                if (script == null) {
                    throw new IllegalStateException("script 必填");
                }
                return new SandboxRequest(this);
            }
        }
    }

    /** 沙箱执行结果 */
    public static final class SandboxResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean timedOut;
        private final boolean outputTruncated;
        private final String mode;
        private final long durationMs;

        SandboxResult(int exitCode, String stdout, String stderr, boolean timedOut,
                      boolean outputTruncated, String mode, long durationMs) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
            this.outputTruncated = outputTruncated;
            this.mode = mode;
            this.durationMs = durationMs;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public boolean isOutputTruncated() {
            return outputTruncated;
        }

        public String getMode() {
            return mode;
        }

        public long getDurationMs() {
            return durationMs;
        }

        /** 执行成功的定义：未超时且退出码为 0 */
        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }
}
