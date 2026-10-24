import { tr, useLocale } from '../../i18n'
import { useState } from 'react'
import type { DAGState } from '../../hooks/dagState'
import { experimentSources, effectValid, effectContextText } from './ExperimentSources'
import './ExperimentContext.css'

export function ExperimentContext({ nodeId, dag, onRetry }: { nodeId: string; dag: DAGState; onRetry: (source: string, rule: string) => void }) {
  useLocale()
  const [choice, setChoice] = useState('')
  const sources = experimentSources(dag, nodeId)
  const selected = sources.length === 1 ? sources[0] : sources.find(s => s.key === choice)
  const source = selected && dag.nodeMap.get(selected.record.sourceNodeId)
  const effect = selected?.output?.effect
  const valid = effect && effectValid(dag, selected?.output)
  const issues = [...dag.ruleResults.values()].filter(r => r.sourceNodeId === nodeId).flatMap(r => r.issues ?? [])
  if (!sources.length && !issues.length) return null
  return <aside className="experiment-context" aria-label={tr("Experiment changes")}>
    {sources.length > 1 && <label>{tr("Source")}{" "}<select aria-label={tr("Experiment source")} value={choice} onChange={e => setChoice(e.target.value)}>
      <option value="">{tr("Choose a source ({0})", sources.length)}</option>
      {sources.map(s => <option key={s.key} value={s.key}>{dag.nodeMap.get(s.record.sourceNodeId)?.label} · {s.record.ruleName} · {s.output?.ordinal ?? tr("Legacy record")}</option>)}
    </select></label>}
    {selected && <>
      <div>{tr("Source:")}{source?.label} · {selected.record.ruleName}</div>
      {!valid ? <div role="status">{tr("Unknown transform ·")}{" "}{effect ? 'TRANSFORM_SOURCE_CHANGED' : 'LEGACY_TRANSFORM_UNKNOWN'} <button onClick={() => onRetry(selected.record.sourceNodeId, selected.record.ruleName)}>{tr("Revalidate")}</button></div>
        : <><strong>{effectContextText(effect.context)}</strong>
          <details><summary>{tr("View actual changes ({0})", effect.changes.length)}</summary>
            {effect.changes.map((c, i) => <div key={i}><b>{c.operation} {c.subject}</b> · {c.scopePath}{c.beforeSql && <pre>{c.beforeSql}</pre>}{c.afterSql && <pre>{c.afterSql}</pre>}</div>)}
            <div>{tr("Retained:")}{effect.retained.map(r => `${r.subject} (${r.scopePath})`).join('、') || tr("None declared")}</div>
          </details>
          {effect.limitations.map((i, n) => <div key={n} role="status">{i.code} · {i.message}</div>)}
        </>}
    </>}
    {issues.map((i, n) => <details key={n}><summary>{i.code} · {i.message}</summary>{i.sql && <pre>{i.sql}</pre>}
      {i.ruleName && <button onClick={() => onRetry(nodeId, i.ruleName!)}>{tr("Retry rule")}</button>}</details>)}
  </aside>
}
