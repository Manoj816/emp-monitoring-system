import { useState, type ReactNode } from "react";
import DateRangePicker from "./DateRangePicker";
import Stats, { type StatTile } from "./Stats";
import { describeDateRange, presetRange, type DateRange } from "../utils/dates";

export interface StatItem extends StatTile {}

/**
 * Summary tiles on demand, shown in a popup behind a single "Stats" button.
 * Dated data gets the same date picker as the tables inside the popup, preset
 * to Today, so today's numbers show first and any range is one pick away.
 * Returns the toolbar control and the popup element (null while closed).
 */
export function useStatsBar(props: {
  compute: (range: DateRange | null, group: string) => StatItem[];
  /** Offers the date range (for data with a timestamp). */
  dated?: boolean;
  /** Popup title. */
  title?: string;
  /**
   * An optional second way to narrow the numbers, beside the period — the
   * department, say. Options come from the caller rather than a fixed list, so
   * a value only the data knows about is still offered.
   */
  groupBy?: { label: string; options: readonly string[]; allLabel?: string };
}): { controls: ReactNode; panel: ReactNode } {
  const [open, setOpen] = useState(false);
  const [range, setRange] = useState<DateRange>(() => presetRange("today"));
  const [group, setGroup] = useState("");

  function show() {
    if (props.dated) setRange(presetRange("today"));
    setGroup("");
    setOpen(true);
  }

  const allLabel = props.groupBy?.allLabel ?? "All";

  const controls = (
    <span className="stats-controls">
      <button className="action toolbar-action" onClick={show}>Stats</button>
    </span>
  );

  const panel = !open ? null : (
    <div className="overlay" onClick={() => setOpen(false)}>
      <div className="modal stats-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <button type="button" className="modal-close" aria-label="Close" title="Close" onClick={() => setOpen(false)}>×</button>
        <h3 style={{ marginTop: 0 }}>{props.title ?? "Stats"}</h3>
        {props.dated && (
          <div className="stats-range">
            <label>
              Period
              <DateRangePicker value={range} onChange={setRange} label="Stats period" />
            </label>
          </div>
        )}
        {props.groupBy && (
          <div className="stats-range">
            <label>
              {props.groupBy.label}
              <select value={group} onChange={(e) => setGroup(e.target.value)}>
                <option value="">{allLabel}</option>
                {props.groupBy.options.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            </label>
          </div>
        )}
        {/* A clickable tile closes the popup first, then jumps to its tab/filter. */}
        <Stats
          items={props.compute(props.dated ? range : null, group).map((item) =>
            item.onClick
              ? { ...item, onClick: () => { setOpen(false); item.onClick!(); } }
              : item,
          )}
        />
        <p className="stats-caption">
          {props.dated ? describeDateRange(range) : "All data"}
          {props.groupBy && group ? ` · ${group}` : ""}
        </p>
        <div className="row">
          <button className="action" onClick={() => setOpen(false)}>Close</button>
        </div>
      </div>
    </div>
  );

  return { controls, panel };
}
