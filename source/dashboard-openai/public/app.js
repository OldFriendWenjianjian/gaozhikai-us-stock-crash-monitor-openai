const $ = (sel) => document.querySelector(sel)

// ---------- 工具 ----------
function probColor(p) {
  // 0→绿, 50→琥珀, 100→红
  const v = Math.max(0, Math.min(100, p))
  const hue = Math.round(135 - (v / 100) * 135)
  const sat = 70 + (v / 100) * 16
  return `hsl(${hue} ${sat}% 58%)`
}

function fmtTime(iso) {
  if (!iso) return '--'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"]/g, (ch) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[ch]
  ))
}

function riskLabel(p) {
  if (p < 25) return '风险偏低 · LOW'
  if (p < 45) return '风险中等 · MODERATE'
  if (p < 65) return '风险偏高 · ELEVATED'
  if (p < 80) return '高度警戒 · HIGH'
  return '极端警戒 · SEVERE'
}

// ---------- 仪表盘 ----------
const GAUGE_R = 120
const GAUGE_C = 2 * Math.PI * GAUGE_R
const GAUGE_ARC = GAUGE_C * 0.75 // 270° 可绘弧

function buildTicks() {
  const g = document.getElementById('gauge-ticks')
  if (!g) return
  let s = ''
  for (let i = 0; i <= 10; i++) {
    const f = i / 10
    const rad = ((135 + f * 270) * Math.PI) / 180
    const major = i % 5 === 0
    const r1 = 131
    const r2 = major ? 143 : 139
    const x1 = 150 + r1 * Math.cos(rad), y1 = 150 + r1 * Math.sin(rad)
    const x2 = 150 + r2 * Math.cos(rad), y2 = 150 + r2 * Math.sin(rad)
    s += `<line class="${major ? 'major' : ''}" x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}" />`
  }
  g.innerHTML = s
}

function setGauge(p) {
  const val = (Math.max(0, Math.min(100, p)) / 100) * GAUGE_ARC
  const el = document.getElementById('gauge-value')
  if (el) el.style.strokeDasharray = `${val.toFixed(2)} ${GAUGE_C.toFixed(2)}`
}

function setRisk(p) {
  document.documentElement.style.setProperty('--risk', probColor(p))
  $('#risk-tag').textContent = riskLabel(p)
}

let probShown = 0
function animateProb(target) {
  target = Math.max(0, Math.min(100, Number(target) || 0))
  setRisk(target)
  const start = probShown
  const t0 = performance.now()
  const dur = 900
  function step(now) {
    const k = Math.min(1, (now - t0) / dur)
    const e = 1 - Math.pow(1 - k, 3)
    const v = start + (target - start) * e
    $('#prob').innerHTML = `${v.toFixed(2)}<span class="pct">%</span>`
    setGauge(v)
    if (k < 1) requestAnimationFrame(step)
    else probShown = target
  }
  requestAnimationFrame(step)
}

// ---------- Ticker ----------
function tickerItem(m) {
  const c = m.lastReading ? (m.lastReading.contribution || 0) : 0
  const cls = c > 0 ? 'up' : c < 0 ? 'down' : 'zero'
  const v = (c > 0 ? '+' : '') + c.toFixed(2) + '%'
  return `<span class="tick"><b>${esc(m.title)}</b><i class="${cls}">${v}</i></span>`
}
function renderTicker(state) {
  const items = state.monitors.map(tickerItem).join('')
  $('#ticker-track').innerHTML = items + items // 复制一份实现无缝循环
}

// ---------- 监控卡片 ----------
function badge(c) {
  c = c || 0
  const v = c.toFixed(2)
  if (c > 0) return `<span class="card-badge up">+${v}%</span>`
  if (c < 0) return `<span class="card-badge down">${v}%</span>`
  return `<span class="card-badge zero">±0.00%</span>`
}

function monitorCard(m) {
  const r = m.lastReading
  const sev = r && typeof r.severity === 'number' ? Math.round(r.severity * 100) : 0
  const c = r ? (r.contribution || 0) : 0
  const color = probColor(sev)
  const hasSummary = !!(r && String(r.summary || '').trim())
  let body, bodyClass
  if (!r) { body = '尚未采集 —— 点击 ⟳ 联网采集'; bodyClass = 'card-body empty' }
  else if (hasSummary) { body = esc(r.summary || ''); bodyClass = 'card-body' }
  else if (r.error) { body = '采集失败：' + esc(r.error); bodyClass = 'card-body err' }
  else { body = '暂无可展示内容'; bodyClass = 'card-body empty' }
  const errorTip = r && r.error
    ? `<div class="card-error-tip">${hasSummary ? `本次刷新失败：${esc(r.error)}` : `采集失败：${esc(r.error)}`}</div>`
    : ''
  const sources = ((r && r.sources) || [])
    .map((s) => `<a href="${esc(s.url)}" target="_blank" rel="noopener">${esc(s.title)}</a>`).join('')
  return `
    <article class="card" data-id="${esc(m.id)}" style="--c:${color}">
      <span class="card-rail"></span>
      <div class="card-head">
        <h3 class="card-title">${esc(m.title)}</h3>
        ${badge(c)}
      </div>
      <div class="meter"><span class="meter-fill" style="width:${sev}%"></span></div>
      <p class="${bodyClass}">${body}</p>
      ${errorTip}
      ${sources ? `<div class="card-src">${sources}</div>` : ''}
      <div class="card-foot">
        <span class="card-meta">${fmtTime(r && r.updatedAt)} · 权重 ${m.weight} · ${m.direction === 'increase' ? '推高' : '压低'}</span>
        <span class="card-act">
          <button class="ico refresh-one" data-id="${esc(m.id)}" title="刷新">⟳</button>
          <button class="ico del-one" data-id="${esc(m.id)}" title="删除">✕</button>
        </span>
      </div>
    </article>`
}

let introShown = false
function render(state) {
  animateProb(state.crashProbability)
  $('#meta-updated').textContent = fmtTime(state.updatedAt)
  const mon = $('#monitors')
  if (!introShown) {
    introShown = true
    mon.classList.add('intro')
    setTimeout(() => mon.classList.remove('intro'), 1500)
  }
  mon.innerHTML = state.monitors.map(monitorCard).join('')
  renderTicker(state)
}

async function getState() {
  const r = await fetch('api/state')
  render(await r.json())
}

async function health() {
  const r = await fetch('api/health')
  const h = await r.json()
  $('#meta-model').textContent = `分析 ${h.analysisModel || h.model} / 搜索 ${h.searchModel || '-'}`
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
  } finally { btn.disabled = false; btn.textContent = '⟳ 立即刷新' }
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

// ---------- 聊天 ----------
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
    const r = await fetch('api/test', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
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
    const r = await fetch('api/config', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
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
  const r = await fetch('api/health'); const h = await r.json()
  showSetup({ baseURL: h.baseURL || '', model: h.model || '' })
})

// ---------- 初始化 ----------
async function init() {
  buildTicks()
  const r = await fetch('api/health')
  const h = await r.json()
  $('#meta-model').textContent = `分析 ${h.analysisModel || h.model} / 搜索 ${h.searchModel || '-'}`
  if (!h.configured) { showSetup({ baseURL: h.baseURL || '', model: h.model || '' }); return }
  showDashboard()
  await getState()
  setInterval(getState, 30000)
}
init()
