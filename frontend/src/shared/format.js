export const TYPE_NAMES = { DINE_IN: "Dine-in", TAKEOUT: "Takeout", DELIVERY: "Delivery" };
export const METHOD_NAMES = { CASH: "Cash", GCASH: "GCash", MAYA: "Maya", CARD: "Card" };
export const STATUS_NAMES = { PAID: "Paid", REFUNDED: "Refunded", VOID: "Void" };
export const DISCOUNT_NAMES = { NONE: "None", SENIOR_PWD: "Senior / PWD", PROMO10: "Promo 10%" };

const MANILA = "Asia/Manila";

export function timeOf(iso) {
  return new Date(iso).toLocaleTimeString("en-PH", { hour: "numeric", minute: "2-digit", timeZone: MANILA });
}

export function hourLabel(h) {
  const hh = h % 12 === 0 ? 12 : h % 12;
  return `${hh}${h < 12 ? "a" : "p"}`;
}

/** Today's date in Manila as YYYY-MM-DD (what the date pickers and the API use). */
export function todayManila() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: MANILA }).format(new Date());
}

export function prettyDate(ymd) {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-PH", { weekday: "long", month: "long", day: "numeric", year: "numeric" });
}
