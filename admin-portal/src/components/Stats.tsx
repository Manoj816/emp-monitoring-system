/** Compact summary tiles shown above a table (counts derived from the loaded rows). */
export default function Stats(props: {
  items: { label: string; value: number | string; tone?: "ok" | "warn" | "bad" | "neutral" }[];
}) {
  return (
    <div className="stats">
      {props.items.map((item) => (
        <div key={item.label} className={`stat ${item.tone ?? "neutral"}`}>
          <span className="stat-value">{item.value}</span>
          <span className="stat-label">{item.label}</span>
        </div>
      ))}
    </div>
  );
}
