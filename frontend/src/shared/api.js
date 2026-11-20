// One place that talks to the backend. Sends the session cookie and the CSRF header.
export class ApiError extends Error {
  constructor(status, message, fields = {}) {
    super(message);
    this.status = status;
    this.fields = fields;
  }
}

function xsrf() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : "";
}

let onUnauthorized = () => {};
export const setUnauthorizedHandler = (fn) => {
  onUnauthorized = fn;
};

export async function api(path, { method = "GET", body, form, blob, timeoutMs } = {}) {
  const headers = { Accept: "application/json" };
  let payload;
  if (blob) {
    headers["Content-Type"] = blob.type;   // a photo goes up as itself, not as JSON
    payload = blob;
  } else if (form) {
    payload = new URLSearchParams(form);
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  if (method !== "GET") headers["X-XSRF-TOKEN"] = xsrf();

  let res;
  try {
    // a connection that is "up" but not carrying data would otherwise wait for minutes
    const signal = timeoutMs ? AbortSignal.timeout(timeoutMs) : undefined;
    res = await fetch(path, { method, headers, body: payload, credentials: "same-origin", signal });
  } catch {
    throw new ApiError(0, "Could not reach the server. Check the connection and try again.");
  }

  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (res.ok) return data;
  if (res.status === 401 && path !== "/api/auth/login") onUnauthorized();
  throw new ApiError(res.status, data.message || `Something went wrong (${res.status}).`, data.fields || {});
}

// The XSRF cookie is set by the first response; /api/me is called on startup.
export const getMe = () => api("/api/me");
// The server drops its security (CSRF) token cookie when someone signs out, and a missing token is answered with 401, which the
// login page reads as a wrong password. So make sure there is a token before signing in; any response from the server sets one.
async function ensureToken() {
  if (xsrf()) return;
  try { await fetch("/api/me", { credentials: "same-origin", headers: { Accept: "application/json" } }); } catch { /* the login itself will report no connection */ }
}

export const login = async (username, password) => {
  await ensureToken();
  return api("/api/auth/login", { method: "POST", form: { username, password } });
};
export const logout = () => api("/api/auth/logout", { method: "POST" });
