import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  slugify, ensureUniqueId, extractJson, textOf, clampSeverity,
  normalizeSources, normalizeMonitor, summarizeState, createLLM, parseBingRss, buildAssessPromptFromSearch,
} from '../server/openai.js'

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

test('textOf 优先 output_text，再回退 output blocks', () => {
  assert.equal(textOf({ output_text: 'hello' }), 'hello')
  const msg = { output: [{ content: [{ type: 'output_text', text: 'a' }, { type: 'reasoning', text: 'x' }, { type: 'output_text', text: 'b' }] }] }
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
  assert.equal(m.weight, 30)
  assert.equal(m.direction, 'increase')
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

test('parseBingRss 解析标题与链接', () => {
  const xml = `<?xml version="1.0"?><rss><channel>
  <item><title>A</title><link>https://a.example</link><description>desc</description><pubDate>now</pubDate></item>
  <item><title>B</title><link>https://b.example</link></item>
  </channel></rss>`
  assert.deepEqual(parseBingRss(xml), [
    { title: 'A', url: 'https://a.example', snippet: 'desc', publishedAt: 'now' },
    { title: 'B', url: 'https://b.example', snippet: '', publishedAt: '' },
  ])
})

test('buildAssessPromptFromSearch 注入搜索结果上下文', () => {
  const prompt = buildAssessPromptFromSearch(
    { title: '债务', searchQuery: '美国国债', impactRule: '越高越危险' },
    [{ title: 'Result', url: 'https://x.example', snippet: 'snippet', publishedAt: 'today' }],
  )
  assert.ok(prompt.includes('搜索结果'))
  assert.ok(prompt.includes('https://x.example'))
  assert.ok(prompt.includes('snippet'))
})

test('createLLM.assess 用假 client 解析 severity', async () => {
  const fakeClient = {
    responses: {
      create: async () => ({
        output_text: '```json\n{"summary":"风险偏高","severity":0.7,"sources":[{"title":"X","url":"http://x"}]}\n```',
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
  const fakeClient = { responses: { create: async () => ({ output_text: '无结构化输出' }) } }
  class FakeClass { constructor() { return fakeClient } }
  const llm = createLLM({ apiKey: 'k', model: 'm' }, FakeClass)
  await assert.rejects(() => llm.assess({ id: 'a', title: 'A', weight: 20, direction: 'increase' }))
})
