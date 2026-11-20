import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "../../shared/api.js";

/** The page behind the receipt QR code. Public: no sign-in. A customer picks stars, adds a note if they like, and sends. */
export default function RatePage() {
  const { token } = useParams();
  const [info, setInfo] = useState(null);
  const [bad, setBad] = useState("");
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState("");
  const [state, setState] = useState("idle");   // idle | sending | done
  const [err, setErr] = useState("");

  useEffect(() => {
    api(`/api/public/feedback/${encodeURIComponent(token)}`).then(setInfo).catch((e) => setBad(e.message || "This rating link is not valid."));
  }, [token]);

  async function send(e) {
    e.preventDefault();
    if (!rating) return;
    setState("sending"); setErr("");
    try {
      await api(`/api/public/feedback/${encodeURIComponent(token)}`, { method: "POST", body: { rating, comment } });
      setState("done");
    } catch (e2) { setErr(e2.message || "Could not send. Please try again."); setState("idle"); }
  }

  return (
    <main className="ratepage">
      <div className="ratecard">
        <h1>{info?.company || "Coffee Postal"}</h1>
        {bad ? <p className="err" role="alert">{bad}</p> : !info ? <p role="status">Loading...</p> : state === "done" ? (
          <p className="ratethanks" role="status">Thank you! Your rating helps {info.branch} get better.</p>
        ) : (
          <form onSubmit={send}>
            <p>How was your visit to <b>{info.branch}</b>?</p>
            <div className="ratestars big" role="group" aria-label="Rating">
              {[1, 2, 3, 4, 5].map((n) => (
                <button key={n} type="button" className={n <= rating ? "on" : ""} aria-label={`${n} ${n === 1 ? "star" : "stars"}`} aria-pressed={n === rating} onClick={() => setRating(n)}>★</button>
              ))}
            </div>
            <label htmlFor="ratecomment" className="hint">Anything to add? (optional)</label>
            <textarea id="ratecomment" rows={3} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} />
            {err && <p className="err" role="alert">{err}</p>}
            <button type="submit" className="btn warm" disabled={!rating || state === "sending"}>Send rating</button>
          </form>
        )}
      </div>
    </main>
  );
}
