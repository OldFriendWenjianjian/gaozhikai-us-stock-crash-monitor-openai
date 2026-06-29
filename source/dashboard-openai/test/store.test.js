import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  createInitialState, loadState, saveState, recompute, addMonitor, removeMonitor,
} from '../server/store.js'

async function tempFile() {
  const dir = await mkdtemp(join(tmpdir(), 'crash-'))
  return { file: join(dir, 'state.json'), dir }
}

test('createInitialState 含预置项', () => {
  const s = createInitialState(15)
  assert.equal(s.baseline, 15)
  assert.equal(s.crashProbability, 15)
  assert.ok(Array.isArray(s.monitors))
  assert.equal(s.monitors.length, 6)
  assert.equal(s.monitors[0].lastReading, null)
})

test('loadState 文件不存在则初始化并落盘', async () => {
  const { file, dir } = await tempFile()
  const s = await loadState(file, 15)
  assert.equal(s.monitors.length, 6)
  const raw = await readFile(file, 'utf8')
  assert.ok(raw.includes('"baseline": 15'))
  await rm(dir, { recursive: true, force: true })
})

test('loadState 文件损坏时自动重建', async () => {
  const { file, dir } = await tempFile()
  await writeFile(file, '', 'utf8')
  const s = await loadState(file, 15)
  assert.equal(s.baseline, 15)
  assert.equal(s.monitors.length, 6)
  const raw = await readFile(file, 'utf8')
  assert.ok(raw.includes('"baseline": 15'))
  await rm(dir, { recursive: true, force: true })
})

test('saveState 2 空格缩进 + 末尾换行', async () => {
  const { file, dir } = await tempFile()
  await saveState(file, createInitialState(15))
  const raw = await readFile(file, 'utf8')
  assert.ok(raw.endsWith('}\n'))
  assert.ok(raw.includes('\n  "baseline"')) // 2 空格缩进
  await rm(dir, { recursive: true, force: true })
})

test('recompute 重算 contribution 与 crashProbability', () => {
  const s = createInitialState(15)
  s.monitors[0].lastReading = { summary: 'x', severity: 0.5, contribution: 0, sources: [], updatedAt: 't' }
  recompute(s)
  assert.equal(s.monitors[0].lastReading.contribution, 10) // 0.5*20
  assert.equal(s.crashProbability, 25) // 15+10
})

test('addMonitor / removeMonitor', () => {
  const s = createInitialState(15)
  addMonitor(s, { id: 'x', title: 'X', searchQuery: '', impactRule: '', weight: 10, direction: 'increase', lastReading: null })
  assert.equal(s.monitors.length, 7)
  removeMonitor(s, 'x')
  assert.equal(s.monitors.length, 6)
  assert.ok(!s.monitors.some((m) => m.id === 'x'))
})
