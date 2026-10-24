import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import type { ComponentProps } from 'react'
import type { ExecutionResult } from '../../../types/execution'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { mockSuccessResult, mockPlanResult, mockErrorResult } from '../../../test/fixtures'
import DataGrid from '../DataGrid'

type DataGridProps = ComponentProps<typeof DataGrid>

/**
 * Real prop surface (verified against DataGrid.tsx): { result, loading, error }
 * only — data lives INSIDE result (columns: ColumnInfo{name,type}, rows:
 * positional unknown[][], rowCount, timing.durationMs). The brief's
 * top-level columns/rows object arrays are fictional. Body rows are
 * TanStack-virtualized (3 rows here, well under the mount budget), so row
 * clicks are not exercised; sorting drives via the non-virtualized
 * columnheader cells with fireEvent.
 */
function renderGrid(overrides: Partial<DataGridProps> = {}) {
  const props: DataGridProps = {
    result: mockSuccessResult,
    loading: false,
    error: null,
    ...overrides,
  }
  return renderWithProviders(<DataGrid {...props} />)
}

/** Positional cell text of column `colIdx` (0 = first data column) per row. */
function columnValues(colIdx: number): (string | null)[] {
  return screen
    .getAllByRole('row')
    .map((row) => within(row).getAllByRole('cell')[colIdx].textContent)
}

function dataColumnHeaders(): HTMLElement[] {
  // columnheader order: '#', then one per ColumnInfo
  return screen.getAllByRole('columnheader').slice(1)
}

// TanStack virtual-core measures the scroll element via offsetHeight at
// subscribe time; jsdom reports 0, calculateRange() returns null and NO body
// rows mount (verified — contradicts the recon's "rows mount" claim). A fake
// non-zero viewport height makes the overscan window render; with ≤3 rows per
// fixture every row is a real mounted tree of cells.
const offsetHeightDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get: () => 400,
  })
})
afterAll(() => {
  if (offsetHeightDesc) {
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', offsetHeightDesc)
  }
})

describe('DataGrid', () => {
  it('renders the empty placeholder when result is null', () => {
    renderGrid({ result: null })
    expect(screen.getByText('Execute SQL or a fragment to see results')).toBeInTheDocument()
    // liveness pair: no table structure without a result
    expect(screen.queryByRole('table', { name: 'Query results' })).toBeNull()
  })

  it('renders an aria-busy skeleton while loading (hiding both result and placeholder)', () => {
    const { container } = renderGrid({ loading: true })
    expect(container.querySelector('.datagrid-loading[aria-busy="true"]')).toBeTruthy()
    expect(container.querySelectorAll('.datagrid-skeleton-row')).toHaveLength(5)
    expect(screen.queryByRole('table', { name: 'Query results' })).toBeNull()
    expect(screen.queryByText('Execute SQL or a fragment to see results')).toBeNull()
  })

  it('shows role=alert with the prop error when the execution failed with no result', () => {
    renderGrid({ result: null, error: 'connection refused' })
    expect(screen.getByRole('alert')).toHaveTextContent('connection refused')
    expect(screen.queryByText('Execute SQL or a fragment to see results')).toBeNull()
  })

  it('shows role=alert with the in-result error for a FAILED result', () => {
    renderGrid({ result: mockErrorResult })
    // mockErrorResult: status FAILED, error 'relation "orders" does not exist'
    expect(screen.getByRole('alert')).toHaveTextContent('relation "orders" does not exist')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('shows the 0-rows diagnostic panel (with timing) for COMPLETED rowCount=0', () => {
    // mockPlanResult: rowCount 0, status COMPLETED, timing 150ms
    renderGrid({ result: mockPlanResult })
    expect(screen.getByText('0 rows returned')).toBeInTheDocument()
    expect(screen.getByText('Query completed in 150ms')).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('shows "No results to display" when columns/rows are null but rows did return', () => {
    // synthetic: rowCount>0 & COMPLETED & error-free but missing tabular payload
    const noPayload: ExecutionResult = {
      ...mockSuccessResult,
      columns: null,
      rows: null,
      rowCount: 5,
    }
    renderGrid({ result: noPayload })
    expect(screen.getByText('No results to display')).toBeInTheDocument()
    expect(screen.queryByText('0 rows returned')).toBeNull()
  })

  it('renders div-ARIA table: columnheaders, positional cells, NULL glyph, footer', () => {
    renderGrid()
    const table = screen.getByRole('table', { name: 'Query results' })
    expect(table).toBeInTheDocument()
    expect(within(table).getAllByRole('rowgroup')).toHaveLength(2) // header + body
    // 3 columns: row-number '#' + id + name (from mockSuccessResult.columns)
    const headers = screen.getAllByRole('columnheader')
    expect(headers).toHaveLength(3)
    expect(headers[1]).toHaveTextContent('id')
    expect(headers[1]).toHaveTextContent('integer')
    expect(headers[2]).toHaveTextContent('name')
    // rows are positional arrays: rows[0] = [1, 'Alice'] → cell[0]='1', cell[1]='Alice'
    expect(screen.getAllByRole('row')).toHaveLength(3)
    expect(within(screen.getAllByRole('row')[2]).getAllByRole('cell')[1]).toHaveTextContent('Charlie')
    expect(within(screen.getAllByRole('row')[0]).getAllByRole('cell')[0]).toHaveTextContent('1')
    // footer reports rowCount + durationMs
    expect(screen.getByText('3 rows returned')).toBeInTheDocument()
    expect(screen.getByText('42ms')).toBeInTheDocument()
  })

  it('renders null cells as NULL and keeps singular footer for 1 row', () => {
    const withNull: ExecutionResult = {
      ...mockSuccessResult,
      columns: [{ name: 'note', type: 'text' }],
      rows: [[null]],
      rowCount: 1,
      timing: null,
    }
    renderGrid({ result: withNull })
    expect(within(screen.getAllByRole('row')[0]).getAllByRole('cell')[0]).toHaveTextContent('NULL')
    expect(screen.getByText('1 row returned')).toBeInTheDocument() // singular, not "1 rows"
    expect(screen.queryByText(/\d+ms/)).toBeNull() // timing null → no footer ms
  })

  it('sort cycles asc → desc → off on one column, proving row ORDER flips', () => {
    renderGrid()
    const nameHeader = dataColumnHeaders()[1] // columns: id(0), name(1)

    expect(columnValues(1)).toEqual(['Alice', 'Bob', 'Charlie'])
    fireEvent.click(nameHeader)
    // 1st click → asc (fixture already asc → same order, but indicator proves asc)
    expect(within(nameHeader).getByText('▲')).toBeInTheDocument()
    expect(columnValues(1)).toEqual(['Alice', 'Bob', 'Charlie'])
    fireEvent.click(nameHeader)
    // 2nd click → desc: first/last content actually swapped
    expect(within(nameHeader).getByText('▼')).toBeInTheDocument()
    expect(columnValues(1)).toEqual(['Charlie', 'Bob', 'Alice'])
    fireEvent.click(nameHeader)
    // 3rd click → sort cleared
    expect(within(nameHeader).queryByText('▲')).toBeNull()
    expect(within(nameHeader).queryByText('▼')).toBeNull()
    expect(columnValues(1)).toEqual(['Alice', 'Bob', 'Charlie'])
  })

  it('numeric columns sort by value (not string), and a new column resets to asc', () => {
    const threeDigits: ExecutionResult = {
      ...mockSuccessResult,
      rows: [[10, 'Alice'], [9, 'Bob'], [100, 'Charlie']],
    }
    renderGrid({ result: threeDigits })
    const idHeader = dataColumnHeaders()[0]
    fireEvent.click(idHeader) // asc numeric
    expect(columnValues(0)).toEqual(['9', '10', '100'])
    fireEvent.click(idHeader) // desc numeric — NOT ['9','100','10'] string order
    expect(columnValues(0)).toEqual(['100', '10', '9'])
    // switch column mid-desc → resets that new column to asc (not inherited desc)
    fireEvent.click(dataColumnHeaders()[1])
    expect(within(dataColumnHeaders()[1]).getByText('▲')).toBeInTheDocument()
    expect(columnValues(1)).toEqual(['Alice', 'Bob', 'Charlie'])
  })
})
