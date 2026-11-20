import { useEffect, useRef, useState } from "react";
import { api } from "../../shared/api.js";
import { startCamera, stopCamera } from "../../shared/camera.js";
import { averageDescriptors, consistent, loadFace, readFace } from "../../shared/face.js";
import Modal from "../../shared/Modal.jsx";

const SAMPLES = 3;

/**
 * Saves an employee's face for the time clock. The manager records that the employee agreed, then the tablet takes three
 * readings a moment apart and keeps their average. No photo is kept from this: only 128 numbers.
 * base = "/api/branch/employees" or "/api/admin/employees".
 */
export default function FaceEnroll({ employee, base, onClose, onDone }) {
  const videoRef = useRef(null);
  const [camera, setCamera] = useState("starting");   // starting | on | off
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [err, setErr] = useState("");

  useEffect(() => {
    let stream = null;
    let alive = true;
    loadFace();
    startCamera(videoRef.current).then((s) => {
      if (!alive) { stopCamera(s); return; }
      stream = s;
      setCamera(s ? "on" : "off");
    });
    return () => { alive = false; stopCamera(stream); };
  }, []);

  async function capture() {
    setErr(""); setBusy(true);
    try {
      const got = [];
      for (let i = 0; i < SAMPLES; i++) {
        setNote(`Reading ${i + 1} of ${SAMPLES}. Look at the camera.`);
        let r = await readFace(videoRef.current, { loadMs: 15000 });
        if (r.state === "off") throw new Error("The face check could not start on this tablet. Check the connection and try again.");
        if (r.state === "multi") throw new Error("More than one face is in view. Only the employee should be in front of the camera.");
        if (r.state !== "ok") throw new Error("No clear face found. Face the camera in good light, and take off a cap or mask.");
        got.push(r.descriptor);
        await new Promise((res) => setTimeout(res, 500));
      }
      if (!consistent(got)) throw new Error("The readings did not agree. Hold still and try again.");
      setNote("Saving...");
      await api(`${base}/${employee.id}/face`, { method: "POST", body: { descriptor: averageDescriptors(got), consent: true } });
      onDone(`${employee.fullName}: face saved for the time clock.`);
    } catch (e) {
      setErr(e.message); setNote("");
    } finally { setBusy(false); }
  }

  /** A photo file instead of the camera: one reading from the picture, and the picture itself is not kept. */
  async function fromPhoto(e) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setErr(""); setBusy(true); setNote("Reading the photo...");
    const url = URL.createObjectURL(file);
    try {
      if (!file.type.startsWith("image/")) throw new Error("Pick a photo file (JPG or PNG).");
      const img = new Image();
      await new Promise((res, rej) => { img.onload = res; img.onerror = () => rej(new Error("That file could not be opened as a photo.")); img.src = url; });
      const r = await readFace(img, { loadMs: 15000 });
      if (r.state === "off") throw new Error("The face check could not start on this device. Check the connection and try again.");
      if (r.state === "multi") throw new Error("More than one face is in the photo. Use a photo with only the employee.");
      if (r.state !== "ok") throw new Error("No clear face found in that photo. Use a recent, front-facing photo in good light, without a cap or mask.");
      setNote("Saving...");
      await api(`${base}/${employee.id}/face`, { method: "POST", body: { descriptor: r.descriptor, consent: true } });
      onDone(`${employee.fullName}: face saved from the photo.`);
    } catch (e2) {
      setErr(e2.message); setNote("");
    } finally { URL.revokeObjectURL(url); setBusy(false); }
  }

  async function remove() {
    setErr(""); setBusy(true);
    try {
      await api(`${base}/${employee.id}/face`, { method: "DELETE" });
      onDone(`${employee.fullName}: face removed.`);
    } catch (e) { setErr(e.message); } finally { setBusy(false); }
  }

  return (
    <Modal title={`Face for ${employee.fullName}`} onClose={onClose}>
      <p className="hint">
        The time clock will compare each punch with this face and flag a poor match for a manager to check. It never blocks a punch.
        Only 128 numbers are saved, not a photo, even when you upload one. They are deleted if you remove them here or when the employee is marked Left.
      </p>
      <video ref={videoRef} className="preview" muted playsInline aria-label="Camera preview" hidden={camera !== "on"} />
      {camera === "off" && <p className="notice" role="status">The camera is off. Allow it for this site in the browser settings.</p>}
      <label className="check">
        <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
        {employee.fullName} agreed, in writing, to a face check for time in and time out.
      </label>
      {note && <p className="hint" role="status">{note}</p>}
      {err && <p className="err" role="alert">{err}</p>}
      <div className="opts">
        <button type="button" className="btn ghost" onClick={onClose}>Close</button>
        {employee.faceEnrolled && <button type="button" className="btn ghost" disabled={busy} onClick={remove}>Remove face</button>}
        <label className={`btn ghost${busy || !consent ? " disabled" : ""}`} style={{ cursor: busy || !consent ? "not-allowed" : "pointer", opacity: busy || !consent ? 0.5 : 1 }}>
          Upload a photo
          <input type="file" accept="image/*" hidden disabled={busy || !consent} onChange={fromPhoto} />
        </label>
        <button type="button" className="btn warm" style={{ flex: 1 }} disabled={busy || !consent || camera !== "on"} onClick={capture}>
          {employee.faceEnrolled ? "Take a new reading" : "Take reading"}
        </button>
      </div>
    </Modal>
  );
}
