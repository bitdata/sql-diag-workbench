import { FragmentNode } from '../../types/fragment'

export interface FlatTreeNode {
  /** 投影内路径键：同一 fragment（同一 id）出现在两个位置时也不撞车。
   *  身份仍是 canonical 文本（ADR-0001），路径键只服务渲染与展开态。 */
  key: string
  id: string
  node: FragmentNode
  depth: number
  /** 父节点的**路径键**（不是父节点 id）。 */
  parentId: string | null
  isExpanded: boolean
  hasChildren: boolean
}

/**
 * Path key for one position in the projected tree. Two occurrences of the same
 * fragment (same canonical id — ADR-0001) are two different rows, so React keys
 * and expansion state must be per-position, not per-id (spec §4.3.2 菱形投影).
 */
export function childKey(parentKey: string | null, nodeId: string): string {
  return parentKey === null ? nodeId : `${parentKey}/${nodeId}`
}

export function flattenTree(
  nodes: FragmentNode[],
  expandedKeys: Set<string>,
  parentKey: string | null = null,
  depth = 0
): FlatTreeNode[] {
  const result: FlatTreeNode[] = []

  for (const node of nodes) {
    const key = childKey(parentKey, node.id)
    const isExpanded = expandedKeys.has(key)
    const hasChildren = node.children.length > 0

    result.push({ key, id: node.id, node, depth, parentId: parentKey, isExpanded, hasChildren })

    if (hasChildren && isExpanded) {
      result.push(...flattenTree(node.children, expandedKeys, key, depth + 1))
    }
  }

  return result
}
