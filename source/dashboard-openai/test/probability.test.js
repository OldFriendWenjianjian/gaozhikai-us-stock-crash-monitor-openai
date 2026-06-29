import { test } from 'node:test'
import assert from 'node:assert/strict'
import { round2, clamp, contributionOf, computeProbability } from '../server/probability.js'

test('round2 保留两位', () => {
  assert.equal(round2(13.005), 13.01)
  assert.equal(round2(1 / 3), 0.33)
})

test('clamp 夹取', () => {
  assert.equal(clamp(150, 0, 100), 100)
  assert.equal(clamp(-5, 0, 100), 0)
  assert.equal(clamp(42, 0, 100), 42)
})

test('contributionOf 未采集返回 0', () => {
  assert.equal(contributionOf({ weight: 20, direction: 'increase', lastReading: null }), 0)
  assert.equal(contributionOf({ weight: 20, direction: 'increase', lastReading: { severity: null } }), 0)
})

test('contributionOf increase 为正、decrease 为负', () => {
  assert.equal(contributionOf({ weight: 20, direction: 'increase', lastReading: { severity: 0.65 } }), 13)
  assert.equal(contributionOf({ weight: 20, direction: 'decrease', lastReading: { severity: 0.5 } }), -10)
})

test('computeProbability = baseline + Σ贡献，clamp 到 0~100', () => {
  const monitors = [
    { weight: 20, direction: 'increase', lastReading: { severity: 0.65 } }, // +13
    { weight: 15, direction: 'increase', lastReading: { severity: 0.4 } },  // +6
    { weight: 10, direction: 'decrease', lastReading: { severity: 0.5 } },  // -5
  ]
  assert.equal(computeProbability(15, monitors), 29) // 15+13+6-5
})

test('computeProbability clamp 上限 100', () => {
  const monitors = [{ weight: 30, direction: 'increase', lastReading: { severity: 1 } }]
  assert.equal(computeProbability(95, monitors), 100)
})
