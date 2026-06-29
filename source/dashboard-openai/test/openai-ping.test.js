import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pingLLM } from '../server/openai.js'

test('pingLLM 成功返回 ok 与样例文本', async () => {
  const fakeClient = { responses: { create: async () => ({ output_text: 'ok' }) } }
  class FakeClass { constructor() { return fakeClient } }
  const r = await pingLLM({ apiKey: 'k', model: 'm' }, FakeClass)
  assert.equal(r.ok, true)
  assert.ok(r.sample.includes('ok'))
})

test('pingLLM 失败抛错', async () => {
  const fakeClient = { responses: { create: async () => { throw new Error('401 unauthorized') } } }
  class FakeClass { constructor() { return fakeClient } }
  await assert.rejects(() => pingLLM({ apiKey: 'bad', model: 'm' }, FakeClass), /401/)
})
