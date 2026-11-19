import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { stepsFor } from "./tutorialSteps.js";

/** A short step-by-step tour, shown once on first sign-in and again from "Quick tour" in the menu. */
export default function Tutorial({ me, can, onClose, onChangePassword }) {
  const [steps] = useState(() => stepsFor(me, can));
  const [i, setI] = useState(0);
  const nav = useNavigate();
  const nextRef = useRef(null);
  const step = steps[i];
  const last = i === steps.length - 1;

  useEffect(() => { nextRef.current?.focus(); }, [i]);
  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  function runAction() {
    if (step.action?.kind === "password") { onClose(); onChangePassword(); }
  }
  function takeMeThere() { onClose(); nav(step.to); }

  return (
    <div className="scrim">
      <div className="modal tour" role="dialog" aria-modal="true" aria-labelledby="tour-title">
        <p className="tour-count" aria-live="polite">Step {i + 1} of {steps.length}</p>
        {step.page && <span className="tour-page">{step.page}</span>}
        <h2 id="tour-title">{step.title}</h2>
        {step.body.map((t, n) => <p key={n} className="tour-p">{t}</p>)}
        {step.buttons && step.buttons.length > 0 && (
          <ul className="tourlist" aria-label="Buttons on this page">
            {step.buttons.map((b) => <li key={b.label}><span className="keycap">{b.label}</span><span>{b.does}</span></li>)}
          </ul>
        )}
        {(step.action || step.to) && (
          <p className="tour-links">
            {step.action && <button type="button" className="crumb" onClick={runAction}>{step.action.label}</button>}
            {step.to && <button type="button" className="crumb" onClick={takeMeThere}>Take me there</button>}
          </p>
        )}
        <div className="tour-dots" aria-hidden="true">
          {steps.map((s, n) => <i key={s.id} className={n === i ? "on" : n < i ? "done" : ""} />)}
        </div>
        <div className="opts">
          {!last && <button type="button" className="btn ghost" onClick={onClose}>Skip</button>}
          {i > 0 && <button type="button" className="btn ghost" onClick={() => setI(i - 1)}>Back</button>}
          <button ref={nextRef} type="button" className="btn warm" style={{ flex: 1 }} onClick={() => (last ? onClose() : setI(i + 1))}>
            {last ? "Got it" : "Next"}
          </button>
        </div>
      </div>
    </div>
  );
}
