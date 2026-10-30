import { useEffect, useRef, useState } from "react";
import { api, type DeviceSummary, type Employee } from "../api";
import CameraCapture from "./CameraCapture";

/**
 * Employee details popup: the record, the uploaded ID/photo, the enrolled live
 * scan and the systems assigned to the employee, plus the two actions that make
 * the employee enroll again: replacing the ID/photo, or asking for the first
 * scan again as it is. Viewing a reference image is audited server-side.
 */
export default function EmployeeDetailsDialog(props: {
  employee: Employee;
  onClose: () => void;
  /** Something changed (photo replaced, re-enrollment requested): the parent refreshes. */
  onChanged: () => void;
}) {
  const { employee } = props;
  const [reference, setReference] = useState<string | null>(null);
  const [enrolled, setEnrolled] = useState<string | null>(null);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [camera, setCamera] = useState(false);
  const [version, setVersion] = useState(0); // bump to reload the pictures
  const fileInputRef = useRef<HTMLInputElement>(null);

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
      setReference(null);
      setEnrolled(null);
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
  }, [employee, version]);

  async function run(label: string, work: () => Promise<string>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const message = await work();
      setNotice(message);
      setVersion((v) => v + 1);
      props.onChanged();
    } catch (e) {
      setError(`${label}: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  }

  function replacePhoto(image: Blob) {
    void run("Replace photo", async () => {
      await api.uploadReferenceImage(employee.id, image);
      return "ID/photo replaced. The previous live scan is retired; every system of this employee runs the enrollment scan at its next check (within a minute if the PC is on).";
    });
  }

  function onFileChosen(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (!/^image\/(jpeg|png)$/.test(file.type)) {
      setError("The ID/photo must be a JPEG or PNG image.");
      return;
    }
    replacePhoto(file);
  }

  function requestReEnrollment() {
    if (
      !confirm(
        `Ask ${employee.fullName} for the first scan again? Their current live scan is retired and their system(s) ` +
          `open the enrollment scan at the next check (within a minute if the PC is on). The uploaded ID/photo stays.`,
      )
    ) {
      return;
    }
    void run("Request first scan", async () => {
      const r = await api.requestReEnrollment(employee.id);
      return r.systemsReset === 0
        ? "Re-enrollment requested. No system was enrolled yet, so nothing else changed."
        : `Re-enrollment requested: ${r.systemsReset} system(s) will ask for the enrollment scan.`;
    });
  }

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
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
              : devices.map((d) => (
                  <span key={d.id} style={{ display: "block" }}>
                    {d.hostname ?? d.id.slice(0, 8)} · {d.status}
                    {d.status !== "REVOKED" && (
                      d.enrolledAt
                        ? <span className="badge match" style={{ marginLeft: 6 }}>enrolled {new Date(d.enrolledAt).toLocaleDateString()}</span>
                        : <span className="badge neutral" style={{ marginLeft: 6 }}>pending first scan</span>
                    )}
                  </span>
                ))}
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
          <div className="photo-actions" style={{ justifyContent: "center" }}>
            <button className="action" disabled={busy} onClick={() => fileInputRef.current?.click()}
                    title="Upload a new ID/photo; the employee enrolls again against it">
              Replace ID / photo
            </button>
            <button className="action" disabled={busy} onClick={() => setCamera(true)}>Replace with webcam</button>
            <button className="action" disabled={busy || employee.status !== "ACTIVE"} onClick={requestReEnrollment}
                    title="Keep the photo, but ask the employee for the enrollment scan again">
              Request first scan again
            </button>
          </div>
        </div>

        {notice && <p className="notice" style={{ marginBottom: 0 }}>{notice}</p>}
        {error && <p className="error-text">{error}</p>}

        <div className="row">
          <button className="action" disabled={busy} onClick={props.onClose}>Close</button>
        </div>

        <input ref={fileInputRef} type="file" accept="image/jpeg,image/png" hidden onChange={onFileChosen} />
      </div>

      {camera && (
        <CameraCapture
          onCancel={() => setCamera(false)}
          onCapture={(blob) => {
            setCamera(false);
            replacePhoto(blob);
          }}
        />
      )}
    </div>
  );
}
