/**
 * Comparison filters for numeric table columns (similarity, liveness).
 *
 * A "contains" box is the wrong control for a score: typing 9 matches 9, 90 and
 * 99.8, which is never what anybody meant. What a reviewer actually asks is
 * "show me everything under 80", so the filter is an operator and a number.
 *
 * The pair is kept in the table's filter record as one string — ">=90", "<80" —
 * so numeric columns need no special case in the plumbing that carries filter
 * values around (useTableFilters, the stat tiles, the active-filter count).
 */

export type NumericOperator = ">=" | "<=" | "=" | ">" | "<";

export interface NumericComparison {
  op: NumericOperator;
  value: number;
}

/** The operators offered, in the order the dropdown lists them. */
export const NUMERIC_OPERATORS: { op: NumericOperator; label: string; title: string }[] = [
  { op: ">=", label: "≥", title: "at least" },
  { op: "<=", label: "≤", title: "at most" },
  { op: "=", label: "=", title: "exactly" },
  { op: ">", label: ">", title: "more than" },
  { op: "<", label: "<", title: "less than" },
];

const PATTERN = /^(>=|<=|=|>|<)?\s*(-?\d+(?:\.\d+)?)$/;

/** Null when there is no usable comparison yet — no value typed, or not a number. */
export function parseNumericFilter(raw: string | undefined): NumericComparison | null {
  if (!raw) return null;
  const match = PATTERN.exec(raw.trim());
  if (!match) return null;
  return { op: (match[1] as NumericOperator) ?? ">=", value: Number(match[2]) };
}

export function serializeNumericFilter(op: NumericOperator, value: string): string {
  // An operator with nothing to compare against is not a filter: it is stored as
  // empty so the Filters count and "Clear filters" do not claim otherwise.
  return value.trim() === "" ? "" : `${op}${value.trim()}`;
}

/**
 * @param score null for a row that has no number at all (a missed check has no
 *   similarity). Those are excluded whenever a comparison is active: "under 80"
 *   is a claim about a score, and a check that was never scored cannot answer
 *   it either way. Clearing the filter brings them back.
 */
export function matchesNumericFilter(comparison: NumericComparison, score: number | null): boolean {
  if (score === null || Number.isNaN(score)) return false;
  switch (comparison.op) {
    case ">=":
      return score >= comparison.value;
    case "<=":
      return score <= comparison.value;
    case ">":
      return score > comparison.value;
    case "<":
      return score < comparison.value;
    case "=":
      return score === comparison.value;
  }
}
