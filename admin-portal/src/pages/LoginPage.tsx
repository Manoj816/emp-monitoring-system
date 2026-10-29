import { useState, type FormEvent } from "react";
import { signIn } from "../auth";

export default function LoginPage({ onSignedIn }: { onSignedIn: () => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(email.trim(), password);
      onSignedIn();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <section className="login-hero" aria-label="About EmpVerify">
        <div className="brand">
          <span className="brand-mark">EV</span>
          <span>
            <span className="brand-name">EmpVerify</span>
            <span className="brand-sub">Admin console</span>
          </span>
        </div>
        <div>
          <h2>Know who is at the keyboard.</h2>
          <p>
            Remote workforce identity verification: a live face scan at a random time each working day,
            matched against the employee's ID photo and their enrolled scan.
          </p>
          <ul>
            <li>Every check is a consented, visible scan — nothing is captured silently.</li>
            <li>Live-person detection defeats held-up photos and screen replays.</li>
            <li>Flags go to a human review with the pictures side by side, never an automatic verdict.</li>
          </ul>
        </div>
        <p className="fine">Verification decisions are made server-side; the agent on the laptop never sees the result.</p>
      </section>
      <div className="login-side">
      <main className="card login-card">
        <h1>Sign in</h1>
        <p className="muted" style={{ margin: 0 }}>Use your administrator account.</p>
        <form onSubmit={submit}>
          <label>
            Email
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoFocus
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>
          {error && <p className="error-text" style={{ margin: 0 }}>{error}</p>}
          <button className="primary" type="submit" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </main>
      </div>
    </div>
  );
}
