// Scale helpers for the column charts. Money is integer centavos; ticks come back in centavos too.

/** A round step (1, 2, 2.5, 5 times a power of ten) that gives about `target` gridlines. */
function niceStep(max, target) {
  const rough = max / target;
  const mag = 10 ** Math.floor(Math.log10(rough));
  const n = rough / mag;
  const nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
  return nice * mag;
}

/** Gridline values for a chart whose tallest bar is `maxCentavos`: 0, then round pesos up to a top that holds the tallest bar. */
export function niceTicks(maxCentavos, target = 4) {
  const maxPesos = Math.max(maxCentavos / 100, 1);
  const step = niceStep(maxPesos, target);
  const top = Math.ceil(maxPesos / step - 1e-9) * step;
  const ticks = [];
  for (let v = 0; v <= top + step / 1000; v += step) ticks.push(Math.round(v * 100));
  return { ticks, top: Math.round(top * 100) };
}

/** An axis label: whole pesos under 10,000, compact (10K, 1.2M) from there. One style per axis, chosen by its top value. */
export function axisLabel(centavos, topCentavos) {
  const pesos = centavos / 100;
  if (topCentavos / 100 < 10000) return "₱" + pesos.toLocaleString("en-PH", { maximumFractionDigits: 0 });
  const compact = (n, d, s) => "₱" + (Math.round((n / d) * 10) / 10).toString().replace(/\.0$/, "") + s;
  if (pesos >= 1e6) return compact(pesos, 1e6, "M");
  if (pesos >= 1e3) return compact(pesos, 1e3, "K");
  return "₱" + Math.round(pesos);
}

/** Which of `n` columns get an x label, so the labels never touch: about `max` of them, evenly spaced, always the first. */
export function labelEvery(n, max = 12) {
  return Math.max(1, Math.ceil(n / max));
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
/** Chart columns for a list of days: the number under each bar, with the month at the first day and on the 1st. */
export function dayPoints(days) {
  return days.map((x, i) => {
    const day = Number(x.date.slice(8));
    const month = MONTHS[Number(x.date.slice(5, 7)) - 1];
    return {
      key: x.date, label: i === 0 || day === 1 ? `${month} ${day}` : String(day),
      title: `${month} ${day}, ${x.date.slice(0, 4)}`, value: x.sales, orders: x.orders,
      note: `${x.orders} ${x.orders === 1 ? "order" : "orders"}`,
    };
  });
}
