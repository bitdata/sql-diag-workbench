import { useSyncExternalStore } from 'react'
export interface TaskNotice { id: number; errorReason: string; message: string }
let sequence = 0
let notices: TaskNotice[] = []
const listeners = new Set<() => void>()
export function reportTaskNotice(errorReason: string, message: string) {
  notices = [...notices.slice(-9), { id: ++sequence, errorReason, message }]
  listeners.forEach(fn => fn())
  return sequence
}
export function dismissTaskNotice(id: number) {
  notices = notices.filter(n => n.id !== id)
  listeners.forEach(fn => fn())
}
export function useTaskNotices() {
  return useSyncExternalStore(fn => { listeners.add(fn); return () => { listeners.delete(fn) } }, () => notices)
}
