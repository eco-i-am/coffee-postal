import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiContext } from "../../shared/apiContext.jsx";
import { useLoad } from "../../shared/hooks.js";
import { OpenShift, ShiftPanel } from "./ShiftParts.jsx";
import { createPracticeShift } from "./practiceShift.js";
import PracticeCoach from "../pos/PracticeCoach.jsx";
import { SHIFT_STEPS } from "../pos/practiceSteps.js";

/**
 * Practice opening, running and closing a shift. The real shift screens run here, but they talk to a pretend drawer
 * kept in memory (practiceShift.js), so nothing reaches the server or this device.
 */
export default function PracticeShiftPage() {
  const navigate = useNavigate();
  const [calls, setCalls] = useState({ opened: false, moved: 0, xread: false, closed: false });
  const sim = useMemo(() => createPracticeShift(setCalls), []);
  const loader = useMemo(() => () => sim.api("/api/branch/shift"), [sim]);
  const { data, reload } = useLoad(loader);
  const [round, setRound] = useState(0);
  const s = data?.shift;

  function restart() { sim.reset(); reload(); setRound((n) => n + 1); }

  return (
    <ApiContext.Provider value={sim.api}>
      <section>
        <header className="pghead">
          <div><h1>Practice: your shift</h1><p>Open a shift, record cash out, then close and count. A pretend drawer, so nothing is real.</p></div>
          <button type="button" className="btn ghost sm" onClick={() => navigate("/branch/cash")}>Leave practice</button>
        </header>
        <p className="notice practicebar" role="status">PRACTICE MODE. Nothing here is saved, counted or sent to HQ.</p>
        {data && !s && <OpenShift practice onDone={reload} />}
        {s && <ShiftPanel practice s={s} reload={reload} onClosed={reload} />}
      </section>
      {data && (
        <PracticeCoach key={round} steps={SHIFT_STEPS} doneTitle="Nice! You ran a practice shift." doneText="Open with the cash you count, record cash in and out with a reason, then count at the end. The system tells you if you are Exact, Short or Over. Nothing was saved."
          realLabel="Go to the real drawer" onRestart={restart} onExit={() => navigate("/branch/cash")} onReal={() => navigate("/branch/cash")}
          state={{ open: !!s, opened: calls.opened, moves: calls.moved, xread: calls.xread, closed: calls.closed }} />
      )}
    </ApiContext.Provider>
  );
}
