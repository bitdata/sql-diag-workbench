import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useFragmentCompare } from '../useFragmentCompare'
import { createDAGState } from '../dagState'
import { createCompareSourceReader, compareTarget, targetIssue } from '../../utils/compareSources'
import type { Tab } from '../../types/tab'
import type { FragmentNode } from '../../types/fragment'
const node = (sql: string): FragmentNode => ({ id: 'same-id', type: 'ORIGINAL', label: sql, sqlFragment: sql, executable: true, children: [], executionHint: null, nodeCount: 1, ruleName: null })
function tab(id: string, sql: string): Tab { return { sessionId: id, name: id, isDirty: false, lastSavedAt: 0,
  workspace: { sessionId: id, tabName: id, sql, isDirty: false, lastSavedAt: 0, connectionId: null, dialect: 'postgresql',
    fragmentTreeJson: JSON.stringify(node(sql)), hydration: 'ready', selectedNodeId: 'same-id', workspaceMode: 'data', viewerMode: 'browse' } } }
function fixture() { const dag = createDAGState(); dag.nodeMap.set('same-id', node('SELECT 11')); return {
  tabs: [tab('one', 'SELECT 11'), tab('two', 'SELECT 22')], active: { sessionId: 'one', dag, parsedSql: 'SELECT 11', dialect: 'postgresql', epoch: 1 } } }
describe('comparison sources and controller', () => {
  it('keeps identical node IDs isolated by source session and detects closed or changed sources', () => {
    const { tabs, active } = fixture(); const sources = createCompareSourceReader()(tabs, active)
    const a = compareTarget(sources[0], sources[0].nodes[0]), b = compareTarget(sources[1], sources[1].nodes[0])
    expect(a.node.sqlFragment).toBe('SELECT 11'); expect(b.node.sqlFragment).toBe('SELECT 22')
    expect(targetIssue(b, sources)).toBeNull(); expect(sources[1].degraded).toBe(true)
    expect(targetIssue(b, sources.slice(0, 1))).toContain('closed')
    expect(targetIssue(a, [{ ...sources[0], version: 'new' }])).toContain('changed')
  })
  it('shows malformed and unhydrated sources instead of using the active graph', () => {
    const { tabs, active } = fixture(); tabs[1].workspace.workspaceSnapshotJson = '{broken'
    const reader = createCompareSourceReader()
    expect(reader(tabs, active)[1].errorReason).toContain('cannot be read')
    tabs[1].workspace.hydration = 'loading'
    expect(reader(tabs, active)[1].nodes).toEqual([])
    expect(reader(tabs, active)[1].errorReason).toContain('loading')
  })
  it('caches decoded background nodes and does not invalidate a target when a facet is added', () => {
    const { tabs, active } = fixture(); const reader = createCompareSourceReader(); const first = reader(tabs, active)
    active.dag.nodeMap.set('extra', { ...node('SELECT 33'), id: 'extra' })
    const next = reader(tabs, active)
    expect(next[1].nodes).toBe(first[1].nodes)
    expect(targetIssue(compareTarget(first[0], first[0].nodes[0]), next)).toBeNull()
  })
  it('opens with an empty B, pins SQL, swaps without mutating sources and exits on tab switch', () => {
    const { tabs, active } = fixture(); const { result, rerender } = renderHook(({ current }) => useFragmentCompare(tabs, current, 'same-id'), { initialProps: { current: active } })
    act(() => result.current.open())
    expect(result.current.state?.b).toBeNull()
    act(() => result.current.choose('B', compareTarget(result.current.sources[1], result.current.sources[1].nodes[0])))
    act(() => result.current.swap())
    expect(result.current.state?.a?.node.sqlFragment).toBe('SELECT 22')
    expect(active.dag.nodeMap.get('same-id')?.sqlFragment).toBe('SELECT 11')
    rerender({ current: { ...active, sessionId: 'two' } })
    expect(result.current.state).toBeNull()
  })
})
