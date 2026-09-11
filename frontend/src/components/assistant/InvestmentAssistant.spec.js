import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import InvestmentAssistant from './InvestmentAssistant.vue'

const request = vi.hoisted(() => vi.fn())
const streamAssistant = vi.hoisted(() => vi.fn())
vi.mock('@/utils/httpClient', () => ({ httpClient: { request } }))
vi.mock('@/services/assistantStream.js', () => ({ streamAssistant }))
vi.mock('element-plus', () => ({ ElMessageBox: { prompt: vi.fn(), confirm: vi.fn() } }))

describe('investment assistant session panel', () => {
  beforeEach(() => {
    request.mockReset()
    streamAssistant.mockReset()
    request.mockImplementation(async ({ url }) => ({ data: { code: 0, data:
      url.endsWith('/messages')
        ? [{ id: 'm', role: 'assistant', content: '本用户的历史回答', status: 'COMPLETED' }]
        : [{ id: 'c', title: '历史会话' }]
    } }))
  })
  it('streams a persisted answer into the active conversation', async () => {
    let finishStream
    streamAssistant.mockImplementation(({ onEvent }) => {
      onEvent('accepted', { userMessageId: 'u1', assistantMessageId: 'a1' })
      onEvent('status', { message: '正在生成回答' })
      onEvent('delta', { text: '第一段' })
      onEvent('delta', { text: '第二段' })
      return new Promise(resolve => {
        finishStream = () => { onEvent('done', { status: 'COMPLETED' }); resolve() }
      })
    })
    const wrapper = mount(InvestmentAssistant)
    await flushPromises()
    await wrapper.get('textarea').setValue('请解释夏普比率')
    await wrapper.get('[aria-label="发送问题"]').trigger('click')
    await flushPromises()
    expect(streamAssistant).toHaveBeenCalledWith(expect.objectContaining({ conversationId: 'c', question: '请解释夏普比率' }))
    expect(wrapper.text()).toContain('第一段第二段')
    expect(wrapper.find('[aria-label="停止生成"]').exists()).toBe(true)
    finishStream()
    await vi.waitFor(() => expect(wrapper.find('[aria-label="发送问题"]').exists()).toBe(true))
    wrapper.unmount()
  })
  it('keeps the loaded conversation when hidden and reopened', async () => {
    const wrapper = mount(InvestmentAssistant)
    await flushPromises()
    expect(wrapper.text()).toContain('本用户的历史回答')
    await wrapper.get('[aria-label="关闭投研助手"]').trigger('click')
    expect(wrapper.get('aside').element.style.display).toBe('none')
    await wrapper.get('.assistant-launch').trigger('click')
    expect(wrapper.get('aside').element.style.display).not.toBe('none')
    expect(wrapper.find('.assistant-launch').exists()).toBe(false)
    expect(wrapper.text()).toContain('本用户的历史回答')
    expect(request).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('renders assistant Markdown while keeping user content as plain text', async () => {
    request.mockImplementation(async ({ url }) => ({ data: { code: 0, data:
      url.endsWith('/messages')
        ? [
            { id: 'a', role: 'assistant', content: '**诊断**\n\n| 股票 | 权重 |\n| --- | ---: |\n| 中国移动 | 25.7% |', status: 'COMPLETED' },
            { id: 'u', role: 'user', content: '**不要渲染我**', status: 'COMPLETED' }
          ]
        : [{ id: 'c', title: '历史会话' }]
    } }))

    const wrapper = mount(InvestmentAssistant)
    await flushPromises()

    expect(wrapper.get('.assistant-markdown strong').text()).toBe('诊断')
    expect(wrapper.find('.assistant-markdown table').exists()).toBe(true)
    expect(wrapper.get('.assistant-message.user').text()).toBe('**不要渲染我**')
    wrapper.unmount()
  })
  it('aborts old-user requests on logout/unmount', async () => {
    const wrapper = mount(InvestmentAssistant)
    await flushPromises()
    const signal = request.mock.calls[0][0].signal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
  it('uploads private knowledge documents as multipart form data', async () => {
    request.mockImplementation(async ({ method, url }) => {
      if (method === 'post' && url === '/assistant/knowledge/documents') {
        return { data: { code: 0, data: { id: 'd1', name: 'research.md' } } }
      }
      if (url === '/assistant/knowledge/documents') return { data: { code: 0, data: [] } }
      return { data: { code: 0, data: url.endsWith('/messages')
        ? [{ id: 'm', role: 'assistant', content: '欢迎', status: 'COMPLETED' }]
        : [{ id: 'c', title: '历史会话' }] } }
    })
    const wrapper = mount(InvestmentAssistant)
    await flushPromises()
    await wrapper.get('[aria-label="私有知识库"]').trigger('click')
    await flushPromises()
    const input = wrapper.get('[aria-label="选择知识库文档"]')
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['knowledge'], 'research.md', { type: 'text/markdown' })]
    })
    await input.trigger('change')
    await flushPromises()

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      method: 'post',
      url: '/assistant/knowledge/documents',
      data: expect.any(FormData),
      headers: { 'Content-Type': 'multipart/form-data' }
    }))
    wrapper.unmount()
  })
})
