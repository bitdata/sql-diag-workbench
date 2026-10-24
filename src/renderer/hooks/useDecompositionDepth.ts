import { useMemo } from 'react'
import { FragmentNode } from '../types/fragment'

export interface NodeDepthInfo {
  maxDepth: number
  globalDepth: number
  nodeMaxDepths: Record<string, number>
  nodeGlobalDepths: Record<string, number>
}

/**
 * Walks the tree and computes depth information for each node.
 *
 * maxDepth: the maximum decomposition depth at this node
 *   - A leaf with no children has maxDepth = 0
 *   - A node with children has maxDepth = 1 + max(children.maxDepth)
 * globalDepth: the depth from root (root = 0)
 */
function computeDepths(
  node: FragmentNode,
  globalDepth: number
): NodeDepthInfo {
  const nodeMaxDepths: Record<string, number> = {}
  const nodeGlobalDepths: Record<string, number> = {}

  nodeGlobalDepths[node.id] = globalDepth

  if (node.children.length === 0) {
    nodeMaxDepths[node.id] = 0
    return { maxDepth: 0, globalDepth, nodeMaxDepths, nodeGlobalDepths }
  }

  let childMaxDepth = 0
  for (const child of node.children) {
    const childInfo = computeDepths(child, globalDepth + 1)
    // First-visited wins for duplicate node ids — prevents silent overwrite
    for (const [id, depth] of Object.entries(childInfo.nodeMaxDepths)) {
      if (!(id in nodeMaxDepths)) nodeMaxDepths[id] = depth
    }
    for (const [id, depth] of Object.entries(childInfo.nodeGlobalDepths)) {
      if (!(id in nodeGlobalDepths)) nodeGlobalDepths[id] = depth
    }
    childMaxDepth = Math.max(childMaxDepth, childInfo.maxDepth)
  }

  const maxDepth = 1 + childMaxDepth
  nodeMaxDepths[node.id] = maxDepth

  return { maxDepth, globalDepth, nodeMaxDepths, nodeGlobalDepths }
}

export function useDecompositionDepth(
  tree: FragmentNode | null,
  _selectedNodeId: string | null
): NodeDepthInfo | null {
  return useMemo(() => {
    if (!tree) return null
    return computeDepths(tree, 0)
  }, [tree])
}