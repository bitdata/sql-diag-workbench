import { tr, useLocale } from '../../i18n'
import type { FragmentNode } from '../../types/fragment'
import type { EditOriginMark } from '../../utils/editLinks'
import './LineageChip.css'

interface LineageChipProps {
  chain: FragmentNode[]
  onOpen: () => void
  /** 跨 Tab 桩：编辑产物回指源 Tab（spec §6.5）。 */
  origin?: EditOriginMark | null
  onOpenOrigin?: (sessionId: string) => void
}

const MAX_LABEL = 30

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s
}

function stepLabel(node: FragmentNode): string {
  if (node.transform?.description) return node.transform.description
  switch (node.type) {
    case 'ORIGINAL':       return tr("Original")
    case 'USER_EDITED':    return tr("User edit")
    case 'PREDICATE_DECOMPOSED': return tr("Decompose")
    default:               return node.label ?? node.type
  }
}

/**
 * Single-line chip showing the operation-tree chain of the selected node.
 * Click opens the full LineagePanel. 2026-06-19 spec §5.1 (常驻 Lineage Chip) —
 * this iteration's spec (2026-09-29) does not restate the placement, §5.1 there
 * is the test-layering table.
 */
export default function LineageChip({ chain, onOpen, origin = null, onOpenOrigin }: LineageChipProps) {
  useLocale()
  const parts = chain.map((n) => truncate(stepLabel(n), MAX_LABEL))

  const chip = chain.length === 0 ? (
    <div className="lineage-chip lineage-chip-empty" title={tr("No lineage yet")}>
      {tr("Lineage ▸ (no chain)")}{" "}</div>
  ) : chain.length === 1 ? (
    <button type="button" className="lineage-chip" onClick={onOpen} title={tr("View lineage")}>
      <span className="lineage-chip-label">{tr("Lineage ▸")}</span>
      <span className="lineage-chip-step">{truncate(stepLabel(chain[0]), MAX_LABEL)}</span>
    </button>
  ) : (
    <button type="button" className="lineage-chip" onClick={onOpen} title={tr("View lineage")}>
      <span className="lineage-chip-label">{tr("Lineage ▸")}</span>
      {parts.map((p, i) => (
        <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span className="lineage-chip-step">{p}</span>
          {i < parts.length - 1 && <span className="lineage-chip-sep">→</span>}
        </span>
      ))}
    </button>
  )

  // 无 origin 时输出与今日完全一致（既有断言的兼容面）。
  if (!origin) return chip

  return (
    <div className="lineage-chip-row">
      {origin.sessionId && onOpenOrigin ? (
        <button
          type="button"
          className="lineage-chip-origin"
          title={origin.text}
          onClick={() => onOpenOrigin(origin.sessionId as string)}
        >
          {origin.text}
        </button>
      ) : (
        <span className="lineage-chip-origin" title={origin.text}>{origin.text}</span>
      )}
      <span className="lineage-chip-sep">▸</span>
      {chip}
    </div>
  )
}
