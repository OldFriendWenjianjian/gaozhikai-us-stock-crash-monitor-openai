import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

test('embed 生成可用的 ASSETS 模块', () => {
  execFileSync('node', ['scripts/embed-assets.mjs'], { cwd: root })
  const gen = readFileSync(join(root, 'server', 'assets.generated.js'), 'utf8')
  assert.ok(gen.includes('export const ASSETS'))
  assert.ok(gen.includes('index.html'))
  assert.ok(gen.includes('text/css'))
  assert.ok(gen.includes('美股崩盘概率')) // 来自 index.html 内容
})
