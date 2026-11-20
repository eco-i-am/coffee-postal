import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useCardPosition } from "./useCardPosition.js";
import { api } from "../shared/api.js";
import { useNavigate } from "react-router-dom";
import { stepsFor } from "./tutorialSteps.js";
import { boxOf, locate } from "./tourTarget.js";

/**
 * A guided tour, shown once on first sign-in and again from "Quick tour" in the menu.
 * For each page it opens the page, points at its menu item, then spotlights each real button in turn.
 * Buttons it cannot find on screen (a row button on an empty list, say) are listed on the page's first card instead.
 * Steps with nothing to point at (welcome, offline, done) are a plain card in the middle.
 */
export default function Tutorial({ me, can, onClose, onChangePassword }) {
  const [steps] = useState(() => stepsFor(me, can));
  const [si, setSi] = useState(0);          // which page step
  const [bi, setBi] = useState(-1);         // -1 = the page itself (its menu item), otherwise which found button
  const [found, setFound] = useState([]);   // this page's buttons that are on screen
  const [missing, setMissing] = useState([]); // this page's buttons that are not
  const [ready, setReady] = useState(false); // the page has opened and its buttons are looked up
  const [rect, setRect] = useState(null);   // the spotlight box
  const nav = useNavigate();
  const nextRef = useRef(null);
  const cardRef = useRef(null);
  const scrolledFor = useRef("");
  const step = steps[si];
  const last = si === steps.length - 1;
  const stop = bi >= 0 ? found[bi] : null;
  const isLastStop = bi >= found.length - 1;

  function go(nextSi, nextBi) {
    if (nextSi !== si) { setFound([]); setMissing([]); setReady(false); setRect(null); }
    setSi(nextSi); setBi(nextBi);
  }
  const next = useCallback(() => {
    if (!ready) return;
    if (bi < found.length - 1) setBi(bi + 1);
    else if (!last) go(si + 1, -1);
    else onClose();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, bi, found, last, si, onClose]);
  const back = useCallback(() => {
    if (bi >= 0) setBi(bi - 1);
    else if (si > 0) go(si - 1, -1);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bi, si]);

  // open the page, then look up which of its buttons are really on screen
  useEffect(() => {
    if (step.to && window.location.pathname !== step.to) nav(step.to);
    if (!step.buttons?.length) { setReady(true); return undefined; }
    let alive = true, timer, prev = -1, same = 0;
    const t0 = Date.now();
    const poll = () => {
      if (!alive) return;
      const onPage = !step.to || window.location.pathname === step.to;
      const root = step.to ? document.querySelector("main") : document;
      const hit = onPage ? step.buttons.filter((b) => locate(b, root).length > 0) : [];
      same = hit.length === prev ? same + 1 : 0; prev = hit.length;
      const waited = Date.now() - t0;
      if (onPage && (hit.length === step.buttons.length || (waited > 600 && same >= 2) || waited > 2500)) {
        setFound(hit); setMissing(step.buttons.filter((b) => !hit.includes(b))); setReady(true);
        return;
      }
      if (waited > 2500) { setFound([]); setMissing(step.buttons); setReady(true); return; }
      timer = setTimeout(poll, 150);
    };
    timer = setTimeout(poll, 150);
    return () => { alive = false; clearTimeout(timer); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [si]);

  // follow the element the card is about: scroll it into view once, then keep the spotlight on it as the screen moves
  useLayoutEffect(() => {
    const key = `${si}:${bi}`;
    const els = () => {
      if (stop) return locate(stop, step.to ? document.querySelector("main") : document);
      if (!step.to) return [];
      const a = document.querySelector(`nav[aria-label="Main"] a[href="${step.to}"]`);
      return a ? [a] : [];
    };
    const measure = () => {
      const list = els();
      if (list.length && scrolledFor.current !== key) {
        scrolledFor.current = key;
        try { list[0].scrollIntoView({ block: stop ? "center" : "nearest", inline: "nearest" }); } catch { /* old browser: the box still shows */ }
      }
      const box = list.length ? boxOf(list) : null;
      setRect((old) => (old && box && old.top === box.top && old.left === box.left && old.width === box.width && old.height === box.height ? old : box));
    };
    measure();
    const timer = setInterval(measure, 250);
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => { clearInterval(timer); window.removeEventListener("resize", measure); window.removeEventListener("scroll", measure, true); };
  }, [si, bi, ready, found, step, stop]);

  const pos = useCardPosition(cardRef, rect);   // the card sits beside the spotlight

  useEffect(() => { if (ready) nextRef.current?.focus(); }, [si, bi, ready]);
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowRight") next();
      else if (e.key === "ArrowLeft") back();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, next, back]);

  function runAction() {
    if (step.action?.kind === "password") { onClose(); onChangePassword(); }
    else if (step.action?.kind === "practice") { onClose(); nav(step.action.to); }
    else if (step.action?.kind === "practiceOrders") {   // switch practice mode on, then a full page load so every screen reads the practice copies
      onClose();
      api("/api/practice/start", { method: "POST", body: {} }).catch(() => {}).finally(() => window.location.assign(step.action.to));
    }
  }

  const buttonNote = bi >= 0 ? ` · Button ${bi + 1} of ${found.length}` : "";
  const card = (
    <>
      <p className="tour-count" aria-live="polite">Step {si + 1} of {steps.length}{buttonNote}</p>
      {step.page && <span className="tour-page">{step.page}</span>}
      {stop ? (
        <>
          <h2 id="tour-title"><span className="keycap big">{stop.label}</span></h2>
          <p className="tour-p">{stop.does}</p>
        </>
      ) : (
        <>
          <h2 id="tour-title">{step.title}</h2>
          {step.body.map((t, n) => <p key={n} className="tour-p">{t}</p>)}
          {ready && missing.length > 0 && (
            <>
              <p className="tour-also">{found.length > 0 ? "Also on this page" : "Buttons on this page"}</p>
              <ul className="tourlist" aria-label="More buttons on this page">
                {missing.map((b) => <li key={b.label}><span className="keycap">{b.label}</span><span>{b.does}</span></li>)}
              </ul>
            </>
          )}
          {step.action && <p className="tour-links"><button type="button" className="crumb" onClick={runAction}>{step.action.label}</button></p>}
          {ready && found.length > 0 && <p className="tour-hint">Press Next and each button on this page lights up in turn.</p>}
        </>
      )}
      <div className="tour-dots" aria-hidden="true">
        {steps.map((s, n) => <i key={s.id} className={n === si ? "on" : n < si ? "done" : ""} />)}
      </div>
      <div className="opts">
        {!last && <button type="button" className="btn ghost" onClick={onClose}>Skip</button>}
        {(si > 0 || bi >= 0) && <button type="button" className="btn ghost" onClick={back}>Back</button>}
        <button ref={nextRef} type="button" className="btn warm" style={{ flex: 1 }} disabled={!ready} onClick={next}>
          {last && isLastStop ? "Got it" : "Next"}
        </button>
      </div>
    </>
  );

  return (
    <div className="tourroot" data-tour-ui>
      {rect ? (
        <>
          <div className="tour-block" />
          <div className="tour-spot" style={{ top: rect.top - 6, left: rect.left - 6, width: rect.width + 12, height: rect.height + 12 }} />
          <div ref={cardRef} className="tourpop" role="dialog" aria-modal="true" aria-labelledby="tour-title"
            style={pos ? { top: pos.top, left: pos.left } : { top: 0, left: 0, visibility: "hidden" }}>
            {card}
          </div>
        </>
      ) : (
        <div className="scrim">
          <div ref={cardRef} className="modal tour" role="dialog" aria-modal="true" aria-labelledby="tour-title">
            {card}
          </div>
        </div>
      )}
    </div>
  );
}
