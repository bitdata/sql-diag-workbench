import { describe, expect, it } from 'vitest'
import raw from './fixtures/investigation-snapshot.json'
import { archivedMappingSource, decodeInvestigationPage, decodeInvestigationSnapshot, decodeObservationReceipt } from '../investigationProtocol'

const copy = () => structuredClone(raw) as any
describe('immutable investigation protocol', () => {
  it('reads a real v1 PG snapshot without refreshing expired historical evidence', () => {
    const s = decodeInvestigationSnapshot(raw, raw.content.id)
    expect(decodeObservationReceipt(s.content.observation).submittedSql).toBe(s.content.source.submittedSql)
    expect(archivedMappingSource(s)?.mode).toBe('archive')
  })
  it.each(['source', 'annotation', 'observation', 'workspace', 'diagnostics', 'mapping'])('rejects missing %s before rendering', key => {
    const value = copy(); value.content[key] = null
    expect(() => decodeInvestigationSnapshot(value)).toThrow('SNAPSHOT_INVALID_RESPONSE')
  })
  it('rejects wrong record, source, section and nested plan/capture shapes', () => {
    expect(() => decodeInvestigationSnapshot(raw, 'other')).toThrow()
    for (const mutate of [
      (v: any) => { v.content.source.executionId = 'other' },
      (v: any) => { v.content.observation.plan.children = null },
      (v: any) => { v.content.observation.planCapture.details.nodes = {} },
      (v: any) => { v.content.mapping.state = 'NOT_AVAILABLE' },
      (v: any) => { v.content.workspace.value.children = [null] },
    ]) { const value = copy(); mutate(value); expect(() => decodeInvestigationSnapshot(value)).toThrow() }
  })
  it('renders unavailable summaries with an explicit reason rather than dropping the page', () => {
    const item = { id: 'broken', createdAt: raw.content.createdAt, title: 'Unavailable snapshot', sessionName: null, dialect: null,
      serverProduct: null, serverVersion: null, partial: true, degraded: true, pinned: false, availability: 'UNAVAILABLE', errorReason: 'SNAPSHOT_CORRUPT' }
    expect(decodeInvestigationPage({ items: [item], nextCursor: null }, 'snapshots').items).toHaveLength(1)
    expect(() => decodeInvestigationPage({ items: [{ ...item, errorReason: null }], nextCursor: null }, 'snapshots')).toThrow()
    expect(() => decodeInvestigationPage({ items: [item, item], nextCursor: null }, 'snapshots')).toThrow()
  })
  it('archive target resolution grants no live owner, tree or execution capability', () => {
    const value = decodeInvestigationSnapshot(copy()), source = archivedMappingSource(value)!
    expect(source.reveal).toBeUndefined(); expect(source.checkReveal).toBeUndefined()
    expect(source.snapshot).not.toHaveProperty('backendUrl'); expect(source.snapshot).not.toHaveProperty('draftScope')
    const mapping = value.content.mapping.value!
    const root = mapping.edges.findIndex(e => e.span?.nodeId === 'submitted_root')
    expect(source.resolve(root)).toMatchObject({ status: 'READY', target: { kind: 'SQL_SPAN', sql: value.content.source.submittedSql } })
    mapping.edges[root].confidence = 'UNKNOWN'
    expect(source.resolve(root)).toMatchObject({ status: 'UNAVAILABLE', code: 'MAPPING_TARGET_UNKNOWN' })
  })
})
