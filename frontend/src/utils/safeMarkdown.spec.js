import { describe, expect, it } from 'vitest'
import { renderSafeMarkdown } from './safeMarkdown.js'

describe('renderSafeMarkdown', () => {
  it('renders headings, emphasis and GitHub-style tables', () => {
    const html = renderSafeMarkdown(`### 持仓诊断

**集中度较高**

| 股票 | 权重 |
| --- | ---: |
| 中国移动 | 25.7% |`)

    expect(html).toContain('<h3>持仓诊断</h3>')
    expect(html).toContain('<strong>集中度较高</strong>')
    expect(html).toContain('<table>')
    expect(html).toMatch(/<td[^>]*>25\.7%<\/td>/)
    expect(html).not.toContain('**')
  })

  it('repairs bold markers attached to Chinese punctuation', () => {
    const html = renderSafeMarkdown('这是**“核心资产 + 科技成长”**混合风格。')

    expect(html).toContain('<strong>“核心资产 + 科技成长”</strong>')
    expect(html).not.toContain('**')
  })

  it('removes executable HTML from model output', () => {
    const html = renderSafeMarkdown('<img src=x onerror="alert(1)"><script>alert(2)</script>')

    expect(html).toContain('<img src="x">')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('<script')
  })
})
