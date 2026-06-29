import { recompute, saveState } from './store.js'

function normalizeCollectorError(err) {
  const raw = String((err && err.message) || err || '').trim()
  const jsonStart = raw.indexOf('{')
  if (jsonStart !== -1) {
    const prefix = raw.slice(0, jsonStart).trim()
    try {
      const parsed = JSON.parse(raw.slice(jsonStart))
      const code = parsed.error_code || parsed.status || prefix || '采集失败'
      if (parsed.cloudflare_error) {
        const zone = parsed.zone ? `（${parsed.zone}）` : ''
        const retry = parsed.retry_after ? `，建议 ${parsed.retry_after} 秒后重试` : ''
        return `${code} 上游网关错误${zone}${retry}`
      }
      if (parsed.detail || parsed.title) {
        return [prefix || parsed.status, parsed.title || parsed.detail].filter(Boolean).join(' ')
      }
    } catch {
      // fall through to raw text cleanup
    }
  }

  const compact = raw.replace(/\s+/g, ' ').trim()
  if (!compact) return '采集失败，请稍后重试'
  if (compact.length <= 160) return compact
  return `${compact.slice(0, 157)}...`
}

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
      const message = normalizeCollectorError(err)
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
