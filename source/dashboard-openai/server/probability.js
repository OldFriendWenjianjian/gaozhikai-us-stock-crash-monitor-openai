export function round2(n) {
  return Math.round((n + Number.EPSILON) * 100) / 100
}

export function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, n))
}

export function contributionOf(monitor) {
  const r = monitor && monitor.lastReading
  if (!r || typeof r.severity !== 'number') return 0
  const sign = monitor.direction === 'decrease' ? -1 : 1
  return round2(r.severity * monitor.weight * sign)
}

export function computeProbability(baseline, monitors) {
  const sum = monitors.reduce((acc, m) => acc + contributionOf(m), 0)
  return round2(clamp(baseline + sum, 0, 100))
}
