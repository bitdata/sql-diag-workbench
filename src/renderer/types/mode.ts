/**
 * Top-level mode toggle for the right-area results panel.
 *
 * Each tab remembers its own mode independently — switching tabs restores
 * the mode that was active when the tab was last used. Stored in
 * localStorage via {@code useTabs} keyed by sessionId.
 *
 * <p>Data mode focuses on query correctness (rows + row trace); Performance
 * mode focuses on query performance (plan + diagnostics). The two modes
 * share the same selected fragment but use different backend endpoints
 * ({@code /api/execution/execute} vs {@code /api/execution/analyze}).
 */
export type WorkspaceMode = 'data' | 'performance'