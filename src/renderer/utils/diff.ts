/**
 * Shared types and helpers for the `/api/diff` wire protocol.
 *
 * Backend (`DiffController.java` → `DiffResponse`) emits `diffMethod` as the
 * uppercase enum-shaped strings from `AstLcsDiffer` (`"LCS"` / `"PATH_SET"`).
 * The frontend prefers the kebab-case spec §3.3 L2 union so callers can switch
 * on the value without parsing wire strings at every consumer. Both
 * `useChangeAttribution` and `useDiffGraph` route through `normalizeDiffMethod`
 * to keep the wire→UI mapping in one place.
 */

/**
 * Diff method reported by the backend `DiffResponse.diffMethod` (see
 * `DiffController.java`). Values mirror the spec §3.3 L2 union.
 */
export type DiffMethod = 'ast-lcs' | 'path-set' | 'unknown'

/**
 * Map backend-emitted wire string (`"LCS"` / `"PATH_SET"`, per
 * `AstLcsDiffer.java`) to the frontend's `DiffMethod` union. Falls back to
 * `'unknown'` for any unrecognized value so downstream UI gates can render a
 * neutral badge instead of crashing on an unexpected string.
 */
export function normalizeDiffMethod(raw: string | undefined | null): DiffMethod | '' {
  if (raw === undefined || raw === null) return ''
  switch (raw) {
    case 'LCS':
      return 'ast-lcs'
    case 'PATH_SET':
      return 'path-set'
    default:
      return 'unknown'
  }
}
