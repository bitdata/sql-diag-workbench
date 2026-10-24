import { tr, useLocale } from '../../i18n'
import { createContext, useContext } from 'react'
import type { DAGState } from '../../hooks/dagState'
import type { RuleResult, TransformOutput } from '../../types/fragment'
import { sha256 } from '../../utils/sha256'

export const ExperimentSourcesContext = createContext<DAGState | null>(null)

export function experimentSources(dag: DAGState, nodeId: string) {
  return [...dag.ruleResults.values()].flatMap<{ key: string; record: RuleResult; output?: TransformOutput }>(record => {
    const outputs = record.outputs?.filter(output => output.targetNodeId === nodeId) ?? []
    return outputs.length ? outputs.map(output => ({ key: `${record.sourceNodeId}::${record.ruleName}::${output.ordinal}`, record, output }))
      : record.childIds.includes(nodeId) ? [{ key: `${record.sourceNodeId}::${record.ruleName}`, record }] : []
  })
}

export function effectValid(dag: DAGState, output?: TransformOutput) {
  const source = output && dag.nodeMap.get(output.sourceNodeId)
  const target = output && dag.nodeMap.get(output.targetNodeId)
  return !!(output && source && target && output.effect.sourceSqlHash === sha256(source.sqlFragment)
    && output.effect.targetSqlHash === sha256(target.sqlFragment))
}

export function effectContextText(context: string) {
  return context === 'DETACHED' ? tr("Context removed · Independent experiment; aggregates do not represent the original parent row")
    : context === 'UNKNOWN' ? tr("Context unknown; no causal inference is supported") : tr("Context preserved · Results belong to this experiment")
}

/** Supplemental surfaces share the persistent context's source and hash checks. */
export function useExperimentSummary(nodeId: string) {
  useLocale()
  const dag = useContext(ExperimentSourcesContext)
  if (!dag) return null
  const sources = experimentSources(dag, nodeId)
  if (!sources.length) return null
  if (sources.length > 1) return tr("{0} sources · Choose a source in SQL context to view changes", sources.length)
  const { record, output } = sources[0]
  const label = dag.nodeMap.get(record.sourceNodeId)?.label ?? record.sourceNodeId
  if (!effectValid(dag, output)) return tr("{0} · Unknown transform · {1}", label, output ? 'TRANSFORM_SOURCE_CHANGED' : 'LEGACY_TRANSFORM_UNKNOWN')
  return `${label} · ${effectContextText(output!.effect.context)} · ${output!.effect.changes.map(c => `${c.operation} ${c.subject}`).join('、')}`
}
