import { useState } from "react";
import { useAuth } from "../../app/AuthContext.jsx";
import { ApiError } from "../../shared/api.js";
import { LOGIN_ART } from "../../shared/loginArt.js";

export default function LoginPage() {
  const { signIn } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await signIn(username.trim(), password);
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? "That username and password do not match. Try again."
          : err.message || "Could not sign in."
      );
      setBusy(false);
    }
  }

  return (
    <div id="login">
      <div className="postcard">
        <div className="pc-l">
          <div className="logo">
            <svg width="62" height="68" viewBox="0 0 40 44" aria-hidden="true">
              <path d="M20 3C29 3 35 10 35 18C35 28 20 41 20 41C20 41 5 28 5 18C5 10 11 3 20 3Z" fill="#FE8282" />
              <ellipse cx="20" cy="15" rx="8" ry="6" fill="#FFC74A" transform="rotate(-25 20 15)" />
              <path d="M14 17c4-1 8-5 12-6" stroke="#FE8282" strokeWidth="1.6" fill="none" />
            </svg>
            <b><span>COFFEE</span><br /><span>POSTAL</span></b>
          </div>
          <p>Your affordable freshly brewed coffee destination</p>
          <div className="lgart" dangerouslySetInnerHTML={{ __html: LOGIN_ART }} />
        </div>
        <form className="pc-r" onSubmit={submit}>
          <h2>Welcome back</h2>
          <p className="sub">Sign in to start your shift.</p>
          <div className="addr">
            <label htmlFor="username">Username</label>
            <input id="username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required />
          </div>
          <div className="addr">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          </div>
          {error && <p className="err" role="alert">{error}</p>}
          <button className="btn" style={{ width: "100%" }} type="submit" disabled={busy}>
            {busy ? "Signing in..." : "Sign in"}
          </button>
          <div className="lgfoot"><span>Forgot your password? Ask HQ to reset it.</span></div>
        </form>
      </div>
    </div>
  );
}
