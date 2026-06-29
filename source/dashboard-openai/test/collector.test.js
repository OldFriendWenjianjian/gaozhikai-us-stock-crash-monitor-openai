import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createInitialState } from '../server/store.js'
import { createCollector } from '../server/collector.js'

async function tempFile() {
  const dir = await mkdtemp(join(tmpdir(), 'crash-c-'))
  return { file: join(dir, 'state.json'), dir }
}

test('collectOne 写回读数、算概率、落盘', async () => {
  const { file, dir } = await tempFile()
  const state = createInitialState(15)
  const assess = async () => ({ summary: '高风险', severity: 0.5, sources: [{ title: 'T', url: 'http://t' }] })
  const { collectOne } = createCollector({ assess })

  await collectOne(state, file, 'us-debt')

  const m = state.monitors.find((x) => x.id === 'us-debt')
  assert.equal(m.lastReading.severity, 0.5)
  assert.equal(m.lastReading.contribution, 10) // 0.5*20
  assert.equal(m.lastReading.error, null)
  assert.equal(state.crashProbability, 25)     // 15+10
  const raw = await readFile(file, 'utf8')
  assert.ok(raw.includes('"severity": 0.5'))
  await rm(dir, { recursive: true, force: true })
})

test('collectOne 失败保留旧读数并写 error，不抛', async () => {
  const { file, dir } = await tempFile()
  const state = createInitialState(15)
  state.monitors[0].lastReading = { summary: '旧', severity: 0.4, contribution: 8, sources: [], updatedAt: 't', error: null }
  const assess = async () => { throw new Error('boom') }
  const { collectOne } = createCollector({ assess })

  await collectOne(state, file, state.monitors[0].id)

  const m = state.monitors[0]
  assert.equal(m.lastReading.severity, 0.4) // 旧值保留
  assert.equal(m.lastReading.error, 'boom')
  await rm(dir, { recursive: true, force: true })
})

test('collectAll 串行覆盖所有项', async () => {
  const { file, dir } = await tempFile()
  const state = createInitialState(15)
  let calls = 0
  const assess = async () => { calls++; return { summary: 's', severity: 0.1, sources: [] } }
  const { collectAll } = createCollector({ assess })

  await collectAll(state, file)

  assert.equal(calls, 6)
  assert.ok(state.monitors.every((m) => m.lastReading && m.lastReading.severity === 0.1))
  await rm(dir, { recursive: true, force: true })
})
