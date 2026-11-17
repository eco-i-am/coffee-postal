// Turns a raw User-Agent header into something a person can read: "Chrome on Windows".
export function deviceName(ua) {
  if (!ua) return "Unknown";
  const browser = /Edg\//.test(ua) ? "Edge" : /OPR\/|Opera/.test(ua) ? "Opera" : /Firefox\//.test(ua) ? "Firefox"
    : /Chrome\/|CriOS/.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : null;
  const os = /Android/.test(ua) ? "Android" : /iPhone|iPad|iPod/.test(ua) ? "iOS" : /Windows/.test(ua) ? "Windows"
    : /Mac OS X|Macintosh/.test(ua) ? "Mac" : /Linux/.test(ua) ? "Linux" : null;
  if (browser && os) return `${browser} on ${os}`;
  return browser || os || "Other";
}

/** Pretty-prints a JSON string from the audit log; falls back to the raw text. */
export function prettyJson(s) {
  if (!s) return "";
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
}

const ACTION_NAMES = {
  USER_CREATE: "Staff added", USER_UPDATE: "Staff edited", USER_PASSWORD_RESET: "Password reset", USER_PIN_SET: "PIN set",
  USER_UNLOCK: "Account unlocked", PASSWORD_CHANGE: "Own password changed", ROLE_CREATE: "Role added", ROLE_UPDATE: "Role edited", ROLE_DELETE: "Role deleted",
};
/** A friendly name for an action code, or the code itself in a readable form. */
export function actionName(code) {
  return ACTION_NAMES[code] || code.toLowerCase().replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
}
