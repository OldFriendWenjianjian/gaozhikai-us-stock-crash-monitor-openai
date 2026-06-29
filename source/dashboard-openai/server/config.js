import 'dotenv/config'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

function numEnv(env, name, dflt) {
  const v = env[name]
  if (v === undefined || v === '') return dflt
  const n = Number(v)
  return Number.isFinite(n) ? n : dflt
}

export function buildConfig(env) {
  const baseURL = env.OPENAI_BASE_URL || env.ANTHROPIC_BASE_URL
  const analysisModel = env.OPENAI_ANALYSIS_MODEL || env.LLM_MODEL || 'gpt-5.5'
  return {
    apiKey: env.OPENAI_API_KEY || env.ANTHROPIC_API_KEY || '',
    baseURL: baseURL && baseURL.trim() !== '' ? baseURL.trim() : undefined,
    model: analysisModel,
    analysisModel,
    searchModel: env.OPENAI_SEARCH_MODEL || 'gpt-5.4-mini',
    basePath: env.BASE_PATH || '/',
    port: numEnv(env, 'PORT', 3000),
    baseline: numEnv(env, 'BASELINE', 15),
    collectIntervalMinutes: numEnv(env, 'COLLECT_INTERVAL_MINUTES', 0),
  }
}

export const config = buildConfig(process.env)

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
