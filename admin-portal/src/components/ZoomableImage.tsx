import { useEffect, useState } from "react";

/**
 * An image that opens a full-screen preview when clicked. Every picture in the
 * portal (ID/photo, enrolled scan, check evidence) renders through this, so
 * "click the picture to actually see it" behaves the same everywhere. Click
 * anywhere or press Escape to close; clicks never bubble into the dialog the
 * thumbnail sits in.
 */
export default function ZoomableImage(props: { src: string; alt: string; className?: string }) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [open]);

  return (
    <>
      <img
        className={`zoomable ${props.className ?? ""}`.trim()}
        src={props.src}
        alt={props.alt}
        title="Click to view full size"
        onClick={(e) => {
          e.stopPropagation();
          setOpen(true);
        }}
      />
      {open && (
        <div
          className="lightbox"
          role="dialog"
          aria-modal="true"
          aria-label={`${props.alt} (full size)`}
          onClick={(e) => {
            e.stopPropagation();
            setOpen(false);
          }}
        >
          <img src={props.src} alt={props.alt} />
        </div>
      )}
    </>
  );
}
