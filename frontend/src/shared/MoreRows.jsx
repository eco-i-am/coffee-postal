/** Shown under a list that was cut at `limit` rows, so nothing is silently missing. */
export default function MoreRows({ count, limit, onMore }) {
  if (count < limit) return null;
  return (
    <p className="hint">
      Showing the newest {count}. <button type="button" className="btn ghost sm" onClick={onMore}>Show more</button>
    </p>
  );
}
