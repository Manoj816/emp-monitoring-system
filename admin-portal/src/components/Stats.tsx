export interface StatTile {
  label: string;
  value: number | string;
  tone?: "ok" | "warn" | "bad" | "neutral";
  /** When given, the tile is a button (e.g. jumps to the matching filter tab). */
  onClick?: () => void;
  /** Tooltip for a clickable tile. */
  title?: string;
}

/** Compact summary tiles shown above a table (counts derived from the loaded rows). */
export default function Stats(props: { items: StatTile[] }) {
  return (
    <div className="stats">
      {props.items.map((item) =>
        item.onClick ? (
          <button
            key={item.label}
            type="button"
            className={`stat clickable ${item.tone ?? "neutral"}`}
            onClick={item.onClick}
            title={item.title ?? `Show: ${item.label}`}
          >
            <span className="stat-value">{item.value}</span>
            <span className="stat-label">{item.label}</span>
          </button>
        ) : (
          <div key={item.label} className={`stat ${item.tone ?? "neutral"}`}>
            <span className="stat-value">{item.value}</span>
            <span className="stat-label">{item.label}</span>
          </div>
        ),
      )}
    </div>
  );
}
