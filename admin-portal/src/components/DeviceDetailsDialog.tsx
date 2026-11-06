import { useEffect, useState } from "react";
import { api, type AdminCheckRow, type DeviceSummary, type Employee } from "../api";
import { checkBadgeClass, checkStatusLabel } from "../utils/checks";

/** How far back the fleet-wide feed is read to find this system's checks. */
const FEED_LIMIT = 500;
const SHOWN = 10;

function statusBadge(status: string): string {
  if (status === "ACTIVE") return "badge match";
  if (status === "PENDING") return "badge check";
  return "badge error"; // REVOKED
}

/**
 * System details popup: the registration record as the agent reported it, the
 * employee it is assigned to (with their enrollment state), and the checks that
 * actually ran on this machine. The actions are the same three the row offers —
 * they are carried out by the page, which owns the confirmations and the refresh.
 */
export default function DeviceDetailsDialog(props: {
  device: DeviceSummary;
  /** The assigned employee's full record, when the list has it. */
  employee: Employee | null;
  busy?: boolean;
  onClose: () => void;
  onAssign: () => void;
  onRevoke: () => void;
  onDelete: () => void;
}) {
  const { device, employee } = props;
  const [checks, setChecks] = useState<AdminCheckRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listChecks(FEED_LIMIT)
      .then((rows) => {
        if (!cancelled) setChecks(rows.filter((r) => r.deviceId === device.id));
      })
      .catch((e) => {
        if (!cancelled) setError((e as Error).message);
      });
    return () => {
      cancelled = true;
    };
  }, [device.id]);

  const name = device.hostname ?? device.id.slice(0, 8);
  const initial = name.trim().charAt(0).toUpperCase() || "?";
  const assigned = device.employeeId !== null;
  const busy = props.busy ?? false;

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <button type="button" className="modal-close" aria-label="Close" title="Close" disabled={busy} onClick={props.onClose}>×</button>
        <header className="details-head">
          <span className="avatar lg">{initial}</span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <h3>
              {name} <span className={statusBadge(device.status)}>{device.status}</span>
              {!assigned && device.status !== "REVOKED" && (
                <span className="badge check" style={{ marginLeft: 4 }}>Unassigned</span>
              )}
            </h3>
            <div className="meta">
              {device.platform}
              {device.agentVersion ? ` · agent ${device.agentVersion}` : ""}
              {" · last seen "}
              {device.lastSeenAt ? new Date(device.lastSeenAt).toLocaleString() : "never"}
            </div>
          </div>
        </header>

        <div className="details-body">
          <section className="panel">
            <h4>System record</h4>
            <dl className="details-grid" style={{ marginBottom: 0 }}>
              <dt>Hostname</dt>
              <dd>{device.hostname ?? <span className="muted">not reported</span>}</dd>
              <dt>Platform</dt>
              <dd>{device.platform}</dd>
              <dt>Agent</dt>
              <dd>
                {device.agentVersion ?? <span className="muted">—</span>}
                {device.agentVersion && (
                  <span className="muted"> · as the agent last reported it (refreshed at every sign-in)</span>
                )}
              </dd>
              <dt>Status</dt>
              <dd><span className={statusBadge(device.status)}>{device.status}</span></dd>
              <dt>Enrollment</dt>
              <dd>
                {!assigned ? (
                  <span className="muted">—</span>
                ) : device.enrolledAt ? (
                  <span className="badge match">done {new Date(device.enrolledAt).toLocaleString()}</span>
                ) : (
                  <span className="badge neutral">pending first scan</span>
                )}
              </dd>
              <dt>Registered</dt>
              <dd>
                {device.registeredAt ? new Date(device.registeredAt).toLocaleString() : <span className="muted">—</span>}
              </dd>
              <dt>Last seen</dt>
              <dd>
                {device.lastSeenAt ? new Date(device.lastSeenAt).toLocaleString() : <span className="muted">never</span>}
              </dd>
              <dt>System ID</dt>
              <dd className="id-value">{device.id}</dd>
            </dl>
            <p className="fine">
              A reinstalled system comes back as Unassigned on this same row, keeping its history.
            </p>
          </section>

          <section className="panel">
            <h4>Assigned employee</h4>
            {!assigned ? (
              <p className="muted" style={{ margin: 0 }}>
                Unassigned. Nothing is verified on this system until it is linked to an employee; the first check
                after that is the enrollment scan.
              </p>
            ) : employee ? (
              <>
                <div style={{ fontWeight: 600 }}>
                  {employee.fullName}{" "}
                  <span className={`badge ${employee.status === "ACTIVE" ? "match" : "neutral"}`}>
                    {employee.status}
                  </span>
                </div>
                <div className="muted" style={{ fontSize: 12, marginTop: 2 }}>
                  {employee.employeeCode} · {employee.email}
                  {employee.department ? ` · ${employee.department}` : ""}
                </div>
                <div style={{ marginTop: 10, display: "flex", gap: 6, flexWrap: "wrap" }}>
                  {employee.hasReferenceImage
                    ? <span className="badge match">ID/photo uploaded</span>
                    : <span className="badge check">ID/photo missing</span>}
                  {employee.hasLiveEnrollment
                    ? <span className="badge match">live enrollment done</span>
                    : <span className="badge neutral">pending first scan</span>}
                </div>
                <p className="fine">Full record and pictures are on the Employees page.</p>
              </>
            ) : (
              <p className="muted" style={{ margin: 0 }}>
                {device.employeeName ?? device.employeeId?.slice(0, 8)}
              </p>
            )}
          </section>

          <section className="panel" style={{ gridColumn: "1 / -1" }}>
            <h4>Checks on this system</h4>
            {error && <p className="error-text" style={{ marginTop: 0 }}>{error}</p>}
            {checks === null ? (
              <p className="muted" style={{ margin: 0 }}>Loading…</p>
            ) : checks.length === 0 ? (
              <p className="muted" style={{ margin: 0 }}>No check has run on this system yet.</p>
            ) : (
              <ul className="record-list">
                {checks.slice(0, SHOWN).map((c) => (
                  <li key={c.checkId}>
                    <span className="host">
                      <span className={checkBadgeClass(c.status)}>{checkStatusLabel(c.status)}</span>
                      {c.mismatchFlag && <span className="badge error">mismatch?</span>}
                      {c.reviewResolution && <span className="badge neutral">{c.reviewResolution}</span>}
                      <span style={{ fontWeight: 400 }}>{new Date(c.requestedAt).toLocaleString()}</span>
                    </span>
                    <span className="sub">
                      {c.checkType}
                      {c.similarityScore != null ? ` · similarity ${c.similarityScore.toFixed(1)}` : ""}
                      {c.livenessConfidence != null ? ` · live person ${c.livenessConfidence.toFixed(1)}` : ""}
                      {c.errorDetail ? ` · ${c.errorDetail}` : ""}
                    </span>
                  </li>
                ))}
              </ul>
            )}
            {checks !== null && checks.length > 0 && (
              <p className="fine">
                {checks.length > SHOWN
                  ? `Showing the ${SHOWN} most recent of ${checks.length}. `
                  : ""}
                Read from the latest {FEED_LIMIT} checks across the fleet — older ones are on the
                Verification checks page.
              </p>
            )}
          </section>
        </div>

        <footer className="details-actions">
          <div className="left">
            {device.status !== "REVOKED" && (
              <button
                className={assigned ? "action" : "primary"}
                disabled={busy}
                onClick={props.onAssign}
                title={assigned ? "Hand this system to a different employee" : "Link this system to an employee and activate it"}
              >
                {assigned ? "Reassign to another employee" : "Assign employee"}
              </button>
            )}
            <button
              className="action"
              disabled={busy || device.status === "REVOKED"}
              onClick={props.onRevoke}
              title="Lost or retired machine: blocks it immediately"
            >
              Revoke
            </button>
            <button
              className="action"
              disabled={busy}
              onClick={props.onDelete}
              title="Remove this row; its check history stays with the employee"
            >
              Delete
            </button>
          </div>
          <button className="action" disabled={busy} onClick={props.onClose}>Close</button>
        </footer>
      </div>
    </div>
  );
}
