export const TYPE_NAMES = { DINE_IN: "Dine-in", TAKEOUT: "Takeout", DELIVERY: "Delivery" };
export const METHOD_NAMES = { CASH: "Cash", GCASH: "GCash", MAYA: "Maya", CARD: "Card" };
export const STATUS_NAMES = { PAID: "Paid", REFUNDED: "Refunded", VOID: "Void" };
export const DISCOUNT_NAMES = { NONE: "None", SENIOR_PWD: "Senior / PWD", PROMO10: "Promo 10%" };

export const PO_STATUS = {
  REQUESTED: "Waiting for HQ", APPROVED: "Approved", SHIPPED: "Sent by HQ",
  RECEIVED: "Received", REJECTED: "Rejected", CANCELLED: "Cancelled",
};
export const MOVE_NAMES = { OPENING: "Opening stock", ADJUST: "Count correction", WASTE: "Waste", PO_RECEIVE: "Received from HQ", SALE: "Used in sales" };

/** Stock is counted in thousandths of a unit (1 bag = 1000). 1500 -> "1.5", -36 -> "-0.036". */
export function fmtQty(thousandths) {
  const n = thousandths / 1000;
  return String(Number(n.toFixed(3)));
}

/** CSS tag colour for a PO status: green = good, yellow = waiting on someone, red = stopped. */
export function poTone(status) {
  if (status === "RECEIVED" || status === "APPROVED" || status === "SHIPPED") return "g";
  if (status === "REQUESTED") return "y";
  return "r";
}
export const poNo = (id) => `PO-${String(id).padStart(4, "0")}`;

export function dateTimeOf(iso) {
  return new Date(iso).toLocaleString("en-PH", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit", timeZone: "Asia/Manila" });
}

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
