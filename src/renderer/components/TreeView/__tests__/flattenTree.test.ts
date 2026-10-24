import { describe, it, expect } from 'vitest'
import { flattenTree, childKey } from '../flattenTree'
import type { FragmentNode } from '../../../types/fragment'

/** 菱形投影：种子 a 同时挂在 p1、p2 下面（spec §4.3.1 的多父加回形态）。 */
function node(id: string, children: FragmentNode[] = []): FragmentNode {
  return {
    id, type: 'COLUMN_PRUNED', label: id, sqlFragment: `select '${id}'`,
    executable: true, executionHint: null, nodeCount: children.length,
    ruleName: 'column-pruning', children,
  }
}

describe('flattenTree path keys', () => {
  it('gives the same fragment at two positions two distinct keys', () => {
    const tree = node('root', [node('p1', [node('a')]), node('p2', [node('a')])])
    const rows = flattenTree([tree], new Set(['root', 'root/p1', 'root/p2']))
    const seeds = rows.filter((r) => r.id === 'a')
    expect(seeds).toHaveLength(2)
    expect(seeds[0].key).toBe('root/p1/a')
    expect(seeds[1].key).toBe('root/p2/a')
    // parentId holds the parent's PATH key, not its node id. Pinned at depth ≥ 2,
    // where the two meanings diverge: at depth 1 childKey(null,'root') === 'root',
    // so a depth-1 assertion would pass identically on the id-keyed implementation.
    expect(seeds[0].parentId).toBe('root/p1')
    expect(seeds[1].parentId).toBe('root/p2')
    // THE assertion Review Focus 2 asks for: React keys are unique per row.
    expect(new Set(rows.map((r) => r.key)).size).toBe(rows.length)
  })

  it('keeps node ids untouched — identity is the canonical text, not the path', () => {
    const rows = flattenTree([node('root', [node('a')])], new Set(['root']))
    expect(rows.map((r) => r.id)).toEqual(['root', 'a'])
    expect(rows[1].parentId).toBe('root')
  })

  it('expands by key, so only the toggled occurrence opens', () => {
    const tree = node('root', [node('p1', [node('a')]), node('p2', [node('a')])])
    const rows = flattenTree([tree], new Set(['root', 'root/p1']))
    expect(rows.map((r) => r.key)).toEqual(['root', 'root/p1', 'root/p1/a', 'root/p2'])
    expect(rows[3].isExpanded).toBe(false)
  })

  it('childKey is the identity at the root level', () => {
    expect(childKey(null, 'root')).toBe('root')
    expect(childKey('root', 'a')).toBe('root/a')
  })
})
