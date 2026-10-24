import { useState, useCallback, useRef } from 'react'

interface PanelResizeResult {
  leftWidth: number
  resizingVertical: boolean
  resizingHorizontal: boolean
  rightAreaRef: React.RefObject<HTMLDivElement>
  startVerticalResize: (e: React.MouseEvent) => void
  startHorizontalResize: (e: React.MouseEvent) => void
  setLeftWidth: React.Dispatch<React.SetStateAction<number>>
}

export function usePanelResize(): PanelResizeResult {
  const [leftWidth, setLeftWidth] = useState(240)
  const [resizingVertical, setResizingVertical] = useState(false)
  const [resizingHorizontal, setResizingHorizontal] = useState(false)
  const rightAreaRef = useRef<HTMLDivElement>(null)

  const startVerticalResize = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setResizingVertical(true)
    const startX = e.clientX
    const startWidth = leftWidth
    const handleMouseMove = (ev: MouseEvent) => {
      const delta = ev.clientX - startX
      setLeftWidth(Math.max(160, Math.min(600, startWidth + delta)))
    }
    const handleMouseUp = () => {
      setResizingVertical(false)
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
  }, [leftWidth])

  const startHorizontalResize = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    const rightArea = rightAreaRef.current
    if (!rightArea) return
    const sqlViewerEl = rightArea.querySelector('.sql-viewer') as HTMLElement
    const resultsPanelEl = rightArea.querySelector('.results-panel') as HTMLElement
    if (!sqlViewerEl || !resultsPanelEl) return
    setResizingHorizontal(true)
    const startY = e.clientY
    const startViewerHeight = sqlViewerEl.getBoundingClientRect().height
    const startResultsHeight = resultsPanelEl.getBoundingClientRect().height
    const totalHeight = startViewerHeight + startResultsHeight
    const handleMouseMove = (ev: MouseEvent) => {
      const delta = ev.clientY - startY
      const newViewerHeight = Math.max(80, Math.min(totalHeight - 80, startViewerHeight + delta))
      const newResultsHeight = totalHeight - newViewerHeight
      sqlViewerEl.style.flex = 'none'
      sqlViewerEl.style.height = `${newViewerHeight}px`
      resultsPanelEl.style.flex = 'none'
      resultsPanelEl.style.height = `${newResultsHeight}px`
    }
    const handleMouseUp = () => {
      setResizingHorizontal(false)
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
    }
    document.addEventListener('mousemove', handleMouseMove)
    document.addEventListener('mouseup', handleMouseUp)
  }, [])

  return {
    leftWidth,
    resizingVertical,
    resizingHorizontal,
    rightAreaRef,
    startVerticalResize,
    startHorizontalResize,
    setLeftWidth,
  }
}
