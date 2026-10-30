/**
 * Title shown inside a card's toolbar, with the page description behind an
 * info icon (tooltip on hover or keyboard focus) instead of a paragraph.
 */
export default function CardTitle(props: { title: string; hint: string }) {
  return (
    <h2 className="card-title">
      {props.title}
      <span className="info-tip" tabIndex={0} role="img" aria-label={props.hint} data-tip={props.hint}>
        i
      </span>
    </h2>
  );
}
