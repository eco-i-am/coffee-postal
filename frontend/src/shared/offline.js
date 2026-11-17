// Offline support for the cashier screen: a saved copy of the menu, and a queue of orders sold without
// internet. Orders are safe to send more than once because each carries its own id and the server
// answers a repeat with the original order number.
//
// Everything lives in this browser's localStorage. Every read and write is guarded, because storage can be
// full, blocked or cleared; the POS must still work online if it is.

const QUEUE_KEY = "cp.queue.v1";
const MENU_KEY = "cp.menu.v1:";
const ME_KEY = "cp.me.v1";

function read(key) {
  try { const s = localStorage.getItem(key); return s ? JSON.parse(s) : null; } catch { return null; }
}
function write(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); return true; } catch { return false; }
}
function remove(key) { try { localStorage.removeItem(key); } catch { /* nothing to do */ } }

// ---- who is signed in (lets the app open with no internet) ----
export const saveMe = (me) => write(ME_KEY, me);
export const loadMe = () => read(ME_KEY);
export const clearMe = () => remove(ME_KEY);

// ---- the menu ----
export const saveMenu = (user, menu) => write(MENU_KEY + user, { menu, savedAt: new Date().toISOString() });
export const loadMenu = (user) => read(MENU_KEY + user);

// ---- the order queue ----
// entry: { id, by, queuedAt, request, expectedTotal, receipt, status: "pending" | "rejected", error, ref }
export const loadQueue = () => {
  const q = read(QUEUE_KEY);
  return Array.isArray(q) ? q : [];
};
export const saveQueue = (q) => write(QUEUE_KEY, q);

/** A short reference a cashier can read out for an order that has no number yet: OFF-7F3A2. */
export const offlineRef = (id) => "OFF-" + String(id).replace(/-/g, "").slice(0, 5).toUpperCase();

export function makeEntry({ request, by, expectedTotal, receipt, now = new Date() }) {
  return {
    id: request.id, by, queuedAt: now.toISOString(), request: { ...request, soldAt: now.toISOString() },
    expectedTotal, receipt, status: "pending", error: null, ref: offlineRef(request.id),
  };
}

/** Adds an entry (no duplicates). Returns the new queue, or null if the browser would not save it. */
export function addEntry(queue, entry) {
  if (queue.some((e) => e.id === entry.id)) return queue;
  const next = [...queue, entry];
  return saveQueue(next) ? next : null;
}

/**
 * What a failed upload means:
 *   network - no connection, or it timed out: keep it and try again later
 *   auth    - signed out: keep it, and carry on after signing in again
 *   server  - the server had a problem or is busy: keep it and try again later
 *   reject  - the server looked at it and said no (menu changed, too old...): needs a person
 */
export function classify(err) {
  const s = err && err.status;
  if (s === 0 || s === undefined) return "network";
  if (s === 401) return "auth";
  if (s === 408 || s === 429 || s >= 500) return "server";
  return "reject";
}

/**
 * Uploads this person's waiting orders, oldest first, using send(request) -> response.
 * Stops at the first connection, sign-in or server problem (the rest would fail the same way).
 * Returns { queue, uploaded: [{ ref, orderNo, total, expectedTotal }], stopped: null | "network" | "auth" | "server" }.
 */
export async function syncQueue(queue, user, send) {
  let q = queue;
  const uploaded = [];
  let stopped = null;
  for (const entry of queue) {
    if (entry.by !== user || entry.status !== "pending") continue;
    try {
      const res = await send(entry.request);
      q = q.filter((e) => e.id !== entry.id);
      uploaded.push({ ref: entry.ref, orderNo: res.orderNo, total: res.total, expectedTotal: entry.expectedTotal });
    } catch (err) {
      const kind = classify(err);
      if (kind === "reject") {
        q = q.map((e) => (e.id === entry.id ? { ...e, status: "rejected", error: err.message || "The server did not accept this order." } : e));
      } else {
        stopped = kind;
        break;
      }
    }
  }
  return { queue: q, uploaded, stopped };
}

export const retryEntry = (queue, id) => queue.map((e) => (e.id === id ? { ...e, status: "pending", error: null } : e));
export const discardEntry = (queue, id) => queue.filter((e) => e.id !== id);
export const pendingFor = (queue, user) => queue.filter((e) => e.by === user && e.status === "pending");
export const rejectedFor = (queue, user) => queue.filter((e) => e.by === user && e.status === "rejected");
