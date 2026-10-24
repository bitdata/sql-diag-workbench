import { tr, useLocale, getLocale } from '../../i18n'
import { useState, useMemo, useRef, useEffect } from 'react'
import * as Tooltip from '@radix-ui/react-tooltip'
import { useVirtualizer } from '@tanstack/react-virtual'
import { FragmentNode, DecomposeOption } from '../../types/fragment'
import FragmentTreeNode from './FragmentTreeNode'
import AddBackBanner, { type AddBackBannerState } from './AddBackBanner'
import { flattenTree, childKey } from './flattenTree'
import LineageChip from '../Lineage/LineageChip'
import type { EditMark, EditOriginMark } from '../../utils/editLinks'
import './FragmentTree.css'

interface FragmentTreeProps {
  tree: FragmentNode | null
  loading: boolean
  error: string | null
  warning?: string | null
  onRetry?: () => void
  selectedNodeId: string | null
  onSelectNode: (nodeId: string) => void
  onCompareNode?: (nodeId: string) => void
  optionsByNodeId: Record<string, DecomposeOption[]>
  optionsLoading: Record<string, boolean>
  decomposing: Record<string, boolean>
  onDecompose: (nodeId: string, ruleName: string) => void
  onEditNode?: (nodeId: string, sql: string, label: string) => void
  /** Spec §5.2 — opens the LineagePanel modal for the right-clicked node. */
  onViewLineage?: (nodeId: string) => void
  /** 以该切面为种子打开加回面板（spec §4.3.2）。 */
  onBuildUpward?: (nodeId: string) => void
  /** 加回探索态；`active: false` 或不传＝自上而下视图。 */
  addBack?: AddBackBannerState
  /** 加回投影解析不出的节点 id（`AddBackProjection.skipped`）：**唯一**消费点
   *  就是本组件空态下的降级提示（规则 #2，见下方 `addBack.active && !tree`
   *  分支）。在此之前该字段由 hook 产出、被 App 原样丢弃（评审 M-2）。 */
  addBackSkipped?: string[]
  /** Operation-tree path from root to the selected node. Rendered as a chip
   *  at the top of the panel (2026-06-19 spec §5.1 常驻 Lineage Chip；本迭代
   *  spec 2026-09-29 未改其位置). */
  lineageChain?: FragmentNode[]
  /** Called when the user clicks the LineageChip to open the full panel. */
  onOpenLineage?: () => void
  /** 按节点 id 索引的编辑标记（spec §6.5）。 */
  editMarks?: Map<string, EditMark>
  /** 跳到编辑产物的 Tab（标记为 link 时）。 */
  onJumpToEdit?: (sessionId: string) => void
  /** 跨 Tab 桩：选中切面若是编辑产物，指回其源 Tab（spec §6.5）。 */
  lineageOrigin?: EditOriginMark | null
  /** 跳到源 Tab。 */
  onOpenOrigin?: (sessionId: string) => void
  /**
   * Identity of the tree INSTANCE currently displayed (root id is content-derived
   * — see `useFragmentTree`'s `treeEpoch`), used to reset the expansion state
   * when a different tree arrives (another tab, or a re-parse of the same SQL —
   * rule #1 sweep).
   *
   * It is only ONE half of the reset key: the effect below also keys on
   * `addBack?.active`, because entering/leaving the exploration swaps the
   * displayed tree for a projection whose root is a different facet — that
   * transition must clear the expansion, and `treeInstanceKey` alone does not
   * change there. (Omitting the prop is what the unit tests do: the effect then
   * resets only on `addBack?.active`.)
   */
  treeInstanceKey?: string | number
  width?: number
  revealRequest?: { id: string; sequence: number } | null
}

/** 默认展开集合，按投影路径键索引（不是节点 id —— 同一 fragment 可出现在两个
 *  位置，spec §4.3.2）。 */
function getDefaultExpandedKeys(tree: FragmentNode | null): Set<string> {
  if (!tree) return new Set()
  const keys = new Set<string>()
  function walk(node: FragmentNode, parentKey: string | null, depth: number) {
    const key = childKey(parentKey, node.id)
    if (depth < 2) keys.add(key)
    node.children.forEach((c) => walk(c, key, depth + 1))
  }
  walk(tree, null, 0)
  return keys
}

export default function FragmentTree({
  tree,
  loading,
  error,
  warning,
  onRetry,
  selectedNodeId,
  onSelectNode,
  onCompareNode,
  optionsByNodeId,
  optionsLoading,
  decomposing,
  onDecompose,
  onEditNode,
  onViewLineage,
  onBuildUpward,
  addBack,
  addBackSkipped,
  lineageChain,
  onOpenLineage,
  editMarks,
  onJumpToEdit,
  lineageOrigin,
  onOpenOrigin,
  treeInstanceKey,
  width,
  revealRequest,
}: FragmentTreeProps) {
  useLocale()
  const scrollRef = useRef<HTMLDivElement>(null)
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(() => getDefaultExpandedKeys(tree))

  // Track which nodes previously had children, so we can auto-expand
  // nodes that just gained children from decomposition (not on manual collapse).
  // Keyed by projection path key, not node id (spec §4.3.2 diamond projection).
  const prevHasChildrenRef = useRef<Map<string, boolean>>(new Map())

  // Reset expanded state when a new tree INSTANCE arrives.
  //
  // Rule #1 sweep (fix round 1): this used to key on `tree?.id`, which is the
  // root facet's CONTENT-derived id — so a re-parse of the same SQL (or another
  // tab holding the same SQL) left the previous instance's expansion set in
  // place. `addBack?.active` is part of the key because the displayed tree is
  // swapped for the projection when the exploration starts/stops, and the
  // projection's root is a different facet — the old key cleared expansion at
  // exactly those two transitions, and that behaviour is kept.
  useEffect(() => {
    setExpandedKeys(getDefaultExpandedKeys(tree))
    prevHasChildrenRef.current = new Map()
  }, [treeInstanceKey, addBack?.active])

  // Auto-expand nodes that just gained children (decomposition result).
  // Only expands on the no-children → has-children transition,
  // so user-initiated collapses are preserved.
  useEffect(() => {
    if (!tree) return
    const newHasChildren = new Map<string, boolean>()
    const toExpand: string[] = []

    function walk(node: FragmentNode, parentKey: string | null) {
      const key = childKey(parentKey, node.id)
      const has = node.children.length > 0
      newHasChildren.set(key, has)
      const prev = prevHasChildrenRef.current.get(key) ?? false
      if (has && !prev) toExpand.push(key)
      node.children.forEach((c) => walk(c, key))
    }
    walk(tree, null)

    prevHasChildrenRef.current = newHasChildren

    if (toExpand.length > 0) {
      setExpandedKeys((prev) => {
        const next = new Set(prev)
        for (const key of toExpand) next.add(key)
        return next
      })
    }
  }, [tree])

  const flatNodes = useMemo(() => flattenTree(tree ? [tree] : [], expandedKeys), [tree, expandedKeys, getLocale()])

  const virtualizer = useVirtualizer({
    count: flatNodes.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 34,
    overscan: 5,
  })

  useEffect(() => {
    if (!revealRequest || !tree) return
    const path = (node: FragmentNode, parent: string | null): string[] | null => {
      const key = childKey(parent, node.id)
      if (node.id === revealRequest.id) return [key]
      for (const child of node.children) { const found = path(child, key); if (found) return [key, ...found] }
      return null
    }
    const keys = path(tree, null)
    if (keys) setExpandedKeys(prev => new Set([...prev, ...keys]))
  }, [revealRequest, tree, addBack?.active])
  useEffect(() => {
    if (!revealRequest) return
    const index = flatNodes.findIndex(n => n.id === revealRequest.id)
    if (index >= 0) virtualizer.scrollToIndex(index, { align: 'center' })
  }, [revealRequest, flatNodes, virtualizer])

  const handleToggleExpand = (key: string) => {
    setExpandedKeys((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }
  return (
    <aside className="left-panel" style={width ? { width, flexShrink: 0 } : undefined}>
      <div className="panel-header">
        <span className="panel-title">{tr("Exploration Path")}</span>
      </div>
      {addBack?.active && (
        <AddBackBanner
          seedLabel={addBack.seedLabel}
          breadcrumb={addBack.breadcrumb}
          reachesOrigin={addBack.reachesOrigin}
          isOriginRoot={addBack.isOriginRoot}
          degraded={addBack.degraded}
          reason={addBack.reason}
          onExit={addBack.onExit}
        />
      )}
      {/* 2026-06-19 spec §5.1 — single-line lineage chip at top of FragmentTree
          (this iteration's spec does not move it). */}
      {lineageChain && onOpenLineage && (
        <div className="lineage-chip-host" style={{ padding: '6px 8px 0' }}>
          <LineageChip chain={lineageChain} onOpen={onOpenLineage} origin={lineageOrigin} onOpenOrigin={onOpenOrigin} />
        </div>
      )}
      <Tooltip.Provider delayDuration={500} skipDelayDuration={200}>
        {loading && (
          <div className="tree-loading" aria-busy="true">
            {[1, 2, 3].map((i) => (
              <div key={i} className="tree-skeleton-node">
                <span className="skeleton-icon" />
                <span className="skeleton-label" style={{ width: `${60 + i * 20}px` }} />
              </div>
            ))}
          </div>
        )}
        {!loading && error && (
          <div className="tree-error" role="alert">
            <span className="error-icon">⚠</span>
            <span className="error-message">{error.split(/\r?\n|Was expecting/i)[0]}</span>
            <details><summary>{tr("Parser details")}</summary><pre>{error}</pre></details>
            <button
              className="retry-btn"
              onClick={onRetry}
              disabled={!onRetry}
            >
              {tr("Retry")}{" "}</button>
          </div>
        )}
        {!loading && warning && (
          <div className="tree-warning" role="status">
            <strong>{tr("Some decomposition candidates are unavailable")}</strong>
            <span>{tr("The parsed query remains available. Other decomposition options can still be used.")}</span>
            {warning.includes('UNRESOLVED_DEPENDENCY') && (
              <span>{tr("Qualify columns with their table or CTE alias, for example table_alias.column_name, when column ownership is unclear.")}</span>
            )}
            <details><summary>{tr("Decomposition details")}</summary><pre>{warning}</pre></details>
          </div>
        )}
        {/* Rule #2 (fix round 1, I1-b): add-back is ACTIVE but the projection
            resolved to no tree. Before this branch the panel fell through to the
            "Paste a SQL query" placeholder — i.e. it told the user to parse
            something while a parse result existed and a banner showed a raw
            64-hex seed id. `skipped` (the seed, plus any dangling parent) names
            what is missing, so the state is stated instead of mislabelled.
            Reachability after the tree-instance key fix: NOT reachable from the
            UI (the exploration now exits on the very render where the DAG is
            replaced, and `enter` can only be called from a rendered tree row, so
            the seed is always in the DAG). Kept as the contract-level surface for
            `AddBackProjection.tree === null` so the render site can never regress
            into the placeholder. */}
        {!loading && !tree && !error && addBack?.active && (
          <div className="tree-empty tree-empty--addback" role="alert">
            <p className="placeholder-text">{tr("Add-back exploration has nothing to show")}</p>
            <p className="placeholder-hint">
              {addBackSkipped?.length
                ? tr("This tab's DAG no longer contains: {0}.", addBackSkipped.join(', '))
                : tr("The projection resolved to no tree.")}
            </p>
            <p className="placeholder-hint">
              {tr("Use “Back to top-down” in the banner above to leave the exploration.")}{" "}</p>
          </div>
        )}
        {!loading && !tree && !error && !addBack?.active && (
          <div className="tree-empty">
            <p className="placeholder-text">{tr("Paste a SQL query")}</p>
            <p className="placeholder-hint">{tr("and press Ctrl+Shift+Enter to explore")}</p>
          </div>
        )}
        {!loading && tree && (
          <div ref={scrollRef} className="tree-content" role="tree" aria-label={tr("SQL Fragment Tree")}>
            <div style={{ height: virtualizer.getTotalSize() }}>
              {virtualizer.getVirtualItems().map((item) => {
                const flat = flatNodes[item.index]
                return (
                  <FragmentTreeNode
                    key={flat.key}
                    node={flat.node}
                    depth={flat.depth}
                    isExpanded={flat.isExpanded}
                    hasChildren={flat.hasChildren}
                    onToggleExpand={() => handleToggleExpand(flat.key)}
                    selectedNodeId={selectedNodeId}
                    onSelectNode={onSelectNode}
                    onCompareNode={onCompareNode}
                    optionsByNodeId={optionsByNodeId}
                    optionsLoading={optionsLoading}
                    decomposing={decomposing}
                    onDecompose={onDecompose}
                    onEditNode={onEditNode}
                    onViewLineage={onViewLineage}
                    onBuildUpward={onBuildUpward}
                    editMarks={editMarks}
                    onJumpToEdit={onJumpToEdit}
                    originMark={
                      addBack?.active && flat.depth === 0
                        ? (addBack.isOriginRoot ? 'origin' : 'not-restored')
                        : null
                    }
                  />
                )
              })}
            </div>
          </div>
        )}
      </Tooltip.Provider>
    </aside>
  )
}
