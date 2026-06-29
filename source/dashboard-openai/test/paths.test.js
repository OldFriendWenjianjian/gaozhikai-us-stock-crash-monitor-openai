import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dirname } from 'node:path'
import { dataDir, configPath, statePath } from '../server/paths.js'

test('开发态 dataDir = cwd', () => {
  delete process.pkg
  assert.equal(dataDir(), process.cwd())
})

test('打包态 dataDir = exe 所在目录', () => {
  process.pkg = { entrypoint: 'x' }
  try {
    assert.equal(dataDir(), dirname(process.execPath))
  } finally { delete process.pkg }
})

test('configPath / statePath 落在 dataDir 下', () => {
  delete process.pkg
  assert.ok(configPath().endsWith('config.json'))
  assert.ok(statePath().endsWith('state.json'))
  assert.ok(configPath().startsWith(process.cwd()))
})
