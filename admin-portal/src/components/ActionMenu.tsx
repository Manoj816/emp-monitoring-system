import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

export interface MenuItem {
  label: ReactNode;
  onClick: () => void;
  disabled?: boolean;
  danger?: boolean;
  title?: string;
}

/**
 * The "⋯" button at the end of a table row. The list is rendered on document.body
 * at a fixed position so a scrolling table never clips it; it closes on an
 * outside click, Escape, scroll or resize.
 */
export default function ActionMenu(props: { items: MenuItem[]; disabled?: boolean; label?: string }) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top?: number; bottom?: number; right: number } | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  function toggle() {
    if (open) {
      setOpen(false);
      return;
    }
    const r = buttonRef.current!.getBoundingClientRect();
    const estimatedHeight = props.items.length * 38 + 12;
    const right = window.innerWidth - r.right;
    setPos(
      r.bottom + 4 + estimatedHeight > window.innerHeight
        ? { bottom: window.innerHeight - r.top + 4, right }
        : { top: r.bottom + 4, right },
    );
    setOpen(true);
  }

  useEffect(() => {
    if (!open) return;
    const outside = (e: Event) => {
      const t = e.target as Node;
      if (listRef.current?.contains(t) || buttonRef.current?.contains(t)) return;
      setOpen(false);
    };
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    const away = () => setOpen(false);
    document.addEventListener("mousedown", outside);
    document.addEventListener("keydown", key);
    window.addEventListener("scroll", away, true);
    window.addEventListener("resize", away);
    return () => {
      document.removeEventListener("mousedown", outside);
      document.removeEventListener("keydown", key);
      window.removeEventListener("scroll", away, true);
      window.removeEventListener("resize", away);
    };
  }, [open]);

  return (
    <>
      <button
        ref={buttonRef}
        className="kebab"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={props.label ?? "Actions"}
        title={props.label ?? "Actions"}
        disabled={props.disabled}
        onClick={toggle}
      >
        <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
          <circle cx="12" cy="5" r="2" />
          <circle cx="12" cy="12" r="2" />
          <circle cx="12" cy="19" r="2" />
        </svg>
      </button>
      {open && pos &&
        createPortal(
          <div ref={listRef} className="menu-list" role="menu" style={pos}>
            {props.items.map((item, i) => (
              <button
                key={i}
                role="menuitem"
                className={`menu-item ${item.danger ? "danger" : ""}`}
                disabled={item.disabled}
                title={item.title}
                onClick={() => {
                  setOpen(false);
                  item.onClick();
                }}
              >
                {item.label}
              </button>
            ))}
          </div>,
          document.body,
        )}
    </>
  );
}
