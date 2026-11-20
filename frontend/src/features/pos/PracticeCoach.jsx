import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useCardPosition } from "../../app/useCardPosition.js";
import { boxOf, locate } from "../../app/tourTarget.js";

/**
 * The practice coach: lights up the next thing to tap and never blocks it. `steps` come from practiceSteps.js.
 * It moves on by itself when the step's done() is true, or with a Next button for steps that only explain.
 * `state` is whatever the practice screen reports (see the step lists); `finish` is the card shown after the last step.
 */
export default function PracticeCoach({ steps, state, onRestart, onExit, onReal, doneTitle, doneText, realLabel = "Go to the real screen" }) {
  const [i, setI] = useState(0);
  const [rect, setRect] = useState(null);
  const [cur, setCur] = useState(null);   // the alt being shown: { text, modal }
  const base = useRef(state);
  const cardRef = useRef(null);
  const scrolled = useRef("");
  const finished = i >= steps.length;
  const step = finished ? null : steps[i];

  const advance = (to) => { base.current = state; setI(to); };

  // move on when the cashier has done what this step asked
  useEffect(() => {
    if (!step || !step.done || !step.done(state, base.current)) return undefined;
    const t = setTimeout(() => advance(i + 1), 350);
    return () => clearTimeout(t);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, i]);

  // follow the first target of this step that is on screen
  useLayoutEffect(() => {
    if (!step) { setRect(null); setCur(null); return undefined; }
    const measure = () => {
      let hit = null;
      for (const a of step.alts) { const els = locate(a, document); if (els.length) { hit = { a, els }; break; } }
      if (hit && scrolled.current !== `${i}:${hit.a.sel || hit.a.label}`) {
        scrolled.current = `${i}:${hit.a.sel || hit.a.label}`;
        try { hit.els[0].scrollIntoView({ block: "nearest", inline: "nearest" }); } catch { /* the box still shows */ }
      }
      const box = hit ? boxOf(hit.els) : null;
      const next = hit ? { text: hit.a.text, modal: (hit.a.sel || "").endsWith(".modal") } : { text: step.alts[step.alts.length - 1].text, modal: false };
      setRect((old) => (old && box && old.top === box.top && old.left === box.left && old.width === box.width && old.height === box.height ? old : box));
      setCur((old) => (old && old.text === next.text && old.modal === next.modal ? old : next));
    };
    measure();
    const timer = setInterval(measure, 250);
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => { clearInterval(timer); window.removeEventListener("resize", measure); window.removeEventListener("scroll", measure, true); };
  }, [step, i]);

  const pos = useCardPosition(cardRef, rect);
  const onModal = !!cur?.modal;   // a dialog is already in front: put the card along the bottom with no spotlight

  return (
    <div className="coachroot" data-tour-ui>
      {finished ? (
        <div className="scrim coachdone">
          <div className="modal tour" role="dialog" aria-modal="true" aria-labelledby="coach-title">
            <h2 id="coach-title">{doneTitle}</h2>
            <p className="tour-p">{doneText}</p>
            <div className="opts">
              <button type="button" className="btn ghost" onClick={() => { setI(0); base.current = state; onRestart(); }}>Practice again</button>
              <button type="button" className="btn warm" style={{ flex: 1 }} onClick={onReal}>{realLabel}</button>
            </div>
          </div>
        </div>
      ) : (
        <>
          {rect && !onModal && <div className="coach-spot" style={{ top: rect.top - 6, left: rect.left - 6, width: rect.width + 12, height: rect.height + 12 }} />}
          <div ref={cardRef} className={`tourpop coachcard${onModal || !rect ? " low" : ""}`} role="status" aria-live="polite"
            style={!onModal && rect ? (pos ? { top: pos.top, left: pos.left } : { top: 0, left: 0, visibility: "hidden" }) : undefined}>
            <p className="tour-count">Practice · Step {i + 1} of {steps.length}</p>
            <h2>{step.title}</h2>
            <p className="tour-p">{cur?.text || step.alts[step.alts.length - 1].text}</p>
            <div className="opts">
              <button type="button" className="btn ghost sm" onClick={onExit}>Leave practice</button>
              {step.optional && <button type="button" className="btn ghost sm" onClick={() => advance(i + 1)}>Skip this</button>}
              {step.manual && <button type="button" className="btn warm sm" onClick={() => advance(i + 1)}>Next</button>}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
