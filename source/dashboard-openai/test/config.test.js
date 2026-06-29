import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildConfig } from '../server/config.js'

test('buildConfig 用默认值', () => {
  const c = buildConfig({})
  assert.equal(c.model, 'gpt-5.5')
  assert.equal(c.port, 3000)
  assert.equal(c.baseline, 15)
  assert.equal(c.collectIntervalMinutes, 30)
  assert.equal(c.apiKey, '')
  assert.equal(c.baseURL, undefined)
})

test('buildConfig 读取并解析 env', () => {
  const c = buildConfig({
    OPENAI_API_KEY: 'k', OPENAI_BASE_URL: 'https://x', LLM_MODEL: 'gpt-5.5-mini',
    PORT: '8080', BASELINE: '20', COLLECT_INTERVAL_MINUTES: '5',
  })
  assert.equal(c.apiKey, 'k')
  assert.equal(c.baseURL, 'https://x')
  assert.equal(c.model, 'gpt-5.5-mini')
  assert.equal(c.port, 8080)
  assert.equal(c.baseline, 20)
  assert.equal(c.collectIntervalMinutes, 5)
})

test('buildConfig 空字符串视为未设', () => {
  const c = buildConfig({ OPENAI_BASE_URL: '', BASELINE: '' })
  assert.equal(c.baseURL, undefined)
  assert.equal(c.baseline, 15)
})
