import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../shared/api.js";
import { peso } from "../shared/money.js";
import { useAuth } from "./AuthContext.jsx";
import { addEntry, discardEntry, loadQueue, pendingFor, rejectedFor, retryEntry, saveQueue, syncQueue } from "../shared/offline.js";

const Ctx = createContext(null);
const NONE = { online: true, pending: 0, rejected: [], syncing: false, notes: [], enqueue: () => false, syncNow() {}, retry() {}, discard() {}, dismissNotes() {} };
export const useSync = () => useContext(Ctx) || NONE;

const sendOrder = (request) => api("/api/pos/orders", { method: "POST", body: request, timeoutMs: 15000 });

/** Keeps the queue of orders sold without internet, and uploads them whenever it can. */
export function SyncProvider({ children }) {
  const { me } = useAuth();
  const user = me ? me.username : null;
  const [queue, setQueue] = useState(loadQueue);
  const queueRef = useRef(queue);
  const [syncing, setSyncing] = useState(false);
  const [notes, setNotes] = useState([]);
  const [online, setOnline] = useState(() => (typeof navigator === "undefined" ? true : navigator.onLine !== false));
  const busy = useRef(false);

  const commit = useCallback((q) => { queueRef.current = q; setQueue(q); saveQueue(q); }, []);

  useEffect(() => {
    const on = () => setOnline(true), off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => { window.removeEventListener("online", on); window.removeEventListener("offline", off); };
  }, []);

  const syncNow = useCallback(async () => {
    if (!user || busy.current || pendingFor(queueRef.current, user).length === 0) return;
    busy.current = true;
    setSyncing(true);
    try {
      const snap = queueRef.current;
      const r = await syncQueue(snap, user, sendOrder);
      // merge with whatever changed while uploading: a sale made meanwhile must not be lost
      const result = new Map(r.queue.map((e) => [e.id, e]));
      const was = new Set(snap.map((e) => e.id));
      commit(queueRef.current.filter((e) => !was.has(e.id) || result.has(e.id)).map((e) => result.get(e.id) || e));
      if (r.uploaded.length) {
        const odd = r.uploaded.filter((u) => u.total !== u.expectedTotal);
        setNotes((n) => [
          ...n,
          `${r.uploaded.length} offline ${r.uploaded.length === 1 ? "order" : "orders"} uploaded.`,
          ...odd.map((u) => `${u.ref} became order #${u.orderNo}, but it was charged ${peso(u.total)} and the customer was shown ${peso(u.expectedTotal)}. A price changed while offline. Tell your manager.`),
        ]);
      }
      // only a lost connection means offline; anything else got an answer from the server
      setOnline(r.stopped !== "network");
    } finally {
      busy.current = false;
      setSyncing(false);
    }
  }, [user, commit]);

  // try when someone signs in, when the connection returns, and every 30 seconds while orders wait
  useEffect(() => { syncNow(); }, [syncNow, online]);
  useEffect(() => {
    const t = setInterval(syncNow, 30000);
    return () => clearInterval(t);
  }, [syncNow]);

  const enqueue = useCallback((entry) => {
    const next = addEntry(queueRef.current, entry);
    if (!next) return false;
    queueRef.current = next;
    setQueue(next);
    setOnline(false);   // an order only gets queued after the server could not be reached
    return true;
  }, []);

  const value = useMemo(() => ({
    online,
    pending: user ? pendingFor(queue, user).length : 0,
    rejected: user ? rejectedFor(queue, user) : [],
    syncing,
    notes,
    enqueue,
    syncNow,
    retry: (id) => { commit(retryEntry(queueRef.current, id)); syncNow(); },
    discard: (id) => commit(discardEntry(queueRef.current, id)),
    dismissNotes: () => setNotes([]),
  }), [online, queue, user, syncing, notes, enqueue, syncNow, commit]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
