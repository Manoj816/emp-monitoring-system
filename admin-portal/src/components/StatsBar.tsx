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
  compute: (range: DateRange | null) => StatItem[];
  /** Offers the date range (for data with a timestamp). */
  dated?: boolean;
  /** Popup title. */
  title?: string;
}): { controls: ReactNode; panel: ReactNode } {
  const [open, setOpen] = useState(false);
  const [range, setRange] = useState<DateRange>(() => presetRange("today"));

  function show() {
    if (props.dated) setRange(presetRange("today"));
    setOpen(true);
  }

  const controls = (
    <span className="stats-controls">
      <button className="action toolbar-action" onClick={show}>Stats</button>
    </span>
  );

  const panel = !open ? null : (
    <div className="overlay" onClick={() => setOpen(false)}>
      <div className="modal stats-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>{props.title ?? "Stats"}</h3>
        {props.dated && (
          <div className="stats-range">
            <label>
              Period
              <DateRangePicker value={range} onChange={setRange} label="Stats period" />
            </label>
          </div>
        )}
        {/* A clickable tile closes the popup first, then jumps to its tab/filter. */}
        <Stats
          items={props.compute(props.dated ? range : null).map((item) =>
            item.onClick
              ? { ...item, onClick: () => { setOpen(false); item.onClick!(); } }
              : item,
          )}
        />
        <p className="stats-caption">{props.dated ? describeDateRange(range) : "All data"}</p>
        <div className="row">
          <button className="action" onClick={() => setOpen(false)}>Close</button>
        </div>
      </div>
    </div>
  );

  return { controls, panel };
}
