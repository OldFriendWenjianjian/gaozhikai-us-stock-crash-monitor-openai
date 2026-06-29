import { dirname, join } from 'node:path'

export function dataDir() {
  // 打包(pkg)态：process.pkg 存在，process.execPath 指向 exe → 用 exe 同目录
  if (process.pkg) return dirname(process.execPath)
  // 开发态：用当前工作目录（npm start / node --test 均在项目根）
  return process.cwd()
}

export function configPath() {
  return join(dataDir(), 'config.json')
}

export function statePath() {
  return join(dataDir(), 'state.json')
}
