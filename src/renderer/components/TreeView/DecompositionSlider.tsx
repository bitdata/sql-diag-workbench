import { tr, useLocale, getLocale } from '../../i18n'
import { useState, useCallback, useMemo } from 'react'
import * as Slider from '@radix-ui/react-slider'
import { FragmentNode, DecomposeOption, getFragmentIcon } from '../../types/fragment'
import { useDecompositionDepth, NodeDepthInfo } from '../../hooks/useDecompositionDepth'
import ViewModeToggle from './ViewModeToggle'
import './DecompositionSlider.css'

interface DecompositionSliderProps {
  tree: FragmentNode | null
  loading: boolean
  error: string | null
  selectedNodeId: string | null
  onSelectNode: (nodeId: string) => void
  optionsByNodeId: Record<string, DecomposeOption[]>
  optionsLoading: Record<string, boolean>
  decomposing: Record<string, boolean>
  onDecompose: (nodeId: string, ruleName: string) => void
  viewMode: 'tree' | 'slider'
  onViewModeChange: (mode: 'tree' | 'slider') => void
}

interface ChildSliderState {
  depth: number
  expanded: boolean
}

interface SliderTreeNodeProps {
  node: FragmentNode
  depth: number
  selectedNodeId: string | null
  onSelectNode: (nodeId: string) => void
  optionsByNodeId: Record<string, DecomposeOption[]>
  optionsLoading: Record<string, boolean>
  decomposing: Record<string, boolean>
  onDecompose: (nodeId: string, ruleName: string) => void
  expandedNodes: Set<string>
  onToggleNode: (nodeId: string) => void
  globalDepth: number
  depthInfo: NodeDepthInfo
  childSliders: Map<string, ChildSliderState>
  onChildSliderChange: (nodeId: string, state: ChildSliderState) => void
  onChildSliderToggle: (nodeId: string) => void
}

const LEVEL_COLORS: Record<string, string> = {
  STRUCTURE: 'level-structure',
  CLAUSE: 'level-clause',
  PREDICATE: 'level-predicate',
}

const LEVEL_LABELS: Record<string, string> = {
  STRUCTURE: 'Structure',
  CLAUSE: 'Clause',
  PREDICATE: 'Predicate',
}

function ChildInlineSlider({
  nodeId,
  childDepth,
  maxChildDepth,
  onDepthChange,
}: {
  nodeId: string
  childDepth: number
  maxChildDepth: number
  onDepthChange: (nodeId: string, depth: number) => void
}) {
  useLocale()
  const handleValueChange = (values: number[]) => {
    onDepthChange(nodeId, values[0])
  }

  return (
    <div className="child-slider">
      <Slider.Root
        className="child-slider-range"
        min={0}
        max={maxChildDepth}
        step={1}
        value={[childDepth]}
        onValueChange={handleValueChange}
        aria-label={tr("Child decomposition depth for {0}", nodeId)}
      >
        <Slider.Track className="child-slider-track">
          <Slider.Range className="child-slider-range-fill" />
        </Slider.Track>
        <Slider.Thumb className="child-slider-thumb" />
      </Slider.Root>
      <div className="child-slider-markers">
        {Array.from({ length: maxChildDepth + 1 }, (_, i) => (
          <span key={i} className={`child-slider-mark ${i <= childDepth ? 'active' : ''}`} />
        ))}
      </div>
    </div>
  )
}

function SliderTreeNode({
  node,
  depth,
  selectedNodeId,
  onSelectNode,
  optionsByNodeId,
  optionsLoading,
  decomposing,
  onDecompose,
  expandedNodes,
  onToggleNode,
  globalDepth,
  depthInfo,
  childSliders,
  onChildSliderChange,
  onChildSliderToggle
}: SliderTreeNodeProps) {
  useLocale()
  const isExpanded = expandedNodes.has(node.id)
  const isSelected = node.id === selectedNodeId
  const nodeMaxDepth = depthInfo.nodeMaxDepths[node.id] ?? 0

  const options = optionsByNodeId[node.id] ?? []
  const isLoading = optionsLoading[node.id]
  const isDecomposing = decomposing[node.id]

  const groupedOptions = useMemo(() => {
    const structure: DecomposeOption[] = []
    const clause: DecomposeOption[] = []
    const predicate: DecomposeOption[] = []
    for (const opt of options) {
      if (opt.level === 'STRUCTURE') structure.push(opt)
      else if (opt.level === 'CLAUSE') clause.push(opt)
      else if (opt.level === 'PREDICATE') predicate.push(opt)
    }
    return { structure, clause, predicate }
  }, [options, getLocale()])

  const hasAppliedChildren = node.children.length > 0
  const hasAnyOptions = options.length > 0 || isLoading

  const childSliderState = childSliders.get(node.id)
  const isChildSliderExpanded = childSliderState?.expanded ?? false
  const childSliderDepth = childSliderState?.depth ?? 0

  const nodeLevel = useMemo(() => {
    const opt = options.find(o => o.level)
    return opt?.level ?? 'STRUCTURE'
  }, [options, getLocale()])

  const handleToggle = useCallback(() => {
    onToggleNode(node.id)
  }, [node.id, onToggleNode])

  const handleChildSliderToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation()
    onChildSliderToggle(node.id)
  }, [node.id, onChildSliderToggle])

  const handleChildDepthChange = useCallback((nId: string, d: number) => {
    const current = childSliders.get(nId) ?? { depth: 0, expanded: true }
    onChildSliderChange(nId, { ...current, depth: d })
  }, [childSliders, onChildSliderChange])

  return (
    <div className="slider-node" style={{ ['--tree-depth' as string]: depth }}>
      <div
        className={`slider-node-row ${isSelected ? 'selected' : ''} ${LEVEL_COLORS[nodeLevel] ?? ''}`}
        onClick={() => onSelectNode(node.id)}
        role="treeitem"
        aria-selected={isSelected}
        aria-expanded={hasAppliedChildren ? isExpanded : undefined}
      >
        <button
          className={`slider-chevron ${hasAppliedChildren ? (isExpanded ? 'expanded' : 'collapsed') : 'hidden'}`}
          onClick={(e) => { e.stopPropagation(); handleToggle() }}
          aria-label={isExpanded ? tr("Collapse") : tr("Expand")}
        >
          &#9654;
        </button>

        <span className={`slider-node-icon ${!hasAnyOptions && !hasAppliedChildren ? 'muted' : ''}`}>{getFragmentIcon(node.type)}</span>
        <span className="slider-node-label" title={node.label}>{node.label}</span>

        {hasAppliedChildren && (
          <span className="slider-applied-dot" title={`${node.children.length} variant(s) applied`} />
        )}

        {hasAppliedChildren && (
          <button
            className={`child-slider-toggle ${isChildSliderExpanded ? 'expanded' : ''}`}
            onClick={handleChildSliderToggle}
            title={isChildSliderExpanded ? tr("Collapse child slider") : tr("Expand child slider")}
            aria-label={tr("Toggle child slider")}
          >
            <span className="child-slider-toggle-icon">{isChildSliderExpanded ? '▼' : '▶'}</span>
          </button>
        )}

        {isDecomposing && <span className="slider-spinner" aria-label={tr("Decomposing")} />}
      </div>

      {hasAppliedChildren && isChildSliderExpanded && (
        <div className="child-slider-container" style={{ ['--tree-depth' as string]: depth + 1 }}>
          <ChildInlineSlider
            nodeId={node.id}
            childDepth={childSliderDepth}
            maxChildDepth={nodeMaxDepth}
            onDepthChange={handleChildDepthChange}
          />
        </div>
      )}

      {nodeMaxDepth > 0 && (
        <div className="slider-depth-bar" style={{ marginLeft: depth * 16 + 24 }}>
          <div className="slider-depth-track">
            {Array.from({ length: nodeMaxDepth + 1 }, (_, i) => (
              <div
                key={i}
                className={`slider-depth-mark ${i <= node.children.length - 1 ? 'filled' : ''}`}
                title={tr("Depth {0}", i)}
              />
            ))}
            <div
              className="slider-depth-fill"
              style={{ width: `${Math.min(100, (node.children.length / nodeMaxDepth) * 100)}%` }}
            />
          </div>
          <span className="slider-depth-label">{node.children.length}/{nodeMaxDepth}</span>
        </div>
      )}

      {!isDecomposing && (options.length > 0 || isLoading) && (
        <div className="slider-options" style={{ ['--tree-depth' as string]: depth + 1 }}>
          {isLoading && <span className="slider-options-loading">{tr("Loading rules...")}</span>}
          {!isLoading && options.length === 0 && node.children.length === 0 && (
            <span className="slider-options-empty">{tr("No rules available")}</span>
          )}
          {!isLoading && groupedOptions.structure.length > 0 && (
            <div className="slider-option-group">
              <span className={`slider-option-group-label ${LEVEL_COLORS.STRUCTURE}`}>
                {LEVEL_LABELS.STRUCTURE}
              </span>
              {groupedOptions.structure.map((opt) => (
                <button
                  key={opt.ruleName}
                  className="slider-rule-btn"
                  onClick={() => onDecompose(node.id, opt.ruleName)}
                  title={tr(opt.description)}
                >
                  <span className="slider-rule-dot" />
                  <span>{tr(opt.displayName)}</span>
                </button>
              ))}
            </div>
          )}
          {!isLoading && groupedOptions.clause.length > 0 && (
            <div className="slider-option-group">
              <span className={`slider-option-group-label ${LEVEL_COLORS.CLAUSE}`}>
                {LEVEL_LABELS.CLAUSE}
              </span>
              {groupedOptions.clause.map((opt) => (
                <button
                  key={opt.ruleName}
                  className="slider-rule-btn"
                  onClick={() => onDecompose(node.id, opt.ruleName)}
                  title={tr(opt.description)}
                >
                  <span className="slider-rule-dot" />
                  <span>{tr(opt.displayName)}</span>
                </button>
              ))}
            </div>
          )}
          {!isLoading && groupedOptions.predicate.length > 0 && (
            <div className="slider-option-group">
              <span className={`slider-option-group-label ${LEVEL_COLORS.PREDICATE}`}>
                {LEVEL_LABELS.PREDICATE}
              </span>
              {groupedOptions.predicate.map((opt) => (
                <button
                  key={opt.ruleName}
                  className="slider-rule-btn"
                  onClick={() => onDecompose(node.id, opt.ruleName)}
                  title={tr(opt.description)}
                >
                  <span className="slider-rule-dot" />
                  <span>{tr(opt.displayName)}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}

      {hasAppliedChildren && isExpanded && (
        <div className="slider-children" role="group">
          {node.children.map((child) => (
            <SliderTreeNode
              key={child.id}
              node={child}
              depth={depth + 1}
              selectedNodeId={selectedNodeId}
              onSelectNode={onSelectNode}
              optionsByNodeId={optionsByNodeId}
              optionsLoading={optionsLoading}
              decomposing={decomposing}
              onDecompose={onDecompose}
              expandedNodes={expandedNodes}
              onToggleNode={onToggleNode}
              globalDepth={globalDepth}
              depthInfo={depthInfo}
              childSliders={childSliders}
              onChildSliderChange={onChildSliderChange}
              onChildSliderToggle={onChildSliderToggle}
            />
          ))}
        </div>
      )}
    </div>
  )
}

export default function DecompositionSlider({
  tree,
  loading,
  error,
  selectedNodeId,
  onSelectNode,
  optionsByNodeId,
  optionsLoading,
  decomposing,
  onDecompose,
  viewMode,
  onViewModeChange
}: DecompositionSliderProps) {
  useLocale()
  const depthInfo = useDecompositionDepth(tree, selectedNodeId)

  const globalDepth = useMemo(() => {
    if (!tree || !selectedNodeId || !depthInfo) return 0
    return depthInfo.nodeGlobalDepths[selectedNodeId] ?? 0
  }, [tree, selectedNodeId, depthInfo, getLocale()])

  const maxGlobalDepth = depthInfo?.maxDepth ?? 0

  const [expandedNodes, setExpandedNodes] = useState<Set<string>>(() => {
    if (!tree) return new Set()
    const expanded = new Set<string>()
    function walk(n: FragmentNode) {
      if (n.children.length > 0) expanded.add(n.id)
      n.children.forEach(walk)
    }
    walk(tree)
    return expanded
  })

  const handleToggleNode = useCallback((nodeId: string) => {
    setExpandedNodes((prev) => {
      const next = new Set(prev)
      if (next.has(nodeId)) next.delete(nodeId)
      else next.add(nodeId)
      return next
    })
  }, [])

  const [childSliders, setChildSliders] = useState<Map<string, ChildSliderState>>(() => new Map())

  const handleChildSliderChange = useCallback((nodeId: string, state: ChildSliderState) => {
    setChildSliders((prev) => {
      const next = new Map(prev)
      next.set(nodeId, state)
      return next
    })
  }, [])

  const handleChildSliderToggle = useCallback((nodeId: string) => {
    setChildSliders((prev) => {
      const next = new Map(prev)
      const current = next.get(nodeId)
      next.set(nodeId, { depth: current?.depth ?? 0, expanded: !current?.expanded })
      return next
    })
  }, [])

  const handleGlobalDepthChange = (values: number[]) => {
    const newDepth = values[0]
    const newExpanded = new Set<string>()
    function expandToDepth(n: FragmentNode, current: number) {
      if (current <= newDepth && n.children.length > 0) {
        newExpanded.add(n.id)
        n.children.forEach(c => expandToDepth(c, current + 1))
      }
    }
    if (tree) expandToDepth(tree, 0)
    setExpandedNodes(newExpanded)
  }

  return (
    <aside className="left-panel">
      <div className="panel-header">
        <span className="panel-title">{tr("Exploration Path")}</span>
        <ViewModeToggle mode={viewMode} onChange={onViewModeChange} />
      </div>

      {tree && maxGlobalDepth > 0 && (
        <div className="slider-global">
          <div className="slider-global-header">
            <span className="slider-global-label">{tr("Depth")}</span>
            <span className="slider-global-value">{globalDepth} / {maxGlobalDepth}</span>
          </div>
          <Slider.Root
            className="slider-global-range"
            min={0}
            max={maxGlobalDepth}
            step={1}
            value={[globalDepth]}
            onValueChange={handleGlobalDepthChange}
            aria-label={tr("Global decomposition depth")}
          >
            <Slider.Track className="slider-global-track">
              <Slider.Range className="slider-global-range-fill" />
            </Slider.Track>
            <Slider.Thumb className="slider-global-thumb" />
          </Slider.Root>
          <div className="slider-global-markers">
            {Array.from({ length: maxGlobalDepth + 1 }, (_, i) => (
              <span key={i} className={`slider-global-mark ${i <= globalDepth ? 'active' : ''}`} />
            ))}
          </div>
        </div>
      )}

      <div className="tree-content" role="tree" aria-label={tr("SQL Decomposition Slider")}>
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
        {!loading && error && !tree && (
          <div className="tree-error" role="alert">
            <span className="error-icon">&#9888;</span>
            <span className="error-message">{error}</span>
          </div>
        )}
        {!loading && !tree && !error && (
          <div className="tree-empty">
            <p className="placeholder-text">{tr("Paste a SQL query")}</p>
            <p className="placeholder-hint">{tr("and press Ctrl+Shift+Enter to explore")}</p>
          </div>
        )}
        {!loading && tree && depthInfo && (
          <SliderTreeNode
            node={tree}
            depth={0}
            selectedNodeId={selectedNodeId}
            onSelectNode={onSelectNode}
            optionsByNodeId={optionsByNodeId}
            optionsLoading={optionsLoading}
            decomposing={decomposing}
            onDecompose={onDecompose}
            expandedNodes={expandedNodes}
            onToggleNode={handleToggleNode}
            globalDepth={globalDepth}
            depthInfo={depthInfo}
            childSliders={childSliders}
            onChildSliderChange={handleChildSliderChange}
            onChildSliderToggle={handleChildSliderToggle}
          />
        )}
      </div>
    </aside>
  )
}