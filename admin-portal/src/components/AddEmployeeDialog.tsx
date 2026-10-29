import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import CameraCapture from "./CameraCapture";

/**
 * "Add employee" popup. The ID/photo is mandatory: it is the reference every
 * verification is compared against, so the employee is created together with it
 * in a single request (the server refuses a record without an image).
 */
export default function AddEmployeeDialog(props: { onClose: () => void; onCreated: () => void }) {
  const [form, setForm] = useState({ employeeCode: "", fullName: "", email: "", department: "" });
  const [photo, setPhoto] = useState<Blob | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [camera, setCamera] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!photo) {
      setPreview(null);
      return;
    }
    const url = URL.createObjectURL(photo);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [photo]);

  const detailsComplete =
    form.employeeCode.trim() !== "" && form.fullName.trim() !== "" && form.email.trim() !== "";
  const canSubmit = detailsComplete && photo !== null && !busy;

  function onFileChosen(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (!/^image\/(jpeg|png)$/.test(file.type)) {
      setError("The ID/photo must be a JPEG or PNG image.");
      return;
    }
    setError(null);
    setPhoto(file);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!photo) {
      setError("A photo or ID image is required to create an employee.");
      return;
    }
    if (!detailsComplete) return;
    setBusy(true);
    setError(null);
    try {
      await api.createEmployee(
        {
          employeeCode: form.employeeCode.trim(),
          fullName: form.fullName.trim(),
          email: form.email.trim(),
          department: form.department.trim() || undefined,
        },
        photo,
      );
      props.onCreated();
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <div className="overlay" onClick={busy ? undefined : props.onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3 style={{ marginTop: 0 }}>Add employee</h3>
        <form onSubmit={submit}>
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
              Department
              <input value={form.department} onChange={(e) => setForm({ ...form, department: e.target.value })} />
            </label>
          </div>

          <div className="photo-pick">
            <div className="photo-pick-head">
              <span>ID / photo *</span>
              <span className="muted">Required — every verification is compared against this image.</span>
            </div>
            <div className="photo-pick-body">
              {preview ? (
                <img className="photo-preview" src={preview} alt="Selected ID / photo" />
              ) : (
                <div className="photo-placeholder">No image selected</div>
              )}
              <div className="photo-actions">
                <button type="button" className="action" disabled={busy} onClick={() => fileInputRef.current?.click()}>
                  Upload photo / ID
                </button>
                <button type="button" className="action" disabled={busy} onClick={() => setCamera(true)}>
                  Use webcam
                </button>
                {photo && (
                  <button type="button" className="action" disabled={busy} onClick={() => setPhoto(null)}>
                    Remove
                  </button>
                )}
              </div>
            </div>
          </div>

          {error && <p className="error-text">{error}</p>}
          {!photo && detailsComplete && !error && (
            <p className="muted" style={{ margin: "8px 0 0" }}>Add a photo or ID image to enable Create.</p>
          )}

          <div className="row">
            <button type="button" className="action" disabled={busy} onClick={props.onClose}>Cancel</button>
            <button type="submit" className="primary" disabled={!canSubmit}>
              {busy ? "Creating…" : "Create employee"}
            </button>
          </div>
        </form>

        <input ref={fileInputRef} type="file" accept="image/jpeg,image/png" hidden onChange={onFileChosen} />
      </div>

      {camera && (
        <CameraCapture
          onCancel={() => setCamera(false)}
          onCapture={(blob) => {
            setCamera(false);
            setError(null);
            setPhoto(blob);
          }}
        />
      )}
    </div>
  );
}
