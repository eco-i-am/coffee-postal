// Philippine bills and coins for the cash drawer counter. Amounts are whole centavos so the total is always exact.
// A bill and a coin can have the same value (20), so each row has its own key.

export const BILLS = [100000, 50000, 20000, 10000, 5000, 2000].map((c) => ({ key: `b${c}`, cents: c }));
export const COINS = [2000, 1000, 500, 100, 25, 10, 5].map((c) => ({ key: `c${c}`, cents: c }));
const ALL = [...BILLS, ...COINS];

/** "1,000" for a bill, "0.25" for a small coin. */
export function denomLabel(cents) {
  const pesos = cents / 100;
  return `₱${cents % 100 === 0 ? pesos.toLocaleString("en-PH") : pesos.toFixed(2)}`;
}

/** counts = { b100000: 2, c25: 4, ... } (anything missing or not a whole number counts as zero) -> total in centavos. */
export function countTotal(counts) {
  let total = 0;
  for (const d of ALL) {
    const n = Number(counts?.[d.key]);
    if (Number.isInteger(n) && n > 0) total += n * d.cents;
  }
  return total;
}

/** How many notes and coins were counted. */
export function pieceCount(counts) {
  let n = 0;
  for (const d of ALL) {
    const q = Number(counts?.[d.key]);
    if (Number.isInteger(q) && q > 0) n += q;
  }
  return n;
}

/** Centavos as the "1234.50" text the amount boxes take. */
export const pesoText = (cents) => (cents / 100).toFixed(2);
