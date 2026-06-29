import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { loadConfig, saveConfig } from '../server/config.js'

// 隔离：清掉开发态 .env 注入的变量，使「默认值」断言确定
for (const k of ['OPENAI_API_KEY', 'OPENAI_BASE_URL', 'ANTHROPIC_API_KEY', 'ANTHROPIC_BASE_URL', 'LLM_MODEL', 'BASELINE', 'PORT', 'COLLECT_INTERVAL_MINUTES']) {
  delete process.env[k]
}

async function tmp() { return await mkdtemp(join(tmpdir(), 'cfg-')) }

test('loadConfig 无文件用默认', async () => {
  const dir = await tmp()
  const c = loadConfig(dir)
  assert.equal(c.model, 'gpt-5.5')
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
