/**
 * Lineage DAG node — represents a fragment in the decomposition history.
 * Used by App.tsx to collect nodes from the parse tree for LineagePanel.
 */
export interface LineageNode {
  id: string
  label: string
  type: string
  parentIds: string[]
  transform?: {
    kind: 'DECOMPOSE'
    description: string
  }
}
