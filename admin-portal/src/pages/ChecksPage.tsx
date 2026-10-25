import { useEffect, useState } from "react";
import { api, type AdminCheckRow, type ReviewResolution } from "../api";

const FILTERS = ["ALL", "CHECK", "MATCH", "ERRORS"] as const;
type Filter = (typeof FILTERS)[number];

function badgeClass(status: string): string {
  if (status === "MATCH") return "badge match";
  if (status === "CHECK" || status === "MULTIPLE_FACES") return "badge check";
  if (status === "PENDING") return "badge neutral";
  return "badge error";
}

export default function ChecksPage() {
  const [rows, setRows] = useState<AdminCheckRow[]>([]);
  const [filter, setFilter] = useState<Filter>("ALL");
  const [error, setError] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState<AdminCheckRow | null>(null);
  const [resolution, setResolution] = useState<ReviewResolution>("CONFIRMED_EMPLOYEE");
  const [notes, setNotes] = useState("");
  const [evidence, setEvidence] = useState<{ checkId: string; url: string } | null>(null);

  async function refresh() {
    try {
      setRows(await api.listChecks(100));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => {
    void refresh();
    const interval = setInterval(() => void refresh(), 20_000);
    return () => clearInterval(interval);
  }, []);

  const visible = rows.filter((r) => {
    if (filter === "ALL") return true;
    if (filter === "ERRORS") return r.status === "VERIFICATION_ERROR" || r.status === "EXPIRED" || r.status === "NO_FACE";
    if (filter === "CHECK") return r.status === "CHECK" || r.status === "MULTIPLE_FACES";
    return r.status === filter;
  });

  async function submitReview() {
    if (!reviewing) return;
    try {
      await api.reviewCheck(reviewing.checkId, resolution, notes);
      setReviewing(null);
      setNotes("");
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function showEvidence(row: AdminCheckRow) {
    try {
      setEvidence({ checkId: row.checkId, url: await api.loadEvidence(row.checkId) });
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <h2 style={{ marginTop: 0 }}>Verification checks</h2>

      <div style={{ display: "flex", gap: 8, marginBottom: 12, alignItems: "center" }}>
        {FILTERS.map((f) => (
          <button key={f} className={`action ${filter === f ? "primary" : ""}`} onClick={() => setFilter(f)}>
            {f === "CHECK" ? "Needs review" : f.toLowerCase()}
          </button>
        ))}
        <span className="muted" style={{ marginLeft: "auto" }}>auto-refreshes every 20s</span>
        <button className="action" onClick={() => void refresh()}>Refresh</button>
      </div>

      {error && <p className="error-text">{error}</p>}

      <table>
        <thead>
          <tr>
            <th>When</th><th>Employee</th><th>Type</th><th>Status</th>
            <th>Similarity</th><th>Review</th><th>Evidence</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((row) => (
            <tr key={row.checkId}>
              <td title={row.checkId}>{new Date(row.requestedAt).toLocaleString()}</td>
              <td>{row.employeeName ?? row.employeeId.slice(0, 8)} <span className="muted">{row.employeeCode ?? ""}</span></td>
              <td className="muted">{row.checkType}</td>
              <td>
                <span className={badgeClass(row.status)}>{row.status}</span>
                {row.mismatchFlag && <span className="badge error" style={{ marginLeft: 4 }}>mismatch?</span>}
                {row.errorDetail && <div className="muted" style={{ fontSize: 12 }}>{row.errorDetail}</div>}
              </td>
              <td>{row.similarityScore?.toFixed(1) ?? "—"}</td>
              <td>
                {row.reviewResolution ? (
                  <span className="badge neutral">{row.reviewResolution}</span>
                ) : row.status === "CHECK" || row.status === "MULTIPLE_FACES" ? (
                  <button className="action" onClick={() => setReviewing(row)}>Review</button>
                ) : (
                  <span className="muted">—</span>
                )}
              </td>
              <td>
                {row.hasEvidence
                  ? <button className="action" onClick={() => void showEvidence(row)}>View</button>
                  : <span className="muted">not retained</span>}
              </td>
            </tr>
          ))}
          {visible.length === 0 && (
            <tr><td colSpan={7} className="muted">No checks match this filter.</td></tr>
          )}
        </tbody>
      </table>

      {reviewing && (
        <div className="overlay">
          <div className="modal">
            <h3 style={{ marginTop: 0 }}>Review check</h3>
            <p className="muted">
              {reviewing.employeeName} — {reviewing.status}
              {reviewing.similarityScore != null && ` (similarity ${reviewing.similarityScore.toFixed(1)})`}.
              A CHECK result is a review request, never proof of misconduct.
            </p>
            <label style={{ display: "block", marginBottom: 8 }}>
              Resolution{" "}
              <select value={resolution} onChange={(e) => setResolution(e.target.value as ReviewResolution)}>
                <option value="CONFIRMED_EMPLOYEE">Confirmed: correct employee</option>
                <option value="CONFIRMED_MISMATCH">Confirmed: identity mismatch</option>
                <option value="TECHNICAL_ISSUE">Technical issue</option>
                <option value="INCONCLUSIVE">Inconclusive</option>
              </select>
            </label>
            <textarea
              placeholder="Notes (optional)"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              style={{ width: "100%" }}
            />
            <div className="row">
              <button className="action" onClick={() => setReviewing(null)}>Cancel</button>
              <button className="primary" onClick={() => void submitReview()}>Record review</button>
            </div>
          </div>
        </div>
      )}

      {evidence && (
        <div className="overlay" onClick={() => setEvidence(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3 style={{ marginTop: 0 }}>Capture evidence</h3>
            <img src={evidence.url} alt="verification capture evidence" />
            <div className="row">
              <button className="action" onClick={() => setEvidence(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
