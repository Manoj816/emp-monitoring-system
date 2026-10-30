/** Local calendar-day helpers shared by the table date filters and the stats popup. */

export type DatePreset = "any" | "today" | "7d" | "30d" | "month" | "custom";

/**
 * A date filter: a preset relative to today, or a custom range of calendar days
 * with an optional start time on the first day and end time on the last day.
 */
export interface DateRange {
  preset: DatePreset;
  /** yyyy-mm-dd bounds; only used by "custom" (presets compute theirs when applied). */
  from: string;
  to: string;
  /** HH:mm on the first / last day of a custom range ("" = whole day). */
  fromTime: string;
  toTime: string;
}

export const ANY_TIME: DateRange = { preset: "any", from: "", to: "", fromTime: "", toTime: "" };

export const PRESET_LABELS: Record<DatePreset, string> = {
  any: "Any Time",
  today: "Today",
  "7d": "Last 7 Days",
  "30d": "Last 30 Days",
  month: "This Month",
  custom: "Custom Range",
};

const pad = (n: number) => String(n).padStart(2, "0");

/** A Date as yyyy-mm-dd in the browser's local time zone. */
export function isoOf(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Today's date as yyyy-mm-dd in the browser's local time zone. */
export function todayIso(): string {
  return isoOf(new Date());
}

/** yyyy-mm-dd shifted by n calendar days (local). */
export function addDays(iso: string, n: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + n);
  return isoOf(d);
}

/** A fresh range for a preset (no custom dates or times). */
export function presetRange(preset: DatePreset): DateRange {
  return { preset, from: "", to: "", fromTime: "", toTime: "" };
}

/** The yyyy-mm-dd bounds a range stands for right now; presets are relative to today. */
export function boundsOf(range: DateRange): { from: string; to: string } {
  const today = todayIso();
  switch (range.preset) {
    case "any":
      return { from: "", to: "" };
    case "today":
      return { from: today, to: today };
    case "7d":
      return { from: addDays(today, -6), to: today };
    case "30d":
      return { from: addDays(today, -29), to: today };
    case "month": {
      const d = new Date();
      return {
        from: isoOf(new Date(d.getFullYear(), d.getMonth(), 1)),
        to: isoOf(new Date(d.getFullYear(), d.getMonth() + 1, 0)),
      };
    }
    case "custom":
      return { from: range.from, to: range.to };
  }
}

/** Whether a timestamp falls inside the range (inclusive, local time; times narrow the first and last day). */
export function inDateRange(raw: string | Date | null | undefined, range: DateRange): boolean {
  const b = boundsOf(range);
  if (!b.from && !b.to) return true;
  if (!raw) return false;
  const t = new Date(raw).getTime();
  const fromTime = (range.preset === "custom" && range.fromTime) || "00:00";
  const toTime = (range.preset === "custom" && range.toTime) || "23:59";
  if (b.from && t < new Date(`${b.from}T${fromTime}:00`).getTime()) return false;
  if (b.to && t > new Date(`${b.to}T${toTime}:59.999`).getTime()) return false;
  return true;
}

/** Table filters keep one string per column: "" means no filter. */
export function serializeRange(range: DateRange): string {
  return range.preset === "any" ? "" : JSON.stringify(range);
}

export function parseRange(serialized: string | undefined): DateRange {
  if (!serialized) return ANY_TIME;
  try {
    return { ...ANY_TIME, ...(JSON.parse(serialized) as Partial<DateRange>) };
  } catch {
    return ANY_TIME;
  }
}

const fmtDay = (iso: string) =>
  new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });

/** "Oct 30, 2026" or "Oct 1 – Oct 31, 2026" for captions. */
export function describeRange(from: string, to: string): string {
  if (from && to) return from === to ? fmtDay(from) : `${fmtDay(from)} – ${fmtDay(to)}`;
  if (from) return `from ${fmtDay(from)}`;
  if (to) return `up to ${fmtDay(to)}`;
  return "all dates";
}

/** Caption for a range: "Today, Oct 30, 2026", "Oct 1 – Oct 15, 2026, 09:00 – 18:00" or "All dates". */
export function describeDateRange(range: DateRange): string {
  if (range.preset === "any") return "All dates";
  const b = boundsOf(range);
  if (range.preset !== "custom") return `${PRESET_LABELS[range.preset]}, ${describeRange(b.from, b.to)}`;
  if (!b.from && !b.to) return "Custom range (no days picked yet)";
  const times = range.fromTime || range.toTime ? `, ${range.fromTime || "00:00"} – ${range.toTime || "23:59"}` : "";
  return describeRange(b.from, b.to) + times;
}

/** Short text for the picker button. */
export function rangeLabel(range: DateRange): string {
  if (range.preset !== "custom") return PRESET_LABELS[range.preset];
  if (range.from && range.to) return describeRange(range.from, range.to);
  if (range.from) return `From ${fmtDay(range.from)}`;
  return PRESET_LABELS.custom;
}
