import { useState, type ReactNode } from "react";

/**
 * Per-column table filters, hidden until asked for. Returns the toolbar controls
 * (a funnel button that sits next to "Stats", and a Clear button that appears
 * beside it as soon as anything is filtering) and the props to hand DataTable,
 * which keeps the filter values here so a page can set one itself — a stat tile
 * applying the period it was read for, say.
 */
export function useTableFilters(): {
  control: ReactNode;
  /** Merge filter values in and reveal the row (used by the stat tiles). */
  apply: (patch: Record<string, string>) => void;
  tableProps: {
    showFilters: boolean;
    filters: Record<string, string>;
    onFiltersChange: (filters: Record<string, string>) => void;
  };
} {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [visible, setVisible] = useState(false);

  const activeCount = Object.values(filters).filter((v) => v !== "").length;

  const control = (
    <>
      <button
        className={`action toolbar-action filter-toggle ${visible ? "on" : ""}`}
        aria-pressed={visible}
        onClick={() => setVisible(!visible)}
        title={visible ? "Hide the column filters" : "Filter the table by column"}
      >
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
             strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M3 5h18l-7 8.2V19l-4 2v-7.8Z" />
        </svg>
        Filters
        {activeCount > 0 && <span className="filter-count">{activeCount}</span>}
      </button>
      {/* Next to the funnel, not at the foot of the table: the way out of a
          filtered view belongs where the way in is, and on screen only while
          something is actually being filtered. */}
      {activeCount > 0 && (
        <button
          className="action toolbar-action clear-filters"
          onClick={() => setFilters({})}
          title={`Clear ${activeCount === 1 ? "the column filter" : `all ${activeCount} column filters`}`}
        >
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
               strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
          Clear filters
        </button>
      )}
    </>
  );

  function apply(patch: Record<string, string>) {
    setFilters((prev) => ({ ...prev, ...patch }));
    // Only pop the row open for a filter that actually narrows something, so
    // "Any Time" on a stat tile quietly clears the date instead of opening it.
    if (Object.values(patch).some((v) => v !== "")) {
      setVisible(true);
    }
  }

  return {
    control,
    apply,
    tableProps: { showFilters: visible, filters, onFiltersChange: setFilters },
  };
}
