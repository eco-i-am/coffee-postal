import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../shared/api.js";
import { useAuth } from "../../app/AuthContext.jsx";
import { timeOf } from "../../shared/format.js";
import { captureJpeg, startCamera, stopCamera } from "../../shared/camera.js";
import { loadFace, readFace } from "../../shared/face.js";
import { clockOnlyAccount, goFullscreen, inKiosk, keepAwake, leaveFullscreen, setKiosk } from "../../shared/kiosk.js";
import { loadPunches, loadRoster, makePunch, matchPin, nextKind, savePunches, saveRoster, uploadPunches, whoIsIn } from "../../shared/punches.js";

const SHOW_MS = 4500;       // a result stays up this long, then the screen is ready for the next person
const LOCAL_MAX_WRONG = 5;  // offline: wrong PINs before a short pause
const LOCAL_LOCK_MS = 60000;
const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "back"];

/**
 * The branch tablet's time clock. Staff type a 6-digit PIN; the server decides time in or time out.
 * With no internet the tablet recognises the PIN from its saved staff list and keeps the punch to send later.
 */
export default function ClockPage() {
  const { me, can, signOut } = useAuth();
  const kiosk = inKiosk(me, can);
  const dedicated = clockOnlyAccount(me, can);   // a Time Clock account: always clock-only
  const [exiting, setExiting] = useState(false);
  const [mgrPin, setMgrPin] = useState("");
  const [exitErr, setExitErr] = useState("");
  const user = me.username;
  const [roster, setRoster] = useState(() => loadRoster(user));
  const [queue, setQueue] = useState(loadPunches);
  const queueRef = useRef(queue);
  const [pin, setPin] = useState("");
  const [result, setResult] = useState(null);   // { name, kind, at, offline, flag } or { error }
  const [busy, setBusy] = useState(false);
  const [online, setOnline] = useState(() => (typeof navigator === "undefined" ? true : navigator.onLine !== false));
  const wrong = useRef({ count: 0, until: 0 });
  const uploading = useRef(false);
  const timer = useRef(null);
  const videoRef = useRef(null);
  const [camera, setCamera] = useState("starting");   // starting | on | off

  // returns false if this browser would not keep it
  const commit = useCallback((q) => { queueRef.current = q; setQueue(q); return savePunches(q); }, []);

  const refreshRoster = useCallback(async () => {
    try {
      const r = await api("/api/clock/roster", { timeoutMs: 8000 });
      setRoster({ ...r, savedAt: new Date().toISOString() });
      saveRoster(user, r);
      setOnline(true);
    } catch (e) {
      if (e.status === 0) setOnline(false);
    }
  }, [user]);

  const flush = useCallback(async () => {
    if (uploading.current || !queueRef.current.some((p) => p.status === "pending")) return;
    uploading.current = true;
    try {
      const snap = queueRef.current;
      const r = await uploadPunches(snap, (p) => api("/api/clock/punch", {
        method: "POST", timeoutMs: 15000,
        body: { employeeId: p.employeeId, check: p.check, clientId: p.id, deviceTime: p.deviceTime, photo: p.photo || null, face: p.face || null },
      }));
      const sent = new Set(r.uploaded.map((p) => p.id));
      const updated = new Map(r.queue.map((p) => [p.id, p]));
      // keep anything punched while uploading
      commit(queueRef.current.filter((p) => !sent.has(p.id)).map((p) => updated.get(p.id) || p));
      setOnline(r.stopped !== "network");
      if (r.uploaded.length) refreshRoster();
    } finally { uploading.current = false; }
  }, [commit, refreshRoster]);

  useEffect(() => {
    refreshRoster();
    flush();
    loadFace();   // warm up the face check so the first punch is quick
    const on = () => { setOnline(true); refreshRoster(); flush(); };
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    let stream = null;
    let alive = true;
    if (videoRef.current) {
      startCamera(videoRef.current).then((s) => {
        if (!alive) { stopCamera(s); return; }
        stream = s;
        setCamera(s ? "on" : "off");
      });
    }
    const every = setInterval(() => { flush(); }, 20000);
    const rosterEvery = setInterval(refreshRoster, 300000);
    return () => {
      window.removeEventListener("online", on); window.removeEventListener("offline", off);
      alive = false; stopCamera(stream);
      clearInterval(every); clearInterval(rosterEvery); clearTimeout(timer.current);
    };
  }, [refreshRoster, flush]);

  function show(r) {
    setResult(r);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setResult(null), SHOW_MS);
  }

  async function submit(code) {
    setBusy(true);
    setPin("");
    const clientId = crypto.randomUUID();
    const deviceTime = new Date();
    const photo = captureJpeg(videoRef.current);   // taken now, while the person is in front of the tablet
    const face = await readFace(videoRef.current);   // flags only: a punch is never refused over a face
    try {
      const res = await api("/api/clock/punch", { method: "POST", timeoutMs: 12000, body: { pin: code, clientId, deviceTime: deviceTime.toISOString(), photo, face } });
      show({ name: res.name, kind: res.kind, at: res.at, offline: false, flag: res.flag, photo: res.photo, faceStatus: res.faceStatus });
      refreshRoster();
    } catch (e) {
      if (e.status) { show({ error: e.message }); }
      else {
        setOnline(false);
        await punchOffline(code, clientId, deviceTime, photo, face);
      }
    } finally { setBusy(false); }
  }

  async function punchOffline(code, clientId, deviceTime, photo, face) {
    if (!roster) { show({ error: "No internet, and this tablet has no saved staff list yet. Connect once to load it." }); return; }
    if (Date.now() < wrong.current.until) { show({ error: "Too many wrong PINs. Wait a minute." }); return; }
    const emp = await matchPin(roster, code);
    if (!emp) {
      wrong.current.count += 1;
      if (wrong.current.count >= LOCAL_MAX_WRONG) { wrong.current = { count: 0, until: Date.now() + LOCAL_LOCK_MS }; }
      show({ error: "That PIN is not right." });
      return;
    }
    wrong.current.count = 0;
    const kind = nextKind(emp, queueRef.current);
    const p = makePunch({ employee: emp, check: emp.check, kind, now: deviceTime, id: clientId, photo, face });
    const base = queueRef.current;
    let kept = photo;
    // a photo makes the punch bigger; if the tablet is nearly full, keep the punch and drop the photo
    if (!commit([...base, p])) {
      kept = null;
      if (!commit([...base, { ...p, photo: null, face: null }])) { show({ error: "This tablet could not save the punch. Write the time down and tell your manager." }); return; }
    }
    show({ name: emp.name, kind, at: deviceTime.toISOString(), offline: true, photo: !!kept });
  }

  const press = (k) => {
    if (busy || result) return;
    if (k === "clear") setPin("");
    else if (k === "back") setPin((p) => p.slice(0, -1));
    else if (pin.length < 6) {
      const next = pin + k;
      setPin(next);
      if (next.length === 6) submit(next);
    }
  };

  // a keyboard works too
  const pressRef = useRef(press);
  pressRef.current = press;
  useEffect(() => {
    const onKey = (e) => {
      if (/^\d$/.test(e.key)) pressRef.current(e.key);
      else if (e.key === "Backspace") pressRef.current("back");
      else if (e.key === "Escape") pressRef.current("clear");
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  function startKiosk() {
    setKiosk(user, true);   // the shell hides the menu; leaving takes a manager PIN
    goFullscreen();
  }

  async function leave(e) {
    e.preventDefault();
    setExitErr("");
    try {
      await api("/api/clock/exit", { method: "POST", timeoutMs: 10000, body: { managerPin: mgrPin } });
      setExiting(false);
      leaveFullscreen();
      if (dedicated) await signOut();
      else setKiosk(user, false);
    } catch (err) { setExitErr(err.status === 0 ? "Leaving clock-only mode needs the internet. Try again when it is back." : err.message); }
  }

  // while the clock is the whole screen, keep the tablet awake
  useEffect(() => {
    if (!kiosk) return undefined;
    let release = () => {};
    let alive = true;
    keepAwake().then((r) => { if (alive) release = r; else r(); });
    return () => { alive = false; release(); };
  }, [kiosk]);

  const pending = queue.filter((p) => p.status === "pending").length;
  const rejected = queue.filter((p) => p.status === "rejected");
  const inNow = whoIsIn(roster, queue);

  return (
    <section className={`clockpage${kiosk ? " kioskpage" : ""}`}>
      <header className="pghead">
        <div><h1>{kiosk ? "Coffee Postal: Time In / Out" : "Time In / Out"}</h1><p>Type your 6-digit PIN. The screen is ready for the next person after each punch.</p></div>
        <div className="toolbar">
          {kiosk
            ? <button type="button" className="btn ghost sm" onClick={() => { setExitErr(""); setMgrPin(""); setExiting(true); }}>{dedicated ? "Manager: sign out" : "Manager: exit clock mode"}</button>
            : can("branch_employees_edit") && <button type="button" className="btn ghost sm" onClick={startKiosk}>Clock-only mode</button>}
        </div>
      </header>
      {camera === "off" && <p className="notice" role="status">The camera is off, so punches are saved without a photo. Allow the camera for this site in the browser settings.</p>}
      {!online && <p className="notice offline" role="status">No internet. Punches are saved on this tablet and sent when it is back.</p>}
      {pending > 0 && <p className="notice" role="status">{pending} {pending === 1 ? "punch is" : "punches are"} saved on this tablet, waiting to upload.</p>}
      {rejected.map((p) => (
        <p className="notice" role="alert" key={p.id}>
          {p.name}&apos;s {p.kind === "IN" ? "time in" : "time out"} at {timeOf(p.deviceTime)} was not accepted: {p.error}
          {" "}<button className="btn ghost sm" type="button" onClick={() => commit(queueRef.current.filter((x) => x.id !== p.id))}>Dismiss</button>
        </p>
      ))}

      <div className="clock">
        <div className="pad">
          <video ref={videoRef} className="preview" muted playsInline aria-label="Camera preview" hidden={camera !== "on"} />
          <div className={`result${result ? (result.error ? " bad" : result.kind === "IN" ? " in" : " out") : ""}`} role="status" aria-live="polite">
            {!result && <span>Enter PIN</span>}
            {result?.error && <span>{result.error}</span>}
            {result && !result.error && (
              <span>
                <b>{result.name}</b><br />
                Time {result.kind === "IN" ? "in" : "out"} {timeOf(result.at)}
                {result.offline && <small><br />Saved on this tablet. It counts at this time once it uploads.</small>}
                {result.photo === false && <small><br />No photo was kept for this punch.</small>}
                {result.faceStatus === "REVIEW" && <small><br />Your face did not match well. A manager will check this punch.</small>}
                {result.faceStatus === "NO_FACE" && <small><br />We could not see your face clearly. Look at the camera next time. A manager may check this punch.</small>}
                {result.flag === "CLOCK_SKEW" && <small><br />This tablet&apos;s clock is off. The server&apos;s time was used. Tell your manager.</small>}
              </span>
            )}
          </div>
          <div className="dots" aria-label={`${pin.length} of 6 digits entered`}>
            {[0, 1, 2, 3, 4, 5].map((i) => <i key={i} className={i < pin.length ? "on" : ""} />)}
          </div>
          <div className="keypad">
            {KEYS.map((k) => (
              <button key={k} type="button" className={`key${k.length > 1 ? " fn" : ""}`} disabled={busy || !!result}
                aria-label={k === "back" ? "Delete last digit" : k === "clear" ? "Clear" : k} onClick={() => press(k)}>
                {k === "back" ? "⌫" : k === "clear" ? "Clear" : k}
              </button>
            ))}
          </div>
          <p className="hint">A photo is taken with every time in and time out. Only your manager and head office can see it. It is deleted after {roster?.photoDays || 90} days.</p>
        </div>
        <div className="sec whoin">
          <h2>Clocked in now ({inNow.length})</h2>
          {inNow.length === 0 && <p className="hint">Nobody yet.</p>}
          {inNow.map((e) => <div key={e.id} className="r"><b>{e.name}</b><small>{e.position || ""}</small></div>)}
          {roster?.employees?.length === 0 && <p className="hint">No staff have a PIN yet. A manager adds them under Employees.</p>}
        </div>
      </div>
      {exiting && (
        <div className="scrim" onClick={(e) => e.target === e.currentTarget && setExiting(false)}>
          <form className="modal" role="dialog" aria-modal="true" aria-label="Manager PIN" onSubmit={leave}>
            <h2>{dedicated ? "Sign this tablet out?" : "Leave clock-only mode?"}</h2>
            <p className="hint">A manager types their PIN. Staff cannot do this.</p>
            <div className="addr"><label htmlFor="mgrpin">Manager PIN</label>
              <input id="mgrpin" type="password" inputMode="numeric" autoComplete="off" maxLength={12} value={mgrPin} onChange={(e) => setMgrPin(e.target.value)} autoFocus required /></div>
            {exitErr && <p className="err" role="alert">{exitErr}</p>}
            <div className="opts"><button type="button" className="btn ghost" onClick={() => setExiting(false)}>Cancel</button><button className="btn warm" style={{ flex: 1 }} type="submit" disabled={!mgrPin}>{dedicated ? "Sign out" : "Leave"}</button></div>
          </form>
        </div>
      )}
    </section>
  );
}
