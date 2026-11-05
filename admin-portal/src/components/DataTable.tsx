import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from "react";
import { inDateRange, parseRange, serializeRange, type DateRange } from "../utils/dates";
import {
  matchesNumericFilter,
  NUMERIC_OPERATORS,
  parseNumericFilter,
  serializeNumericFilter,
  type NumericOperator,
} from "../utils/numericFilter";
import DateRangePicker from "./DateRangePicker";

/**
 * Table with a filter on every column and client-side pagination (25 rows a
 * page by default). Each column declares the plain-text value a row has for it;
 * that value drives the filter (a free-text "contains" box, or a dropdown of the
 * values present) and is what is shown unless a custom render is given.
 */
export interface Column<T> {
  key: string;
  header: string;
  /** Plain-text value of the cell, used for filtering and as the default display. */
  value: (row: T) => string;
  render?: (row: T) => ReactNode;
  /**
   * "text" = contains match (default); "select" = pick one of the values present;
   * "date" = preset / custom range picker on the column's date;
   * "number" = an operator (at least / at most / …) and a value; "none" = no filter.
   */
  filter?: "text" | "select" | "date" | "number" | "none";
  /** The raw timestamp for a "date" column (the display value stays `value`). */
  date?: (row: T) => string | Date | null | undefined;
  /**
   * The raw score for a "number" column. Needed because `value` is already
   * formatted for display — "99.8", or a dash for a check that has none — and
   * comparing against rounded text would quietly answer a different question.
   */
  number?: (row: T) => number | null | undefined;
  cellStyle?: CSSProperties;
  headerStyle?: CSSProperties;
}

const PAGE_SIZES = [10, 25, 50, 100];

/**
 * A click that landed on a control inside the row (action button, menu, filter)
 * belongs to that control, not to the row: it must not open the details popup.
 */
function onControl(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest("button, a, input, select, textarea, label") !== null;
}

export default function DataTable<T>(props: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  pageSize?: number;
  emptyText?: string;
  /** Show the per-column filter row; off unless the page's Filters button is on. */
  showFilters?: boolean;
  /** Controlled filter values, so a page can set one itself (see useTableFilters). */
  filters?: Record<string, string>;
  onFiltersChange?: (filters: Record<string, string>) => void;
  /** Opens the row's details popup; the whole row becomes clickable and focusable. */
  onRowClick?: (row: T) => void;
  /** What a clickable row is called to a screen reader ("Open <name>"). */
  rowLabel?: (row: T) => string;
}) {
  const { columns, rows, rowKey, onRowClick } = props;
  // Uncontrolled by default; a page that needs to set a filter passes both props.
  const [ownFilters, setOwnFilters] = useState<Record<string, string>>({});
  const filters = props.filters ?? ownFilters;
  const setFilters = props.onFiltersChange ?? setOwnFilters;
  const showFilters = props.showFilters ?? false;
  const [pageSize, setPageSize] = useState(props.pageSize ?? 25);
  const [page, setPage] = useState(1);
  // The operator and the half-typed number live here rather than in the filter
  // record, so picking "at most" before typing anything does not register as a
  // filter that hides nothing.
  const [numericOps, setNumericOps] = useState<Record<string, NumericOperator>>({});
  const [numericText, setNumericText] = useState<Record<string, string>>({});

  const activeFilters = Object.entries(filters).filter(([, v]) => v !== "");

  const filtered = useMemo(() => {
    if (activeFilters.length === 0) return rows;
    const ranges = new Map<string, DateRange>();
    columns.forEach((c) => {
      if (c.filter === "date") ranges.set(c.key, parseRange(filters[c.key]));
    });
    return rows.filter((row) =>
      columns.every((column) => {
        if (column.filter === "none") return true;
        if (column.filter === "date") {
          return inDateRange(column.date ? column.date(row) : column.value(row), ranges.get(column.key)!);
        }
        if (column.filter === "number") {
          const comparison = parseNumericFilter(filters[column.key]);
          if (comparison === null) return true;
          const score = column.number ? column.number(row) : Number(column.value(row));
          return matchesNumericFilter(comparison, score ?? null);
        }
        const needle = filters[column.key] ?? "";
        if (needle === "") return true;
        const haystack = column.value(row);
        return column.filter === "select"
          ? haystack === needle
          : haystack.toLowerCase().includes(needle.toLowerCase());
      }),
    );
  }, [rows, columns, filters]); // eslint-disable-line react-hooks/exhaustive-deps

  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
  // Filters and page size send the reader back to page 1; a data refresh only
  // clamps the page so a live table never jumps around under them.
  useEffect(() => setPage(1), [filters, pageSize]);
  useEffect(() => {
    if (page > pageCount) setPage(pageCount);
  }, [page, pageCount]);

  const start = (page - 1) * pageSize;
  const visible = filtered.slice(start, start + pageSize);

  function numericOp(key: string): NumericOperator {
    return numericOps[key] ?? parseNumericFilter(filters[key])?.op ?? ">=";
  }

  function numericValue(key: string): string {
    return numericText[key] ?? (parseNumericFilter(filters[key])?.value?.toString() ?? "");
  }

  function setNumeric(key: string, op: NumericOperator, text: string) {
    setNumericOps((prev) => ({ ...prev, [key]: op }));
    setNumericText((prev) => ({ ...prev, [key]: text }));
    setFilters({ ...filters, [key]: serializeNumericFilter(op, text) });
  }

  function options(column: Column<T>): string[] {
    const values = new Set<string>();
    rows.forEach((r) => values.add(column.value(r)));
    return Array.from(values).sort((a, b) => a.localeCompare(b));
  }

  return (
    <>
      <div className="table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} style={c.headerStyle}>{c.header}</th>
            ))}
          </tr>
          {showFilters && (
          <tr className="filter-row">
            {columns.map((c) => (
              <th key={c.key}>
                {c.filter === "none" ? null : c.filter === "date" ? (
                  <DateRangePicker
                    value={parseRange(filters[c.key])}
                    onChange={(range) => setFilters({ ...filters, [c.key]: serializeRange(range) })}
                    label={c.header}
                  />
                ) : c.filter === "number" ? (
                  <span className="num-filter">
                    <select
                      value={numericOp(c.key)}
                      onChange={(e) =>
                        setNumeric(c.key, e.target.value as NumericOperator, numericValue(c.key))
                      }
                      aria-label={`How to compare ${c.header}`}
                    >
                      {NUMERIC_OPERATORS.map((o) => (
                        <option key={o.op} value={o.op} title={o.title}>{o.label}</option>
                      ))}
                    </select>
                    <input
                      type="number"
                      inputMode="decimal"
                      step="0.1"
                      placeholder="Value"
                      value={numericValue(c.key)}
                      onChange={(e) => setNumeric(c.key, numericOp(c.key), e.target.value)}
                      aria-label={`Filter ${c.header}`}
                    />
                  </span>
                ) : c.filter === "select" ? (
                  <select
                    value={filters[c.key] ?? ""}
                    onChange={(e) => setFilters({ ...filters, [c.key]: e.target.value })}
                    aria-label={`Filter ${c.header}`}
                  >
                    <option value="">All</option>
                    {options(c).map((v) => (
                      <option key={v} value={v}>{v === "" ? "(blank)" : v}</option>
                    ))}
                  </select>
                ) : (
                  <input
                    type="search"
                    placeholder="Filter…"
                    value={filters[c.key] ?? ""}
                    onChange={(e) => setFilters({ ...filters, [c.key]: e.target.value })}
                    aria-label={`Filter ${c.header}`}
                  />
                )}
              </th>
            ))}
          </tr>
          )}
        </thead>
        <tbody>
          {visible.map((row) => (
            <tr
              key={rowKey(row)}
              className={onRowClick ? "clickable" : undefined}
              tabIndex={onRowClick ? 0 : undefined}
              aria-label={onRowClick ? (props.rowLabel?.(row) ?? "Open details") : undefined}
              onClick={
                onRowClick
                  ? (e) => {
                      if (!onControl(e.target)) onRowClick(row);
                    }
                  : undefined
              }
              onKeyDown={
                onRowClick
                  ? (e) => {
                      // Only the row itself: Enter or Space inside a cell control is that control's.
                      if (e.target !== e.currentTarget) return;
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        onRowClick(row);
                      }
                    }
                  : undefined
              }
            >
              {columns.map((c) => (
                // Every cell is one line; overflow is cut with an ellipsis and the full
                // text is the tooltip, so all rows stay the same height.
                <td
                  key={c.key}
                  style={c.cellStyle}
                  className={c.filter === "none" ? "no-truncate" : undefined}
                  title={c.filter === "none" ? undefined : c.value(row)}
                >
                  {c.render ? c.render(row) : c.value(row)}
                </td>
              ))}
            </tr>
          ))}
          {visible.length === 0 && (
            <tr>
              <td colSpan={columns.length} className="muted">
                {rows.length === 0 ? (props.emptyText ?? "Nothing to show.") : "No rows match the filters."}
              </td>
            </tr>
          )}
        </tbody>
      </table>
      </div>

      <div className="table-footer">
        <span className="muted">
          {filtered.length === 0
            ? "0 rows"
            : `Showing ${start + 1}–${Math.min(start + pageSize, filtered.length)} of ${filtered.length}`}
          {/* Clearing them is a toolbar action (next to the Filters button), so the
              footer only says how much is being hidden. */}
          {activeFilters.length > 0 && ` (filtered from ${rows.length})`}
        </span>
        <span className="pager">
          <label className="muted">
            Per page{" "}
            <select value={pageSize} onChange={(e) => setPageSize(Number(e.target.value))}>
              {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
          <button className="action" disabled={page <= 1} onClick={() => setPage(page - 1)}>‹ Prev</button>
          <span className="muted">Page {page} of {pageCount}</span>
          <button className="action" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>Next ›</button>
        </span>
      </div>
    </>
  );
}
