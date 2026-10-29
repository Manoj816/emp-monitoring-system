import { useEffect, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";

/**
 * Employee details popup: the record, the uploaded ID/photo, the enrolled live
 * scan (once the first matching scan has happened) and the systems assigned to
 * the employee. Viewing a reference image is audited server-side.
 */
export default function EmployeeDetailsDialog(props: { employee: Employee; onClose: () => void }) {
  const { employee } = props;
  const [reference, setReference] = useState<string | null>(null);
  const [enrolled, setEnrolled] = useState<string | null>(null);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const urls: string[] = [];
    async function load() {
      try {
        const all = await api.listDevices();
        if (!cancelled) setDevices(all.filter((d) => d.employeeId === employee.id));
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      }
      if (employee.hasReferenceImage) {
        try {
          const url = await api.loadReferenceImage(employee.id);
          urls.push(url);
          if (!cancelled) setReference(url);
        } catch (e) {
          if (!cancelled) setError((e as Error).message);
        }
      }
      if (employee.hasLiveEnrollment) {
        try {
          const url = await api.loadEnrolledImage(employee.id);
          urls.push(url);
          if (!cancelled) setEnrolled(url);
        } catch (e) {
          if (!cancelled) setError((e as Error).message);
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
      urls.forEach((u) => URL.revokeObjectURL(u));
    };
  }, [employee]);

  return (
    <div className="overlay" onClick={props.onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>
          {employee.fullName}{" "}
          <span className={`badge ${employee.status === "ACTIVE" ? "match" : "neutral"}`}>{employee.status}</span>
        </h3>

        <dl className="details-grid">
          <dt>Employee code</dt><dd>{employee.employeeCode}</dd>
          <dt>Email</dt><dd>{employee.email}</dd>
          <dt>Department</dt><dd>{employee.department ?? "—"}</dd>
          <dt>Assigned systems</dt>
          <dd>
            {devices.length === 0
              ? "none"
              : devices.map((d) => `${d.hostname ?? d.id.slice(0, 8)} (${d.status})`).join(", ")}
          </dd>
        </dl>

        <div className="photo-row">
          <figure>
            {reference ? (
              <img className="photo-preview" src={reference} alt="Uploaded ID / photo" />
            ) : (
              <div className="photo-placeholder">No ID/photo uploaded.</div>
            )}
            <figcaption>Uploaded ID / photo</figcaption>
          </figure>
          <figure>
            {enrolled ? (
              <img className="photo-preview" src={enrolled} alt="Enrolled live scan" />
            ) : (
              <div className="photo-placeholder">
                {employee.hasLiveEnrollment ? "Loading…" : "Pending first scan — the first live scan that matches the photo is kept here."}
              </div>
            )}
            <figcaption>Enrolled live scan</figcaption>
          </figure>
        </div>

        {error && <p className="error-text">{error}</p>}

        <div className="row">
          <button className="action" onClick={props.onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
