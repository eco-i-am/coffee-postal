// Time clock on the branch tablet, including with no internet.
//   - a saved copy of the staff list (names plus a salted check of each PIN, never a PIN) lets the tablet
//     recognise a PIN offline;
//   - a punch that could not be sent is kept with the tablet's own clock and uploaded later. It carries its own
//     id, so sending it twice is harmless, and it carries the employee's check rather than the PIN.
// Everything lives in localStorage and every read and write is guarded: storage can be full, blocked or cleared.

const QUEUE_KEY = "cp.punches.v1";
const ROSTER_KEY = "cp.roster.v1:";

function read(key) {
  try { const s = localStorage.getItem(key); return s ? JSON.parse(s) : null; } catch { return null; }
}
function write(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); return true; } catch { return false; }
}

export const saveRoster = (user, roster) => write(ROSTER_KEY + user, { ...roster, savedAt: new Date().toISOString() });
export const loadRoster = (user) => read(ROSTER_KEY + user);

export const loadPunches = () => {
  const q = read(QUEUE_KEY);
  return Array.isArray(q) ? q : [];
};
export const savePunches = (q) => write(QUEUE_KEY, q);

/** SHA-256 as lowercase hex (needs a secure page: https or localhost, which the tablet uses). */
export async function sha256Hex(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Which saved employee has this PIN? Null if none. */
export async function matchPin(roster, pin, hash = sha256Hex) {
  for (const e of roster?.employees || []) {
    if (e.salt && e.check && (await hash(`${e.salt}:${pin}`)) === e.check) return e;
  }
  return null;
}

/** What the next punch for this person will be: the saved state, flipped by each punch still waiting to upload. */
export function nextKind(employee, queue) {
  let isIn = !!employee.in;
  for (const p of queue) if (p.employeeId === employee.id && p.status !== "rejected") isIn = !isIn;
  return isIn ? "OUT" : "IN";
}

/** Who is clocked in, counting punches that have not uploaded yet. */
export function whoIsIn(roster, queue) {
  return (roster?.employees || []).filter((e) => nextKind(e, queue) === "OUT");
}

// photo: base64 JPEG taken at the punch, or null. It travels with the punch and is dropped from the tablet once sent.
export function makePunch({ employee, check, kind, now = new Date(), id = crypto.randomUUID(), photo = null }) {
  return { id, employeeId: employee.id, name: employee.name, check, kind, deviceTime: now.toISOString(), status: "pending", error: null, photo };
}

/**
 * Sends waiting punches oldest first.
 *   sent ok          -> removed
 *   no connection / signed out / server trouble -> stop, keep everything, try again later
 *   the server said no (PIN changed, too old...) -> marked rejected with its message, needs a manager, carry on
 * send(punch) resolves on success, or rejects with an error that has a numeric status (0 = no connection).
 */
export async function uploadPunches(queue, send) {
  const next = [];
  const uploaded = [];
  let stopped = null;
  for (let i = 0; i < queue.length; i++) {
    const p = queue[i];
    if (p.status !== "pending" || stopped) { next.push(p); continue; }
    try {
      await send(p);
      uploaded.push(p);
    } catch (e) {
      const s = e && typeof e.status === "number" ? e.status : 0;
      if (s === 0 || s === 401 || s === 429 || s >= 500) { stopped = s === 0 ? "network" : "server"; next.push(p); }
      else next.push({ ...p, status: "rejected", error: (e && e.message) || "The server did not accept this punch." });
    }
  }
  return { queue: next, uploaded, stopped };
}
