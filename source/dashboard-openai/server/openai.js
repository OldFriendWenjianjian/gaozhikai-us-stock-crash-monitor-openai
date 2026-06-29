import OpenAI from 'openai'
import { clamp } from './probability.js'

const WEB_SEARCH_TOOL = { type: 'web_search_preview' }
const SEARCH_TIMEOUT_MS = 20000
const RSS_ITEM_RE = /<item>([\s\S]*?)<\/item>/gi

function decodeXml(text) {
  return String(text || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
}

function tagValue(block, tag) {
  const re = new RegExp(`<${tag}>([\\s\\S]*?)<\\/${tag}>`, 'i')
  const m = String(block || '').match(re)
  return m ? decodeXml(m[1]).trim() : ''
}

export function parseBingRss(xml) {
  const items = []
  for (const match of String(xml || '').matchAll(RSS_ITEM_RE)) {
    const block = match[1]
    const title = tagValue(block, 'title')
    const url = tagValue(block, 'link')
    if (!title || !url) continue
    items.push({
      title,
      url,
      snippet: tagValue(block, 'description'),
      publishedAt: tagValue(block, 'pubDate'),
    })
  }
  return items
}

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} timed out.`)), ms)),
  ])
}

async function searchWeb(query) {
  const url = `https://www.bing.com/search?format=rss&q=${encodeURIComponent(query)}`
  const res = await withTimeout(fetch(url, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Safari/537.36',
      'Accept': 'application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.1',
    },
  }), 12000, 'Web search')
  if (!res.ok) throw new Error(`Web search failed: ${res.status}`)
  const xml = await withTimeout(res.text(), 12000, 'Web search body')
  const items = parseBingRss(xml).slice(0, 8)
  if (!items.length) throw new Error('Web search returned no results')
  return items
}

function searchContext(results) {
  return results.map((item, index) => {
    const bits = [
      `${index + 1}. ${item.title}`,
      item.url,
      item.snippet,
      item.publishedAt ? `时间：${item.publishedAt}` : '',
    ].filter(Boolean)
    return bits.join('\n')
  }).join('\n\n')
}

export function slugify(s) {
  const out = String(s).toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return out || 'monitor'
}

export function ensureUniqueId(base, existingIds) {
  const slug = slugify(base)
  if (!existingIds.includes(slug)) return slug
  let i = 2
  while (existingIds.includes(`${slug}-${i}`)) i++
  return `${slug}-${i}`
}

export function extractJson(text) {
  if (typeof text !== 'string') return null
  const candidates = []
  const fences = [...text.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)]
  if (fences.length) candidates.push(fences[fences.length - 1][1])
  const first = text.indexOf('{')
  const last = text.lastIndexOf('}')
  if (first !== -1 && last > first) candidates.push(text.slice(first, last + 1))
  for (const c of candidates) {
    try { return JSON.parse(c.trim()) } catch { /* try next */ }
  }
  return null
}

export function textOf(response) {
  if (!response || typeof response !== 'object') return ''
  if (typeof response.output_text === 'string' && response.output_text) return response.output_text
  const items = Array.isArray(response.output) ? response.output : []
  const texts = []
  for (const item of items) {
    const content = Array.isArray(item && item.content) ? item.content : []
    for (const block of content) if (block && block.type === 'output_text' && typeof block.text === 'string') texts.push(block.text)
  }
  return texts.join('')
}

export function clampSeverity(n) {
  if (typeof n !== 'number' || !Number.isFinite(n)) return 0
  return clamp(n, 0, 1)
}

export function normalizeSources(arr) {
  if (!Array.isArray(arr)) return []
  const out = []
  for (const s of arr) {
    if (!s || typeof s !== 'object') continue
    const url = String(s.url || '').trim()
    if (!url) continue
    out.push({ title: String(s.title || url).trim(), url })
  }
  return out
}

export function normalizeMonitor(json, existingIds) {
  const obj = json && typeof json === 'object' ? json : {}
  const title = String(obj.title || '未命名监控').trim()
  const id = ensureUniqueId(obj.id || title, existingIds)
  const w = Number(obj.weight)
  const weight = Number.isFinite(w) ? clamp(Math.round(w), 5, 30) : 15
  const direction = obj.direction === 'decrease' ? 'decrease' : 'increase'
  return {
    id,
    title,
    searchQuery: String(obj.searchQuery || '').trim(),
    impactRule: String(obj.impactRule || '').trim(),
    weight,
    direction,
    lastReading: null,
  }
}

export function summarizeState(state) {
  const lines = state.monitors.map((m) => {
    const r = m.lastReading
    const c = r ? r.contribution : 0
    const sign = c >= 0 ? '+' : ''
    const text = r ? r.summary : '（尚未采集）'
    return `- ${m.title}：贡献 ${sign}${c}%。${text}`
  })
  return `当前美股崩盘概率：${state.crashProbability}%（基线 ${state.baseline}%）\n监控项：\n${lines.join('\n')}`
}

export function buildAssessPrompt(monitor) {
  return [
    `你正在评估一个影响【美股崩盘概率】的监控项。请联网搜索最新信息后给出客观评估。`,
    ``,
    `监控项标题：${monitor.title}`,
    `需要搜索的内容：${monitor.searchQuery}`,
    `该内容如何影响美股崩盘概率：${monitor.impactRule}`,
    ``,
    '请在最后用一个 ```json 围栏输出对象（只输出这一个 JSON）：',
    `{`,
    `  "summary": "用中文 2~3 句话总结你搜到的最新事实，并说明它当前对美股风险的含义",`,
    `  "severity": 0到1之间的数字（0=毫无风险，1=极端风险，依据 impactRule 评估当前严重度）,`,
    `  "sources": [{"title": "来源标题", "url": "链接"}]`,
    `}`,
  ].join('\n')
}

export function buildAssessPromptFromSearch(monitor, results) {
  return [
    `你正在评估一个影响【美股崩盘概率】的监控项。以下是已经搜集好的网页搜索结果，请只基于这些结果完成评估，不要编造未提供的事实。`,
    ``,
    `监控项标题：${monitor.title}`,
    `需要搜索的内容：${monitor.searchQuery}`,
    `该内容如何影响美股崩盘概率：${monitor.impactRule}`,
    ``,
    `搜索结果：`,
    searchContext(results),
    ``,
    '请在最后用一个 ```json 围栏输出对象（只输出这一个 JSON）：',
    `{`,
    `  "summary": "用中文 2~3 句话总结搜索结果中的最新事实，并说明它当前对美股风险的含义",`,
    `  "severity": 0到1之间的数字（0=毫无风险，1=极端风险，依据 impactRule 评估当前严重度）,`,
    `  "sources": [{"title": "来源标题", "url": "链接"}]`,
    `}`,
  ].join('\n')
}

export function buildStructurePrompt(text) {
  return [
    `用户想新增一个影响【美股崩盘概率】的动态监控项。下面是用户的原始描述：`,
    ``,
    `"""`,
    text,
    `"""`,
    ``,
    '请把它整理成一个监控项配置，用一个 ```json 围栏输出对象（只输出这一个 JSON）：',
    `{`,
    `  "id": "简短英文 kebab-case 标识，如 oil-price",`,
    `  "title": "中文标题",`,
    `  "searchQuery": "要让 AI 联网搜索什么（中文，具体）",`,
    `  "impactRule": "搜到的内容如何影响美股崩盘概率（中文）",`,
    `  "weight": 5到30之间的整数（该项最多贡献的百分点，越重要越大）,`,
    `  "direction": "increase 或 decrease（该因素是推高还是压低崩盘概率）"`,
    `}`,
  ].join('\n')
}

const ASSESS_SYSTEM = '你是严谨的宏观与市场风险分析助手。基于联网搜到的最新事实做客观评估，不夸大不臆测，并给出可点击的来源链接。'
const CHAT_SYSTEM = '你是一个美股系统性风险分析助手，正在和用户讨论一个「美股崩盘概率」监控台的数据。回答简洁、客观、用中文。'

function buildClient(config, ClientClass = OpenAI) {
  const opts = { apiKey: config.apiKey }
  if (config.baseURL) opts.baseURL = config.baseURL
  return new ClientClass(opts)
}

function toInputMessages(messages) {
  return messages.map((m) => ({ role: m.role, content: [{ type: 'input_text', text: String(m.content || '') }] }))
}

export function createLLM(config, ClientClass = OpenAI) {
  const client = buildClient(config, ClientClass)
  const analysisModel = config.analysisModel || config.model || 'gpt-5.5'
  const searchModel = config.searchModel || 'gpt-5.4-mini'

  async function assessWithSearchTool(monitor) {
    return await withTimeout(client.responses.create({
      model: searchModel,
      input: [
        { role: 'system', content: [{ type: 'input_text', text: ASSESS_SYSTEM }] },
        { role: 'user', content: [{ type: 'input_text', text: buildAssessPrompt(monitor) }] },
      ],
      tools: [WEB_SEARCH_TOOL],
      max_output_tokens: 4000,
    }), SEARCH_TIMEOUT_MS, 'OpenAI web search')
  }

  async function assess(monitor) {
    try {
      const res = await assessWithSearchTool(monitor)
      const json = extractJson(textOf(res))
      if (!json || typeof json.severity !== 'number') {
        throw new Error('无法从模型输出解析出 severity（可能端点不支持联网搜索或返回非结构化）')
      }
      return {
        summary: String(json.summary || '').trim(),
        severity: clampSeverity(json.severity),
        sources: normalizeSources(json.sources),
      }
    } catch {
      const results = await searchWeb(monitor.searchQuery || monitor.title)
      const res = await client.responses.create({
        model: analysisModel,
        input: [
          { role: 'system', content: [{ type: 'input_text', text: ASSESS_SYSTEM }] },
          { role: 'user', content: [{ type: 'input_text', text: buildAssessPromptFromSearch(monitor, results) }] },
        ],
        max_output_tokens: 2200,
      })
      const json = extractJson(textOf(res))
      if (!json || typeof json.severity !== 'number') {
        throw new Error('无法从模型输出解析出 severity（搜索后备路径返回非结构化）')
      }
      const fallbackSources = normalizeSources(json.sources)
      return {
        summary: String(json.summary || '').trim(),
        severity: clampSeverity(json.severity),
        sources: fallbackSources.length ? fallbackSources : results.map(({ title, url }) => ({ title, url })),
      }
    }
  }

  async function structure(text, existingIds) {
    const res = await client.responses.create({
      model: analysisModel,
      input: [{ role: 'user', content: [{ type: 'input_text', text: buildStructurePrompt(text) }] }],
      max_output_tokens: 1500,
    })
    const json = extractJson(textOf(res))
    if (!json) throw new Error('无法从模型输出解析出监控项配置')
    return normalizeMonitor(json, existingIds)
  }

  async function chatStream(messages, summary, onText) {
    const stream = await client.responses.stream({
      model: analysisModel,
      input: [
        { role: 'system', content: [{ type: 'input_text', text: `${CHAT_SYSTEM}\n\n当前监控台快照：\n${summary}` }] },
        ...toInputMessages(messages),
      ],
      max_output_tokens: 2000,
    })
    try {
      for await (const event of stream) {
        if (event && event.type === 'response.output_text.delta' && event.delta) onText(event.delta)
      }
      await stream.finalResponse()
    } finally {
      if (typeof stream.close === 'function') stream.close()
    }
  }

  return { assess, structure, chatStream }
}

export async function pingLLM(cfg, ClientClass = OpenAI) {
  const client = buildClient(cfg, ClientClass)
  const res = await client.responses.create({
    model: cfg.model,
    input: 'ping，请只回复 ok',
    max_output_tokens: 32,
  })
  return { ok: true, sample: textOf(res).slice(0, 40) }
}
