// Date ranges as YYYY-MM-DD strings (Manila calendar days). Pure functions so they are easy to test.

const pad = (n) => String(n).padStart(2, "0");
const toYmd = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
const parse = (ymd) => { const [y, m, d] = ymd.split("-").map(Number); return new Date(Date.UTC(y, m - 1, d)); };

export function addDays(ymd, n) {
  const d = parse(ymd);
  d.setUTCDate(d.getUTCDate() + n);
  return toYmd(d);
}

export function daysBetween(from, to) {
  return Math.round((parse(to) - parse(from)) / 86400000) + 1;
}

export const PRESETS = [
  ["today", "Today"], ["7d", "Last 7 days"], ["30d", "Last 30 days"], ["month", "This month"], ["lastmonth", "Last month"],
];

/** The from/to for a preset, given today's date. */
export function presetRange(key, today) {
  const [y, m] = today.split("-").map(Number);
  switch (key) {
    case "today": return { from: today, to: today };
    case "7d": return { from: addDays(today, -6), to: today };
    case "30d": return { from: addDays(today, -29), to: today };
    case "month": return { from: `${y}-${pad(m)}-01`, to: today };
    case "lastmonth": {
      const first = new Date(Date.UTC(y, m - 2, 1));
      const last = new Date(Date.UTC(y, m - 1, 0));
      return { from: toYmd(first), to: toYmd(last) };
    }
    default: return { from: addDays(today, -6), to: today };
  }
}
