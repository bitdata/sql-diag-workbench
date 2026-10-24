import { describe, it, expect } from 'vitest'
import { addEditLink, createEditLinks, resolveEditMark, resolveEditOrigin, editLinkKey, type EditLink } from './editLinks'
import type { FragmentNode } from '../types/fragment'
const node = (id: string, type: FragmentNode['type'] = 'JOIN_SPLIT'): FragmentNode =>
  ({ id, type, label: id, sqlFragment: 'SELECT 1', executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [] })
const link: EditLink = { sourceNodeId: 'same', sourceSessionId: 'a', sourceTabName: 'A',
  targetSessionId: 'b', targetTabName: 'B', targetNodeId: 'edited' }

describe('session-qualified edit links', () => {
  it('keeps identical SQL nodes in different workspaces independent', () => {
    const second = { ...link, sourceSessionId: 'c', sourceTabName: 'C', targetSessionId: 'd' }
    const state = addEditLink(addEditLink(createEditLinks(), link), second)
    expect(state.bySource.size).toBe(2)
    expect(state.byTarget.size).toBe(2)
    expect(resolveEditOrigin(node('edited'), state, ['a','c'], 'b')?.text).toBe('← From A')
    expect(resolveEditOrigin(node('edited'), state, ['a','c'], 'd')?.text).toBe('← From C')
    expect(resolveEditMark(node('same'), state, ['b','d'], 'a')?.targetSessionId).toBe('b')
    expect(resolveEditMark(node('same'), state, ['b','d'], 'c')?.targetSessionId).toBe('d')
  })
  it('retains every target and latest source without mutating input', () => {
    const empty = createEditLinks()
    const state = addEditLink(addEditLink(empty, link), { ...link, targetSessionId: 'new' })
    expect(empty.bySource.size).toBe(0)
    expect(state.bySource.get(editLinkKey('a', 'same'))?.targetSessionId).toBe('new')
    expect(state.byTarget.size).toBe(2)
    expect(state.allLinks.size).toBe(2)
  })
  it('reports an orphan when an endpoint is deleted even if local tab data is stale', () => {
    const state = addEditLink(createEditLinks(), { ...link, targetAlive: false, sourceAlive: false })
    expect(resolveEditMark(node('same'), state, ['b'], 'a')?.kind).toBe('orphan')
    expect(resolveEditOrigin(node('edited'), state, ['a'], 'b')?.kind).toBe('orphan')
  })
  it('retains legacy product marks and does not invent an origin', () => {
    expect(resolveEditMark(node('old','USER_EDITED'), createEditLinks(), [], 'a')?.kind).toBe('legacy')
    expect(resolveEditOrigin(node('edited'), createEditLinks(), [], 'b')).toBeNull()
  })
})