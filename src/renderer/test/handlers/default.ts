import { http, HttpResponse } from 'msw'

/**
 * Default MSW handlers — return safe empty responses for endpoints
 * that ConnectionProvider calls on mount. Individual tests override
 * with server.use(...) for specific scenarios.
 */
export const defaultHandlers = [
  http.get('*/api/connections/:id/plan-capabilities', ({ params }) => HttpResponse.json({ connectionId: params.id, dialect: 'postgresql', serverProduct: 'PostgreSQL', serverVersion: '16', estimated: { state: 'SUPPORTED', errorReason: null }, actual: { state: 'SUPPORTED', errorReason: null }, degraded: false, errorReason: null })),
  http.put('*/api/storage/protection/:profile', () => HttpResponse.json({ confirmed: true })),
  http.get('*/api/storage/status', () => HttpResponse.json({
    policy: { enabled: false, retentionDays: 30, maxStorageMiB: 500, revision: 1 },
    size: { mainBytes: 0, walBytes: 0, shmBytes: 0, totalBytes: 0, reusableBytes: 0, degraded: false },
    protectionReady: true, job: null, degraded: false,
  })),
  http.get('*/api/session-edit-links', () => HttpResponse.json([])),
  http.put('*/api/sessions/:id/workspace', () => HttpResponse.json({})),
  // ConnectionProvider → useConnections → GET /api/connections
  http.get('*/api/connections', () => {
    return HttpResponse.json([])
  }),

  // ConnectionProvider → useConnections → GET /api/connections/selected (if called)
  http.get('*/api/connections/selected', () => {
    return HttpResponse.json(null)
  }),
]
