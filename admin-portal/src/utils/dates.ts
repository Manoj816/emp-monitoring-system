/** Local calendar-day helpers shared by the table filters and the stats bar. */

/** Today's date as yyyy-mm-dd in the browser's local time zone. */
export function todayIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Inclusive local-day bounds for a From / To pair of yyyy-mm-dd values (either may be empty). */
export function inLocalDayRange(raw: string | Date | null | undefined, from: string, to: string): boolean {
  if (!raw) return false;
  const t = new Date(raw).getTime();
  if (from && t < new Date(`${from}T00:00:00`).getTime()) return false;
  if (to && t > new Date(`${to}T23:59:59.999`).getTime()) return false;
  return true;
}

/** "Oct 30, 2026" or "Oct 1 – Oct 31, 2026" for captions. */
export function describeRange(from: string, to: string): string {
  const fmt = (iso: string) =>
    new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  if (from && to) return from === to ? fmt(from) : `${fmt(from)} – ${fmt(to)}`;
  if (from) return `from ${fmt(from)}`;
  if (to) return `up to ${fmt(to)}`;
  return "all dates";
}
