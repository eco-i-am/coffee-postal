// Clock-only mode: the tablet shows nothing but the time clock.
//   - An account that can only use the clock (the "Time Clock" role) is always in this mode.
//   - Any other account that can use the clock can be put in it by a manager. Leaving needs a manager PIN.
// The lock is part of the screen, not the server: for real separation give the tablet the Time Clock role.
// Storage can be blocked or full, so every read and write is guarded.

const key = (user) => `cp.kiosk.v1:${user}`;

/** A branch account whose only job is the clock: it cannot ring up sales or open orders. */
export function clockOnlyAccount(me, can) {
  return !!me && me.scope !== "ADMIN" && can("time_clock_view") && !can("pos_view") && !can("orders_view");
}

export function kioskOn(user) {
  try { return localStorage.getItem(key(user)) === "1"; } catch { return false; }
}

export function setKiosk(user, on) {
  try {
    if (on) localStorage.setItem(key(user), "1");
    else localStorage.removeItem(key(user));
    window.dispatchEvent(new Event("cp-kiosk"));
    return true;
  } catch { return false; }
}

/** True when this signed-in account should see only the clock. */
export function inKiosk(me, can) {
  return clockOnlyAccount(me, can) || kioskOn(me.username);
}

/** Keeps the screen from sleeping while the clock is on show. Returns a function that lets go. Not every browser has it. */
export async function keepAwake() {
  try {
    if (!navigator.wakeLock) return () => {};
    let lock = await navigator.wakeLock.request("screen");
    const again = async () => { if (document.visibilityState === "visible") { try { lock = await navigator.wakeLock.request("screen"); } catch { /* ignore */ } } };
    document.addEventListener("visibilitychange", again);
    return () => { document.removeEventListener("visibilitychange", again); try { lock?.release(); } catch { /* ignore */ } };
  } catch { return () => {}; }
}

export async function goFullscreen() {
  try { if (!document.fullscreenElement) await document.documentElement.requestFullscreen(); } catch { /* the browser said no */ }
}
export async function leaveFullscreen() {
  try { if (document.fullscreenElement) await document.exitFullscreen(); } catch { /* ignore */ }
}
