import express from 'express'
import { spawn } from 'node:child_process'
import { loadConfig, saveConfig } from './config.js'
import { dataDir, statePath } from './paths.js'
import { loadState, saveState, addMonitor, removeMonitor } from './store.js'
import { createLLM, summarizeState, pingLLM } from './openai.js'
import { createCollector } from './collector.js'
import { ASSETS } from './assets.generated.js'
import { artifactCatalog, buildDownloadPayload, downloadPageHtml, latestWidgetPayload, routeWithBase } from './public-api.js'

const DIR = dataDir()
const STATE_FILE = statePath()

// 模块级运行时（由 main 填充、applyConfig 重建）
let cfg = loadConfig(DIR)
let state = null
let llm = null
let collector = null
let timer = null

function autoCollectEnabled() {
  return !!cfg.apiKey && Number(cfg.collectIntervalMinutes) > 0
}

function resetScheduler() {
  if (timer) { clearInterval(timer); timer = null }
  if (autoCollectEnabled()) {
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
  return {
    ok: true,
    configured: !!cfg.apiKey,
    hasApiKey: !!cfg.apiKey,
    model: cfg.model,
    analysisModel: cfg.analysisModel || cfg.model,
    searchModel: cfg.searchModel || null,
    baseURL: cfg.baseURL || null,
    basePath: cfg.basePath || '/',
  }
}

function requestOrigin(req) {
  const proto = req.headers['x-forwarded-proto'] || req.protocol || 'http'
  const host = req.headers['x-forwarded-host'] || req.get('host') || ''
  return host ? `${proto}://${host}` : ''
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
const basePath = () => (cfg.basePath && cfg.basePath !== '' ? cfg.basePath : '/')
app.get('/', (req, res) => serveAsset(res, 'index.html'))
app.get('/index.html', (req, res) => serveAsset(res, 'index.html'))
app.get('/style.css', (req, res) => serveAsset(res, 'style.css'))
app.get('/app.js', (req, res) => serveAsset(res, 'app.js'))
app.get('/health', (req, res) => res.json(healthPayload()))
app.get('/download', (req, res) => {
  const catalog = artifactCatalog(DIR)
  const mobile = buildDownloadPayload({ basePath: basePath(), requestOrigin: requestOrigin(req), item: catalog.mobile })
  const desktop = buildDownloadPayload({ basePath: basePath(), requestOrigin: requestOrigin(req), item: catalog.desktop })
  res.setHeader('Content-Type', 'text/html; charset=utf-8')
  res.send(downloadPageHtml({ basePath: basePath(), payload: mobile, desktopPayload: desktop }))
})
app.get('/download/:name', (req, res) => {
  const item = Object.values(artifactCatalog(DIR)).find((artifact) => artifact && artifact.fileName === req.params.name)
  if (!item) { res.status(404).json({ error: '文件不存在' }); return }
  res.download(item.filePath)
})
app.get('/latest.json', (req, res) => {
  res.json(latestWidgetPayload(state, cfg))
})
app.get('/api/download', (req, res) => {
  const catalog = artifactCatalog(DIR)
  const preferred = buildDownloadPayload({ basePath: basePath(), requestOrigin: requestOrigin(req), item: catalog.mobile || catalog.desktop })
  const desktop = buildDownloadPayload({ basePath: basePath(), requestOrigin: requestOrigin(req), item: catalog.desktop })
  res.json({
    ...preferred,
    desktop,
    dashboardUrl: routeWithBase(basePath()),
    latestJsonUrl: routeWithBase(basePath(), 'latest.json'),
  })
})

// ---- 配置相关 ----
app.get('/api/health', (req, res) => res.json(healthPayload()))

app.post('/api/test', async (req, res) => {
  const b = req.body || {}
  const probe = {
    apiKey: (b.apiKey || '').trim(),
    baseURL: typeof b.baseURL === 'string' && b.baseURL.trim() ? b.baseURL.trim() : undefined,
    model: (b.model || cfg.analysisModel || cfg.model || '').trim() || 'gpt-5.5',
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
    console.log(`模型: 分析 ${cfg.analysisModel || cfg.model} / 搜索 ${cfg.searchModel || 'gpt-5.4-mini'}${cfg.baseURL ? ' @ ' + cfg.baseURL : ''}  配置: ${cfg.apiKey ? '已配置' : '未配置（请在网页里设置）'}`)
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
