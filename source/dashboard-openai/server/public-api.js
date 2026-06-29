import { createHash } from 'node:crypto'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { join, posix as posixPath } from 'node:path'

const MOBILE_APK_NAME = 'gaozhikai-crash-monitor-android.apk'
const DESKTOP_EXE_NAME = 'gaozhikai-crash-monitor-openai-win-x64.exe'

function safeBasePath(basePath) {
  const raw = String(basePath || '/')
  const normalized = raw.startsWith('/') ? raw : `/${raw}`
  return normalized.endsWith('/') ? normalized : `${normalized}/`
}

export function routeWithBase(basePath, suffix = '') {
  const base = safeBasePath(basePath)
  const clean = String(suffix || '').replace(/^\/+/, '')
  return clean ? posixPath.join(base, clean).replace(/\\/g, '/') : base
}

function fileMetadata(filePath) {
  if (!existsSync(filePath)) return null
  const stat = statSync(filePath)
  const sha256 = createHash('sha256').update(readFileSync(filePath)).digest('hex')
  return {
    filePath,
    fileName: filePath.split(/[/\\]/).pop(),
    fileSizeBytes: stat.size,
    updatedAt: stat.mtime.toISOString(),
    sha256,
  }
}

export function artifactCatalog(dir) {
  const distDir = join(dir, 'dist')
  return {
    mobile: fileMetadata(join(distDir, MOBILE_APK_NAME)),
    desktop: fileMetadata(join(distDir, DESKTOP_EXE_NAME)),
  }
}

export function buildDownloadPayload({ basePath, requestOrigin, item }) {
  if (!item) {
    return { available: false }
  }
  const relative = routeWithBase(basePath, `download/${item.fileName}`)
  const origin = requestOrigin ? requestOrigin.replace(/\/+$/, '') : ''
  return {
    available: true,
    fileName: item.fileName,
    fileSizeBytes: item.fileSizeBytes,
    sha256: item.sha256,
    downloadUrl: relative,
    absoluteDownloadUrl: origin ? `${origin}${relative}` : relative,
    updatedAt: item.updatedAt,
  }
}

export function latestWidgetPayload(state, cfg) {
  const top = [...state.monitors]
    .filter((m) => m.lastReading && typeof m.lastReading.contribution === 'number')
    .sort((a, b) => Math.abs(b.lastReading.contribution) - Math.abs(a.lastReading.contribution))

  const lead = top[0]
  const updated = new Date(state.updatedAt)
  const riskScore = Math.round(state.crashProbability)
  const stage = riskStage(state.crashProbability)
  return {
    product: '高志凯美股崩盘概率预测程序（OpenAI版）',
    crashProbability: state.crashProbability,
    riskScore,
    date: updated.toISOString().slice(0, 10),
    time: updated.toISOString().slice(11, 19),
    updatedAt: state.updatedAt,
    model: cfg.model,
    baseURL: cfg.baseURL || null,
    stage,
    keyTrigger: lead ? lead.title : '等待数据',
    summary: lead && lead.lastReading ? lead.lastReading.summary : '等待首次采集',
    action: lead && lead.lastReading ? lead.lastReading.summary : '等待首次采集',
    confidence: lead && lead.lastReading && typeof lead.lastReading.severity === 'number'
      ? `${Math.round(lead.lastReading.severity * 100)}%`
      : '',
    source: 'openai_dashboard',
    assessmentStatus: 'final',
    analysisMode: 'standard',
    monitors: top.slice(0, 5).map((m) => ({
      id: m.id,
      title: m.title,
      contribution: m.lastReading ? m.lastReading.contribution : 0,
      severity: m.lastReading ? m.lastReading.severity : null,
      updatedAt: m.lastReading ? m.lastReading.updatedAt : null,
    })),
  }
}

function riskStage(probability) {
  if (probability >= 80) return '极端警戒'
  if (probability >= 65) return '高度警戒'
  if (probability >= 45) return '风险偏高'
  if (probability >= 25) return '风险中等'
  return '风险偏低'
}

export function downloadPageHtml({ basePath, payload, desktopPayload }) {
  const mobileLine = payload.available
    ? `
      <li><strong>Android App</strong> · <a href="${payload.downloadUrl}">${payload.fileName}</a> · ${payload.fileSizeBytes} bytes</li>
      <li>SHA256 · <code>${payload.sha256}</code></li>
    `
    : '<li>Android App · 暂未提供</li>'

  const desktopLine = desktopPayload && desktopPayload.available
    ? `
      <li><strong>Windows 单文件版</strong> · <a href="${desktopPayload.downloadUrl}">${desktopPayload.fileName}</a> · ${desktopPayload.fileSizeBytes} bytes</li>
      <li>SHA256 · <code>${desktopPayload.sha256}</code></li>
    `
    : '<li>Windows 单文件版 · 暂未提供</li>'

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>高志凯美股崩盘概率预测程序（OpenAI版）下载</title>
  <style>
    :root { color-scheme: dark; }
    body { margin: 0; font-family: "Microsoft YaHei", sans-serif; background: radial-gradient(circle at top, #2b1b08, #090d12 55%); color: #f5f0e6; }
    main { max-width: 920px; margin: 0 auto; padding: 56px 24px 88px; }
    .card { background: rgba(15, 18, 24, 0.92); border: 1px solid rgba(255,255,255,0.08); border-radius: 28px; padding: 28px; box-shadow: 0 24px 80px rgba(0,0,0,0.35); }
    h1 { margin: 0 0 12px; font-size: 40px; }
    p { color: #b8b1a4; line-height: 1.75; }
    ul { padding-left: 20px; line-height: 1.9; }
    a { color: #f1cd53; }
    code { color: #f5f0e6; word-break: break-all; }
    .meta { margin-top: 24px; color: #9a9387; font-size: 14px; }
  </style>
</head>
<body>
  <main>
    <div class="card">
      <h1>高志凯美股崩盘概率预测程序（OpenAI版）</h1>
      <p>这个页面用于共享服务器下载与接入说明。主仪表盘入口位于 <a href="${routeWithBase(basePath)}">${routeWithBase(basePath)}</a>。</p>
      <ul>
        ${mobileLine}
        ${desktopLine}
      </ul>
      <p>安装建议：手机端优先使用 Android App 与桌面挂件，挂件会在每天早上 8 点前自动刷新当日预测；桌面端适合浏览完整监控项和来源。</p>
      <div class="meta">Base Path: <code>${safeBasePath(basePath)}</code></div>
    </div>
  </main>
</body>
</html>`
}
