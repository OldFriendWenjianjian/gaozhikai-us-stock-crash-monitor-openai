# 美股崩盘概率 AI 监控台 — 设计文档

日期：2026-06-23

## 一句话目标

做一个 AI 页面：基于高志凯「明年年底会发生金融危机」的思路，让 AI **实时联网采集**信息，
在页面顶部给出 **美股崩盘概率：XX.XX%**，下面列出各项动态监控（某事件 → 概率 +X.XX%），
用户可与 AI agent 聊天讨论，也可以用大白话新增自己的动态监控项。

## 关键决策（已与用户确认）

1. **AI 采集方式**：真·实时。用 Claude Opus 4.8 的服务端联网搜索工具 `web_search`，用户提供 `ANTHROPIC_API_KEY`。
2. **技术栈**：前端单页（原生 HTML/CSS/JS）+ Node 轻后端（Express）。
3. **内容预置**：预置高志凯核心论点为初始监控项。
4. **概率模型**：基线 + 加权加减。
5. **采集频率**：手动刷新 + 每 30 分钟自动采集。
6. **存储**：`data/state.json` —— 最原始的 JSON 文件，2 空格缩进、字段顺序稳定，人可直接读懂。
7. **模型可配置**：`model`、`baseURL`、`apiKey` 三项全部进 `.env`，可随时切换。约束：端点必须是
   **Anthropic Messages API 协议**兼容（Claude 各型号、或 Anthropic 兼容网关）。换 Claude 型号完全可用；
   换到不支持服务端 `web_search` 的兼容端点时，联网采集自动降级（保留旧读数、提示该项无法联网），其余功能照常。

## 架构

```
浏览器 (public/: index.html + app.js + style.css)
        ↕  HTTP / SSE
Node 后端 (Express, server/)
        ↕  @anthropic-ai/sdk  →  可配置模型(默认 Claude Opus 4.8) + web_search_20260209
本地存储:  data/state.json (监控项 + 最新读数)
配置:      .env (ANTHROPIC_API_KEY / ANTHROPIC_BASE_URL / LLM_MODEL，不提交、不进前端)
```

设计原则：后端拆成职责单一的小模块，每个都能单独理解和测试。

- `server/store.js` —— 读写 `data/state.json`（加载、保存、增删监控项）。唯一碰文件的地方。
- `server/probability.js` —— 纯函数：由监控项数组算出每项 contribution 与总概率。无 IO、易测。
- `server/config.js` —— 从 `.env` 读 `apiKey`、`baseURL`(可选)、`model`(默认 `claude-opus-4-8`)、`port`、`baseline`、采集间隔。唯一碰环境变量的地方。
- `server/anthropic.js` —— 封装 LLM 调用：①采集评估（带 web_search）②新增监控的文本结构化 ③聊天流式。唯一碰 Anthropic SDK 的地方；client 用 `new Anthropic({ apiKey, baseURL })` 构造，`model` 来自 config，便于整体换模型/换端点。
- `server/collector.js` —— 编排采集：对一个/全部监控项调用 anthropic 评估、更新 store、重算概率。
- `server/index.js` —— Express 接口 + 30 分钟定时任务。
- `public/` —— 前端，纯静态，由后端托管。

## 数据模型

`data/state.json` 顶层结构：

```jsonc
{
  "baseline": 15,            // 基线概率（百分点）
  "crashProbability": 47.32, // 缓存的最新总概率，便于直接读
  "updatedAt": "2026-06-23T10:30:00.000Z",
  "monitors": [ /* 监控项数组，见下 */ ]
}
```

单个监控项：

```jsonc
{
  "id": "us-debt",                 // kebab-case 唯一标识
  "title": "美国国债与利息支出",
  "searchQuery": "要搜什么（自然语言，喂给 AI 联网搜）",
  "impactRule": "搜到的内容怎样影响美股崩盘概率（自然语言规则）",
  "weight": 20,                    // 该项最多贡献的百分点
  "direction": "increase",         // "increase" 推高 / "decrease" 压低
  "lastReading": {                 // 由采集自动填充；首次采集前为 null
    "summary": "AI 联网总结的发现",
    "severity": 0.65,              // 0~1，AI 评估的当前严重度
    "contribution": 13.0,         // = round(severity × weight × 方向符号, 2)
    "sources": [{ "title": "...", "url": "..." }],
    "updatedAt": "2026-06-23T10:30:00.000Z"
  }
}
```

字段顺序固定按上面写出（保存时不按 key 排序，保持可读叙事顺序）；JSON 以 2 空格缩进序列化。

## 概率模型（纯函数 `computeProbability`）

```
对每个有 lastReading 的监控项：
  sign = direction === "increase" ? +1 : -1
  contribution = round(severity × weight × sign, 2)
total = clamp(baseline + Σ contribution, 0, 100)
crashProbability = round(total, 2)
```

- 未采集（`lastReading === null`）的项 contribution 记 0，不计入。
- clamp 到 [0, 100]，显示保留两位小数。
- 直接对应「某事件 → 概率 +X.XX%」：每张卡片显示自己的 `contribution`。

## 后端接口

| 方法 | 路径 | 作用 |
|---|---|---|
| `GET` | `/api/state` | 返回完整 state（监控项+读数+总概率）。前端每 ~30s 轮询。 |
| `POST` | `/api/refresh` | body 可选 `{id}`：重采集指定项；无 id 则全部。返回新 state。 |
| `POST` | `/api/monitors` | body `{text}`：用户原始文本 → AI 结构化成监控项 → 落库 → 立即采集。 |
| `DELETE` | `/api/monitors/:id` | 删除监控项，重算概率。 |
| `POST` | `/api/chat` | body `{messages}`：SSE 流式返回 AI 回复，system 注入当前 state 摘要。 |
| `GET` | `/api/health` | 返回 `{ ok, hasApiKey, model, baseURL }`，供前端提示是否配置了 Key 并显示当前模型。 |

## LLM 调用细节（`server/anthropic.js`）

模型来自 config（默认 `claude-opus-4-8`，可在 `.env` 改 `LLM_MODEL`）；client 用 `apiKey` + 可选 `baseURL` 构造。
所有调用走 Anthropic Messages API 形状，因此换 Claude 型号或换 Anthropic 兼容端点都无需改业务代码。

### ① 采集评估（带联网搜索）
- `tools: [{ type: "web_search_20260209", name: "web_search" }]`
- system：说明在评估「美股崩盘风险」，要客观、给来源。
- user：拼入该项的 `searchQuery` + `impactRule`，要求联网搜索后，**最后输出一个 JSON 对象**：
  `{ "summary": string(中文), "severity": number(0~1), "sources": [{title,url}] }`。
- 解析：从最后一个 text block 用容错提取（找最后一个 `{...}` JSON 块）解析。
  - 不用 `output_config.format`：联网搜索会带 citations，而 citations 与结构化输出格式互斥；改用「提示里要 JSON + 容错解析」更稳。
- `output_config: { effort: "medium" }` 控成本；失败时 `lastReading` 保留旧值并记录一次错误（不崩）。

### ② 新增监控的结构化（不联网）
- 用 `client.messages.parse()` + JSON Schema（结构化输出），把用户大白话转成
  `{ id, title, searchQuery, impactRule, weight(5~30), direction }`。
- id 由 title 生成 kebab-case，重复则加序号。

### ③ 聊天（流式）
- `client.messages.stream(...)`，system 注入「当前总概率 + 各监控项标题/贡献/摘要」快照，
  让 AI 能针对真实数据讨论。可选开启 `web_search` 让它现查（默认开，便于讨论时事）。

## 采集编排（`server/collector.js`）

- `collectOne(id)`：取监控项 → 调用评估 → 写回 `lastReading` → 重算并保存概率。
- `collectAll()`：对所有监控项**串行**采集（避免并发打满速率/成本），逐个保存，便于前端轮询时渐进看到更新。
- 30 分钟定时任务调用 `collectAll()`；服务启动时若从未采集过则触发一次。

## 前端（`public/`）

- 顶部巨幅：**美股崩盘概率：XX.XX%**，颜色随概率绿→黄→红，带数字滚动/仪表动效。
- 监控卡片列表：标题、`+X.XX%` 贡献徽标、严重度条、AI 总结、来源链接、更新时间、单项「⟳ 刷新 / 🗑 删除」。
- 「➕ 新增动态监控」：一个 textarea，提示用户写：要搜什么 / 搜到什么算数 / 怎样影响美股；提交走 `POST /api/monitors`。
- 「⟳ 立即刷新全部」按钮 → `POST /api/refresh`。
- 聊天面板：消息列表 + 输入框，走 SSE 流式。
- 每 ~30s 轮询 `GET /api/state` 反映后台自动更新；采集中显示 loading 态。
- 启动时若 `/api/health` 报 `hasApiKey:false`，顶部横幅提示把 Key 写进 `.env`。

## 预置监控项（高志凯核心论点，AI 填实时读数）

| id | 标题 | weight | direction |
|---|---|---|---|
| us-debt | 美国国债与利息支出 | 20 | increase |
| fed-rates | 美联储利率与降息路径 | 15 | increase |
| de-dollarization | 去美元化与美元信用 | 15 | increase |
| ai-bubble | AI 科技股泡沫与估值集中度 | 20 | increase |
| cre-banks | 美国商业地产与区域银行风险 | 15 | increase |
| geopolitics | 地缘政治冲突（台海/中东/贸易战） | 15 | increase |

每项预置 `searchQuery` + `impactRule`，初始 `lastReading: null`，由首次采集填充。

## 配置与运行

`.env`（用户填，`.gitignore` 忽略），提供 `.env.example` 模板：

```ini
ANTHROPIC_API_KEY=sk-...        # 必填
ANTHROPIC_BASE_URL=             # 可选，留空用官方端点；填 Anthropic 兼容网关地址即可换端点
LLM_MODEL=claude-opus-4-8       # 可选，随时换型号
PORT=3000                       # 可选
BASELINE=15                     # 可选，基线概率
COLLECT_INTERVAL_MINUTES=30     # 可选，自动采集间隔
```

- `npm install`（express、@anthropic-ai/sdk、dotenv）。
- `npm start` → 启动后端并托管前端，浏览器打开 `http://localhost:3000`。
- 换模型/端点：改 `.env` 里的 `LLM_MODEL` / `ANTHROPIC_BASE_URL` / `ANTHROPIC_API_KEY`，重启即可。

## 错误处理

- 缺 Key：接口返回友好错误，前端横幅提示，不崩。
- 单项采集失败：保留旧读数、记录错误、其余项继续。
- AI 返回非法 JSON：容错解析失败则跳过该次更新并提示。
- `data/state.json` 不存在：首次启动用预置项初始化。

## 测试要点

- `probability.js` 纯函数：基线、加权、clamp、未采集项跳过 —— 单元测试覆盖。
- `store.js`：增删改查 + JSON 可读性（2 空格缩进、字段顺序）。
- 采集/聊天调用 Anthropic 的部分：用假 client 注入，验证 prompt 拼装与解析逻辑。

## 明确不做（YAGNI）

- 不做用户登录/多用户、不做真正的金融数据 API 接入（一切走 AI 联网搜索）。
- 不做数据库/ORM —— 就一个 JSON 文件。
- 不做历史曲线/回测（初版只展示当前快照）。
