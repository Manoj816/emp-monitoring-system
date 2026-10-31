import { useState, type ReactNode } from "react";

/**
 * Per-column table filters, hidden until asked for. Returns the toolbar toggle
 * (a funnel button that sits next to "Stats") and the props to hand DataTable,
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
