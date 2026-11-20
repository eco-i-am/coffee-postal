import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useCardPosition } from "../../app/useCardPosition.js";
import { boxOf, locate } from "../../app/tourTarget.js";

// Each step points at a real part of the practice screen and moves on by itself once the cashier does the thing.
//   state = { cat, cartCount, otype, disc, discReady, discOpen, modal }   (modal: "item" | "pay" | "receipt" | null)
//   done(now, atStart) is checked whenever the screen changes. `during` swaps the target while a dialog is open.
const STEPS = [
  { id: "cat", sel: ".catrail", title: "Pick a category", text: "Tap a group on the left, like Coffee.", done: (s, b) => s.cat !== b.cat },
  { id: "item", sel: ".pos .menu", title: "Tap a drink", text: "Tap Iced Latte to start the order.", during: { modal: "item", sel: ".modal", text: "Choose a size and an add-on if you like, then tap Add to order." }, done: (s, b) => s.cartCount > b.cartCount },
  { id: "qty", sel: ".cartlines .line .qty", title: "Change the amount", text: "The customer wants one more. Tap + on the order line.", done: (s, b) => s.cartCount > b.cartCount },
  { id: "type", sel: ".bigseg", title: "Dine-in or takeout", text: "Tap Takeout. Every order needs a type.", done: (s) => s.otype === "TAKEOUT" },
  { id: "disc", sel: ".discbtn", title: "Add a discount", text: "Tap Discount and pick Senior / PWD. Not needed for every order, so you can skip this.", optional: true, during: { modalOpen: "disc", sel: ".modal", text: "Pick Senior / PWD, type any ID number like 1234, then tap Done." }, done: (s) => s.disc !== "NONE" && s.discReady && !s.discOpen },
  { id: "charge", sel: ".chargebtn", title: "Charge", text: "The order is ready. Tap Charge.", done: (s) => s.modal === "pay" },
  { id: "pay", sel: ".modal", title: "Take payment", text: "Tap Cash, tap the amount the customer gave, then tap Complete order. Nothing is really charged.", done: (s) => s.modal === "receipt" },
  { id: "receipt", sel: ".modal", title: "The receipt", text: "This is what the customer gets. Tap New order to finish.", done: (s) => s.modal === null },
];

export default function PracticeCoach({ state, onRestart, onExit, onReal }) {
  const [i, setI] = useState(0);
  const [rect, setRect] = useState(null);
  const base = useRef(state);
  const cardRef = useRef(null);
  const scrolled = useRef("");
  const finished = i >= STEPS.length;
  const step = finished ? null : STEPS[i];

  const advance = (to) => { base.current = state; setI(to); };

  // move on when the cashier has done what this step asked
  useEffect(() => {
    if (!step) return undefined;
    if (!step.done(state, base.current)) return undefined;
    const t = setTimeout(() => advance(i + 1), 350);
    return () => clearTimeout(t);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, i]);

  const during = step?.during && ((step.during.modal && state.modal === step.during.modal) || (step.during.modalOpen === "disc" && state.discOpen)) ? step.during : null;
  const sel = during ? during.sel : step?.sel;
  const text = during ? during.text : step?.text;

  useLayoutEffect(() => {
    if (!sel) { setRect(null); return undefined; }
    const measure = () => {
      const els = locate({ sel }, document);
      if (els.length && scrolled.current !== `${i}:${sel}`) {
        scrolled.current = `${i}:${sel}`;
        try { els[0].scrollIntoView({ block: "nearest", inline: "nearest" }); } catch { /* the box still shows */ }
      }
      const box = els.length ? boxOf(els) : null;
      setRect((old) => (old && box && old.top === box.top && old.left === box.left && old.width === box.width && old.height === box.height ? old : box));
    };
    measure();
    const timer = setInterval(measure, 250);
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => { clearInterval(timer); window.removeEventListener("resize", measure); window.removeEventListener("scroll", measure, true); };
  }, [sel, i]);

  const pos = useCardPosition(cardRef, rect);
  const onModal = sel === ".modal";   // a dialog is already in front: just put the card along the bottom, no spotlight

  return (
    <div className="coachroot" data-tour-ui>
      {finished ? (
        <div className="scrim coachdone">
          <div className="modal tour" role="dialog" aria-modal="true" aria-labelledby="coach-title">
            <h2 id="coach-title">Nice! You rang up a practice sale.</h2>
            <p className="tour-p">That is the whole flow: pick, add, set the order type, charge, take payment. Nothing was really sold.</p>
            <div className="opts">
              <button type="button" className="btn ghost" onClick={() => { setI(0); base.current = state; onRestart(); }}>Practice again</button>
              <button type="button" className="btn warm" style={{ flex: 1 }} onClick={onReal}>Go to the real POS</button>
            </div>
          </div>
        </div>
      ) : (
        <>
          {rect && !onModal && <div className="coach-spot" style={{ top: rect.top - 6, left: rect.left - 6, width: rect.width + 12, height: rect.height + 12 }} />}
          <div ref={cardRef} className={`tourpop coachcard${onModal || !rect ? " low" : ""}`} role="status" aria-live="polite"
            style={!onModal && rect ? (pos ? { top: pos.top, left: pos.left } : { top: 0, left: 0, visibility: "hidden" }) : undefined}>
            <p className="tour-count">Practice · Step {i + 1} of {STEPS.length}</p>
            <h2>{step.title}</h2>
            <p className="tour-p">{text}</p>
            <div className="opts">
              <button type="button" className="btn ghost sm" onClick={onExit}>Leave practice</button>
              {step.optional && <button type="button" className="btn ghost sm" onClick={() => advance(i + 1)}>Skip this</button>}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
