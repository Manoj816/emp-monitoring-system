import { useState } from "react";
import Stats from "./Stats";
import { describeRange, todayIso } from "../utils/dates";

export interface StatItem {
  label: string;
  value: number | string;
  tone?: "ok" | "warn" | "bad" | "neutral";
}

/**
 * Summary tiles on demand. Hidden until asked for: "Show stats" for a whole
 * data set, or (dated) "Today's stats" / "Stats for dates…" with a From / To
 * pair. The parent computes the tiles for the requested range.
 */
export default function StatsBar(props: {
  compute: (range: { from: string; to: string } | null) => StatItem[];
  /** Offers Today and a date range (for data with a timestamp). */
  dated?: boolean;
}) {
  const [mode, setMode] = useState<"hidden" | "all" | "today" | "range">("hidden");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const range =
    mode === "today" ? { from: todayIso(), to: todayIso() } : mode === "range" ? { from, to } : null;
  const caption =
    mode === "today" ? `Today, ${describeRange(todayIso(), todayIso())}`
      : mode === "range" ? describeRange(from, to)
        : mode === "all" ? "All data" : "";

  return (
    <div className="stats-bar">
      <div className="stats-controls">
        {props.dated ? (
          <>
            <button className={`action ${mode === "today" ? "primary" : ""}`} onClick={() => setMode(mode === "today" ? "hidden" : "today")}>
              Today's stats
            </button>
            <button className={`action ${mode === "range" ? "primary" : ""}`} onClick={() => setMode(mode === "range" ? "hidden" : "range")}>
              Stats for dates…
            </button>
            {mode === "range" && (
              <span className="stats-range">
                <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} aria-label="Stats from" />
                <span className="muted">–</span>
                <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} aria-label="Stats to" />
              </span>
            )}
          </>
        ) : (
          <button className={`action ${mode === "all" ? "primary" : ""}`} onClick={() => setMode(mode === "all" ? "hidden" : "all")}>
            {mode === "all" ? "Hide stats" : "Show stats"}
          </button>
        )}
        {mode !== "hidden" && props.dated && (
          <button className="link" onClick={() => setMode("hidden")}>Hide</button>
        )}
      </div>
      {mode !== "hidden" && (
        <>
          <Stats items={props.compute(range)} />
          <span className="stats-caption">{caption}</span>
        </>
      )}
    </div>
  );
}
