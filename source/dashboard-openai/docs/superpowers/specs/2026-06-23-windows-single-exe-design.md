# Windows 单文件一键启动 + 首启配置向导 — 设计文档

日期：2026-06-23

## 目标

把现有「美股崩盘概率监控台」做成 **Windows 单个 `.exe` 的傻瓜软件**：

- 用户双击 `美股崩盘概率.exe` → 本地起服务 → 自动打开浏览器。
- **首次进入显示「配置大模型」向导**（填 API Key / Base URL / 模型，可测试连接）→ 保存后进入仪表盘。
- 用户机器 **不需要安装 Node**。
- 配置与运行数据作为 **exe 同目录的外部文件**（`config.json` / `state.json`）——因为单个 exe 自身只读、存不了东西。

## 已确认决策

1. 形式：**单个可执行文件**（非 Electron，浏览器呈现）。
2. 目标系统：**仅 Windows x64**（`.exe`）。
3. 傻瓜式一键：双击即起、自动开浏览器、首启走配置向导。
4. 外部文件：`config.json`（用户输入）、`state.json`（运行产生）放 exe 同目录。

## 诚实约束

构建在 Linux 上进行（**交叉编译** Windows 二进制）。可验证：①Linux 上同一套服务端逻辑端到端跑通（含配置向导、用真 key 试连）②`npm run build:win` 真的产出 `.exe` 且体积正常。**不可验证**：在 Windows 上双击 `.exe` 的实跑体验（本机是 Linux）。构建 .exe 需联网下载 Windows 版 Node 基座，若构建环境无网，则由用户在自己机器上跑构建命令。

## 打包链路（关键）

项目是 ESM，pkg 对 ESM 支持不稳，故用两段式：

```
public/*  ──embed──▶  server/assets.generated.js   (前端内联成字符串)
server/index.js (ESM) ──esbuild bundle──▶  build/bundle.cjs (单个 CJS)
build/bundle.cjs ──@yao-pkg/pkg──▶  dist/美股崩盘概率.exe (node18-win-x64)
```

- **`scripts/embed-assets.mjs`**：读 `public/index.html|style.css|app.js`，生成 `server/assets.generated.js`，导出
  `export const ASSETS = { 'index.html': {type, body}, 'style.css': {...}, 'app.js': {...} }`。
  → 前端由**内存托管**，不依赖 pkg 的快照文件系统（绕开 `express.static` + pkg 的已知坑）。
- **esbuild**：`--bundle --platform=node --format=cjs --target=node18`，把服务端 + `@anthropic-ai/sdk` + `dotenv` 全打进一个 `build/bundle.cjs`。
- **@yao-pkg/pkg**（维护版 fork，支持新 Node）：`--targets node18-win-x64 --output dist/美股崩盘概率.exe`。
- `package.json` 脚本：
  - `embed`: `node scripts/embed-assets.mjs`（生成 `server/assets.generated.js`）
  - `start`: `npm run embed && node server/index.js`（开发态启动前先生成内联资源，避免缺文件）
  - `bundle`: `esbuild server/index.js --bundle --platform=node --format=cjs --target=node18 --outfile=build/bundle.cjs`
  - `build:win`: `npm run embed && npm run bundle && pkg build/bundle.cjs --targets node18-win-x64 --output dist/美股崩盘概率.exe`
  - devDependencies：`esbuild`、`@yao-pkg/pkg`。
- `server/assets.generated.js` 是生成物：gitignore，由 `embed`（start/build 前置）生成；源真相在 `public/`。
  `index.js` 通过静态 ESM `import { ASSETS } from './assets.generated.js'` 引用，故启动前必须已生成。

## 数据落点（`server/paths.js`）

```
dataDir():
  打包态(process.pkg 为真) → path.dirname(process.execPath)   // exe 同目录
  开发态                   → process.cwd()                     // 项目根（npm start / node --test 均在此）
configPath = join(dataDir(), 'config.json')
statePath  = join(dataDir(), 'state.json')
```

pkg 快照只读，所以读写都走 `dataDir()`（在快照之外，真实可写）。

## 配置：持久化 + 可重建（`server/config.js`）

在现有 `buildConfig(env)` 之上叠加文件层（老单测不变）：

- `loadConfig(dataDir)`：以 `buildConfig(process.env)` 为默认，再用 `config.json`（若存在）覆盖；返回 cfg。
- `saveConfig(dataDir, partial)`：读现有 → 合并 partial → 写 `config.json`（2 空格缩进）→ 返回新 cfg。
- `config.json` 字段：`apiKey` / `baseURL` / `model` / `baseline` / `collectIntervalMinutes`。
- `configured = !!cfg.apiKey`。

## 运行时可重建（`server/index.js`）

LLM 客户端、采集器、定时器收进一个可重建单元：

- `applyConfig(newCfg)`：`cfg = newCfg；llm = createLLM(cfg)；collector = createCollector({assess: llm.assess})；resetScheduler()`。
- `state` 独立于 config，启动即 `loadState(statePath, cfg.baseline)` 加载一次。
- 启动顺序：加载 cfg + state → `applyConfig(cfg)` → **先 listen 起服务**（即便未配置）→ 自动开浏览器。未配置时前端显示向导。
- 保存配置后：`saveConfig` → `applyConfig` → 若 `state.monitors` 全未采集则后台 `collectAll`。

## 接口变化

| 方法 | 路径 | 作用 |
|---|---|---|
| `GET` | `/api/health` | 增加 `configured` 字段：`{ ok, configured, hasApiKey, model, baseURL }` |
| `POST` | `/api/config` | body `{apiKey, baseURL, model}`：保存 → 重建 → 若空则首采 → 返回 `{ ok, ...health }` |
| `POST` | `/api/test` | body `{apiKey, baseURL, model}`：用这组临时配置发一条极小 `messages.create`（max_tokens 小、无工具）试连，返回 `{ ok }` 或 `{ ok:false, error }`，**不落库** |
| 其余 | `/api/state` `/api/refresh` `/api/monitors` `/api/chat` | 不变，但内部引用「当前」`llm`/`collector`/`cfg` |

静态：根路由及 `/style.css` `/app.js` 由内存 `ASSETS` 托管（带正确 Content-Type）。

## 启动辅助

- **自动开浏览器**：listen 成功后按平台 spawn（win32: `cmd /c start "" <url>`；linux: `xdg-open`；darwin: `open`），失败静默。
- **端口自增**：默认 3000，遇 `EADDRINUSE` 顺延（最多尝试若干次），最终 url 用实际端口。
- 控制台打印 url 与「已配置/未配置」。

## 前端：配置向导

- `index.html` 增加 `#setup` 覆盖层（向导）与把仪表盘包进 `#dashboard`。
- 向导字段：**API Key**（必填）/ **Base URL**（选填，placeholder 给 DeepSeek 示例 `https://api.deepseek.com/anthropic`）/ **模型名**（默认 `claude-opus-4-8`）。
- 按钮：**「测试连接」**（POST `/api/test`，显示成功/失败）、**「保存并进入」**（POST `/api/config`，成功后切到仪表盘并 `getState`）。
- 顶部加 **⚙ 设置**：重新打开向导改配置（预填当前值，Key 留空表示不改）。
- 初始化：`health()` → `configured ? 显示 #dashboard + getState() : 显示 #setup`。

## 错误处理

- 未配置时调用 `/api/refresh /api/monitors /api/chat` → 友好 400（前端引导去配置）。
- `/api/test` 把底层错误信息回给前端（区分鉴权失败 / 网络 / 模型名错）。
- 数据文件不存在 → 用预置项初始化（同现状）。
- 写 `config.json` 失败（极少见的只读目录）→ 返回错误提示用户换目录运行。

## 测试

- 单测：`paths`（dev/pkg 分支）、`loadConfig`/`saveConfig`（临时目录覆盖、合并、缺省）、`applyConfig` 重建（注入假 client，验证换 cfg 后 collector 用新 assess）、`embed-assets` 生成物结构。
- 我实跑验证（Linux）：起服务→`/api/health` 未配置→`/api/test`(真 DeepSeek key)→`/api/config` 落 `config.json`→`/api/refresh` 真采→`/api/state` 反映；以及 `npm run build:win` 产出 `.exe`。

## 明确不做（YAGNI）

- 不打 Linux/macOS 包（仅 Windows）。
- 不做 .env 兜底之外的多来源配置；不做配置加密（本地傻瓜工具，明文 `config.json` 可接受，已 gitignore）。
- 不做自动更新、不做安装程序（绿色单文件）。

## 受影响文件

```
新增: server/paths.js, server/assets.generated.js(生成物), scripts/embed-assets.mjs
改:   server/config.js(+loadConfig/saveConfig), server/index.js(applyConfig/内存静态/test/config/open/端口)
改:   public/index.html, public/app.js(向导 + ⚙)
改:   package.json(scripts + devDeps), .gitignore(config.json/state.json/build/dist/assets.generated.js)
新增: README 打包章节
```
