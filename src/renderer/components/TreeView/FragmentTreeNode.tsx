import { tr, useLocale } from '../../i18n'
import { memo, useCallback, useState, useRef, useEffect } from 'react'
import { FragmentNode, DecomposeOption, getFragmentIcon, getFragmentBadge, isDegraded, getDegradedReason } from '../../types/fragment'
import NodeTooltip from './NodeTooltip'
import type { EditMark } from '../../utils/editLinks'
import './FragmentTree.css'

interface FragmentTreeNodeProps {
  node: FragmentNode
  depth: number
  isExpanded: boolean
  hasChildren: boolean
  onToggleExpand: () => void
  selectedNodeId: string | null
  onSelectNode: (nodeId: string) => void
  onCompareNode?: (nodeId: string) => void
  optionsByNodeId: Record<string, DecomposeOption[]>
  optionsLoading: Record<string, boolean>
  decomposing: Record<string, boolean>
  onDecompose: (nodeId: string, ruleName: string) => void
  onEditNode?: (nodeId: string, sql: string, label: string) => void
  onViewLineage?: (nodeId: string) => void
  /** 以该切面为种子打开加回面板（spec §4.3.2）。 */
  onBuildUpward?: (nodeId: string) => void
  /** spec §4.3.4 #2：显示树的**根**才带标注 —— 该根就是 DAG 根（反查索引中无入边）时写
   *  「原 SQL」，否则写「未还原到原 SQL」。非根行传 null；自上而下视图恒为 null。
   *  沿用 Task 3 `AddBackProjection.isOriginRoot` / Task 4 hook 的同名字段语义。 */
  originMark?: 'origin' | 'not-restored' | null
  /** 按**节点 id** 索引的编辑标记（spec §6.5）。 */
  editMarks?: Map<string, EditMark>
  /** 跳到编辑产物的 Tab（标记为 link 时）。 */
  onJumpToEdit?: (sessionId: string) => void
}

const levelOrder = ['structure', 'clause', 'predicate'] as const
const levelLabels: Record<string, string> = {
  structure: 'Structure',
  clause: 'Clause',
  predicate: 'Predicate'
}

const FragmentTreeNode = memo(function FragmentTreeNode({
  node,
  depth,
  isExpanded,
  hasChildren,
  onToggleExpand,
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
  originMark,
  editMarks,
  onJumpToEdit
}: FragmentTreeNodeProps) {
  useLocale()
  const isSelected = node.id === selectedNodeId
  const editMark = editMarks?.get(node.id) ?? null
  const originMarkText =
    originMark === 'origin' ? tr("Original SQL") : originMark === 'not-restored' ? tr("Not restored to original SQL") : null

  const degraded = isDegraded(node)
  const degradedReason = getDegradedReason(node)

  const nodeOptions = optionsByNodeId[node.id] ?? []
  const nodeOptionsLoading = optionsLoading[node.id] ?? false
  const nodeDecomposing = decomposing[node.id] ?? false

  const [contextPos, setContextPos] = useState<{x: number, y: number} | null>(null)
  const rowRef = useRef<HTMLDivElement>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  const handleClick = useCallback((event: React.MouseEvent) => {
    if ((event.ctrlKey || event.metaKey) && onCompareNode) onCompareNode(node.id)
    else onSelectNode(node.id)
  }, [node.id, onSelectNode, onCompareNode])

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation()
    if (hasChildren) {
      onToggleExpand()
    }
  }, [hasChildren, onToggleExpand])

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.target !== e.currentTarget) return
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const nodes = Array.from(e.currentTarget.closest('[role=tree]')?.querySelectorAll<HTMLElement>('[role=treeitem]') ?? [])
      nodes[nodes.indexOf(e.currentTarget as HTMLElement) + (e.key === 'ArrowDown' ? 1 : -1)]?.focus()
    } else if (e.shiftKey && e.key === 'F10') {
      e.preventDefault(); const rect = e.currentTarget.getBoundingClientRect(); setContextPos({ x: rect.left, y: rect.bottom })
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      onSelectNode(node.id)
    } else if (e.key === 'ArrowRight' && hasChildren && !isExpanded) {
      e.preventDefault()
      onToggleExpand()
    } else if (e.key === 'ArrowLeft' && hasChildren && isExpanded) {
      e.preventDefault()
      onToggleExpand()
    }
  }, [node.id, onSelectNode, hasChildren, isExpanded, onToggleExpand])

  const handleContextMenu = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    // Also select the node on right-click
    onSelectNode(node.id)
    setContextPos({x: e.clientX, y: e.clientY})
  }, [node.id, onSelectNode])

  const closeContextMenu = useCallback(() => {
    setContextPos(null)
    rowRef.current?.focus()
  }, [])

  // Close context menu on click outside or Escape
  useEffect(() => {
    if (!contextPos) return

    const menu = menuRef.current
    if (menu) {
      const rect = menu.getBoundingClientRect()
      menu.style.left = `${Math.max(4, Math.min(contextPos.x, window.innerWidth - rect.width - 4))}px`
      menu.style.top = `${Math.max(4, Math.min(contextPos.y, window.innerHeight - rect.height - 4))}px`
      menu.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
    }
    const handleMouseDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        closeContextMenu()
      }
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        closeContextMenu()
      }
    }
    // Delay to avoid the right-click event from immediately closing
    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleMouseDown)
    }, 0)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      clearTimeout(timer)
      document.removeEventListener('mousedown', handleMouseDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [contextPos, closeContextMenu])

  // Group decompose options by level
  const groupedOptions = nodeOptions.reduce<Record<string, DecomposeOption[]>>((acc, opt) => {
    const key = opt.level.toLowerCase()
    if (!acc[key]) acc[key] = []
    acc[key].push(opt)
    return acc
  }, {})

  const hasAnyOptions = Object.keys(groupedOptions).length > 0
  const hasEditOrHistory = !!(onEditNode || onViewLineage)

  const handleMenuItemClick = useCallback((action: () => void) => {
    action()
    closeContextMenu()
  }, [closeContextMenu])

  const nodeContent = (
    <NodeTooltip node={node}>
      <div
        ref={rowRef}
        className={`tree-node ${isSelected ? 'selected' : ''}`}
        style={{ ['--tree-depth' as string]: depth }}
        role="treeitem"
        aria-expanded={hasChildren ? isExpanded : undefined}
        aria-selected={isSelected}
        tabIndex={0}
        onClick={handleClick}
        onKeyDown={handleKeyDown}
      >
        <span
          className={`tree-chevron ${hasChildren ? (isExpanded ? 'expanded' : 'collapsed') : 'hidden'}`}
          onClick={handleToggle}
          aria-hidden={!hasChildren}
        >
          {hasChildren ? '▾' : ''}
        </span>
        <span className={`tree-node-icon ${!hasAnyOptions && !hasChildren ? 'muted' : ''}`} aria-hidden="true">
          {getFragmentIcon(node.type)}
        </span>
        <span className="tree-node-label" title={node.label}>{node.label}</span>
        {originMarkText && (
          <span className="tree-node-origin-mark" title={originMarkText}>{originMarkText}</span>
        )}
        <button className="tree-node-actions toolbar-btn" aria-label={tr("Actions for {0}", node.label)} aria-haspopup="menu" onClick={event => {
          event.stopPropagation(); onSelectNode(node.id); const rect = event.currentTarget.getBoundingClientRect(); setContextPos({ x: rect.left, y: rect.bottom })
        }}>⋯</button>
        <span className="tree-node-badge">
          {getFragmentBadge(node)}
        </span>
        {editMark && (
          editMark.targetSessionId && onJumpToEdit ? (
            <button
              type="button"
              className="tree-node-edit-mark"
              title={editMark.text}
              onClick={(e) => {
                e.stopPropagation()
                onJumpToEdit(editMark.targetSessionId as string)
              }}
            >
              {editMark.text}
            </button>
          ) : (
            <span className="tree-node-edit-mark" title={editMark.text}>{editMark.text}</span>
          )
        )}
        {degraded && (
          <span className="tree-node-degraded-mark" title={degradedReason ?? undefined}>{tr("Identity degraded")}</span>
        )}
        {nodeDecomposing && (
          <span className="tree-node-spinner" aria-label={tr("Decomposing...")} />
        )}
      </div>
    </NodeTooltip>
  )

  return (
    <div className="tree-node-group" role="group" onContextMenu={handleContextMenu}>
      {nodeContent}

      {/* Custom context menu positioned at cursor */}
      {contextPos && (
        <div
          ref={menuRef}
          role="menu"
          onKeyDown={event => {
            const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
            const i = buttons.indexOf(document.activeElement as HTMLButtonElement)
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault(); event.stopPropagation(); buttons[(i + (event.key === 'ArrowDown' ? 1 : buttons.length - 1)) % buttons.length]?.focus()
            }
          }}
          className="context-menu"
          style={{
            position: 'fixed',
            left: contextPos.x,
            top: contextPos.y,
            zIndex: 9999
          }}
        >
          {onCompareNode && <button role="menuitem" className="context-menu-item"
            onClick={() => handleMenuItemClick(() => onCompareNode(node.id))}>{tr("Compare fragment")}</button>}
          {onEditNode && (
            <button
              role="menuitem"
              className="context-menu-item"
              onClick={() => handleMenuItemClick(() => onEditNode(node.id, node.sqlFragment, node.label))}
            >
              <span className="context-menu-icon">✎</span>
              {tr("Edit this fragment")}{" "}</button>
          )}
          {onViewLineage && (
            <button
              role="menuitem"
              className="context-menu-item"
              onClick={() => handleMenuItemClick(() => onViewLineage(node.id))}
            >
              <span className="context-menu-icon">⏱</span>
              {tr("View lineage")}{" "}</button>
          )}
          {onBuildUpward && (
            <button
              role="menuitem"
              className="context-menu-item"
              onClick={() => handleMenuItemClick(() => onBuildUpward(node.id))}
              title={tr("Add this facet's parent facets back, one hop at a time")}
            >
              <span className="context-menu-icon">⤴</span>
              {tr("Build upward from here")}{" "}</button>
          )}

          {/* Decompose options */}
          {(hasAnyOptions || nodeOptionsLoading) && hasEditOrHistory && (
            <div className="context-menu-divider" />
          )}

          {nodeOptionsLoading && (
            <div className="context-menu-loading">{tr("Checking available rules...")}</div>
          )}

          {hasAnyOptions && levelOrder.map((level) => {
            const options = groupedOptions[level]
            if (!options || options.length === 0) return null
            return (
              <div key={level}>
                <div className="context-menu-label">{tr(levelLabels[level])}</div>
                {options.map((opt) => (
                  <button
                    role="menuitem"
                    key={opt.ruleName}
                    className="context-menu-item"
                    onClick={() => handleMenuItemClick(() => onDecompose(node.id, opt.ruleName))}
                    disabled={nodeDecomposing}
                  >
                    <span className="context-menu-icon">⚡</span>
                    {tr(opt.displayName)}
                  </button>
                ))}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
})

export default FragmentTreeNode
