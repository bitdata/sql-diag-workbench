import { describe, it, expect } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '../../test/msw-server'
import { useEditLinks } from '../useEditLinks'
import { editLinkKey, type EditLink } from '../../utils/editLinks'
import type { Tab } from '../../types/tab'
const link: EditLink = { linkId: 'link', sequence: 1, sourceNodeId: 'src', sourceSessionId: 'a', sourceTabName: 'A',
  targetSessionId: 'b', targetTabName: 'B', targetNodeId: 'tgt' }
const tabs = [{ sessionId: 'a', name: 'A' }, { sessionId: 'b', name: 'B' }] as Tab[]
describe('persistent edit link loading', () => {
  it('loads a fresh index after remount, independently of runtime record', async () => {
    server.use(http.get('*/api/session-edit-links', () => HttpResponse.json([link])))
    const first = renderHook(() => useEditLinks('http://localhost:8080', tabs))
    await waitFor(() => expect(first.result.current.links.degraded).toBe(false))
    first.unmount()
    const second = renderHook(() => useEditLinks('http://localhost:8080', tabs))
    await waitFor(() => expect(second.result.current.links.byTarget.get(editLinkKey('b','tgt'))).toEqual(link))
  })
  it('reports read failure and supports retry without claiming an empty history', async () => {
    server.use(http.get('*/api/session-edit-links', () => new HttpResponse(null, { status: 503 })))
    const { result } = renderHook(() => useEditLinks('http://localhost:8080', tabs))
    await waitFor(() => expect(result.current.links.reason).toBe('LOAD_FAILED'))
    expect(result.current.error).toContain('503')
    server.use(http.get('*/api/session-edit-links', () => HttpResponse.json([link])))
    act(() => result.current.retry())
    await waitFor(() => expect(result.current.links.degraded).toBe(false))
    expect(result.current.error).toBeNull()
  })
})