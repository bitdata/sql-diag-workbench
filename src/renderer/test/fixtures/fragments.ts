import type { FragmentNode, DecomposeOption } from '../../types/fragment'

/*
 * NOTE: children are declared BEFORE the root node to avoid a temporal dead
 * zone bug. const bindings are not hoisted, so referencing mockJoinChild /
 * mockWhereChild inside mockRootNode's initializer would throw at module load.
 */

const mockJoinChild: FragmentNode = {
  id: 'node-join',
  type: 'JOIN_SPLIT',
  label: 'orders',
  sqlFragment: 'SELECT * FROM orders',
  executable: true,
  executionHint: null,
  nodeCount: 0,
  ruleName: 'join-split',
  children: [],
  parentIds: ['node-root'],
}

const mockWhereChild: FragmentNode = {
  id: 'node-where',
  type: 'STRIPPED',
  label: 'WHERE clause',
  sqlFragment: "WHERE orders.status = 'active'",
  executable: false,
  executionHint: null,
  nodeCount: 0,
  ruleName: 'clause-stripping',
  children: [],
  parentIds: ['node-root'],
}

export const mockRootNode: FragmentNode = {
  id: 'node-root',
  type: 'ORIGINAL',
  label: 'Full Query',
  sqlFragment:
    'SELECT * FROM orders JOIN customers ON orders.customer_id = customers.id',
  executable: true,
  executionHint: null,
  nodeCount: 3,
  ruleName: null,
  children: [mockJoinChild, mockWhereChild],
  parentIds: [],
}

/** Build a fresh tree on demand — useful when tests mutate the returned node. */
export function buildMockTree(): FragmentNode {
  return {
    ...mockRootNode,
    children: [
      { ...mockJoinChild },
      { ...mockWhereChild },
    ],
  }
}

export const mockDecomposeOptions: DecomposeOption[] = [
  {
    ruleName: 'join-split',
    displayName: 'Strip JOIN',
    description: 'Split into individual tables',
    level: 'STRUCTURE',
  },
  {
    ruleName: 'clause-stripping',
    displayName: 'Strip WHERE',
    description: 'Remove WHERE clause',
    level: 'CLAUSE',
  },
]
