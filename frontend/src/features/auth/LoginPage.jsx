import { useState } from "react";
import { useAuth } from "../../app/AuthContext.jsx";
import { ApiError } from "../../shared/api.js";
import { LOGIN_ART } from "../../shared/loginArt.js";
import PasswordInput from "../../shared/PasswordInput.jsx";

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
    // read what is in the boxes now: a browser that autofills a saved password may not tell React about it
    const form = e.currentTarget.elements;
    const u = (form.username?.value ?? username).trim();
    const p = form.password?.value ?? password;
    try {
      await signIn(u, p);
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
            <img className="loginlogo" src="/logo.png" alt="Coffee Postal" width="300" height="91" />
          </div>
          <p>Your affordable freshly brewed coffee destination</p>
          <div className="lgart" dangerouslySetInnerHTML={{ __html: LOGIN_ART }} />
        </div>
        <form className="pc-r" onSubmit={submit}>
          <h2>Welcome back</h2>
          <p className="sub">Sign in to start your shift.</p>
          <div className="addr">
            <label htmlFor="username">Username</label>
            <input id="username" name="username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required />
          </div>
          <div className="addr">
            <label htmlFor="password">Password</label>
            <PasswordInput id="password" name="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
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
