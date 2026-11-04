import { useEffect, useState } from "react";
import { api, type AdminCheckRow, type ReviewResolution } from "../api";
import { checkBadgeClass, checkStatusLabel, needsReview } from "../utils/checks";

const RESOLUTIONS: { value: ReviewResolution; label: string }[] = [
  { value: "CONFIRMED_EMPLOYEE", label: "Confirmed: correct employee" },
  { value: "CONFIRMED_MISMATCH", label: "Confirmed: identity mismatch" },
  { value: "TECHNICAL_ISSUE", label: "Technical issue" },
  { value: "INCONCLUSIVE", label: "Inconclusive" },
];

/**
 * A check nobody answered can carry a frame taken automatically as its window
 * closed. It is evidence of an empty desk, not a scan the employee sat for, and
 * nothing was compared against it — labelling it like a normal capture would
 * invite exactly the wrong reading.
 */
function unanswered(check: AdminCheckRow): boolean {
  return check.status === "EXPIRED" && check.hasEvidence;
}

/** Why no picture is shown for this check — the reason itself matters for a review. */
function capturedPlaceholder(check: AdminCheckRow, loaded: boolean): string {
  if (check.hasEvidence) {
    return loaded ? "The retained frame could not be loaded (it is not a readable image)." : "Loading…";
  }
  if (check.errorDetail?.includes("no usable frame")) {
    return "The liveness service returned no picture for this scan — the camera most likely did not show a live face.";
  }
  return "No frame was retained for this check.";
}

/**
 * Check details popup: one scan in full — the two references it was judged
 * against next to the frame captured at the time, the scores, the system it ran
 * on, and the review. A check waiting for a human is answered here; one already
 * resolved shows its resolution instead.
 */
export default function CheckDetailsDialog(props: {
  check: AdminCheckRow;
  /** Hostname of the system the check ran on, when that row still exists. */
  deviceHostname?: string | null;
  onClose: () => void;
  /** A review was recorded: the parent refreshes. */
  onReviewed: () => void;
}) {
  const { check } = props;
  const [images, setImages] = useState<{
    reference?: string; enrolled?: string; captured?: string; loaded: boolean;
  }>({ loaded: false });
  const [resolution, setResolution] = useState<ReviewResolution>("CONFIRMED_EMPLOYEE");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Keyed on the check, not on the row object: the list behind this popup
  // refreshes every 20s and must not reload the pictures under the reader.
  useEffect(() => {
    let cancelled = false;
    const urls: string[] = [];
    setImages({ loaded: false });
    void (async () => {
      const [reference, enrolled, captured] = await Promise.all([
        api.loadReferenceImage(check.employeeId).catch(() => undefined),
        api.loadEnrolledImage(check.employeeId).catch(() => undefined),
        check.hasEvidence ? api.loadEvidence(check.checkId).catch(() => undefined) : Promise.resolve(undefined),
      ]);
      [reference, enrolled, captured].forEach((u) => u && urls.push(u));
      if (cancelled) {
        urls.forEach((u) => URL.revokeObjectURL(u));
        return;
      }
      setImages({ reference, enrolled, captured, loaded: true });
    })();
    return () => {
      cancelled = true;
      urls.forEach((u) => URL.revokeObjectURL(u));
    };
  }, [check.checkId, check.employeeId, check.hasEvidence]);

  async function submitReview() {
    setBusy(true);
    setError(null);
    try {
      await api.reviewCheck(check.checkId, resolution, notes);
      setNotes("");
      props.onReviewed();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const employee = check.employeeName ?? check.employeeId.slice(0, 8);
  const initial = employee.trim().charAt(0).toUpperCase() || "?";

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <header className="details-head">
          <span className="avatar lg">{initial}</span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <h3>
              {employee}{" "}
              <span className={checkBadgeClass(check.status)}>{checkStatusLabel(check.status)}</span>
              {check.mismatchFlag && <span className="badge error" style={{ marginLeft: 4 }}>mismatch?</span>}
            </h3>
            <div className="meta">
              {check.employeeCode ? `${check.employeeCode} · ` : ""}
              {check.checkType} · {new Date(check.requestedAt).toLocaleString()}
            </div>
          </div>
        </header>

        <section className="panel" style={{ marginBottom: 14 }}>
          <h4>Pictures</h4>
          <div className="photo-row">
            <figure>
              {images.reference ? (
                <img className="photo-preview" src={images.reference} alt="uploaded ID or photo" />
              ) : (
                <div className="photo-placeholder">{images.loaded ? "No ID/photo uploaded." : "Loading…"}</div>
              )}
              <figcaption>Uploaded ID / photo</figcaption>
            </figure>
            <figure>
              {images.enrolled ? (
                <img className="photo-preview" src={images.enrolled} alt="enrolled live scan" />
              ) : (
                <div className="photo-placeholder">
                  {images.loaded ? "Not enrolled yet (no matching live scan so far)." : "Loading…"}
                </div>
              )}
              <figcaption>Enrolled live scan</figcaption>
            </figure>
            <figure>
              {images.captured ? (
                <img className="photo-preview" src={images.captured} alt="frame captured during this check" />
              ) : (
                <div className="photo-placeholder">{capturedPlaceholder(check, images.loaded)}</div>
              )}
              <figcaption>
                {unanswered(check) ? "Taken automatically when nobody answered" : "Captured during this check"}
              </figcaption>
            </figure>
          </div>
          <p className="fine">
            The first two are the employee's current references; a frame is kept only for a check that was flagged
            or needs review. Opening a picture is recorded in the audit log.
          </p>
          {unanswered(check) && (
            <p className="fine">
              Nobody completed this check, so this frame was taken by itself as the response window closed. It has
              not been compared against the references above and is not a verdict about anybody — it only shows
              what the camera saw at that moment.
            </p>
          )}
        </section>

        <div className="details-body">
          <section className="panel">
            <h4>Check record</h4>
            <dl className="details-grid" style={{ marginBottom: 0 }}>
              <dt>Status</dt>
              <dd>
                <span className={checkBadgeClass(check.status)}>{checkStatusLabel(check.status)}</span>
                {check.mismatchFlag && <span className="badge error" style={{ marginLeft: 4 }}>mismatch?</span>}
              </dd>
              <dt>Type</dt>
              <dd>{check.checkType}</dd>
              <dt>Face similarity</dt>
              <dd>{check.similarityScore != null ? `${check.similarityScore.toFixed(1)} / 100` : "not computed"}</dd>
              <dt>Live person</dt>
              <dd>{check.livenessConfidence != null ? `${check.livenessConfidence.toFixed(1)} / 100` : "—"}</dd>
              <dt>System</dt>
              <dd>
                {check.deviceId === null ? (
                  <span className="muted">system removed</span>
                ) : (
                  props.deviceHostname ?? check.deviceId.slice(0, 8)
                )}
              </dd>
              <dt>Requested</dt>
              <dd>{new Date(check.requestedAt).toLocaleString()}</dd>
              <dt>Completed</dt>
              <dd>
                {check.completedAt ? new Date(check.completedAt).toLocaleString() : <span className="muted">—</span>}
              </dd>
              <dt>Evidence</dt>
              <dd>{check.hasEvidence ? "retained" : <span className="muted">not retained</span>}</dd>
              <dt>Check ID</dt>
              <dd className="id-value">{check.checkId}</dd>
            </dl>
            {check.errorDetail && (
              <p className="fine" style={{ color: "var(--bad)" }}>
                <strong>What went wrong:</strong> {check.errorDetail}
              </p>
            )}
          </section>

          <section className="panel">
            <h4>Review</h4>
            {check.reviewResolution ? (
              <>
                <span className="badge neutral">{check.reviewResolution}</span>
                <p className="fine">Already reviewed. The resolution and its notes are kept in the audit log.</p>
              </>
            ) : needsReview(check) ? (
              <>
                <p className="fine" style={{ margin: "0 0 10px" }}>
                  A CHECK result is a review request, never proof of misconduct.
                </p>
                <label style={{ display: "block", marginBottom: 8, fontSize: 12, color: "var(--muted)" }}>
                  Resolution
                  <select
                    value={resolution}
                    disabled={busy}
                    onChange={(e) => setResolution(e.target.value as ReviewResolution)}
                    style={{ display: "block", width: "100%", marginTop: 2 }}
                  >
                    {RESOLUTIONS.map((r) => (
                      <option key={r.value} value={r.value}>{r.label}</option>
                    ))}
                  </select>
                </label>
                <textarea
                  placeholder="Notes (optional)"
                  value={notes}
                  disabled={busy}
                  onChange={(e) => setNotes(e.target.value)}
                  rows={3}
                  style={{ width: "100%" }}
                />
                <button className="primary" disabled={busy} onClick={() => void submitReview()} style={{ marginTop: 8 }}>
                  {busy ? "Recording…" : "Record review"}
                </button>
              </>
            ) : (
              <p className="muted" style={{ margin: 0 }}>No review is needed for this result.</p>
            )}
          </section>
        </div>

        {error && <p className="error-text">{error}</p>}

        <footer className="details-actions">
          <span className="muted" style={{ fontSize: 12 }}>
            Every verification decision is made server-side; this screen only records what a human concluded.
          </span>
          <button className="action" disabled={busy} onClick={props.onClose}>Close</button>
        </footer>
      </div>
    </div>
  );
}
