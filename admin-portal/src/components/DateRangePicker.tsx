import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import {
  PRESET_LABELS, describeDateRange, describeRange, isoOf, presetRange, rangeLabel, todayIso,
  type DatePreset, type DateRange,
} from "../utils/dates";

const PRESETS: DatePreset[] = ["any", "today", "7d", "30d", "month", "custom"];
const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

interface Month { year: number; month: number } // month is 0-based

function monthOf(iso: string): Month {
  const d = new Date(`${iso}T00:00:00`);
  return { year: d.getFullYear(), month: d.getMonth() };
}

function shift(m: Month, by: number): Month {
  const d = new Date(m.year, m.month + by, 1);
  return { year: d.getFullYear(), month: d.getMonth() };
}

/**
 * Date filter like the ones in ticketing tools: an input-looking button that
 * opens a popover with presets (Any Time, Today, Last 7 Days, Last 30 Days,
 * This Month) and a Custom Range with a two-month calendar plus a start time on
 * the first day and an end time on the last day. The popover is rendered on
 * document.body at a fixed position so a scrolling table never clips it.
 */
export default function DateRangePicker(props: {
  value: DateRange;
  onChange: (range: DateRange) => void;
  /** What the dates are, for assistive technology ("When", "Stats period"). */
  label?: string;
  className?: string;
}) {
  const { value, onChange } = props;
  const [open, setOpen] = useState(false);
  const [anchor, setAnchor] = useState<DOMRect | null>(null);
  const [style, setStyle] = useState<CSSProperties>({ left: 0, top: 0 });
  const [view, setView] = useState<Month>(() => monthOf(value.from || todayIso()));
  const [hover, setHover] = useState<string | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const popRef = useRef<HTMLDivElement>(null);

  function toggle() {
    if (open) {
      setOpen(false);
      return;
    }
    setAnchor(buttonRef.current!.getBoundingClientRect());
    setView(monthOf(value.from || todayIso()));
    setOpen(true);
  }

  // Place the popover under the button, right-aligned like a dropdown, kept
  // inside the viewport; its width changes when the calendar is shown.
  useLayoutEffect(() => {
    if (!open || !anchor || !popRef.current) return;
    const w = popRef.current.offsetWidth;
    const h = popRef.current.offsetHeight;
    const left = Math.max(8, Math.min(anchor.right - w, window.innerWidth - w - 8));
    let top = anchor.bottom + 4;
    if (top + h > window.innerHeight - 8) {
      top = anchor.top - h - 4 >= 8 ? anchor.top - h - 4 : Math.max(8, window.innerHeight - h - 8);
    }
    setStyle({ left, top });
  }, [open, anchor, value.preset]);

  useEffect(() => {
    if (!open) return;
    const outside = (e: Event) => {
      const t = e.target as Node;
      if (popRef.current?.contains(t) || buttonRef.current?.contains(t)) return;
      setOpen(false);
    };
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    // Scrolling or resizing moves the button, so the popover follows it rather than closing.
    let frame = 0;
    const follow = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        if (buttonRef.current) setAnchor(buttonRef.current.getBoundingClientRect());
      });
    };
    document.addEventListener("mousedown", outside);
    document.addEventListener("keydown", key);
    window.addEventListener("scroll", follow, true);
    window.addEventListener("resize", follow);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("mousedown", outside);
      document.removeEventListener("keydown", key);
      window.removeEventListener("scroll", follow, true);
      window.removeEventListener("resize", follow);
    };
  }, [open]);

  function choose(preset: DatePreset) {
    if (preset === "custom") {
      onChange({ ...value, preset: "custom" });
      return;
    }
    onChange(presetRange(preset));
    setOpen(false);
  }

  function pickDay(iso: string) {
    if (!value.from || value.to) {
      onChange({ ...value, preset: "custom", from: iso, to: "" });
    } else if (iso < value.from) {
      onChange({ ...value, from: iso, to: value.from });
    } else {
      onChange({ ...value, to: iso });
    }
  }

  const custom = value.preset === "custom";
  const start = custom ? value.from : "";
  // While only the first day is picked, the hovered day previews the range.
  const end = custom ? value.to || (value.from && hover && hover > value.from ? hover : "") : "";
  const today = todayIso();
  const thisYear = new Date().getFullYear();
  const years = Array.from({ length: 7 }, (_, i) => thisYear - 5 + i);

  function monthGrid(offset: 0 | 1) {
    const m = shift(view, offset);
    const first = new Date(m.year, m.month, 1);
    const cells = Array.from({ length: 42 }, (_, i) => {
      const d = new Date(m.year, m.month, 1 - first.getDay() + i);
      return { iso: isoOf(d), day: d.getDate(), outside: d.getMonth() !== m.month };
    });
    const title = first.toLocaleDateString(undefined, { month: "long" });
    return (
      <div className="cal" key={offset}>
        <div className="cal-head">
          <button type="button" className="cal-nav" onClick={() => setView(shift(view, -1))} aria-label="Previous month">‹</button>
          <span className="cal-title">{title}</span>
          <button type="button" className="cal-nav" onClick={() => setView(shift(view, 1))} aria-label="Next month">›</button>
          <select
            className="cal-year"
            value={m.year}
            aria-label="Year"
            onChange={(e) => setView(shift({ year: Number(e.target.value), month: m.month }, -offset))}
          >
            {years.map((y) => <option key={y} value={y}>{y}</option>)}
          </select>
        </div>
        <div className="cal-grid" role="grid">
          {WEEKDAYS.map((w) => <span key={w} className="cal-dow">{w}</span>)}
          {cells.map((c) => {
            const classes = ["cal-day"];
            if (c.outside) classes.push("outside");
            if (c.iso === today) classes.push("today");
            if (c.iso === start) classes.push("range-start");
            if (c.iso === end) classes.push("range-end");
            if (start && end && c.iso > start && c.iso < end) classes.push("in-range");
            return (
              <button
                type="button"
                key={c.iso}
                className={classes.join(" ")}
                aria-pressed={c.iso === start || (!!value.to && c.iso === value.to)}
                onClick={() => pickDay(c.iso)}
                onMouseEnter={() => setHover(c.iso)}
                onMouseLeave={() => setHover(null)}
              >
                {c.day}
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  const hint = !value.from
    ? "Pick the first day"
    : !value.to
      ? "Now pick the last day"
      : describeRange(value.from, value.to);

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={`date-pick ${value.preset !== "any" ? "active" : ""} ${props.className ?? ""}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`${props.label ?? "Date"}: ${describeDateRange(value)}`}
        title={describeDateRange(value)}
        onClick={toggle}
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
          <rect x="3" y="5" width="18" height="16" rx="2" />
          <path d="M3 10h18M8 3v4M16 3v4" />
        </svg>
        <span className="date-pick-label">{rangeLabel(value)}</span>
        <span className="date-pick-chevron" aria-hidden="true">▾</span>
      </button>
      {open &&
        createPortal(
          <div ref={popRef} className="date-pop" style={style} role="dialog" aria-label={`${props.label ?? "Date"} filter`}>
            {custom && (
              <div className="cal-panel">
                <div className="cal-months">
                  {monthGrid(0)}
                  {monthGrid(1)}
                </div>
                <div className="cal-times">
                  <label>
                    Start Time
                    <input
                      type="time"
                      value={value.fromTime}
                      onChange={(e) => onChange({ ...value, fromTime: e.target.value })}
                      aria-label="Start time on the first day"
                    />
                  </label>
                  <label>
                    End Time
                    <input
                      type="time"
                      value={value.toTime}
                      onChange={(e) => onChange({ ...value, toTime: e.target.value })}
                      aria-label="End time on the last day"
                    />
                  </label>
                </div>
                <div className="cal-foot">
                  <span className="muted">{hint}</span>
                  <button
                    type="button"
                    className="link"
                    onClick={() => onChange({ ...value, from: "", to: "", fromTime: "", toTime: "" })}
                  >
                    Clear
                  </button>
                  <button type="button" className="action" onClick={() => setOpen(false)}>Done</button>
                </div>
              </div>
            )}
            <div className="date-presets" role="listbox" aria-label="Quick ranges">
              {PRESETS.map((p) => (
                <button
                  type="button"
                  key={p}
                  role="option"
                  aria-selected={value.preset === p}
                  className={`date-preset ${value.preset === p ? "active" : ""}`}
                  onClick={() => choose(p)}
                >
                  {PRESET_LABELS[p]}
                  {p === "custom" && <span className="chev" aria-hidden="true">»</span>}
                </button>
              ))}
            </div>
          </div>,
          document.body,
        )}
    </>
  );
}
