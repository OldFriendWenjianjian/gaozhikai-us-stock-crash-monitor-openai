# Windows 单文件一键启动 + 首启配置向导 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有「美股崩盘概率监控台」打包成 Windows 单个 `.exe` 傻瓜软件：双击起服务、自动开浏览器、首启走「配置大模型」向导、配置与数据存 exe 同目录的外部 JSON。

**Architecture:** 用 `scripts/embed-assets.mjs` 把前端三件套内联成 `server/assets.generated.js` 由内存托管；`esbuild` 把 ESM 服务端打成单个 `build/bundle.cjs`；`@yao-pkg/pkg` 把它编成 `dist/美股崩盘概率.exe`(node18-win-x64)。配置走 `config.json`（UI 写入）、运行数据走 `state.json`，均落在 `dataDir()`（打包态=exe 目录，开发态=cwd）。LLM/采集器/定时器收进 `applyConfig(cfg)` 可热重建。

**Tech Stack:** Node 18 (ESM)、Express、@anthropic-ai/sdk、esbuild、@yao-pkg/pkg；测试用内置 `node:test`。

## Global Constraints

- ESM 项目（`"type":"module"`），import 带 `.js` 后缀。
- 打包链路：embed → esbuild(`--format=cjs --target=node18`) → pkg(`--targets node18-win-x64`)。**cjs 不允许顶层 await**，启动逻辑必须包进 `async main()`。
- 数据落点：`dataDir()` = `process.pkg ? dirname(process.execPath) : process.cwd()`；`config.json`、`state.json` 均在其下。
- 前端由内存 `ASSETS` 托管，不用 `express.static`、不依赖 pkg 快照文件。
- `server/assets.generated.js` 为生成物：gitignore，`start`/`build` 前置 `embed` 生成。
- 仅 Windows x64 一个产物；输出名 `dist/美股崩盘概率.exe`。
- 老单测（config/probability/store/anthropic/collector）必须继续通过。
- 自动开浏览器在 `process.env.NO_OPEN` 设置时跳过（供测试/无显示环境）。

---

## File Structure

```
新增: server/paths.js              dataDir/configPath/statePath
改:   server/config.js             + loadConfig / saveConfig（叠加在 buildConfig 上）
改:   server/anthropic.js          + pingLLM(cfg, ClientClass?) 试连
新增: scripts/embed-assets.mjs     生成 server/assets.generated.js
生成: server/assets.generated.js   前端内联（gitignore）
改:   server/index.js              async main / applyConfig / 内存静态 / /api/test /api/config / open / 端口自增
改:   public/index.html            #setup 向导覆盖层 + 包裹 #dashboard + ⚙ 设置
改:   public/app.js                向导逻辑 + 初始化分流
改:   public/style.css             + 向导样式
改:   package.json                 scripts(embed/start/bundle/build:win) + devDeps(esbuild,@yao-pkg/pkg) + bin/pkg
改:   .gitignore                   config.json/state.json/build/dist/assets.generated.js
改:   README.md                    打包章节
test: test/paths.test.js, test/config-file.test.js, test/anthropic-ping.test.js, test/embed-assets.test.js
```

---

### Task 1: 数据落点 `paths.js`

**Files:**
- Create: `server/paths.js`, `test/paths.test.js`

**Interfaces:**
- Produces: `dataDir() → string`、`configPath() → string`、`statePath() → string`

- [ ] **Step 1: 写失败测试 `test/paths.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dirname } from 'node:path'
import { dataDir, configPath, statePath } from '../server/paths.js'

test('开发态 dataDir = cwd', () => {
  delete process.pkg
  assert.equal(dataDir(), process.cwd())
})

test('打包态 dataDir = exe 所在目录', () => {
  process.pkg = { entrypoint: 'x' }
  try {
    assert.equal(dataDir(), dirname(process.execPath))
  } finally { delete process.pkg }
})

test('configPath / statePath 落在 dataDir 下', () => {
  delete process.pkg
  assert.ok(configPath().endsWith('config.json'))
  assert.ok(statePath().endsWith('state.json'))
  assert.ok(configPath().startsWith(process.cwd()))
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `node --test test/paths.test.js`
Expected: FAIL（找不到 `../server/paths.js`）

- [ ] **Step 3: 实现 `server/paths.js`**

```js
import { dirname, join } from 'node:path'

export function dataDir() {
  // 打包(pkg)态：process.pkg 存在，process.execPath 指向 exe → 用 exe 同目录
  if (process.pkg) return dirname(process.execPath)
  // 开发态：用当前工作目录（npm start / node --test 均在项目根）
  return process.cwd()
}

export function configPath() {
  return join(dataDir(), 'config.json')
}

export function statePath() {
  return join(dataDir(), 'state.json')
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `node --test test/paths.test.js`
Expected: PASS（3 tests）

- [ ] **Step 5: 提交**

```bash
git add server/paths.js test/paths.test.js
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat: data-dir resolver (pkg exe-dir vs dev cwd)"
```

---

### Task 2: 配置文件层 `config.js`

**Files:**
- Modify: `server/config.js`
- Create: `test/config-file.test.js`

**Interfaces:**
- Consumes: `buildConfig(env)`（已存在）
- Produces:
  - `loadConfig(dir) → cfg`（以 `buildConfig(process.env)` 为默认，叠加 `dir/config.json`）
  - `saveConfig(dir, partial) → cfg`（合并写入 `dir/config.json`，2 空格缩进；`baseURL===''` 视为未设；只持久化 apiKey/baseURL/model/baseline/collectIntervalMinutes）

- [ ] **Step 1: 写失败测试 `test/config-file.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { loadConfig, saveConfig } from '../server/config.js'

async function tmp() { return await mkdtemp(join(tmpdir(), 'cfg-')) }

test('loadConfig 无文件用默认', async () => {
  const dir = await tmp()
  const c = loadConfig(dir)
  assert.equal(c.model, 'claude-opus-4-8')
  assert.equal(c.baseline, 15)
  await rm(dir, { recursive: true, force: true })
})

test('saveConfig 落盘并可被 loadConfig 读回', async () => {
  const dir = await tmp()
  saveConfig(dir, { apiKey: 'k', baseURL: 'https://x', model: 'deepseek-v4-flash' })
  const c = loadConfig(dir)
  assert.equal(c.apiKey, 'k')
  assert.equal(c.baseURL, 'https://x')
  assert.equal(c.model, 'deepseek-v4-flash')
  const raw = await readFile(join(dir, 'config.json'), 'utf8')
  assert.ok(raw.includes('\n  "model"')) // 2 空格缩进
  assert.ok(raw.endsWith('}\n'))
  await rm(dir, { recursive: true, force: true })
})

test('saveConfig 合并保留旧字段', async () => {
  const dir = await tmp()
  saveConfig(dir, { apiKey: 'k' })
  saveConfig(dir, { model: 'm2' })
  const c = loadConfig(dir)
  assert.equal(c.apiKey, 'k')
  assert.equal(c.model, 'm2')
  await rm(dir, { recursive: true, force: true })
})

test('baseURL 空串视为未设', async () => {
  const dir = await tmp()
  saveConfig(dir, { apiKey: 'k', baseURL: '' })
  assert.equal(loadConfig(dir).baseURL, undefined)
  await rm(dir, { recursive: true, force: true })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `node --test test/config-file.test.js`
Expected: FAIL（`loadConfig is not a function`）

- [ ] **Step 3: 在 `server/config.js` 末尾追加文件层**

在 `export const config = buildConfig(process.env)` 之后追加：

```js
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const PERSIST_KEYS = ['apiKey', 'baseURL', 'model', 'baseline', 'collectIntervalMinutes']

function normalize(partial) {
  const out = {}
  for (const k of PERSIST_KEYS) {
    if (!(k in partial)) continue
    let v = partial[k]
    if (k === 'baseURL') { v = typeof v === 'string' ? v.trim() : v; if (!v) v = undefined }
    out[k] = v
  }
  return out
}

export function loadConfig(dir) {
  const base = buildConfig(process.env)
  const file = join(dir, 'config.json')
  try {
    const saved = JSON.parse(readFileSync(file, 'utf8'))
    return { ...base, ...normalize(saved) }
  } catch {
    return base // 文件不存在或损坏 → 用默认
  }
}

export function saveConfig(dir, partial) {
  const next = { ...loadConfig(dir), ...normalize(partial) }
  const persist = {}
  for (const k of PERSIST_KEYS) if (next[k] !== undefined) persist[k] = next[k]
  writeFileSync(join(dir, 'config.json'), JSON.stringify(persist, null, 2) + '\n', 'utf8')
  return next
}
```

注意：`import` 必须放在文件顶部。把这两行 `import` 移到 `server/config.js` 顶部（与现有 `import 'dotenv/config'` 并列），其余函数体留在原位置即可。

- [ ] **Step 4: 运行测试，确认通过**

Run: `node --test test/config-file.test.js`
Expected: PASS（4 tests）

- [ ] **Step 5: 确认老配置测试仍过**

Run: `node --test test/config.test.js`
Expected: PASS（3 tests）

- [ ] **Step 6: 提交**

```bash
git add server/config.js test/config-file.test.js
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat: file-backed config (loadConfig/saveConfig)"
```

---

### Task 3: 内联前端 `embed-assets.mjs`

**Files:**
- Create: `scripts/embed-assets.mjs`, `test/embed-assets.test.js`
- Modify: `package.json`(scripts), `.gitignore`
- Generated: `server/assets.generated.js`

**Interfaces:**
- Produces: 运行 `node scripts/embed-assets.mjs` → 生成 `server/assets.generated.js`，导出 `ASSETS = { 'index.html':{type,body}, 'style.css':{...}, 'app.js':{...} }`

- [ ] **Step 1: 实现 `scripts/embed-assets.mjs`**

```js
import { readFileSync, writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const files = [
  ['index.html', 'text/html; charset=utf-8'],
  ['style.css', 'text/css; charset=utf-8'],
  ['app.js', 'application/javascript; charset=utf-8'],
]

const entries = files.map(([name, type]) => {
  const body = readFileSync(join(root, 'public', name), 'utf8')
  return `  ${JSON.stringify(name)}: { type: ${JSON.stringify(type)}, body: ${JSON.stringify(body)} }`
})

const out = `// AUTO-GENERATED by scripts/embed-assets.mjs — 请勿手改\nexport const ASSETS = {\n${entries.join(',\n')}\n}\n`
writeFileSync(join(root, 'server', 'assets.generated.js'), out, 'utf8')
console.log(`embedded ${files.length} assets → server/assets.generated.js`)
```

- [ ] **Step 2: 写测试 `test/embed-assets.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

test('embed 生成可用的 ASSETS 模块', () => {
  execFileSync('node', ['scripts/embed-assets.mjs'], { cwd: root })
  const gen = readFileSync(join(root, 'server', 'assets.generated.js'), 'utf8')
  assert.ok(gen.includes('export const ASSETS'))
  assert.ok(gen.includes('index.html'))
  assert.ok(gen.includes('text/css'))
  assert.ok(gen.includes('美股崩盘概率')) // 来自 index.html 内容
})
```

- [ ] **Step 3: 运行测试，确认通过**

Run: `node --test test/embed-assets.test.js`
Expected: PASS（1 test），并生成 `server/assets.generated.js`

- [ ] **Step 4: 改 `package.json` 的 scripts**

把 `scripts` 改为：

```json
  "scripts": {
    "embed": "node scripts/embed-assets.mjs",
    "start": "npm run embed && node server/index.js",
    "test": "node --test",
    "bundle": "esbuild server/index.js --bundle --platform=node --format=cjs --target=node18 --outfile=build/bundle.cjs",
    "build:win": "npm run embed && npm run bundle && pkg build/bundle.cjs --targets node18-win-x64 --output dist/美股崩盘概率.exe"
  }
```

- [ ] **Step 5: 改 `.gitignore`**

把内容改为：

```gitignore
node_modules/
.env
config.json
state.json
data/state.json
build/
dist/
server/assets.generated.js
```

- [ ] **Step 6: 提交**

```bash
git add scripts/embed-assets.mjs test/embed-assets.test.js package.json .gitignore
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat: embed-assets build step + scripts/gitignore"
```

---

### Task 4: 试连 `pingLLM` + 重写 `index.js`

**Files:**
- Modify: `server/anthropic.js`(+pingLLM), `server/index.js`(整体重写)
- Create: `test/anthropic-ping.test.js`

**Interfaces:**
- Produces: `pingLLM(cfg, ClientClass=Anthropic) → Promise<{ok:true, sample:string}>`（失败抛错）
- Consumes: `loadConfig/saveConfig`(config.js)、`dataDir/statePath`(paths.js)、`loadState/saveState/addMonitor/removeMonitor`(store.js)、`createLLM/summarizeState/pingLLM`(anthropic.js)、`createCollector`(collector.js)、`ASSETS`(assets.generated.js)

- [ ] **Step 1: 写失败测试 `test/anthropic-ping.test.js`**

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pingLLM } from '../server/anthropic.js'

test('pingLLM 成功返回 ok 与样例文本', async () => {
  const fakeClient = { messages: { create: async () => ({ content: [{ type: 'text', text: 'ok' }] }) } }
  class FakeClass { constructor() { return fakeClient } }
  const r = await pingLLM({ apiKey: 'k', model: 'm' }, FakeClass)
  assert.equal(r.ok, true)
  assert.ok(r.sample.includes('ok'))
})

test('pingLLM 失败抛错', async () => {
  const fakeClient = { messages: { create: async () => { throw new Error('401 unauthorized') } } }
  class FakeClass { constructor() { return fakeClient } }
  await assert.rejects(() => pingLLM({ apiKey: 'bad', model: 'm' }, FakeClass), /401/)
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `node --test test/anthropic-ping.test.js`
Expected: FAIL（`pingLLM is not exported`）

- [ ] **Step 3: 在 `server/anthropic.js` 追加 `pingLLM`**

在文件末尾（`createLLM` 之后）追加：

```js
export async function pingLLM(cfg, ClientClass = Anthropic) {
  const client = new ClientClass({ apiKey: cfg.apiKey, baseURL: cfg.baseURL })
  const res = await client.messages.create({
    model: cfg.model,
    max_tokens: 16,
    messages: [{ role: 'user', content: 'ping，请只回复 ok' }],
  })
  return { ok: true, sample: textOf(res).slice(0, 40) }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `node --test test/anthropic-ping.test.js`
Expected: PASS（2 tests）

- [ ] **Step 5: 整体重写 `server/index.js`**

```js
import express from 'express'
import { spawn } from 'node:child_process'
import { loadConfig, saveConfig } from './config.js'
import { dataDir, statePath } from './paths.js'
import { loadState, saveState, addMonitor, removeMonitor } from './store.js'
import { createLLM, summarizeState, pingLLM } from './anthropic.js'
import { createCollector } from './collector.js'
import { ASSETS } from './assets.generated.js'

const DIR = dataDir()
const STATE_FILE = statePath()

// 模块级运行时（由 main 填充、applyConfig 重建）
let cfg = loadConfig(DIR)
let state = null
let llm = null
let collector = null
let timer = null

function resetScheduler() {
  if (timer) { clearInterval(timer); timer = null }
  if (cfg.apiKey) {
    timer = setInterval(() => {
      collector.collectAll(state, STATE_FILE).catch((e) => console.error('定时采集失败:', e))
    }, cfg.collectIntervalMinutes * 60 * 1000)
  }
}

function applyConfig(newCfg) {
  cfg = newCfg
  llm = createLLM(cfg)
  collector = createCollector({ assess: llm.assess })
  resetScheduler()
}

function requireKey(res) {
  if (!cfg.apiKey) { res.status(400).json({ error: '尚未配置大模型，请先在设置里填入 API Key。' }); return false }
  return true
}

function healthPayload() {
  return { ok: true, configured: !!cfg.apiKey, hasApiKey: !!cfg.apiKey, model: cfg.model, baseURL: cfg.baseURL || null }
}

const app = express()
app.use(express.json({ limit: '1mb' }))

// ---- 内存静态 ----
function serveAsset(res, name) {
  const a = ASSETS[name]
  if (!a) { res.status(404).end(); return }
  res.setHeader('Content-Type', a.type)
  res.send(a.body)
}
app.get('/', (req, res) => serveAsset(res, 'index.html'))
app.get('/index.html', (req, res) => serveAsset(res, 'index.html'))
app.get('/style.css', (req, res) => serveAsset(res, 'style.css'))
app.get('/app.js', (req, res) => serveAsset(res, 'app.js'))

// ---- 配置相关 ----
app.get('/api/health', (req, res) => res.json(healthPayload()))

app.post('/api/test', async (req, res) => {
  const b = req.body || {}
  const probe = {
    apiKey: (b.apiKey || '').trim(),
    baseURL: typeof b.baseURL === 'string' && b.baseURL.trim() ? b.baseURL.trim() : undefined,
    model: (b.model || cfg.model || '').trim() || 'claude-opus-4-8',
  }
  if (!probe.apiKey) { res.status(400).json({ ok: false, error: '请填写 API Key' }); return }
  try { const r = await pingLLM(probe); res.json({ ok: true, sample: r.sample }) }
  catch (e) { res.json({ ok: false, error: String((e && e.message) || e) }) }
})

app.post('/api/config', async (req, res) => {
  const b = req.body || {}
  const partial = {}
  if (typeof b.apiKey === 'string' && b.apiKey.trim()) partial.apiKey = b.apiKey.trim()
  if (typeof b.baseURL === 'string') partial.baseURL = b.baseURL.trim() // '' → 由 normalize 视为未设
  if (typeof b.model === 'string' && b.model.trim()) partial.model = b.model.trim()
  try {
    const next = saveConfig(DIR, partial)
    applyConfig(next)
    if (cfg.apiKey && state.monitors.every((m) => !m.lastReading)) {
      collector.collectAll(state, STATE_FILE).catch((e) => console.error('首采失败:', e))
    }
    res.json(healthPayload())
  } catch (e) { res.status(500).json({ error: String((e && e.message) || e) }) }
})

// ---- 业务接口 ----
app.get('/api/state', (req, res) => res.json(state))

app.post('/api/refresh', async (req, res) => {
  if (!requireKey(res)) return
  try {
    const id = req.body && req.body.id
    if (id) await collector.collectOne(state, STATE_FILE, id)
    else await collector.collectAll(state, STATE_FILE)
    res.json(state)
  } catch (e) { res.status(500).json({ error: String((e && e.message) || e) }) }
})

app.post('/api/monitors', async (req, res) => {
  if (!requireKey(res)) return
  const text = req.body && req.body.text
  if (!text || !String(text).trim()) { res.status(400).json({ error: '请输入要监控的内容描述。' }); return }
  try {
    const existingIds = state.monitors.map((m) => m.id)
    const monitor = await llm.structure(String(text), existingIds)
    addMonitor(state, monitor)
    await saveState(STATE_FILE, state)
    await collector.collectOne(state, STATE_FILE, monitor.id)
    res.json(state)
  } catch (e) { res.status(500).json({ error: String((e && e.message) || e) }) }
})

app.delete('/api/monitors/:id', async (req, res) => {
  removeMonitor(state, req.params.id)
  await saveState(STATE_FILE, state)
  res.json(state)
})

app.post('/api/chat', async (req, res) => {
  if (!cfg.apiKey) { res.status(400).json({ error: '尚未配置大模型。' }); return }
  res.setHeader('Content-Type', 'text/event-stream')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')
  const messages = (req.body && req.body.messages) || []
  try {
    await llm.chatStream(messages, summarizeState(state), (delta) => res.write(`data: ${JSON.stringify({ delta })}\n\n`))
    res.write(`data: ${JSON.stringify({ done: true })}\n\n`)
  } catch (e) { res.write(`data: ${JSON.stringify({ error: String((e && e.message) || e) })}\n\n`) }
  res.end()
})

// ---- 启动 ----
function openBrowser(url) {
  try {
    if (process.platform === 'win32') spawn('cmd', ['/c', 'start', '', url], { detached: true, stdio: 'ignore' }).unref()
    else if (process.platform === 'darwin') spawn('open', [url], { detached: true, stdio: 'ignore' }).unref()
    else spawn('xdg-open', [url], { detached: true, stdio: 'ignore' }).unref()
  } catch { /* 忽略：无浏览器环境 */ }
}

function listen(port, attemptsLeft) {
  const server = app.listen(port)
  server.on('listening', () => {
    const url = `http://localhost:${port}`
    console.log(`美股崩盘概率监控台 → ${url}`)
    console.log(`模型: ${cfg.model}${cfg.baseURL ? ' @ ' + cfg.baseURL : ''}  配置: ${cfg.apiKey ? '已配置' : '未配置（请在网页里设置）'}`)
    console.log(`数据目录: ${DIR}`)
    if (!process.env.NO_OPEN) openBrowser(url)
    if (cfg.apiKey && state.monitors.every((m) => !m.lastReading)) {
      collector.collectAll(state, STATE_FILE).catch((e) => console.error('首采失败:', e))
    }
  })
  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE' && attemptsLeft > 0) listen(port + 1, attemptsLeft - 1)
    else { console.error('启动失败:', err); process.exit(1) }
  })
}

async function main() {
  state = await loadState(STATE_FILE, cfg.baseline)
  applyConfig(cfg)
  const startPort = Number(process.env.PORT) || cfg.port || 3000
  listen(startPort, 10)
}

main()
```

- [ ] **Step 6: 生成内联资源（index 依赖它）**

Run: `npm run embed`
Expected: 输出 `embedded 3 assets`，生成 `server/assets.generated.js`

- [ ] **Step 7: Linux 端到端验证（未配置 → 试连 → 保存 → 采集）**

在一个干净临时目录、清空 key 环境变量下运行，模拟「首启未配置」：

```bash
WORK=$(mktemp -d); P=/home/sumscope/.xinglong.shi/usa-market
( cd "$WORK" && NO_OPEN=1 PORT=3777 env -u ANTHROPIC_API_KEY node "$P/server/index.js" & echo $! > "$WORK/pid" )
sleep 1.5
echo "--- health（应 configured:false）---"; curl -s http://localhost:3777/api/health; echo
echo "--- 首页（内存静态）---"; curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3777/
echo "--- 试连真 DeepSeek ---"; curl -s -X POST http://localhost:3777/api/test -H 'Content-Type: application/json' \
  -d '{"apiKey":"sk-8638468748ff4129bc77a37f680224f9","baseURL":"https://api.deepseek.com/anthropic","model":"deepseek-v4-flash"}'; echo
echo "--- 保存配置 ---"; curl -s -X POST http://localhost:3777/api/config -H 'Content-Type: application/json' \
  -d '{"apiKey":"sk-8638468748ff4129bc77a37f680224f9","baseURL":"https://api.deepseek.com/anthropic","model":"deepseek-v4-flash"}'; echo
echo "--- config.json 是否落在工作目录 ---"; test -f "$WORK/config.json" && echo "YES: $WORK/config.json"
kill "$(cat "$WORK/pid")" 2>/dev/null
```
Expected: 第一次 health `configured:false`；首页返回 200；`/api/test` 返回 `{"ok":true,...}`；`/api/config` 返回 `configured:true`；`config.json` 出现在临时工作目录（证明落点 = cwd/exe 目录）。

- [ ] **Step 8: 全量单测回归**

Run: `npm test`
Expected: 全部 PASS（老 + 新，共 ~38 test）

- [ ] **Step 9: 提交**

```bash
git add server/anthropic.js server/index.js test/anthropic-ping.test.js
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat: setup-aware server (config/test endpoints, in-memory static, applyConfig, auto-open)"
```

---

### Task 5: 前端配置向导

**Files:**
- Modify: `public/index.html`, `public/app.js`, `public/style.css`

**Interfaces:**
- Consumes: `/api/health`(configured)、`/api/test`、`/api/config`

- [ ] **Step 1: 整体重写 `public/index.html`**（在现有基础上：包裹 `#dashboard`、加 `#setup` 覆盖层与 ⚙ 设置按钮）

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>美股崩盘概率 · 风险终端</title>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Fraunces:ital,opsz,wght@0,9..144,500;0,9..144,600;0,9..144,900;1,9..144,500;1,9..144,600&family=JetBrains+Mono:wght@400;500;700;800&display=swap" rel="stylesheet" />
  <link rel="stylesheet" href="./style.css" />
</head>
<body>
  <div class="fx-grain" aria-hidden="true"></div>
  <div class="fx-scan" aria-hidden="true"></div>

  <!-- 配置向导（未配置 / 点设置时显示） -->
  <div id="setup" class="setup hidden">
    <form id="setup-form" class="setup-card">
      <div class="eyebrow"><span class="live"><i></i>SETUP</span> 配置大模型</div>
      <h2 class="setup-title">接入你的大模型</h2>
      <p class="setup-sub">支持 Anthropic 官方或任意 Anthropic 兼容端点（如 DeepSeek）。配置保存在本程序同目录的 <code>config.json</code>。</p>

      <label>API Key <span class="req">*</span>
        <input id="su-key" type="password" placeholder="sk-..." autocomplete="off" />
      </label>
      <label>Base URL <span class="opt">（选填，留空=官方端点）</span>
        <input id="su-url" type="text" placeholder="https://api.deepseek.com/anthropic" autocomplete="off" />
      </label>
      <label>模型名称
        <input id="su-model" type="text" placeholder="claude-opus-4-8" autocomplete="off" />
      </label>

      <div class="setup-actions">
        <button id="su-test" type="button" class="btn btn-ghost">测试连接</button>
        <button id="su-save" type="submit" class="btn btn-primary">保存并进入</button>
      </div>
      <div id="su-status" class="status"></div>
    </form>
  </div>

  <!-- 仪表盘 -->
  <div id="dashboard" class="hidden">
    <div id="key-banner" class="banner hidden">
      ⚠️ 未配置大模型 —— 点右上角 ⚙ 设置填入 API Key。
    </div>

    <header class="hero">
      <div class="hero-inner">
        <button id="open-settings" class="settings-btn" title="设置">⚙</button>
        <div class="eyebrow">
          <span class="live"><i></i>LIVE</span>
          U.S. EQUITY · CRASH&nbsp;PROBABILITY&nbsp;INDEX
        </div>
        <h1 class="hero-zh">美股崩盘概率</h1>

        <div class="gauge-wrap">
          <svg class="gauge" viewBox="0 0 300 300" aria-hidden="true">
            <defs>
              <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
                <feGaussianBlur stdDeviation="5" result="b" />
                <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
              </filter>
            </defs>
            <g transform="rotate(135 150 150)">
              <circle class="zone zone-g" cx="150" cy="150" r="120" />
              <circle class="zone zone-a" cx="150" cy="150" r="120" />
              <circle class="zone zone-r" cx="150" cy="150" r="120" />
              <circle id="gauge-value" class="gauge-value" cx="150" cy="150" r="120" filter="url(#glow)" />
            </g>
            <g id="gauge-ticks"></g>
          </svg>
          <div class="gauge-center">
            <div id="prob" class="hero-prob">--.--<span class="pct">%</span></div>
            <div id="risk-tag" class="risk-tag">— 等待数据 —</div>
          </div>
        </div>

        <div class="hero-meta">
          <span id="meta-model" class="mono">模型 —</span>
          <span class="sep">/</span>
          <span class="mono">更新 <span id="meta-updated">--</span></span>
          <button id="refresh-all" class="btn btn-ghost">⟳ 立即刷新</button>
        </div>
      </div>
    </header>

    <div class="ticker" aria-hidden="true"><div id="ticker-track" class="ticker-track"></div></div>

    <main class="grid">
      <section class="col">
        <div class="col-head">
          <span class="kicker">LIVE FEED</span>
          <h2>动态监控项</h2>
          <span class="hint">AI 每隔一段时间自动联网采集</span>
        </div>
        <div id="monitors" class="monitors"></div>

        <details class="add-box">
          <summary><span class="plus">＋</span> 新增动态监控</summary>
          <p class="hint">用大白话写：要搜什么 / 搜到什么算数 / 它怎样影响美股崩盘概率。</p>
          <textarea id="add-text" rows="4" placeholder="例如：监控国际油价。如果布伦特原油快速突破 100 美元，会推高通胀、压制美股估值，从而提高崩盘概率。"></textarea>
          <button id="add-btn" class="btn btn-primary">让 AI 添加并采集</button>
          <div id="add-status" class="status"></div>
        </details>
      </section>

      <section class="col">
        <div class="col-head">
          <span class="kicker">TERMINAL</span>
          <h2>与 AI 讨论数据</h2>
          <span class="hint">基于当前监控台快照</span>
        </div>
        <div id="chat" class="chat"></div>
        <form id="chat-form" class="chat-form">
          <input id="chat-input" type="text" placeholder="问问 AI，比如：哪个因素最危险？" autocomplete="off" />
          <button class="btn btn-primary" type="submit">发送</button>
        </form>
      </section>
    </main>

    <footer class="page-foot">
      <span>基于高志凯「明年年底可能爆发金融危机」之思路 · AI 实时联网采集 · 仅供研究参考，非投资建议</span>
    </footer>
  </div>

  <script src="./app.js"></script>
</body>
</html>
```

- [ ] **Step 2: 在 `public/style.css` 末尾追加向导样式**

```css
/* ---------- 配置向导 ---------- */
.setup {
  position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 24px;
  background: radial-gradient(80% 60% at 50% 0%, color-mix(in srgb, var(--risk) 12%, transparent), transparent 60%), rgba(6,6,9,.92);
  backdrop-filter: blur(6px);
}
.setup-card {
  width: min(460px, 94vw); background: var(--panel); border: 1px solid var(--line-2);
  border-radius: 16px; padding: 26px 26px 22px; box-shadow: 0 30px 80px rgba(0,0,0,.55);
  animation: fadeUp .5s both;
}
.setup-title { font-family: var(--serif); font-weight: 900; font-size: 26px; letter-spacing: .04em; margin: 10px 0 6px; }
.setup-sub { color: var(--ink-dim); font-size: 12.5px; line-height: 1.6; margin: 0 0 18px; }
.setup-sub code { font-family: var(--mono); color: var(--ink-2); background: rgba(255,255,255,.05); padding: 1px 5px; border-radius: 4px; }
.setup-card label { display: block; font-size: 12.5px; color: var(--ink-2); margin: 12px 0 0; letter-spacing: .02em; }
.setup-card .req { color: var(--red); }
.setup-card .opt { color: var(--ink-dim); }
.setup-card input {
  width: 100%; margin-top: 6px; padding: 11px 12px; font-family: var(--mono); font-size: 13.5px;
  color: var(--ink); background: rgba(0,0,0,.32); border: 1px solid var(--line); border-radius: 10px;
}
.setup-card input:focus { outline: none; border-color: var(--risk); }
.setup-actions { display: flex; gap: 10px; margin-top: 20px; }
.setup-actions .btn { flex: 1; padding: 11px; }

.settings-btn {
  position: absolute; top: 0; right: 4px; cursor: pointer; width: 38px; height: 38px;
  border-radius: 10px; border: 1px solid var(--line); background: rgba(255,255,255,.03);
  color: var(--ink-2); font-size: 17px; transition: .2s;
}
.settings-btn:hover { color: var(--ink); border-color: var(--risk); transform: rotate(40deg); }
```

- [ ] **Step 3: 改 `public/app.js` —— 替换底部「初始化 + 轮询」整段**

把文件最后的 `// ---------- 初始化 + 轮询 ----------` 到文件结尾整段，替换为：

```js
// ---------- 配置向导 ----------
function showSetup(prefill) {
  if (prefill) {
    $('#su-url').value = prefill.baseURL || ''
    $('#su-model').value = prefill.model || ''
  }
  $('#setup').classList.remove('hidden')
  $('#dashboard').classList.add('hidden')
}
function showDashboard() {
  $('#setup').classList.add('hidden')
  $('#dashboard').classList.remove('hidden')
}

async function testConn() {
  const body = { apiKey: $('#su-key').value.trim(), baseURL: $('#su-url').value.trim(), model: $('#su-model').value.trim() }
  if (!body.apiKey) { $('#su-status').textContent = '请先填写 API Key'; return }
  $('#su-status').textContent = '正在测试连接…'
  try {
    const r = await fetch('/api/test', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    const d = await r.json()
    $('#su-status').textContent = d.ok ? `连接成功 ✓ 模型回了：${d.sample || 'ok'}` : '连接失败：' + d.error
  } catch (e) { $('#su-status').textContent = '连接失败：' + e.message }
}

async function saveConfig(e) {
  e.preventDefault()
  const body = { apiKey: $('#su-key').value.trim(), baseURL: $('#su-url').value.trim(), model: $('#su-model').value.trim() }
  if (!body.apiKey) { $('#su-status').textContent = '请填写 API Key'; return }
  $('#su-save').disabled = true; $('#su-status').textContent = '正在保存…'
  try {
    const r = await fetch('/api/config', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    const d = await r.json()
    if (r.ok && d.configured) {
      $('#su-key').value = ''
      showDashboard()
      await health()
      await getState()
    } else { $('#su-status').textContent = '保存失败：' + (d.error || '未知错误') }
  } catch (e) { $('#su-status').textContent = '保存失败：' + e.message }
  finally { $('#su-save').disabled = false }
}

// ---------- 事件绑定 ----------
$('#refresh-all').addEventListener('click', refreshAll)
$('#add-btn').addEventListener('click', addMonitor)
$('#chat-form').addEventListener('submit', sendChat)
$('#monitors').addEventListener('click', (e) => {
  const btn = e.target.closest('button')
  if (!btn) return
  const id = btn.dataset.id
  if (!id) return
  if (btn.classList.contains('refresh-one')) refreshOne(id)
  if (btn.classList.contains('del-one')) delOne(id)
})
$('#su-test').addEventListener('click', testConn)
$('#setup-form').addEventListener('submit', saveConfig)
$('#open-settings').addEventListener('click', async () => {
  const r = await fetch('/api/health'); const h = await r.json()
  showSetup({ baseURL: h.baseURL || '', model: h.model || '' })
})

// ---------- 初始化 ----------
async function init() {
  buildTicks()
  const r = await fetch('/api/health')
  const h = await r.json()
  $('#meta-model').textContent = `模型 ${h.model}`
  if (!h.configured) { showSetup({ baseURL: h.baseURL || '', model: h.model || '' }); return }
  showDashboard()
  await getState()
  setInterval(getState, 30000)
}
init()
```

注意：`health()` 函数仍保留在 app.js 上方（设置 meta-model 与 key-banner），`saveConfig`/`open-settings` 复用它；初始化改由 `init()` 统一分流，不再在底部直接调用 `health()/getState()`。

- [ ] **Step 4: 生成内联资源并验证页面**

```bash
cd /home/sumscope/.xinglong.shi/usa-market && npm run embed
WORK=$(mktemp -d)
( cd "$WORK" && NO_OPEN=1 PORT=3778 env -u ANTHROPIC_API_KEY node "$PWD/../"*/server/index.js 2>/dev/null & ) 2>/dev/null
# 用绝对路径更稳：
( cd "$WORK" && NO_OPEN=1 PORT=3778 env -u ANTHROPIC_API_KEY node /home/sumscope/.xinglong.shi/usa-market/server/index.js & echo $! > "$WORK/pid" )
sleep 1.5
echo "setup 覆盖层: $(curl -s http://localhost:3778/ | grep -c 'id=\"setup\"')"
echo "dashboard: $(curl -s http://localhost:3778/ | grep -c 'id=\"dashboard\"')"
echo "app.js: $(curl -s -o /dev/null -w '%{http_code}' http://localhost:3778/app.js)"
node --check public/app.js && echo "app.js 语法 OK"
kill "$(cat "$WORK/pid")" 2>/dev/null
```
Expected: setup 覆盖层计数 ≥1、dashboard ≥1、app.js 200、语法 OK。

- [ ] **Step 5: 提交**

```bash
git add public/index.html public/app.js public/style.css
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat: first-run setup wizard + settings gear"
```

---

### Task 6: 打包成 Windows .exe

**Files:**
- Modify: `package.json`(devDeps + bin/pkg 配置)

**Interfaces:**
- Produces: `dist/美股崩盘概率.exe`

- [ ] **Step 1: 安装打包依赖**

Run: `npm install -D esbuild @yao-pkg/pkg`
Expected: 装好，`package.json` 出现 `devDependencies`。

- [ ] **Step 2: 给 `package.json` 加 bin 与 pkg 配置**

在 `package.json` 顶层加入（与 scripts 同级）：

```json
  "bin": "build/bundle.cjs",
  "pkg": {
    "targets": ["node18-win-x64"],
    "outputPath": "dist"
  }
```

- [ ] **Step 3: 跑构建**

Run: `npm run build:win`
Expected: 依次 `embedded 3 assets` → esbuild 产出 `build/bundle.cjs` → pkg 下载 win 基座并产出 `dist/美股崩盘概率.exe`。

> 若构建机无外网导致 pkg 拉取基座失败：embed 与 esbuild 两步应已成功（`build/bundle.cjs` 存在）。记录失败原因，让用户在有网环境执行 `npm run build:win`。**不要伪报成功。**

- [ ] **Step 4: 验证产物**

Run:
```bash
ls -lh dist/ 2>/dev/null; file dist/*.exe 2>/dev/null
echo "bundle.cjs 行数: $(wc -l < build/bundle.cjs 2>/dev/null)"
```
Expected: `dist/美股崩盘概率.exe` 存在、`file` 显示为 PE32+/MS Windows executable、体积约 40–90MB；`build/bundle.cjs` 存在且非空。（若因无网未生成 exe，则只验证 bundle.cjs 存在，并在提交信息与回复里如实说明。）

- [ ] **Step 5: 提交**

```bash
git add package.json package-lock.json
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "build: pkg windows single-exe packaging (esbuild bundle)"
```

---

### Task 7: 文档 + 全量回归 + 端到端

**Files:**
- Modify: `README.md`

**Interfaces:** 无新接口；收尾。

- [ ] **Step 1: 在 `README.md` 追加「打包成 Windows 单文件」章节**

````markdown

## 打包成 Windows 单文件（傻瓜一键启动）

```bash
npm install            # 首次
npm run build:win      # 产出 dist/美股崩盘概率.exe（需联网下载 Win 版 Node 基座）
```

把 `dist/美股崩盘概率.exe` 拷到任意目录，**双击**即可：
- 自动起本地服务并打开浏览器；
- 首次进入显示「配置大模型」向导，填入 API Key / Base URL（选填）/ 模型名，点「保存并进入」；
- 配置与运行数据存在 exe 同目录的 `config.json` 与 `state.json`（可直接用记事本查看）。

> 说明：构建在 Linux 上交叉编译 Windows 二进制；Windows 上的双击实跑请在 Windows 机器验证。
> 用户机器无需安装 Node。换模型：点右上角 ⚙ 设置，或直接编辑 `config.json` 后重启。
````

- [ ] **Step 2: 全量单测回归**

Run: `npm test`
Expected: 全部 PASS。

- [ ] **Step 3: 用真 key 走一遍完整链路（开发态）**

```bash
cd /home/sumscope/.xinglong.shi/usa-market && npm run embed
WORK=$(mktemp -d)
( cd "$WORK" && NO_OPEN=1 PORT=3779 env -u ANTHROPIC_API_KEY node /home/sumscope/.xinglong.shi/usa-market/server/index.js & echo $! > "$WORK/pid" )
sleep 1.5
curl -s -X POST http://localhost:3779/api/config -H 'Content-Type: application/json' \
  -d '{"apiKey":"sk-8638468748ff4129bc77a37f680224f9","baseURL":"https://api.deepseek.com/anthropic","model":"deepseek-v4-flash"}' >/dev/null
curl -s -X POST http://localhost:3779/api/refresh -H 'Content-Type: application/json' -d '{"id":"us-debt"}' \
 | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const s=JSON.parse(d);const m=s.monitors.find(x=>x.id==='us-debt');console.log('概率',s.crashProbability,'severity',m.lastReading&&m.lastReading.severity,'error',m.lastReading&&m.lastReading.error)})"
kill "$(cat "$WORK/pid")" 2>/dev/null
```
Expected: 打印出数值概率与 `us-debt` 的 severity（0~1）、error 为 null；`$WORK/config.json` 与 `$WORK/state.json` 均已生成。

- [ ] **Step 4: 提交**

```bash
git add README.md
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "docs: windows packaging guide + e2e verification"
```

---

## Self-Review（写完计划后自查）

**Spec 覆盖：**
- 单 exe 打包 → Task 3(embed/scripts) + Task 6(esbuild+pkg) ✓
- 仅 Windows → Task 6 `node18-win-x64` ✓
- 双击起服务 + 自动开浏览器 → Task 4(openBrowser/listen) ✓
- 首启配置向导 → Task 4(/api/test,/api/config,health.configured) + Task 5(前端向导) ✓
- 外部文件 config.json/state.json 落 exe 同目录 → Task 1(paths) + Task 2(config 文件层) ✓
- LLM 可热重建 → Task 4(applyConfig) ✓
- 前端内存托管绕开 pkg 静态坑 → Task 3(embed) + Task 4(serveAsset) ✓
- cjs 无顶层 await → Task 4(async main) ✓
- ⚙ 设置可改配置 → Task 5 ✓

**占位符扫描：** 无 TBD/TODO；每个代码步骤含完整代码；Task 6 明确「无网则只验证 bundle、如实说明、不伪报」。

**类型一致性：** `dataDir/configPath/statePath`、`loadConfig(dir)/saveConfig(dir,partial)`、`pingLLM(cfg,ClientClass)→{ok,sample}`、`applyConfig(cfg)`、`ASSETS[name]={type,body}`、`healthPayload()` 含 `configured` —— 各任务间一致 ✓。
