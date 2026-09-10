import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import InvestmentAssistant from './InvestmentAssistant.vue'

const request = vi.hoisted(() => vi.fn())
vi.mock('@/utils/httpClient', () => ({ httpClient: { request } }))
vi.mock('element-plus', () => ({ ElMessageBox: { prompt: vi.fn(), confirm: vi.fn() } }))

describe('investment assistant session panel', () => {
  beforeEach(() => {
    request.mockReset()
    request.mockImplementation(async ({ url }) => ({ data: { code: 0, data:
      url.endsWith('/messages')
        ? [{ id: 'm', role: 'assistant', content: '本用户的历史回答', status: 'COMPLETED' }]
        : [{ id: 'c', title: '历史会话' }]
    } }))
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
  it('aborts old-user requests on logout/unmount', async () => {
    const wrapper = mount(InvestmentAssistant)
    await flushPromises()
    const signal = request.mock.calls[0][0].signal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
})
