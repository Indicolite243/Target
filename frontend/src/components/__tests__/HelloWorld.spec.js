import { describe, expect, it } from 'vitest'
import { sanitizeData, validateData } from '../../utils/dataTransformers.js'

describe('data transformers', () => {
  it('removes null values without changing valid values', () => {
    expect(sanitizeData({ symbol: '510300.SH', quantity: 0, stale: null }))
      .toEqual({ symbol: '510300.SH', quantity: 0 })
  })

  it('recognises non-empty API objects', () => {
    expect(validateData({ dataVersion: '12' }, 'object')).toBe(true)
    expect(validateData({}, 'object')).toBe(false)
  })
})
