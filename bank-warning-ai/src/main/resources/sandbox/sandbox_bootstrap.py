"""Skill 沙箱引导脚本（阶段二 · 执行隔离）。

用法:
    python -I sandbox_bootstrap.py <script> [args...]

职责:
    1. 资源限制（仅 POSIX）: 内存 RLIMIT_AS、CPU 时间 RLIMIT_CPU、文件描述符 RLIMIT_NOFILE
    2. 审计钩子: 拦截 socket.* / subprocess.* / os.system / ctypes.dlopen
    3. 文件系统围栏: 只允许读写 SKILL_SANDBOX_WORKDIR，
       只允许读 SKILL_SANDBOX_READ_ROOTS 与解释器自身 sys.path 目录
    4. 以 __main__ 语义加载目标脚本，保持 sys.argv 与直接执行一致

这是"软沙箱"（应用层护栏），不是安全边界；对抗恶意代码请配合 docker 硬沙箱。
"""
import os
import sys


def _norm(path: str) -> str:
    """转绝对路径并解析符号链接，保证后续比较的基准一致。"""
    return os.path.realpath(os.path.abspath(path))


def _within(path: str, roots: set) -> bool:
    """判断 path 是否位于 roots 中某个根目录之内（含根目录本身）。"""
    for root in roots:
        if path == root or path.startswith(root + os.sep):
            return True
    return False


def _is_write(mode: int, flags: int) -> bool:
    access = mode & 3
    if access != 0:
        return True
    return bool(flags & (getattr(os, "O_CREAT", 0) | getattr(os, "O_APPEND", 0) | getattr(os, "O_TRUNC", 0)))


def _open_is_write(args) -> bool:
    """判断一次 open 审计事件是否是写操作。

    CPython 在不同平台/版本上审计事件参数形态不一致：
    - 部分版本: (path, mode_int, flags_int)
    - Windows 3.14 等: (path, mode_str, flags_int)，mode_str 形如 'r'/'w'/'a'/'x'
    """
    if len(args) < 2:
        return False
    mode_arg = args[1]
    if isinstance(mode_arg, str):
        return any(c in mode_arg for c in "wax+")
    try:
        mode = int(mode_arg)
        flags = int(args[2]) if len(args) > 2 else 0
        return _is_write(mode, flags)
    except (TypeError, ValueError):
        return False


def _dll_allowed(name: str) -> bool:
    """判断一次 ctypes.dlopen 是否放行。

    以前这里是「一律拒绝」，理由是禁止脚本加载任意原生库。但 torch / numpy /
    opencv 这类科学计算库在**导入期**就会 dlopen 原生扩展——numpy 甚至在
    `import ctypes` 时就要解析 kernel32，一律拒绝等于禁止所有带原生扩展的依赖
    （实测表现是一句看不出因果的 `AttributeError: kernel32`）。

    改成按来源分档：
      - 裸名（不含路径分隔符，如 kernel32）→ 走系统目录查找，放行；
        但工作目录里已有同名文件时拒绝——Windows 的 DLL 搜索顺序里当前目录
        排在系统目录之前，而脚本对工作目录有写权限，放行裸名等于给它留了
        一条「自己写个 kernel32.dll 再按裸名加载」的绕行路；
      - 绝对路径 → 必须落在解释器前缀（venv / stdlib）或系统目录内；
      - 其余 → 拒绝。
    """
    if not name:
        return False
    if os.sep not in name and "/" not in name:
        try:
            if os.path.exists(os.path.join(os.getcwd(), name)):
                return False
        except OSError:
            # 取不到工作目录时按不安全处理：宁可让脚本失败，也不放行来源不明的库
            return False
        return True
    path = _norm(name)
    allowed = {_norm(sys.prefix), _norm(sys.base_prefix)}
    for env in ("SYSTEMROOT", "WINDIR"):
        value = os.environ.get(env)
        if value:
            allowed.add(_norm(value))
    return _within(path, allowed)


def main() -> None:
    if len(sys.argv) < 2:
        print("usage: sandbox_bootstrap.py <script> [args...]", file=sys.stderr)
        sys.exit(2)

    script = _norm(sys.argv[1])
    sys.argv = [script] + sys.argv[2:]
    script_dir = os.path.dirname(script)

    # 1. 资源限制（Windows 下依赖 Job Object / 容器，进程级软沙箱降级为超时 + 输出上限）
    # 资源预算由 Java 执行器通过环境变量传入，对应配置里的 memory-mb 与 cpu-seconds
    memory_mb = int(os.environ.get("SKILL_SANDBOX_MEMORY_MB", "512"))
    cpu_seconds = int(os.environ.get("SKILL_SANDBOX_CPU_SECONDS", "30"))
    # 仅类 Unix 系统支持 resource.setrlimit；Windows 上 os.name == 'nt'，整个块直接跳过
    if os.name != "nt":
        try:
            import resource
            resource.setrlimit(resource.RLIMIT_AS, (memory_mb * 1024 * 1024,) * 2)
            resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds,) * 2)
            resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
        except (ImportError, ValueError, OSError):
            pass

    # 2. 文件系统围栏
    workdir = _norm(os.environ.get("SKILL_SANDBOX_WORKDIR", os.getcwd()))
    rw_roots = {workdir}
    ro_roots = {script_dir}
    # 解释器自身的 sys.path（stdlib / venv site-packages）允许读取，
    # 否则脚本连 import 标准库都会被自己的审计钩子拦掉
    for entry in sys.path:
        if entry:
            ro_roots.add(_norm(entry))
    for root in os.environ.get("SKILL_SANDBOX_READ_ROOTS", "").split(os.pathsep):
        if root:
            ro_roots.add(_norm(root))

    def audit(event, args):
        # 危险通道直接拒绝：联网 / 起子进程 / 系统命令
        if event.startswith(("socket.", "subprocess.")) or event == "os.system":
            raise PermissionError("blocked by skill sandbox: " + event)
        # 原生库加载按来源分档（科学计算库导入期就要 dlopen，见 _dll_allowed）
        if event == "ctypes.dlopen":
            name = str(args[0]) if args else ""
            if not _dll_allowed(name):
                raise PermissionError("blocked ctypes.dlopen outside venv/system: " + name)
        if event == "open" and args:
            # 空设备是进程级设施而不是数据文件：dill/pickle 在导入期就会 open(os.devnull)
            # 探测文件类型，拦掉它会让一切用到 dill 的库（torch 走这条）在 import 阶段就死。
            if str(args[0]) in (os.devnull, "nul", "/dev/null"):
                return
            path = _norm(str(args[0]))
            if _open_is_write(args):
                if not _within(path, rw_roots):
                    raise PermissionError("blocked write outside workdir: " + path)
            else:
                if not _within(path, rw_roots) and not _within(path, ro_roots):
                    raise PermissionError("blocked read outside sandbox roots: " + path)

    # 注册审计钩子：此后脚本的每次安全敏感操作（打开文件、建 socket、起子进程等）
    # 都会先经过 audit()，钩子抛异常即中断该操作
    sys.addaudithook(audit)

    # 3. 执行目标脚本（-I 隔离模式下脚本目录不在 sys.path，需手动注入）
    sys.path.insert(0, script_dir)

    import runpy
    runpy.run_path(script, run_name="__main__")


if __name__ == "__main__":
    main()
