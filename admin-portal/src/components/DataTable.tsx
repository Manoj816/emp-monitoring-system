import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from "react";

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
  /** "text" = contains match (default); "select" = pick one of the values present; "none" = no filter. */
  filter?: "text" | "select" | "none";
  cellStyle?: CSSProperties;
  headerStyle?: CSSProperties;
}

const PAGE_SIZES = [10, 25, 50, 100];

export default function DataTable<T>(props: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  pageSize?: number;
  emptyText?: string;
}) {
  const { columns, rows, rowKey } = props;
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [pageSize, setPageSize] = useState(props.pageSize ?? 25);
  const [page, setPage] = useState(1);

  const activeFilters = Object.entries(filters).filter(([, v]) => v !== "");

  const filtered = useMemo(() => {
    if (activeFilters.length === 0) return rows;
    return rows.filter((row) =>
      activeFilters.every(([key, needle]) => {
        const column = columns.find((c) => c.key === key);
        if (!column) return true;
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
          <tr className="filter-row">
            {columns.map((c) => (
              <th key={c.key}>
                {c.filter === "none" ? null : c.filter === "select" ? (
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
        </thead>
        <tbody>
          {visible.map((row) => (
            <tr key={rowKey(row)}>
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
          {activeFilters.length > 0 && ` (filtered from ${rows.length})`}
          {activeFilters.length > 0 && (
            <>
              {" "}
              <button className="link" onClick={() => setFilters({})}>Clear filters</button>
            </>
          )}
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
