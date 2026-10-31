import { useEffect, useRef, useState } from "react";
import { api, DEPARTMENTS, type DeviceSummary, type Employee } from "../api";
import ConfirmPhotoDialog from "./ConfirmPhotoDialog";

/**
 * Employee details popup: the record itself (editable in place via the pencil
 * icon — a name change, a department move, a typo in the code or email),
 * identity references (uploaded ID/photo and the enrolled live scan), the
 * systems assigned to the employee with their enrollment state, and the two
 * actions that make the employee enroll again: replacing the ID/photo, or
 * asking for the first scan again as it is. Viewing a reference image is
 * audited server-side.
 */
export default function EmployeeDetailsDialog(props: {
  employee: Employee;
  onClose: () => void;
  /** Something changed (details edited, photo replaced, re-enrollment requested): the parent refreshes. */
  onChanged: () => void;
}) {
  const { employee } = props;
  const [reference, setReference] = useState<string | null>(null);
  const [enrolled, setEnrolled] = useState<string | null>(null);
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);
  // Picked but not uploaded: the confirmation step shows it next to the current one.
  const [pendingPhoto, setPendingPhoto] = useState<Blob | null>(null);
  const [form, setForm] = useState({ employeeCode: "", fullName: "", email: "", department: "" });
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

  function confirmReplacePhoto() {
    const image = pendingPhoto;
    if (!image) return;
    void run("Replace photo", async () => {
      await api.uploadReferenceImage(employee.id, image);
      setPendingPhoto(null);
      return "ID/photo replaced. The previous live scan is retired; each system of this employee runs the enrollment scan as its next check (the day's random time, or Verify now).";
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
    setError(null);
    setPendingPhoto(file); // nothing is uploaded until the confirmation step
  }

  // ---- editing the record in place --------------------------------------------

  // A legacy record may carry a department outside today's fixed list (or none):
  // it still shows as the current choice so saving without touching it keeps it.
  const departments: string[] = [...DEPARTMENTS];
  if (employee.department && !departments.includes(employee.department)) {
    departments.unshift(employee.department);
  }

  function startEdit() {
    setForm({
      employeeCode: employee.employeeCode,
      fullName: employee.fullName,
      email: employee.email,
      department: employee.department ?? "",
    });
    setError(null);
    setNotice(null);
    setEditing(true);
  }

  const formComplete =
    form.employeeCode.trim() !== "" && form.fullName.trim() !== "" &&
    form.email.trim() !== "" && form.department !== "";

  function saveEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!formComplete) return;
    void run("Save details", async () => {
      await api.updateEmployee(employee.id, {
        employeeCode: form.employeeCode.trim(),
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        department: form.department,
      });
      setEditing(false);
      return "Details updated.";
    });
  }

  function requestReEnrollment() {
    if (
      !confirm(
        `Ask ${employee.fullName} for the first scan again? The current live scan is retired and their system(s) ` +
          `run the enrollment scan as the next check (the day's random time, or Verify now). The uploaded ID/photo stays.`,
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

  const initial = employee.fullName.trim().charAt(0).toUpperCase() || "?";
  const active = devices.filter((d) => d.status !== "REVOKED");

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <header className="details-head">
          <span className="avatar lg">{initial}</span>
          <div style={{ flex: 1, minWidth: 0 }}>
            <h3>
              {employee.fullName}{" "}
              <span className={`badge ${employee.status === "ACTIVE" ? "match" : "neutral"}`}>{employee.status}</span>
            </h3>
            <div className="meta">
              {employee.employeeCode} · {employee.email}{employee.department ? ` · ${employee.department}` : ""}
            </div>
          </div>
          {!editing && (
            <button
              className="kebab"
              disabled={busy}
              onClick={startEdit}
              title="Edit details (name, code, email, department)"
              aria-label="Edit details"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor"
                   strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 20h9" />
                <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
              </svg>
            </button>
          )}
        </header>

        {editing && (
          <section className="panel" style={{ marginBottom: 14 }}>
            <h4>Edit details</h4>
            <form onSubmit={saveEdit}>
              <div className="form-grid">
                <label>
                  Employee code *
                  <input
                    required
                    autoFocus
                    value={form.employeeCode}
                    onChange={(e) => setForm({ ...form, employeeCode: e.target.value })}
                  />
                </label>
                <label>
                  Full name *
                  <input required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
                </label>
                <label>
                  Email *
                  <input
                    required
                    type="email"
                    value={form.email}
                    onChange={(e) => setForm({ ...form, email: e.target.value })}
                  />
                </label>
                <label>
                  Department *
                  <select
                    required
                    value={form.department}
                    onChange={(e) => setForm({ ...form, department: e.target.value })}
                  >
                    <option value="">Select department…</option>
                    {departments.map((d) => (
                      <option key={d} value={d}>{d}</option>
                    ))}
                  </select>
                </label>
              </div>
              <p className="fine">
                Corrections only: these details do not affect verification. Use "Replace ID / photo" to change the picture.
              </p>
              <div className="row">
                <button type="button" className="action" disabled={busy} onClick={() => setEditing(false)}>Cancel</button>
                <button type="submit" className="primary" disabled={busy || !formComplete}>
                  {busy ? "Saving…" : "Save changes"}
                </button>
              </div>
            </form>
          </section>
        )}

        <div className="details-body">
          <section className="panel">
            <h4>Identity references</h4>
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
                    {employee.hasLiveEnrollment ? "Loading…" : "Pending first scan"}
                  </div>
                )}
                <figcaption>Enrolled live scan</figcaption>
              </figure>
            </div>
            <p className="fine">
              Every check is compared against both pictures. The live scan is captured by the system's
              enrollment scan and is refreshed whenever the employee enrolls again.
            </p>
          </section>

          <section className="panel">
            <h4>Assigned systems</h4>
            {active.length === 0 ? (
              <p className="muted" style={{ margin: 0 }}>No system assigned. Assign one on the Devices page.</p>
            ) : (
              <ul className="system-list">
                {active.map((d) => (
                  <li key={d.id}>
                    <span className="host">
                      {d.hostname ?? d.id.slice(0, 8)}
                      <span className={`badge ${d.status === "ACTIVE" ? "match" : "check"}`}>{d.status}</span>
                      {d.enrolledAt
                        ? <span className="badge match">enrolled {new Date(d.enrolledAt).toLocaleDateString()}</span>
                        : <span className="badge neutral">pending first scan</span>}
                    </span>
                    <span className="sub">
                      agent {d.agentVersion ?? "?"} · last seen {d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : "never"}
                    </span>
                  </li>
                ))}
              </ul>
            )}
            {devices.length > active.length && (
              <p className="fine">{devices.length - active.length} revoked system(s) not shown.</p>
            )}
          </section>
        </div>

        {notice && <p className="notice" style={{ marginBottom: 0 }}>{notice}</p>}
        {error && <p className="error-text">{error}</p>}

        <footer className="details-actions">
          <div className="left">
            <button className="primary" disabled={busy} onClick={() => fileInputRef.current?.click()}
                    title="Upload a new ID/photo; the employee enrolls again against it">
              Replace ID / photo
            </button>
            <button className="action" disabled={busy || employee.status !== "ACTIVE"} onClick={requestReEnrollment}
                    title="Keep the photo, but ask the employee for the enrollment scan again">
              Request first scan again
            </button>
          </div>
          <button className="action" disabled={busy} onClick={props.onClose}>Close</button>
        </footer>

        <input ref={fileInputRef} type="file" accept="image/jpeg,image/png" hidden onChange={onFileChosen} />
      </div>

      {pendingPhoto && (
        <ConfirmPhotoDialog
          employee={employee}
          photo={pendingPhoto}
          busy={busy}
          onCancel={() => setPendingPhoto(null)}
          onConfirm={confirmReplacePhoto}
        />
      )}
    </div>
  );
}
