import { useEffect, useRef } from "react";

/**
 * Runs `refresh` when a page becomes the one on screen, but only if its data is
 * older than `ttlMs`. Pages stay mounted across section switches (App hides the
 * inactive ones), so without this every switch either refetched on remount —
 * the old behavior: a fresh burst of list calls per click — or showed stale
 * data forever. This is the middle: switching is instant, and each page makes
 * at most one refresh per TTL window, however often the admin flips around.
 */
export function useFreshOnActive(active: boolean, refresh: () => void, ttlMs = 60_000) {
  const lastFetched = useRef(0);
  useEffect(() => {
    if (!active) return;
    const now = Date.now();
    if (now - lastFetched.current < ttlMs) return;
    lastFetched.current = now;
    refresh();
    // `refresh` is an inline function with a fresh identity every render; the
    // TTL guard above is what actually limits the work.
  }, [active, refresh, ttlMs]);
}
