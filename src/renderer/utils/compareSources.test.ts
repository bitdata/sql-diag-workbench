import { describe, it, expect } from 'vitest'
import { createCompareSourceReader } from './compareSources'
import { currentWorkspace } from '../test/currentWorkspace'
import { createDAGState } from '../hooks/dagState'
import type { Tab } from '../types/tab'

describe('comparison source policy isolation', () => {
  it('never exposes legacy nodes as targets from active or saved tabs', () => {
    const tab = { sessionId: 'legacy', name: 'Old graph', workspace: { ...currentWorkspace,
      sessionId: 'legacy', readOnly: true, identityPolicy: 'unversioned-legacy' } } as Tab
    const reader = createCompareSourceReader()
    for (const sessionId of ['legacy', 'other']) {
      const [source] = reader([tab], { sessionId, dag: createDAGState(), parsedSql: '', dialect: 'sqlserver', epoch: 0 })
      expect(source.nodes).toEqual([])
      expect(source.errorReason).toMatch(/read.only/i)
      expect(source.degraded).toBe(true)
    }
  })
})
