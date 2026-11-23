# 知识库入库的运行时依赖（PDF / Word）

> 结论先说：入库这条路真正干活的是 **Python**，不是 Java。Java 只负责调度、围栏、切块。所以部署时"jar 起来了、接口 200 了"不代表能上传文档——`uv` 在不在、skill 的 venv 里装了什么、模型权重有没有搬过去，三件事任意一件缺失，上传都会失败或悄悄降级。
>
> 数字口径截至 2026-11-23，均为本机实测。

## 1. 谁在干活

```
前端上传 ──► KnowledgeController.extract()
                ├─ .pdf  ──► PdfService.processPdf("pdf", "extract_text_docling.py")
                │              └─ 失败（任何原因）──► processPdf("pdf", "extract_text.py")
                └─ .docx ──► PdfService.processPdf("word", "extract_text_docx.py")
                                └─ 沙箱执行：uv run --project <skill-dir> python -I <bootstrap> <script>
```

`--project` 指向 `.skills-cache/<skill>/<版本号>/`，uv 以它为根去找 `pyproject.toml`、决定用哪个 venv、装哪些依赖。**venv 在 `.skills-cache` 里，而 `.skills-cache` 在 `.gitignore` 中**——这就是"代码没问题、机器上却跑不起来"的根源。

## 2. 四类输入各要什么

| 输入 | 抽取脚本 | 依赖 | 缺了会怎样 |
|---|---|---|---|
| 文本粘贴 | 无（直接走 `chunkText`） | 无 | — |
| `.docx` | `extract_text_docx.py` | `python-docx` | 上传失败，报 `ModuleNotFoundError: docx` |
| `.pdf`（结构化） | `extract_text_docling.py` | `docling-slim[standard]` + 模型权重 | **回落 pypdf**，表格拍平、层级丢失，界面标「降级」 |
| `.pdf`（降级） | `extract_text.py` | `pypdf` | 上传失败，报 `ModuleNotFoundError: pypdf` |

`python-docx` 与 `pypdf` 写在各自 skill 的 `pyproject.toml` 里，**首次执行时 uv 自动装**（需要联网，或预置 wheel 缓存）。

**Docling 刻意不写进 `dependencies`**：它是可选增强。写进去之后，离线机器的 `uv run --project` 会试图解算这份依赖、装不上就整体非 0 退出——连 pypdf 降级路径都跑不起来，PDF 上传从「质量降级」变成「完全不可用」。所以它装在同一个 venv 里，但必须由人显式执行一次安装。

## 3. Windows 原生部署

### 3.1 三样东西

| 东西 | 位置 | 大小 | 来源 |
|---|---|---|---|
| Python 依赖（pypdf / python-docx） | `.skills-cache/<skill>/<版本>/.venv` | 几 MB | 首次执行时 uv 自动装 |
| Docling 及其依赖（torch / rtree / opencv / rapidocr） | 同一个 `.venv` | 装完后整个 venv **1.2 GB** | 手动执行一次 |
| 版面与表格模型权重 | `HF_HOME`（默认 `%USERPROFILE%\.cache\huggingface`） | **510 MB** | 首次转换时联网下载，或从旧机搬 |

OCR 的三个 `.onnx`（det / rec / cls，共 30.9 MB）**在 pip 包里**，不走下载——`rapidocr` 整个包解压 62 MB。

注意最后一行：**权重不在 `~/.cache/docling`**。那个目录只是 docling 的运行期工作区，权重由 `huggingface_hub` 下到 `HF_HOME`。按前者去找部署产物必然找不到。

### 3.2 安装步骤

```bash
# 1) 先让服务把 skill 物化出来并自动装 pypdf（上传一次任意 PDF 即可，或直接手动 sync）
cd <部署目录>/.skills-cache/pdf/0.0.0 && uv sync

# 2) 装 docling（只装 format-pdf,models-local 会依次缺 rtree 与 opencv，必须用 standard）
uv pip install "docling-slim[standard]"

# 3) 预热权重（沙箱里联不了网，必须在沙箱外先跑一次）
uv run --quiet python scripts/extract_text_docling.py <任意一份 PDF>
#   走系统代理拉不下来时加镜像：HF_ENDPOINT=https://hf-mirror.com

# 4) 验证能完全离线跑
HF_HUB_OFFLINE=1 uv run --quiet python scripts/extract_text_docling.py <同一份 PDF>
```

第 3 步成功之后，`HF_HOME` 里会有 `docling-layout-heron`、`docling-models` 等目录，共约 510 MB。

### 3.3 配置

`application.yml` 三个键，前两个可以留空（留空 = 走默认）：

```yaml
app:
  ai:
    pdf:
      timeout-seconds: 300          # 超时下限，实际值会按页数上浮
      docling-artifacts-path: ${DOCLING_ARTIFACTS_PATH:}
      docling-offline: false        # 部署机无外网时置 true，让启动自检按「离线」口径告警
```

`docling-artifacts-path` 留空时用 `HF_HOME`（或 `~/.cache/huggingface`）。只有当权重被放到别处时才需要显式指定。

`docling-offline` 只影响启动告警的措辞（决定"没找到模型"该不该算硬错），不改变任何运行行为——名字容易让人以为它是个开关。真正让子进程离线的，是 `PdfService` 无条件注入的 `HF_HUB_OFFLINE=1`。

这两个键都是**跨进程配置**：Spring 读到之后，由 `PdfService` 翻译成子进程的 `HF_HOME` / `DOCLING_ARTIFACTS_PATH` / `HF_HUB_OFFLINE=1`。原因是沙箱会 `environment().clear()` 再按白名单回填，Java 进程内的属性不会自动出现在 Python 侧。

### 3.4 超时预算

| 页数 | 预算 |
|---|---|
| 1 页 | 45 秒（25 固定 + 20/页） |
| 10 页 | 225 秒 |
| 40 页 | 825 秒 |
| ≥44 页 | 封顶 900 秒，**注定超时** |

实测：1 页扫描件 46 秒、3 页 86 秒，边际成本约 20 秒/页，固定开销约 26 秒。**44 页以上的扫描件请拆分上传**——超时后不是报错，是回落 pypdf，表格与层级全丢。启动日志里会提前打一行 warn 提示这一点。

页数是扫字节流估出来的，不是真解析，所以**同时看两个信号取大**：页对象 `/Type /Page` 的个数，与页树上的 `/Count`。实测两份样本正好各缺一边：

| 样本 | `/Type /Page` | `/Count` | 估出 |
|---|---|---|---|
| reportlab 生成的单页（压缩） | **0** | 1 | 1 |
| img2pdf 生成的 3 页（未压缩） | 3 | 3 | 3 |

只认其中一个信号，第一份就会被判成"估不出"。

## 4. Docker 部署

容器里没有真正的 `uv`，只有 `deploy/docker/uv-shim.sh`：把 `uv run python ...` 直接转交给 `/opt/venv/bin/python`，**忽略 `--project`**。因此：

- skill 自己的 `pyproject.toml` 在容器里**不生效**
- 能用什么库，完全取决于 `deploy/docker/requirements-model.txt`

当前这份清单里 `python-docx` 有、`pypdf` **没有** ⇒ 容器内 Word 能上传、PDF 会 `ModuleNotFoundError`。已补上 `pypdf`，重建镜像后 PDF 至少能走降级路径。

**容器内不装 Docling**（torch + 模型权重都要进镜像，体积翻倍），所以容器部署下 PDF 一律走 pypdf 降级，界面会显示「降级」。要容器内也做结构化抽取，需要另外扩镜像并挂载 `HF_HOME` 卷——目前不做。

真要在容器里装，有一条好消息：`docling-slim[standard]` 拉的是 **`opencv-python-headless`**（本机实测 5.1.0.95），不带 GUI 依赖，Linux 上不会踩 `libGL.so.1: cannot open shared object file`。PDF 渲染走 `pypdfium2`（二进制在 wheel 里），也不需要装 poppler。

## 5. 离线交付产物清单

换机 / 交付时，除了 jar 和数据库，还要带这两样：

```bash
# ① 模型权重（约 510 MB）
robocopy "%USERPROFILE%\.cache\huggingface" "D:\deliver\huggingface" /E

# ② docling 的 venv 没法直接搬（路径写死在 Scripts/*.exe 里），到目标机重装一次
```

也就是说：**权重可以搬，venv 必须在新机器上重装**。清单里最容易被漏掉的是 ①——漏了之后症状是"上传 PDF 不报错，但界面上一直标降级"。

## 6. 自检

启动时 `DoclingStartupReport` 会打印模型目录、缓存文件数与降级策略。三种典型输出：

| 日志 | 含义 | 处置 |
|---|---|---|
| `[docling] 模型路径 : ...（存在）` + `本地缓存 : ...（N 个文件）`，N > 0 | 就绪 | 不用管 |
| `模型路径 : 未设置`，且 `本地缓存 : ...（0 个文件）` | 权重没搬过来 | 按 §3.2 预热，或搬 `HF_HOME` |
| `模型路径 : ...（不存在）` | 路径配错 | 改成真实目录，或清空改走默认 |

上传后看两处：接口返回的 `extractor` / `degraded`，以及界面上列表行尾的「降级」徽标。

排错对照：

| 现象 | 原因 |
|---|---|
| `ModuleNotFoundError: pypdf` | 该 skill 的 venv 没 sync（离线且无 wheel 缓存） |
| `blocked read outside sandbox roots: ...\huggingface\token` | 模型目录没进只读根 |
| `AttributeError: kernel32` | 沙箱的 `ctypes.dlopen` 策略把 numpy 的导入挡了 |
| `ProxyError: 502` | 子进程不知道权重在哪，去联网又被 socket 拦；检查 `HF_HOME` 是否传进子进程 |
| 一直 `status=degraded` 但日志无异常 | 权重目录存在但文件不全，`HF_HUB_OFFLINE=1` 下静默失败 |
