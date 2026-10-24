import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Tab } from '../types/tab'
import { compareTarget, createCompareSourceReader, type ActiveCompareGraph, type CompareTarget } from '../utils/compareSources'

export function useFragmentCompare(tabs: Tab[], active: ActiveCompareGraph, selectedNodeId: string | null) {
  const reader = useRef(createCompareSourceReader())
  const sources = useMemo(() => reader.current(tabs, active), [tabs, active.sessionId, active.dag, active.parsedSql, active.dialect, active.epoch])
  const [state, setState] = useState<{ owner: string | null; a: CompareTarget | null; b: CompareTarget | null; side: 'A' | 'B' } | null>(null)
  const focus = useRef<HTMLElement | null>(null)
  const close = useCallback(() => {
    setState(null)
    setTimeout(() => { if (focus.current?.isConnected) focus.current.focus() }, 0)
  }, [])
  useEffect(() => { if (state && state.owner !== active.sessionId) close() }, [active.sessionId, state?.owner, close])
  const choose = (side: 'A' | 'B', target: CompareTarget | null) => setState(s => s ? { ...s, [side === 'A' ? 'a' : 'b']: target } : s)
  const open = (secondNodeId?: string) => {
    const source = sources.find(s => s.sessionId === active.sessionId)
    const first = source?.nodes.find(n => n.id === selectedNodeId)
    const second = source?.nodes.find(n => n.id === secondNodeId)
    if (!state) focus.current = document.activeElement as HTMLElement | null
    setState({ owner: active.sessionId, a: source && first ? compareTarget(source, first) : null,
      b: source && second ? compareTarget(source, second) : null, side: 'B' })
  }
  const selectNode = (id: string) => {
    const source = sources.find(s => s.sessionId === active.sessionId)
    const node = source?.nodes.find(n => n.id === id)
    if (state && source && node) choose(state.side, compareTarget(source, node))
  }
  return { state: state?.owner === active.sessionId ? state : null, sources, open, close, choose, selectNode,
    setSide: (side: 'A' | 'B') => setState(s => s ? { ...s, side } : s),
    swap: () => setState(s => s ? { ...s, a: s.b, b: s.a } : s) }
}
