# 美股崩盘概率 AI 监控台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做一个 AI 页面，基于高志凯「明年年底金融危机」思路，让 Claude 实时联网采集信息，顶部展示「美股崩盘概率 XX.XX%」，下列各动态监控项（事件→概率±X.XX%），可与 AI 聊天，可用大白话新增监控项。

**Architecture:** Node(Express) 轻后端 + 原生前端单页。后端用 `@anthropic-ai/sdk` 调用可配置模型（默认 Claude Opus 4.8）+ 服务端 `web_search` 工具做联网采集；状态存一个可读的 `data/state.json`。后端拆成职责单一的小模块：config / presets / probability(纯函数) / store(唯一碰文件) / anthropic(唯一碰 SDK) / collector(编排) / index(接口+定时)。

**Tech Stack:** Node 18 (ESM, `"type":"module"`)、Express、@anthropic-ai/sdk、dotenv；测试用 Node 内置 `node:test` + `node:assert`（`node --test`），零额外测试依赖。

## Global Constraints

- 模块系统：ESM（`package.json` 含 `"type": "module"`），所有 import 用 `.js` 后缀。
- 模型可配置：`apiKey`/`baseURL`/`model` 全部来自 `.env`，默认 model `claude-opus-4-8`。端点须 Anthropic Messages API 协议兼容。
- 联网采集工具固定为 `{ type: "web_search_20260209", name: "web_search" }`。
- 存储：唯一文件 `data/state.json`，`JSON.stringify(state, null, 2)` + 末尾换行；字段顺序保持代码里写出的顺序，不按 key 排序。
- 概率公式：`总概率 = round2(clamp(baseline + Σ(severity×weight×sign), 0, 100))`，`sign = direction==='decrease' ? -1 : 1`；未采集项贡献 0。保留两位小数。
- 优雅降级：单项采集失败保留旧读数、写 `error` 字段、不抛、不影响其余项；缺 key 时受影响接口返回友好 400。
- 不提交密钥：`.env` 与 `data/state.json` 进 `.gitignore`。
- 前端纯静态，由后端 `express.static('public')` 托管；前端不直接接触 key。
- 注释/文案与现有风格一致；中文为主。

---

## File Structure

```
package.json            # ESM, scripts, deps
.gitignore
.env.example
data/                   # 运行时生成 state.json（gitignored）
server/
  config.js             # buildConfig(env) + config
  presets.js            # presets（6 个高志凯预置监控项）
  probability.js        # 纯函数：round2/clamp/contributionOf/computeProbability
  store.js              # 文件读写 + recompute/addMonitor/removeMonitor
  anthropic.js          # 纯helpers + createLLM 工厂（assess/structure/chatStream）
  collector.js          # createCollector：collectOne/collectAll
  index.js              # Express 接口 + 30 分钟定时
test/
  config.test.js
  probability.test.js
  store.test.js
  anthropic.test.js
  collector.test.js
public/
  index.html
  style.css
  app.js
README.md
```

---

### Task 1: 项目脚手架 + config

**Files:**
- Create: `package.json`, `.gitignore`, `.env.example`, `server/config.js`, `test/config.test.js`

**Interfaces:**
- Produces: `buildConfig(env)` → `{ apiKey:string, baseURL:string|undefined, model:string, port:number, baseline:number, collectIntervalMinutes:number }`；以及 `config`（= `buildConfig(process.env)`）。

- [ ] **Step 1: 写 `package.json`**

```json
{
  "name": "usa-market-crash-monitor",
  "version": "1.0.0",
  "type": "module",
  "private": true,
  "scripts": {
    "start": "node server/index.js",
    "test": "node --test"
  },
  "dependencies": {
    "@anthropic-ai/sdk": "latest",
    "dotenv": "latest",
    "express": "latest"
  }
}
```

- [ ] **Step 2: 写 `.gitignore`**

```gitignore
node_modules/
.env
data/state.json
```

- [ ] **Step 3: 写 `.env.example`**

```ini
# 必填：Anthropic API Key（或 Anthropic 兼容端点的 key）
ANTHROPIC_API_KEY=sk-...
# 可选：留空用官方端点；填 Anthropic 兼容网关地址即可换端点
ANTHROPIC_BASE_URL=
# 可选：随时换型号（默认 claude-opus-4-8）
LLM_MODEL=claude-opus-4-8
# 可选
PORT=3000
BASELINE=15
COLLECT_INTERVAL_MINUTES=30
```

- [ ] **Step 4: 写失败测试 `test/config.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildConfig } from '../server/config.js'

test('buildConfig 用默认值', () => {
  const c = buildConfig({})
  assert.equal(c.model, 'claude-opus-4-8')
  assert.equal(c.port, 3000)
  assert.equal(c.baseline, 15)
  assert.equal(c.collectIntervalMinutes, 30)
  assert.equal(c.apiKey, '')
  assert.equal(c.baseURL, undefined)
})

test('buildConfig 读取并解析 env', () => {
  const c = buildConfig({
    ANTHROPIC_API_KEY: 'k', ANTHROPIC_BASE_URL: 'https://x', LLM_MODEL: 'claude-sonnet-4-6',
    PORT: '8080', BASELINE: '20', COLLECT_INTERVAL_MINUTES: '5',
  })
  assert.equal(c.apiKey, 'k')
  assert.equal(c.baseURL, 'https://x')
  assert.equal(c.model, 'claude-sonnet-4-6')
  assert.equal(c.port, 8080)
  assert.equal(c.baseline, 20)
  assert.equal(c.collectIntervalMinutes, 5)
})

test('buildConfig 空字符串视为未设', () => {
  const c = buildConfig({ ANTHROPIC_BASE_URL: '', BASELINE: '' })
  assert.equal(c.baseURL, undefined)
  assert.equal(c.baseline, 15)
})
```

- [ ] **Step 5: 运行测试，确认失败**

Run: `node --test test/config.test.js`
Expected: FAIL（`Cannot find module '../server/config.js'`）

- [ ] **Step 6: 实现 `server/config.js`**

```js
import 'dotenv/config'

function numEnv(env, name, dflt) {
  const v = env[name]
  if (v === undefined || v === '') return dflt
  const n = Number(v)
  return Number.isFinite(n) ? n : dflt
}

export function buildConfig(env) {
  const baseURL = env.ANTHROPIC_BASE_URL
  return {
    apiKey: env.ANTHROPIC_API_KEY || '',
    baseURL: baseURL && baseURL.trim() !== '' ? baseURL.trim() : undefined,
    model: env.LLM_MODEL || 'claude-opus-4-8',
    port: numEnv(env, 'PORT', 3000),
    baseline: numEnv(env, 'BASELINE', 15),
    collectIntervalMinutes: numEnv(env, 'COLLECT_INTERVAL_MINUTES', 30),
  }
}

export const config = buildConfig(process.env)
```

- [ ] **Step 7: 安装依赖**

Run: `npm install`
Expected: 生成 `node_modules/` 与 `package-lock.json`，无报错。

- [ ] **Step 8: 运行测试，确认通过**

Run: `node --test test/config.test.js`
Expected: PASS（3 tests）

- [ ] **Step 9: 提交**

```bash
git init -q 2>/dev/null; git add package.json package-lock.json .gitignore .env.example server/config.js test/config.test.js
git commit -m "chore: scaffold project + configurable config module"
```

---

### Task 2: 概率纯函数 `probability.js`

**Files:**
- Create: `server/probability.js`, `test/probability.test.js`

**Interfaces:**
- Produces:
  - `round2(n:number) → number`（四舍五入两位小数）
  - `clamp(n, lo, hi) → number`
  - `contributionOf(monitor) → number`（无 lastReading 或 severity 非数字时返回 0）
  - `computeProbability(baseline:number, monitors:Array) → number`
- Consumes（monitor 形状，约定）：`{ weight:number, direction:'increase'|'decrease', lastReading: { severity:number }|null }`

- [ ] **Step 1: 写失败测试 `test/probability.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { round2, clamp, contributionOf, computeProbability } from '../server/probability.js'

test('round2 保留两位', () => {
  assert.equal(round2(13.005), 13.01)
  assert.equal(round2(1 / 3), 0.33)
})

test('clamp 夹取', () => {
  assert.equal(clamp(150, 0, 100), 100)
  assert.equal(clamp(-5, 0, 100), 0)
  assert.equal(clamp(42, 0, 100), 42)
})

test('contributionOf 未采集返回 0', () => {
  assert.equal(contributionOf({ weight: 20, direction: 'increase', lastReading: null }), 0)
  assert.equal(contributionOf({ weight: 20, direction: 'increase', lastReading: { severity: null } }), 0)
})

test('contributionOf increase 为正、decrease 为负', () => {
  assert.equal(contributionOf({ weight: 20, direction: 'increase', lastReading: { severity: 0.65 } }), 13)
  assert.equal(contributionOf({ weight: 20, direction: 'decrease', lastReading: { severity: 0.5 } }), -10)
})

test('computeProbability = baseline + Σ贡献，clamp 到 0~100', () => {
  const monitors = [
    { weight: 20, direction: 'increase', lastReading: { severity: 0.65 } }, // +13
    { weight: 15, direction: 'increase', lastReading: { severity: 0.4 } },  // +6
    { weight: 10, direction: 'decrease', lastReading: { severity: 0.5 } },  // -5
  ]
  assert.equal(computeProbability(15, monitors), 29) // 15+13+6-5
})

test('computeProbability clamp 上限 100', () => {
  const monitors = [{ weight: 30, direction: 'increase', lastReading: { severity: 1 } }]
  assert.equal(computeProbability(95, monitors), 100)
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `node --test test/probability.test.js`
Expected: FAIL（找不到模块）

- [ ] **Step 3: 实现 `server/probability.js`**

```js
export function round2(n) {
  return Math.round((n + Number.EPSILON) * 100) / 100
}

export function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, n))
}

export function contributionOf(monitor) {
  const r = monitor && monitor.lastReading
  if (!r || typeof r.severity !== 'number') return 0
  const sign = monitor.direction === 'decrease' ? -1 : 1
  return round2(r.severity * monitor.weight * sign)
}

export function computeProbability(baseline, monitors) {
  const sum = monitors.reduce((acc, m) => acc + contributionOf(m), 0)
  return round2(clamp(baseline + sum, 0, 100))
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `node --test test/probability.test.js`
Expected: PASS（6 tests）

- [ ] **Step 5: 提交**

```bash
git add server/probability.js test/probability.test.js
git commit -m "feat: probability pure functions (baseline + weighted deltas)"
```

---

### Task 3: 预置项 + 存储 `presets.js` / `store.js`

**Files:**
- Create: `server/presets.js`, `server/store.js`, `test/store.test.js`

**Interfaces:**
- Produces:
  - `presets`（数组，6 项，每项 `{id,title,searchQuery,impactRule,weight,direction,lastReading:null}`）
  - `createInitialState(baseline) → state`，state = `{ baseline, crashProbability, updatedAt, monitors }`
  - `async loadState(filePath, baseline) → state`（文件不存在则用预置初始化并落盘）
  - `async saveState(filePath, state) → void`（2 空格缩进 + 末尾换行）
  - `recompute(state) → state`（重算每项 contribution 与 crashProbability、刷新 updatedAt）
  - `addMonitor(state, monitor) → state`、`removeMonitor(state, id) → state`
- Consumes: `computeProbability`, `contributionOf`（来自 `probability.js`）

- [ ] **Step 1: 实现 `server/presets.js`**

```js
// 高志凯核心论点预置监控项（AI 填实时读数）。lastReading 首次采集前为 null。
export const presets = [
  {
    id: 'us-debt',
    title: '美国国债与利息支出',
    searchQuery: '美国国债总额最新数据、年度利息支出、利息占财政收入比例、是否超过国防开支',
    impactRule: '国债规模与利息支出越高、占财政比例越大、临近债务上限或评级下调风险越高，则美股系统性风险越高。',
    weight: 20,
    direction: 'increase',
    lastReading: null,
  },
  {
    id: 'fed-rates',
    title: '美联储利率与降息路径',
    searchQuery: '美联储最新联邦基金利率、最近一次议息会议表态、市场对降息/加息预期、点阵图',
    impactRule: '利率维持高位、降息预期落空或重新加息，会抬高融资成本、压制估值，推高崩盘概率；明确降息则降低。',
    weight: 15,
    direction: 'increase',
    lastReading: null,
  },
  {
    id: 'de-dollarization',
    title: '去美元化与美元信用',
    searchQuery: '去美元化最新进展、金砖国家本币结算、各国美元储备占比变化、美元指数走势',
    impactRule: '去美元化加速、美元储备地位下降、美元信用受质疑，会削弱美股长期资金基础，推高风险。',
    weight: 15,
    direction: 'increase',
    lastReading: null,
  },
  {
    id: 'ai-bubble',
    title: 'AI 科技股泡沫与估值集中度',
    searchQuery: '美股 Magnificent 7 估值、标普500 席勒市盈率 CAPE、AI 板块估值是否泡沫、市值集中度',
    impactRule: '估值越高、市值越集中于少数 AI 科技股、泡沫迹象越明显，一旦回调引发系统性下跌的概率越高。',
    weight: 20,
    direction: 'increase',
    lastReading: null,
  },
  {
    id: 'cre-banks',
    title: '美国商业地产与区域银行风险',
    searchQuery: '美国商业地产空置率与违约、区域银行存款与坏账、银行业流动性压力最新情况',
    impactRule: '商业地产违约上升、区域银行暴露风险、流动性紧张，可能引发信用事件并传导至股市。',
    weight: 15,
    direction: 'increase',
    lastReading: null,
  },
  {
    id: 'geopolitics',
    title: '地缘政治冲突（台海/中东/贸易战）',
    searchQuery: '台海局势、中东冲突、中美贸易战与关税最新进展，对市场风险偏好的影响',
    impactRule: '地缘冲突升级、关税与贸易摩擦加剧，会打击风险偏好、扰乱供应链，推高崩盘概率。',
    weight: 15,
    direction: 'increase',
    lastReading: null,
  },
]
```

- [ ] **Step 2: 写失败测试 `test/store.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  createInitialState, loadState, saveState, recompute, addMonitor, removeMonitor,
} from '../server/store.js'

async function tempFile() {
  const dir = await mkdtemp(join(tmpdir(), 'crash-'))
  return { file: join(dir, 'state.json'), dir }
}

test('createInitialState 含预置项', () => {
  const s = createInitialState(15)
  assert.equal(s.baseline, 15)
  assert.equal(s.crashProbability, 15)
  assert.ok(Array.isArray(s.monitors))
  assert.equal(s.monitors.length, 6)
  assert.equal(s.monitors[0].lastReading, null)
})

test('loadState 文件不存在则初始化并落盘', async () => {
  const { file, dir } = await tempFile()
  const s = await loadState(file, 15)
  assert.equal(s.monitors.length, 6)
  const raw = await readFile(file, 'utf8')
  assert.ok(raw.includes('"baseline": 15'))
  await rm(dir, { recursive: true, force: true })
})

test('saveState 2 空格缩进 + 末尾换行', async () => {
  const { file, dir } = await tempFile()
  await saveState(file, createInitialState(15))
  const raw = await readFile(file, 'utf8')
  assert.ok(raw.endsWith('}\n'))
  assert.ok(raw.includes('\n  "baseline"')) // 2 空格缩进
  await rm(dir, { recursive: true, force: true })
})

test('recompute 重算 contribution 与 crashProbability', () => {
  const s = createInitialState(15)
  s.monitors[0].lastReading = { summary: 'x', severity: 0.5, contribution: 0, sources: [], updatedAt: 't' }
  recompute(s)
  assert.equal(s.monitors[0].lastReading.contribution, 10) // 0.5*20
  assert.equal(s.crashProbability, 25) // 15+10
})

test('addMonitor / removeMonitor', () => {
  const s = createInitialState(15)
  addMonitor(s, { id: 'x', title: 'X', searchQuery: '', impactRule: '', weight: 10, direction: 'increase', lastReading: null })
  assert.equal(s.monitors.length, 7)
  removeMonitor(s, 'x')
  assert.equal(s.monitors.length, 6)
  assert.ok(!s.monitors.some((m) => m.id === 'x'))
})
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `node --test test/store.test.js`
Expected: FAIL（找不到 `../server/store.js`）

- [ ] **Step 4: 实现 `server/store.js`**

```js
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { dirname } from 'node:path'
import { presets } from './presets.js'
import { computeProbability, contributionOf } from './probability.js'

export function createInitialState(baseline) {
  return {
    baseline,
    crashProbability: baseline,
    updatedAt: new Date().toISOString(),
    monitors: presets.map((m) => ({ ...m, lastReading: null })),
  }
}

export function recompute(state) {
  for (const m of state.monitors) {
    if (m.lastReading) m.lastReading.contribution = contributionOf(m)
  }
  state.crashProbability = computeProbability(state.baseline, state.monitors)
  state.updatedAt = new Date().toISOString()
  return state
}

export function addMonitor(state, monitor) {
  state.monitors.push(monitor)
  recompute(state)
  return state
}

export function removeMonitor(state, id) {
  state.monitors = state.monitors.filter((m) => m.id !== id)
  recompute(state)
  return state
}

export async function saveState(filePath, state) {
  await mkdir(dirname(filePath), { recursive: true })
  await writeFile(filePath, JSON.stringify(state, null, 2) + '\n', 'utf8')
}

export async function loadState(filePath, baseline) {
  try {
    const raw = await readFile(filePath, 'utf8')
    return JSON.parse(raw)
  } catch (err) {
    if (err.code === 'ENOENT') {
      const state = createInitialState(baseline)
      await saveState(filePath, state)
      return state
    }
    throw err
  }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `node --test test/store.test.js`
Expected: PASS（5 tests）

- [ ] **Step 6: 提交**

```bash
git add server/presets.js server/store.js test/store.test.js
git commit -m "feat: preset monitors + readable JSON store"
```

---

### Task 4: LLM 封装 `anthropic.js`（纯 helpers + createLLM）

**Files:**
- Create: `server/anthropic.js`, `test/anthropic.test.js`

**Interfaces:**
- Produces 纯函数（可单测，无网络）：
  - `slugify(s) → string`
  - `ensureUniqueId(base, existingIds:string[]) → string`
  - `extractJson(text) → object|null`（优先取最后一个 ```json 围栏；回退首{到末}）
  - `textOf(message) → string`（拼接 content 里所有 text block）
  - `clampSeverity(n) → number`（夹到 0~1，非数字→0）
  - `normalizeSources(arr) → Array<{title,url}>`
  - `normalizeMonitor(json, existingIds) → monitor`（含校验：weight 夹 5~30、direction 合法、id 唯一、lastReading:null）
  - `summarizeState(state) → string`（system 注入用的状态快照）
  - `buildAssessPrompt(monitor) → string`、`buildStructurePrompt(text) → string`
- Produces 工厂：`createLLM(config, ClientClass=Anthropic) → { assess(monitor), structure(text, existingIds), chatStream(messages, summary, onText) }`
  - `assess(monitor)` → `{ summary, severity(0~1), sources }`，内部开 `web_search`，处理 `pause_turn`
  - `structure(text, existingIds)` → monitor（经 normalizeMonitor）
  - `chatStream(messages, summary, onText)` → 流式，逐段回调 `onText(delta)`；不开联网，只讨论现有数据
- Consumes: `clamp`（来自 `probability.js`）

- [ ] **Step 1: 写失败测试 `test/anthropic.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  slugify, ensureUniqueId, extractJson, textOf, clampSeverity,
  normalizeSources, normalizeMonitor, summarizeState, createLLM,
} from '../server/anthropic.js'

test('slugify 转 kebab；非 ascii 回退 monitor', () => {
  assert.equal(slugify('US Debt Risk!'), 'us-debt-risk')
  assert.equal(slugify('中文标题'), 'monitor')
})

test('ensureUniqueId 去重加序号', () => {
  assert.equal(ensureUniqueId('us-debt', []), 'us-debt')
  assert.equal(ensureUniqueId('us-debt', ['us-debt']), 'us-debt-2')
  assert.equal(ensureUniqueId('us-debt', ['us-debt', 'us-debt-2']), 'us-debt-3')
})

test('extractJson 取围栏块', () => {
  const t = '前言\n```json\n{"severity":0.5,"summary":"hi"}\n```\n后语'
  assert.deepEqual(extractJson(t), { severity: 0.5, summary: 'hi' })
})

test('extractJson 回退首{到末}', () => {
  assert.deepEqual(extractJson('noise {"a":1} tail'), { a: 1 })
})

test('extractJson 无 json 返回 null', () => {
  assert.equal(extractJson('没有 json'), null)
})

test('textOf 拼接 text block', () => {
  const msg = { content: [{ type: 'text', text: 'a' }, { type: 'web_search_tool_result' }, { type: 'text', text: 'b' }] }
  assert.equal(textOf(msg), 'ab')
})

test('clampSeverity 夹 0~1', () => {
  assert.equal(clampSeverity(1.5), 1)
  assert.equal(clampSeverity(-1), 0)
  assert.equal(clampSeverity('x'), 0)
  assert.equal(clampSeverity(0.4), 0.4)
})

test('normalizeSources 规整', () => {
  assert.deepEqual(normalizeSources([{ title: 'T', url: 'U' }, { url: 'V' }, 'bad']),
    [{ title: 'T', url: 'U' }, { title: 'V', url: 'V' }])
})

test('normalizeMonitor 校验并补默认', () => {
  const m = normalizeMonitor({ id: 'My Risk', title: 'My Risk', searchQuery: 'q', impactRule: 'r', weight: 999, direction: 'down' }, [])
  assert.equal(m.id, 'my-risk')
  assert.equal(m.weight, 30) // 夹到 30
  assert.equal(m.direction, 'increase') // 非法值回退 increase
  assert.equal(m.lastReading, null)
})

test('summarizeState 含概率与各项', () => {
  const state = {
    baseline: 15, crashProbability: 28,
    monitors: [{ title: '债务', lastReading: { contribution: 13, summary: '高' } }, { title: '空项', lastReading: null }],
  }
  const s = summarizeState(state)
  assert.ok(s.includes('28%'))
  assert.ok(s.includes('债务'))
  assert.ok(s.includes('尚未采集'))
})

test('createLLM.assess 用假 client 解析 severity', async () => {
  const fakeClient = {
    messages: {
      create: async () => ({
        stop_reason: 'end_turn',
        content: [{ type: 'text', text: '```json\n{"summary":"风险偏高","severity":0.7,"sources":[{"title":"X","url":"http://x"}]}\n```' }],
      }),
    },
  }
  class FakeClass { constructor() { return fakeClient } }
  const llm = createLLM({ apiKey: 'k', model: 'm' }, FakeClass)
  const r = await llm.assess({ id: 'a', title: 'A', searchQuery: 'q', impactRule: 'r', weight: 20, direction: 'increase' })
  assert.equal(r.severity, 0.7)
  assert.equal(r.summary, '风险偏高')
  assert.deepEqual(r.sources, [{ title: 'X', url: 'http://x' }])
})

test('createLLM.assess 解析失败抛错', async () => {
  const fakeClient = { messages: { create: async () => ({ stop_reason: 'end_turn', content: [{ type: 'text', text: '无结构化输出' }] }) } }
  class FakeClass { constructor() { return fakeClient } }
  const llm = createLLM({ apiKey: 'k', model: 'm' }, FakeClass)
  await assert.rejects(() => llm.assess({ id: 'a', title: 'A', weight: 20, direction: 'increase' }))
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `node --test test/anthropic.test.js`
Expected: FAIL（找不到 `../server/anthropic.js`）

- [ ] **Step 3: 实现 `server/anthropic.js`**

```js
import Anthropic from '@anthropic-ai/sdk'
import { clamp } from './probability.js'

const WEB_SEARCH_TOOL = { type: 'web_search_20260209', name: 'web_search' }

// ---------- 纯 helpers ----------
export function slugify(s) {
  const out = String(s).toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return out || 'monitor'
}

export function ensureUniqueId(base, existingIds) {
  const slug = slugify(base)
  if (!existingIds.includes(slug)) return slug
  let i = 2
  while (existingIds.includes(`${slug}-${i}`)) i++
  return `${slug}-${i}`
}

export function extractJson(text) {
  if (typeof text !== 'string') return null
  const candidates = []
  const fences = [...text.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)]
  if (fences.length) candidates.push(fences[fences.length - 1][1])
  const first = text.indexOf('{')
  const last = text.lastIndexOf('}')
  if (first !== -1 && last > first) candidates.push(text.slice(first, last + 1))
  for (const c of candidates) {
    try { return JSON.parse(c.trim()) } catch { /* try next */ }
  }
  return null
}

export function textOf(message) {
  if (!message || !Array.isArray(message.content)) return ''
  return message.content.filter((b) => b.type === 'text').map((b) => b.text).join('')
}

export function clampSeverity(n) {
  if (typeof n !== 'number' || !Number.isFinite(n)) return 0
  return clamp(n, 0, 1)
}

export function normalizeSources(arr) {
  if (!Array.isArray(arr)) return []
  const out = []
  for (const s of arr) {
    if (!s || typeof s !== 'object') continue
    const url = String(s.url || '').trim()
    if (!url) continue
    out.push({ title: String(s.title || url).trim(), url })
  }
  return out
}

export function normalizeMonitor(json, existingIds) {
  const obj = json && typeof json === 'object' ? json : {}
  const title = String(obj.title || '未命名监控').trim()
  const id = ensureUniqueId(obj.id || title, existingIds)
  const w = Number(obj.weight)
  const weight = Number.isFinite(w) ? clamp(Math.round(w), 5, 30) : 15
  const direction = obj.direction === 'decrease' ? 'decrease' : 'increase'
  return {
    id,
    title,
    searchQuery: String(obj.searchQuery || '').trim(),
    impactRule: String(obj.impactRule || '').trim(),
    weight,
    direction,
    lastReading: null,
  }
}

export function summarizeState(state) {
  const lines = state.monitors.map((m) => {
    const r = m.lastReading
    const c = r ? r.contribution : 0
    const sign = c >= 0 ? '+' : ''
    const text = r ? r.summary : '（尚未采集）'
    return `- ${m.title}：贡献 ${sign}${c}%。${text}`
  })
  return `当前美股崩盘概率：${state.crashProbability}%（基线 ${state.baseline}%）\n监控项：\n${lines.join('\n')}`
}

export function buildAssessPrompt(monitor) {
  return [
    `你正在评估一个影响【美股崩盘概率】的监控项。请联网搜索最新（${new Date().toISOString().slice(0, 10)} 前后）信息后给出客观评估。`,
    ``,
    `监控项标题：${monitor.title}`,
    `需要搜索的内容：${monitor.searchQuery}`,
    `该内容如何影响美股崩盘概率：${monitor.impactRule}`,
    ``,
    `请在最后用一个 \`\`\`json 围栏输出对象（只输出这一个 JSON）：`,
    `{`,
    `  "summary": "用中文 2~3 句话总结你搜到的最新事实，并说明它当前对美股风险的含义",`,
    `  "severity": 0到1之间的数字（0=毫无风险，1=极端风险，依据 impactRule 评估当前严重度）,`,
    `  "sources": [{"title": "来源标题", "url": "链接"}]`,
    `}`,
  ].join('\n')
}

export function buildStructurePrompt(text) {
  return [
    `用户想新增一个影响【美股崩盘概率】的动态监控项。下面是用户的原始描述：`,
    ``,
    `"""`,
    text,
    `"""`,
    ``,
    `请把它整理成一个监控项配置，用一个 \`\`\`json 围栏输出对象（只输出这一个 JSON）：`,
    `{`,
    `  "id": "简短英文 kebab-case 标识，如 oil-price",`,
    `  "title": "中文标题",`,
    `  "searchQuery": "要让 AI 联网搜索什么（中文，具体）",`,
    `  "impactRule": "搜到的内容如何影响美股崩盘概率（中文）",`,
    `  "weight": 5到30之间的整数（该项最多贡献的百分点，越重要越大）,`,
    `  "direction": "increase 或 decrease（该因素是推高还是压低崩盘概率）"`,
    `}`,
  ].join('\n')
}

const ASSESS_SYSTEM = '你是严谨的宏观与市场风险分析助手。基于联网搜到的最新事实做客观评估，不夸大不臆测，并给出可点击的来源链接。'
const CHAT_SYSTEM = '你是一个美股系统性风险分析助手，正在和用户讨论一个「美股崩盘概率」监控台的数据。回答简洁、客观、用中文。'

// ---------- 工厂 ----------
export function createLLM(config, ClientClass = Anthropic) {
  const client = new ClientClass({ apiKey: config.apiKey, baseURL: config.baseURL })
  const model = config.model

  async function completeWithSearch(userText) {
    let messages = [{ role: 'user', content: userText }]
    let res
    for (let i = 0; i < 4; i++) {
      res = await client.messages.create({
        model,
        max_tokens: 4000,
        output_config: { effort: 'medium' },
        tools: [WEB_SEARCH_TOOL],
        system: ASSESS_SYSTEM,
        messages,
      })
      if (res.stop_reason === 'pause_turn') {
        messages = [...messages, { role: 'assistant', content: res.content }]
        continue
      }
      break
    }
    return res
  }

  async function assess(monitor) {
    const res = await completeWithSearch(buildAssessPrompt(monitor))
    const json = extractJson(textOf(res))
    if (!json || typeof json.severity !== 'number') {
      throw new Error('无法从模型输出解析出 severity（可能端点不支持联网搜索或返回非结构化）')
    }
    return {
      summary: String(json.summary || '').trim(),
      severity: clampSeverity(json.severity),
      sources: normalizeSources(json.sources),
    }
  }

  async function structure(text, existingIds) {
    const res = await client.messages.create({
      model,
      max_tokens: 1500,
      messages: [{ role: 'user', content: buildStructurePrompt(text) }],
    })
    const json = extractJson(textOf(res))
    if (!json) throw new Error('无法从模型输出解析出监控项配置')
    return normalizeMonitor(json, existingIds)
  }

  async function chatStream(messages, summary, onText) {
    const stream = client.messages.stream({
      model,
      max_tokens: 2000,
      system: `${CHAT_SYSTEM}\n\n当前监控台快照：\n${summary}`,
      messages,
    })
    stream.on('text', (delta) => onText(delta))
    await stream.finalMessage()
  }

  return { assess, structure, chatStream }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `node --test test/anthropic.test.js`
Expected: PASS（11 tests）

- [ ] **Step 5: 提交**

```bash
git add server/anthropic.js test/anthropic.test.js
git commit -m "feat: LLM wrapper (web-search assess, structure, chat) + pure helpers"
```

---

### Task 5: 采集编排 `collector.js`

**Files:**
- Create: `server/collector.js`, `test/collector.test.js`

**Interfaces:**
- Produces: `createCollector({ assess }) → { collectOne(state, filePath, id), collectAll(state, filePath) }`
  - `collectOne`：调用 `assess(monitor)` 写回 `monitor.lastReading`（`{summary,severity,contribution,sources,updatedAt,error}`），失败则保留旧读数并写 `error`、不抛；随后 `recompute` + `saveState`，返回 state。
  - `collectAll`：串行对所有监控项 `collectOne`。
- Consumes: `recompute`, `saveState`（来自 `store.js`）；`assess` 由调用方注入（生产用 `createLLM().assess`）

- [ ] **Step 1: 写失败测试 `test/collector.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createInitialState } from '../server/store.js'
import { createCollector } from '../server/collector.js'

async function tempFile() {
  const dir = await mkdtemp(join(tmpdir(), 'crash-c-'))
  return { file: join(dir, 'state.json'), dir }
}

test('collectOne 写回读数、算概率、落盘', async () => {
  const { file, dir } = await tempFile()
  const state = createInitialState(15)
  const assess = async () => ({ summary: '高风险', severity: 0.5, sources: [{ title: 'T', url: 'http://t' }] })
  const { collectOne } = createCollector({ assess })

  await collectOne(state, file, 'us-debt')

  const m = state.monitors.find((x) => x.id === 'us-debt')
  assert.equal(m.lastReading.severity, 0.5)
  assert.equal(m.lastReading.contribution, 10) // 0.5*20
  assert.equal(m.lastReading.error, null)
  assert.equal(state.crashProbability, 25)     // 15+10
  const raw = await readFile(file, 'utf8')
  assert.ok(raw.includes('"severity": 0.5'))
  await rm(dir, { recursive: true, force: true })
})

test('collectOne 失败保留旧读数并写 error，不抛', async () => {
  const { file, dir } = await tempFile()
  const state = createInitialState(15)
  state.monitors[0].lastReading = { summary: '旧', severity: 0.4, contribution: 8, sources: [], updatedAt: 't', error: null }
  const assess = async () => { throw new Error('boom') }
  const { collectOne } = createCollector({ assess })

  await collectOne(state, file, state.monitors[0].id)

  const m = state.monitors[0]
  assert.equal(m.lastReading.severity, 0.4) // 旧值保留
  assert.equal(m.lastReading.error, 'boom')
  await rm(dir, { recursive: true, force: true })
})

test('collectAll 串行覆盖所有项', async () => {
  const { file, dir } = await tempFile()
  const state = createInitialState(15)
  let calls = 0
  const assess = async () => { calls++; return { summary: 's', severity: 0.1, sources: [] } }
  const { collectAll } = createCollector({ assess })

  await collectAll(state, file)

  assert.equal(calls, 6)
  assert.ok(state.monitors.every((m) => m.lastReading && m.lastReading.severity === 0.1))
  await rm(dir, { recursive: true, force: true })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `node --test test/collector.test.js`
Expected: FAIL（找不到 `../server/collector.js`）

- [ ] **Step 3: 实现 `server/collector.js`**

```js
import { recompute, saveState } from './store.js'

export function createCollector({ assess }) {
  async function collectOne(state, filePath, id) {
    const monitor = state.monitors.find((m) => m.id === id)
    if (!monitor) throw new Error(`monitor not found: ${id}`)
    try {
      const reading = await assess(monitor)
      monitor.lastReading = {
        summary: reading.summary,
        severity: reading.severity,
        contribution: 0, // recompute 填
        sources: reading.sources,
        updatedAt: new Date().toISOString(),
        error: null,
      }
    } catch (err) {
      const message = String((err && err.message) || err)
      if (monitor.lastReading) {
        monitor.lastReading.error = message
        monitor.lastReading.updatedAt = new Date().toISOString()
      } else {
        monitor.lastReading = {
          summary: '', severity: null, contribution: 0, sources: [],
          updatedAt: new Date().toISOString(), error: message,
        }
      }
    }
    recompute(state)
    await saveState(filePath, state)
    return state
  }

  async function collectAll(state, filePath) {
    for (const m of state.monitors) {
      await collectOne(state, filePath, m.id)
    }
    return state
  }

  return { collectOne, collectAll }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `node --test test/collector.test.js`
Expected: PASS（3 tests）

- [ ] **Step 5: 全量测试回归**

Run: `node --test`
Expected: PASS（全部任务的测试，共 ~28 个 test）

- [ ] **Step 6: 提交**

```bash
git add server/collector.js test/collector.test.js
git commit -m "feat: collector orchestration (collectOne/collectAll, graceful degradation)"
```

---

### Task 6: Express 接口 + 定时任务 `index.js`

**Files:**
- Create: `server/index.js`

**Interfaces:**
- Consumes: `config`(config.js)、`loadState/saveState/addMonitor/removeMonitor`(store.js)、`createLLM/summarizeState`(anthropic.js)、`createCollector`(collector.js)
- Produces: HTTP 服务（路由见下）；模块级内存 `state`，启动时 `loadState` 加载。

- [ ] **Step 1: 实现 `server/index.js`**

```js
import express from 'express'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { config } from './config.js'
import { loadState, saveState, addMonitor, removeMonitor } from './store.js'
import { createLLM, summarizeState } from './anthropic.js'
import { createCollector } from './collector.js'

const __dirname = dirname(fileURLToPath(import.meta.url))
const STATE_FILE = join(__dirname, '..', 'data', 'state.json')
const PUBLIC_DIR = join(__dirname, '..', 'public')

const llm = createLLM(config)
const collector = createCollector({ assess: llm.assess })

let state = await loadState(STATE_FILE, config.baseline)

function requireKey(res) {
  if (!config.apiKey) {
    res.status(400).json({ error: '未配置 ANTHROPIC_API_KEY，请在 .env 中填写后重启。' })
    return false
  }
  return true
}

const app = express()
app.use(express.json({ limit: '1mb' }))
app.use(express.static(PUBLIC_DIR))

app.get('/api/health', (req, res) => {
  res.json({ ok: true, hasApiKey: !!config.apiKey, model: config.model, baseURL: config.baseURL || null })
})

app.get('/api/state', (req, res) => {
  res.json(state)
})

app.post('/api/refresh', async (req, res) => {
  if (!requireKey(res)) return
  try {
    const id = req.body && req.body.id
    if (id) await collector.collectOne(state, STATE_FILE, id)
    else await collector.collectAll(state, STATE_FILE)
    res.json(state)
  } catch (err) {
    res.status(500).json({ error: String((err && err.message) || err) })
  }
})

app.post('/api/monitors', async (req, res) => {
  if (!requireKey(res)) return
  const text = req.body && req.body.text
  if (!text || !String(text).trim()) {
    res.status(400).json({ error: '请输入要监控的内容描述。' })
    return
  }
  try {
    const existingIds = state.monitors.map((m) => m.id)
    const monitor = await llm.structure(String(text), existingIds)
    addMonitor(state, monitor)
    await saveState(STATE_FILE, state)
    await collector.collectOne(state, STATE_FILE, monitor.id) // 立即采集一次
    res.json(state)
  } catch (err) {
    res.status(500).json({ error: String((err && err.message) || err) })
  }
})

app.delete('/api/monitors/:id', async (req, res) => {
  removeMonitor(state, req.params.id)
  await saveState(STATE_FILE, state)
  res.json(state)
})

app.post('/api/chat', async (req, res) => {
  if (!config.apiKey) {
    res.status(400).json({ error: '未配置 ANTHROPIC_API_KEY。' })
    return
  }
  res.setHeader('Content-Type', 'text/event-stream')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')
  const messages = (req.body && req.body.messages) || []
  try {
    await llm.chatStream(messages, summarizeState(state), (delta) => {
      res.write(`data: ${JSON.stringify({ delta })}\n\n`)
    })
    res.write(`data: ${JSON.stringify({ done: true })}\n\n`)
  } catch (err) {
    res.write(`data: ${JSON.stringify({ error: String((err && err.message) || err) })}\n\n`)
  }
  res.end()
})

// 定时自动采集
if (config.apiKey) {
  const ms = config.collectIntervalMinutes * 60 * 1000
  setInterval(() => {
    collector.collectAll(state, STATE_FILE).catch((err) => console.error('定时采集失败:', err))
  }, ms)
  // 启动时若全部未采集，后台跑一次
  if (state.monitors.every((m) => !m.lastReading)) {
    collector.collectAll(state, STATE_FILE).catch((err) => console.error('首次采集失败:', err))
  }
}

app.listen(config.port, () => {
  console.log(`美股崩盘概率监控台 → http://localhost:${config.port}`)
  console.log(`模型: ${config.model}${config.baseURL ? ' @ ' + config.baseURL : ''}  Key: ${config.apiKey ? '已配置' : '未配置'}`)
})
```

- [ ] **Step 2: 冒烟测试（无 key 也能起服务、静态接口可用）**

Run（无需 key）：
```bash
node server/index.js & SERVER_PID=$!; sleep 1
curl -s http://localhost:3000/api/health
echo
curl -s http://localhost:3000/api/state | head -c 200
echo
kill $SERVER_PID
```
Expected: health 返回 `{"ok":true,"hasApiKey":false,...}`；state 返回含 `"monitors"` 的 JSON；进程被正常关闭。

- [ ] **Step 3: 提交**

```bash
git add server/index.js
git commit -m "feat: express API + scheduler wiring"
```

---

### Task 7: 前端页面 `public/`

**Files:**
- Create: `public/index.html`, `public/style.css`, `public/app.js`

**Interfaces:**
- Consumes 后端接口：`GET /api/health`、`GET /api/state`、`POST /api/refresh`、`POST /api/monitors`、`DELETE /api/monitors/:id`、`POST /api/chat`(SSE)

- [ ] **Step 1: 写 `public/index.html`**

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>美股崩盘概率 · AI 实时监控台</title>
  <link rel="stylesheet" href="./style.css" />
</head>
<body>
  <div id="key-banner" class="banner hidden">
    ⚠️ 未配置 API Key。请在项目根目录 <code>.env</code> 写入 <code>ANTHROPIC_API_KEY</code> 后重启服务。
  </div>

  <header class="hero">
    <div class="hero-label">美股崩盘概率</div>
    <div id="prob" class="hero-prob">--.--<span class="pct">%</span></div>
    <div class="hero-meta">
      <span id="meta-model"></span> · 更新于 <span id="meta-updated">--</span>
      <button id="refresh-all" class="btn btn-ghost">⟳ 立即刷新全部</button>
    </div>
  </header>

  <main class="grid">
    <section class="col">
      <h2>动态监控项 <span class="hint">（AI 每隔一段时间自动联网采集）</span></h2>
      <div id="monitors" class="monitors"></div>

      <details class="add-box">
        <summary>➕ 新增动态监控</summary>
        <p class="hint">用大白话写：要搜什么 / 搜到什么算数 / 它怎样影响美股崩盘概率。</p>
        <textarea id="add-text" rows="4" placeholder="例如：监控国际油价。如果布伦特原油快速突破 100 美元，会推高通胀、压制美股估值，从而提高崩盘概率。"></textarea>
        <button id="add-btn" class="btn btn-primary">让 AI 添加并采集</button>
        <div id="add-status" class="status"></div>
      </details>
    </section>

    <section class="col">
      <h2>与 AI 讨论这些数据</h2>
      <div id="chat" class="chat"></div>
      <form id="chat-form" class="chat-form">
        <input id="chat-input" type="text" placeholder="问问 AI，比如：哪个因素最危险？" autocomplete="off" />
        <button class="btn btn-primary" type="submit">发送</button>
      </form>
    </section>
  </main>

  <script src="./app.js"></script>
</body>
</html>
```

- [ ] **Step 2: 写 `public/style.css`**

```css
:root {
  --bg: #0b0e14;
  --panel: #141925;
  --panel-2: #1b2233;
  --text: #e6e9ef;
  --muted: #8b94a7;
  --line: #283044;
  --accent: #5b8cff;
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--text);
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", Segoe UI, Roboto, sans-serif;
}
.hidden { display: none !important; }
.banner {
  background: #4a2; color: #fff; padding: 10px 16px; text-align: center; background: #7a4b00;
}
.banner code { background: rgba(255,255,255,.15); padding: 1px 5px; border-radius: 4px; }

.hero {
  text-align: center; padding: 40px 16px 28px;
  background: radial-gradient(120% 120% at 50% 0%, #1a2236 0%, var(--bg) 70%);
  border-bottom: 1px solid var(--line);
}
.hero-label { color: var(--muted); letter-spacing: .3em; font-size: 14px; }
.hero-prob {
  font-size: 92px; font-weight: 800; line-height: 1.05; margin: 6px 0;
  font-variant-numeric: tabular-nums; transition: color .4s;
}
.hero-prob .pct { font-size: 40px; margin-left: 6px; }
.hero-meta { color: var(--muted); font-size: 13px; display: flex; gap: 12px; justify-content: center; align-items: center; flex-wrap: wrap; }

.grid { max-width: 1100px; margin: 0 auto; padding: 24px 16px 60px; display: grid; grid-template-columns: 1.2fr .8fr; gap: 24px; }
@media (max-width: 860px) { .grid { grid-template-columns: 1fr; } }
.col h2 { font-size: 18px; margin: 0 0 12px; }
.hint { color: var(--muted); font-size: 12px; font-weight: 400; }

.monitors { display: flex; flex-direction: column; gap: 12px; }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 14px 16px; }
.card.loading { opacity: .6; }
.card-top { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.card-title { font-weight: 700; }
.badge { font-variant-numeric: tabular-nums; font-weight: 700; padding: 2px 10px; border-radius: 999px; font-size: 13px; }
.badge.up { background: rgba(255,90,90,.16); color: #ff7a7a; }
.badge.down { background: rgba(90,200,120,.16); color: #6ddf94; }
.badge.zero { background: var(--panel-2); color: var(--muted); }
.sev { height: 6px; background: var(--panel-2); border-radius: 999px; margin: 10px 0; overflow: hidden; }
.sev > i { display: block; height: 100%; background: linear-gradient(90deg, #5b8cff, #ff5a5a); }
.card-summary { font-size: 14px; color: #cdd3df; line-height: 1.6; }
.card-summary.err { color: #ff9a9a; }
.sources { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 8px; }
.sources a { color: var(--accent); font-size: 12px; text-decoration: none; border: 1px solid var(--line); padding: 2px 8px; border-radius: 999px; }
.card-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 10px; color: var(--muted); font-size: 12px; }
.card-actions { display: flex; gap: 8px; }

.btn { cursor: pointer; border: 1px solid var(--line); background: var(--panel-2); color: var(--text); border-radius: 8px; padding: 6px 12px; font-size: 13px; }
.btn:hover { border-color: var(--accent); }
.btn-primary { background: var(--accent); border-color: var(--accent); color: #fff; }
.btn-ghost { background: transparent; }
.btn:disabled { opacity: .5; cursor: not-allowed; }

.add-box { margin-top: 18px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px 16px; }
.add-box summary { cursor: pointer; font-weight: 600; }
.add-box textarea { width: 100%; margin: 10px 0; background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 10px; resize: vertical; }
.status { margin-top: 8px; font-size: 13px; color: var(--muted); }

.chat { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; height: 420px; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 10px; }
.msg { padding: 8px 12px; border-radius: 10px; max-width: 85%; line-height: 1.6; font-size: 14px; white-space: pre-wrap; }
.msg.user { align-self: flex-end; background: var(--accent); color: #fff; }
.msg.ai { align-self: flex-start; background: var(--panel-2); }
.chat-form { display: flex; gap: 8px; margin-top: 10px; }
.chat-form input { flex: 1; background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 10px; }
```

- [ ] **Step 3: 写 `public/app.js`**

```js
const $ = (sel) => document.querySelector(sel)

function probColor(p) {
  // 0→绿, 50→黄, 100→红
  const hue = Math.round(120 - (Math.max(0, Math.min(100, p)) / 100) * 120)
  return `hsl(${hue} 80% 60%)`
}

function fmtTime(iso) {
  if (!iso) return '--'
  const d = new Date(iso)
  return d.toLocaleString('zh-CN', { hour12: false })
}

function badge(contribution) {
  const c = contribution || 0
  if (c > 0) return `<span class="badge up">+${c}%</span>`
  if (c < 0) return `<span class="badge down">${c}%</span>`
  return `<span class="badge zero">±0%</span>`
}

function monitorCard(m) {
  const r = m.lastReading
  const sev = r && typeof r.severity === 'number' ? Math.round(r.severity * 100) : 0
  const summary = r
    ? (r.error ? `采集失败：${r.error}` : (r.summary || ''))
    : '尚未采集，点击「⟳」联网采集。'
  const summaryClass = r && r.error ? 'card-summary err' : 'card-summary'
  const sources = (r && r.sources || []).map((s) => `<a href="${s.url}" target="_blank" rel="noopener">${s.title}</a>`).join('')
  return `
    <div class="card" data-id="${m.id}">
      <div class="card-top">
        <div class="card-title">${m.title}</div>
        ${badge(r ? r.contribution : 0)}
      </div>
      <div class="sev"><i style="width:${sev}%"></i></div>
      <div class="${summaryClass}">${summary}</div>
      <div class="sources">${sources}</div>
      <div class="card-foot">
        <span>更新于 ${fmtTime(r && r.updatedAt)} · 权重 ${m.weight} · ${m.direction === 'increase' ? '推高' : '压低'}</span>
        <span class="card-actions">
          <button class="btn refresh-one" data-id="${m.id}">⟳ 刷新</button>
          <button class="btn del-one" data-id="${m.id}">🗑 删除</button>
        </span>
      </div>
    </div>`
}

function render(state) {
  $('#prob').innerHTML = `${state.crashProbability.toFixed(2)}<span class="pct">%</span>`
  $('#prob').style.color = probColor(state.crashProbability)
  $('#meta-updated').textContent = fmtTime(state.updatedAt)
  $('#monitors').innerHTML = state.monitors.map(monitorCard).join('')
}

async function getState() {
  const r = await fetch('/api/state')
  render(await r.json())
}

async function health() {
  const r = await fetch('/api/health')
  const h = await r.json()
  $('#meta-model').textContent = `模型 ${h.model}`
  if (!h.hasApiKey) $('#key-banner').classList.remove('hidden')
}

function setLoading(id, on) {
  const card = document.querySelector(`.card[data-id="${id}"]`)
  if (card) card.classList.toggle('loading', on)
}

async function refreshAll() {
  const btn = $('#refresh-all')
  btn.disabled = true; btn.textContent = '采集中…'
  try {
    const r = await fetch('/api/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    if (r.ok) render(await r.json()); else alert((await r.json()).error)
  } finally { btn.disabled = false; btn.textContent = '⟳ 立即刷新全部' }
}

async function refreshOne(id) {
  setLoading(id, true)
  try {
    const r = await fetch('/api/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }) })
    if (r.ok) render(await r.json()); else alert((await r.json()).error)
  } finally { setLoading(id, false) }
}

async function delOne(id) {
  if (!confirm('确定删除这个监控项？')) return
  const r = await fetch('/api/monitors/' + encodeURIComponent(id), { method: 'DELETE' })
  if (r.ok) render(await r.json())
}

async function addMonitor() {
  const text = $('#add-text').value.trim()
  if (!text) return
  const btn = $('#add-btn'); btn.disabled = true
  $('#add-status').textContent = 'AI 正在理解并联网采集…'
  try {
    const r = await fetch('/api/monitors', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) })
    const data = await r.json()
    if (r.ok) { render(data); $('#add-text').value = ''; $('#add-status').textContent = '已添加 ✓' }
    else $('#add-status').textContent = '失败：' + data.error
  } catch (e) { $('#add-status').textContent = '失败：' + e.message }
  finally { btn.disabled = false }
}

// ---- 聊天 ----
const chatHistory = []
function addBubble(role, text) {
  const div = document.createElement('div')
  div.className = 'msg ' + (role === 'user' ? 'user' : 'ai')
  div.textContent = text
  $('#chat').appendChild(div)
  $('#chat').scrollTop = $('#chat').scrollHeight
  return div
}

async function sendChat(e) {
  e.preventDefault()
  const input = $('#chat-input')
  const text = input.value.trim()
  if (!text) return
  input.value = ''
  addBubble('user', text)
  chatHistory.push({ role: 'user', content: text })
  const bubble = addBubble('ai', '')
  let acc = ''
  try {
    const res = await fetch('/api/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages: chatHistory }),
    })
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split('\n\n'); buf = parts.pop()
      for (const part of parts) {
        const line = part.trim()
        if (!line.startsWith('data:')) continue
        const payload = JSON.parse(line.slice(5).trim())
        if (payload.delta) { acc += payload.delta; bubble.textContent = acc; $('#chat').scrollTop = $('#chat').scrollHeight }
        else if (payload.error) { bubble.textContent = '出错：' + payload.error }
      }
    }
    chatHistory.push({ role: 'assistant', content: acc })
  } catch (e) { bubble.textContent = '出错：' + e.message }
}

// ---- 事件绑定 ----
$('#refresh-all').addEventListener('click', refreshAll)
$('#add-btn').addEventListener('click', addMonitor)
$('#chat-form').addEventListener('submit', sendChat)
$('#monitors').addEventListener('click', (e) => {
  const id = e.target.dataset.id
  if (!id) return
  if (e.target.classList.contains('refresh-one')) refreshOne(id)
  if (e.target.classList.contains('del-one')) delOne(id)
})

// ---- 初始化 + 轮询 ----
health()
getState()
setInterval(getState, 30000)
```

- [ ] **Step 4: 手动验证前端结构（无需 key）**

Run:
```bash
node server/index.js & SERVER_PID=$!; sleep 1
curl -s http://localhost:3000/ | grep -c "美股崩盘概率"
curl -s http://localhost:3000/style.css | head -c 40; echo
curl -s http://localhost:3000/app.js | head -c 40; echo
kill $SERVER_PID
```
Expected: 首页含「美股崩盘概率」(计数 ≥1)，style.css / app.js 能正常返回内容。

- [ ] **Step 5: 提交**

```bash
git add public/index.html public/style.css public/app.js
git commit -m "feat: frontend (probability hero, monitor cards, add-monitor, chat)"
```

---

### Task 8: 文档 + 端到端联调（真 key）

**Files:**
- Create: `README.md`

**Interfaces:**
- 无新接口；本任务收尾、文档化、用真 key 验证一次完整链路。

- [ ] **Step 1: 写 `README.md`**

````markdown
# 美股崩盘概率 · AI 实时监控台

基于高志凯「明年年底可能爆发金融危机」的思路，让 AI（Claude）实时联网采集信息，
在页面顶部给出「美股崩盘概率 XX.XX%」，并列出各动态监控项（事件 → 概率 ±X.XX%）。
可与 AI 聊天讨论，也可用大白话新增自己的监控项。

## 运行

```bash
cp .env.example .env       # 填入 ANTHROPIC_API_KEY
npm install
npm start                  # → http://localhost:3000
```

## 配置（.env）

| 变量 | 说明 |
|---|---|
| `ANTHROPIC_API_KEY` | 必填。Anthropic API Key（或 Anthropic 兼容端点的 key） |
| `ANTHROPIC_BASE_URL` | 可选。留空用官方端点；填兼容网关地址即可换端点 |
| `LLM_MODEL` | 可选。默认 `claude-opus-4-8`，随时换型号 |
| `PORT` / `BASELINE` / `COLLECT_INTERVAL_MINUTES` | 可选 |

> 换模型/端点：改 `.env` 重启即可。端点须 Anthropic Messages API 协议兼容；
> 换到不支持服务端 `web_search` 的端点时，联网采集会降级（该项标记采集失败），其余功能照常。

## 概率怎么算

`总概率 = clamp(基线 + Σ(各项 严重度×权重×方向), 0, 100)`，保留两位小数。
每个监控项的「严重度(0~1)」由 AI 联网评估，权重与方向在监控项配置里。

## 数据

所有状态存在 `data/state.json`（人可直接读）。删除该文件会用预置项重新初始化。

## 测试

```bash
npm test
```
````

- [ ] **Step 2: 全量单元测试回归**

Run: `npm test`
Expected: 全部 PASS。

- [ ] **Step 3: 真 key 端到端（需 `.env` 已填 `ANTHROPIC_API_KEY`）**

Run:
```bash
node server/index.js & SERVER_PID=$!; sleep 2
# 触发单项采集（联网，约需若干秒）
curl -s -X POST http://localhost:3000/api/refresh -H 'Content-Type: application/json' -d '{"id":"us-debt"}' | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const s=JSON.parse(d);const m=s.monitors.find(x=>x.id==='us-debt');console.log('概率:',s.crashProbability,'| us-debt severity:',m.lastReading&&m.lastReading.severity,'| error:',m.lastReading&&m.lastReading.error)})"
kill $SERVER_PID
```
Expected: 打印出一个数值概率，`us-debt` 的 `severity` 为 0~1 数字、`error` 为 null（若你的端点不支持联网搜索，会看到 error 文案 —— 属预期降级）。浏览器打开 `http://localhost:3000` 应看到概率、卡片更新、可新增监控、可聊天。

- [ ] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs: README + end-to-end verification"
```

---

## Self-Review（写完计划后自查）

**Spec 覆盖检查：**
- AI 实时采集 → Task 4 `assess`(web_search) + Task 5 collector + Task 6 定时任务 ✓
- 顶部崩盘概率 XX.XX% → Task 2 probability + Task 7 hero ✓
- 各监控项 事件→概率±X.XX% → Task 2 contributionOf + Task 7 badge ✓
- 与 AI 聊天 → Task 4 chatStream + Task 6 /api/chat + Task 7 chat ✓
- 用户大白话新增监控 → Task 4 structure + Task 6 /api/monitors + Task 7 add-box ✓
- 概率模型(基线+加权加减) → Task 2 ✓
- 采集频率(手动+30min) → Task 6 /api/refresh + setInterval ✓
- JSON 可读存储 → Task 3 saveState(2空格+换行) ✓
- 模型/baseURL/key 可配置 → Task 1 config + Task 4 createLLM ✓
- 优雅降级 → Task 5 collectOne catch + Task 6 requireKey ✓
- 预置高志凯论点 → Task 3 presets ✓

**占位符扫描：** 无 TBD/TODO；每个代码步骤含完整代码。

**类型一致性：** `assess→{summary,severity,sources}`、`structure→monitor`、`createCollector({assess})→{collectOne,collectAll}`、`computeProbability(baseline,monitors)`、`contributionOf(monitor)`、`saveState(filePath,state)` 在各任务间签名一致 ✓。
