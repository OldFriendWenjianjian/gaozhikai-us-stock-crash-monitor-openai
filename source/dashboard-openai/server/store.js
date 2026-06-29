import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { dirname } from 'node:path'
import { presets } from './presets.js'
import { computeProbability, contributionOf } from './probability.js'

function normalizeStoredError(error) {
  const raw = String(error || '').trim()
  if (!raw) return null
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
      // fall through
    }
  }
  const compact = raw.replace(/\s+/g, ' ').trim()
  if (!compact) return null
  return compact.length <= 160 ? compact : `${compact.slice(0, 157)}...`
}

function normalizeLoadedState(state) {
  if (!state || !Array.isArray(state.monitors)) return state
  for (const monitor of state.monitors) {
    if (!monitor || !monitor.lastReading) continue
    monitor.lastReading.error = normalizeStoredError(monitor.lastReading.error)
  }
  return state
}

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
    return normalizeLoadedState(JSON.parse(raw))
  } catch (err) {
    if (err.code === 'ENOENT' || err instanceof SyntaxError) {
      const state = createInitialState(baseline)
      await saveState(filePath, state)
      return state
    }
    throw err
  }
}
