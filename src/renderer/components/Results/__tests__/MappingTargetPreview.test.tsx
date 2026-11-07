import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import MappingDetails from '../MappingTargetPreview'
import type { PlanSqlMapping } from '../../../types/execution'
import { resolveMappingTarget, type MappingPreviewSnapshot, type MappingPreviewSource } from '../../../utils/mappingTargets'
import { getLocale, setLocale } from '../../../i18n'

const sql = 'SELECT * FROM orders'
const fragment = { id: 'f', type: 'ORIGINAL', label: 'Orders', sqlFragment: sql, executable: true, children: [] }
const snapshot: MappingPreviewSnapshot = { key: 's1', generation: 1, scopeKey: 'scope', draftScope: 'draft', backendUrl: 'test', sessionId: 's', connectionId: 'c', dialect: 'postgresql', executionId: 'e', submittedSql: sql, submittedSqlSha256: 'hash', fragment, findings: [], evaluationExecutionId: null, planPaths: ['$'] }
const mapping: PlanSqlMapping = { version: 1, executionId: 'e', dialect: 'postgresql', captureKey: 'e:postgresql:hash', submittedSqlSha256: 'hash', degraded: true, errorReason: 'OTHER_NODE_FAILED', issues: [], edges: [
  { planNodePath: '$', targetKind: 'SQL_SPAN', targetId: null, confidence: 'EXACT', relation: 'PLAN_TO_SQL', reason: 'observation', span: { nodeId: 'submitted_root', type: 'QUERY', label: 'Submitted SQL', startOffset: 0, endOffset: sql.length } },
  { planNodePath: '$', targetKind: 'FRAGMENT', targetId: 'f', confidence: 'INFERRED', relation: 'PLAN_TO_FRAGMENT', reason: 'candidate', span: null },
  { planNodePath: '$', targetKind: 'FRAGMENT', targetId: 'missing', confidence: 'UNKNOWN', relation: 'PLAN_TO_FRAGMENT', reason: 'no evidence', span: null },
] }
const source = (): MappingPreviewSource => ({ snapshot, resolve: index => resolveMappingTarget(mapping, snapshot, index, 'scope'), checkReveal: () => ({ status: 'READY' }), reveal: vi.fn(() => ({ status: 'READY' as const })) })

describe('mapping target preview', () => {
  it('renders exact original text as text, preserves other candidates and restores the opening button', () => {
    const value = source()
    const { container } = render(<MappingDetails mapping={mapping} path="$" source={value} />)
    expect(screen.getByText(/OTHER_NODE_FAILED/)).toBeInTheDocument()
    expect(screen.getByText(/MAPPING_TARGET_UNKNOWN/)).toBeInTheDocument()
    const open = screen.getByRole('button', { name: 'View submitted SQL' })
    fireEvent.click(open)
    expect(screen.getByLabelText('Submitted SQL preview').textContent).toBe(sql)
    expect(container.querySelector('mark')?.textContent).toBe(sql)
    expect(screen.getByRole('button', { name: 'View fragment' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Close preview' }))
    expect(screen.queryByLabelText('Mapping target preview')).toBeNull()
    expect(open).toHaveFocus()
  })
  it('consumes global action shortcuts, allows copy, and makes Escape close without cancelling', () => {
    render(<MappingDetails mapping={mapping} path="$" source={source()} />)
    fireEvent.click(screen.getByRole('button', { name: 'View submitted SQL' }))
    const preview = screen.getByLabelText('Mapping target preview')
    const global = vi.fn()
    window.addEventListener('keydown', global)
    try {
      for (const event of [{ key: 'F5' }, { key: 'Enter', ctrlKey: true }, { key: 'Enter', metaKey: true, shiftKey: true }, { key: 'F', ctrlKey: true, shiftKey: true }, { key: 'n', ctrlKey: true }])
        expect(fireEvent.keyDown(preview, event)).toBe(false)
      expect(global).not.toHaveBeenCalled()
      fireEvent.keyDown(preview, { key: 'c', ctrlKey: true })
      expect(global).toHaveBeenCalledTimes(1)
      fireEvent.keyDown(preview, { key: 'Escape' })
      expect(global).toHaveBeenCalledTimes(1)
      expect(screen.queryByLabelText('Mapping target preview')).toBeNull()
    } finally { window.removeEventListener('keydown', global) }
  })
  it('rechecks eligibility on click and displays structured reveal feedback', () => {
    const value = source()
    const { rerender } = render(<MappingDetails mapping={mapping} path="$" source={value} />)
    fireEvent.click(screen.getByRole('button', { name: 'View fragment' }))
    fireEvent.click(screen.getByRole('button', { name: 'Locate in tree' }))
    expect(value.reveal).toHaveBeenCalledTimes(1)
    expect(screen.getByText('Expanded and scrolled to the fragment.')).toBeInTheDocument()
    rerender(<MappingDetails mapping={mapping} path="$" source={{ ...value, checkReveal: () => ({ status: 'UNAVAILABLE', code: 'MAPPING_TREE_TARGET_NOT_VISIBLE', message: 'This fragment is not shown in the current tree.' }) }} />)
    expect(screen.getByRole('button', { name: 'Locate in tree' })).toBeDisabled()
    expect(screen.getByText(/MAPPING_TREE_TARGET_NOT_VISIBLE/)).toBeInTheDocument()
  })
  it('clears previews on snapshot or plan-path change and focuses the remaining evidence area', () => {
    const value = source()
    const { rerender } = render(<div className="plan-node-details" tabIndex={-1}><MappingDetails mapping={mapping} path="$" source={value} /></div>)
    fireEvent.click(screen.getByRole('button', { name: 'View fragment' }))
    expect(screen.getByLabelText('Mapping target preview')).toHaveFocus()
    rerender(<div className="plan-node-details" tabIndex={-1}><MappingDetails mapping={mapping} path="$" source={{ ...value, snapshot: { ...snapshot, key: 's2' } }} /></div>)
    expect(screen.queryByLabelText('Mapping target preview')).toBeNull()
    expect(document.activeElement).toHaveClass('plan-node-details')
    fireEvent.click(screen.getByRole('button', { name: 'View fragment' }))
    rerender(<div className="plan-node-details" tabIndex={-1}><MappingDetails mapping={mapping} path="different" source={value} /></div>)
    expect(screen.queryByLabelText('Mapping target preview')).toBeNull()
  })
  it('localizes controls and leaves SQL containing markup literal', () => {
    const previous = getLocale()
    setLocale('zh-CN')
    const text = "SELECT '<img src=x onerror=alert(1)>'"
    const value = source()
    value.resolve = () => ({ status: 'READY', target: { kind: 'SQL_SPAN', sql: text, start: 0, end: text.length } })
    const { container, unmount } = render(<MappingDetails mapping={mapping} path="$" source={value} />)
    try {
      fireEvent.click(screen.getByRole('button', { name: '查看提交原文' }))
      expect(screen.getByLabelText('提交原文预览').textContent).toBe(text)
      expect(container.querySelector('img')).toBeNull()
      expect(screen.getByRole('button', { name: '关闭预览' })).toBeInTheDocument()
    } finally { unmount(); setLocale(previous) }
  })
})
