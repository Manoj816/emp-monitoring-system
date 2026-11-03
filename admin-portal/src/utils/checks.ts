import type { AdminCheckRow } from "../api";

/**
 * How a stored check status is shown to an administrator.
 *
 * A check that ended without a scan is stored as EXPIRED; administrators read it
 * as a miss. NO_CHECK is the server's record of a work day on which no check was
 * opened at all (system off), listed under Missed as well.
 */
export function checkStatusLabel(status: string): string {
  if (status === "EXPIRED") return "MISSED";
  if (status === "NO_CHECK") return "NO CHECK";
  return status;
}

export function checkBadgeClass(status: string): string {
  if (status === "MATCH") return "badge match";
  if (status === "CHECK" || status === "MULTIPLE_FACES") return "badge check";
  if (status === "PENDING") return "badge neutral";
  return "badge error";
}

export function isMissed(status: string): boolean {
  return status === "EXPIRED" || status === "NO_CHECK";
}

/** CHECK is a review request, never a verdict: it stays open until someone records a resolution. */
export function needsReview(row: Pick<AdminCheckRow, "status" | "reviewResolution">): boolean {
  return (row.status === "CHECK" || row.status === "MULTIPLE_FACES") && !row.reviewResolution;
}
