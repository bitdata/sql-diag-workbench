// src/renderer/test/setup.ts
import { vi, beforeAll, beforeEach, afterEach, afterAll } from 'vitest'
import '@testing-library/jest-dom/vitest'
import { server } from './msw-server'

// Mock Monaco editor (hooks don't depend on it but imports may pull it in)
vi.mock('@monaco-editor/react', () => ({
  default: () => null,
  loader: { config: vi.fn() }
}))

// Mock Electron IPC
Object.defineProperty(window, 'electron', {
  value: {
    invoke: vi.fn(),
    on: vi.fn(),
    send: vi.fn(),
    removeListener: vi.fn()
  },
  writable: true
})

// Stub ResizeObserver (not in jsdom)
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver

// Stub Range geometry (not implemented in jsdom). CodeMirror's measure loop calls
// `TextRange.getClientRects()` from `clientRectsFor()` on every re-measure, so a test
// that actually types into a CodeMirror editor gets an UNHANDLED
// "textRange(...).getClientRects is not a function" thrown from a rAF callback after
// the assertions have already passed (observed in SqlViewer.valueSync.test.tsx).
// Empty lists, not fake numbers: "no rects" is what CodeMirror treats as unmeasured.
if (typeof Range !== 'undefined') {
  if (typeof Range.prototype.getClientRects !== 'function') {
    Range.prototype.getClientRects = function () {
      return [] as unknown as DOMRectList
    }
  }
  if (typeof Range.prototype.getBoundingClientRect !== 'function') {
    Range.prototype.getBoundingClientRect = function () {
      return {
        x: 0, y: 0, top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0,
        toJSON() { return {} },
      } as DOMRect
    }
  }
}

// MSW lifecycle
beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))

// MSW replaces globalThis.fetch with its interceptor in beforeAll.
// Wrap it in a vi.spyOn so existing tests that use toHaveBeenCalled()
// still work — the spy delegates to MSW's interceptor transparently.
beforeEach(() => {
  vi.spyOn(globalThis, 'fetch')
})

afterEach(() => {
  server.resetHandlers()
  vi.restoreAllMocks()
})
afterAll(() => server.close())

// Default fetch mock — MSW intercepts at network level, but keep this
// as fallback for any fetch that bypasses MSW (e.g. different origins)
globalThis.fetch = vi.fn().mockRejectedValue(
  new Error('fetch not mocked — override in test or add MSW handler')
)
