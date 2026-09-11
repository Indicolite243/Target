import { describe, expect, it, vi } from 'vitest'
import { SseParser } from './assistantStream.js'

describe('assistant SSE parser', () => {
  it('parses fragmented CRLF events and JSON newlines', () => {
    const received = []
    const parser = new SseParser((event, data) => received.push([event, data]))
    parser.feed('event: del')
    parser.feed('ta\r\ndata: {"text":"第一行\\n第二行"}\r')
    parser.feed('\n\r\nevent: done\ndata: {"status":"COMPLETED"}\n\n')
    expect(received).toEqual([
      ['delta', { text: '第一行\n第二行' }],
      ['done', { status: 'COMPLETED' }]
    ])
  })

  it('does not expose malformed payload as an event', () => {
    const callback = vi.fn()
    const parser = new SseParser(callback)
    expect(() => parser.feed('event: delta\ndata: not-json\n\n')).toThrow()
    expect(callback).not.toHaveBeenCalled()
  })
})

