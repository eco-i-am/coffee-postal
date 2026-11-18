export const TYPE_NAMES = { DINE_IN: "Dine-in", TAKEOUT: "Takeout", DELIVERY: "Delivery" };
export const METHOD_NAMES = { CASH: "Cash", GCASH: "GCash", MAYA: "Maya", CARD: "Card" };
export const STATUS_NAMES = { PAID: "Paid", REFUNDED: "Refunded", VOID: "Void" };
export const DISCOUNT_NAMES = { NONE: "None", SENIOR_PWD: "Senior / PWD", PROMO10: "Promo 10%" };

// Nine stages from request to completed, then the two ways an order can stop.
export const PO_STATUS = {
  REQUESTED: "Requested", PENDING: "Pending", TO_PAY: "To pay", PROCESSING: "Processing", FOR_PICKUP: "For pick-up",
  IN_TRANSIT: "In transit", PICKED_UP: "Picked up", DELIVERED: "Delivered", COMPLETED: "Completed",
  REJECTED: "Rejected", CANCELLED: "Cancelled",
};
export const PO_STAGES = ["REQUESTED", "PENDING", "TO_PAY", "PROCESSING", "FOR_PICKUP", "IN_TRANSIT", "PICKED_UP", "DELIVERED", "COMPLETED"];
/** The steps HQ can take from each stage (the server enforces the same table). */
export const PO_NEXT = {
  PENDING: ["TO_PAY", "PROCESSING"], TO_PAY: ["PROCESSING"], PROCESSING: ["FOR_PICKUP"],
  FOR_PICKUP: ["IN_TRANSIT", "PICKED_UP"], IN_TRANSIT: ["PICKED_UP", "DELIVERED"], PICKED_UP: ["DELIVERED"],
};
/** The button text for moving to a stage. */
export const PO_STEP_LABEL = {
  TO_PAY: "Ask branch to pay", PROCESSING: "Start processing", FOR_PICKUP: "Ready for pick-up",
  IN_TRANSIT: "Mark in transit", PICKED_UP: "Mark picked up", DELIVERED: "Mark delivered",
};
export const PO_REJECTABLE = ["REQUESTED", "PENDING", "TO_PAY"];
/** From these stages the branch can count what has arrived. */
export const PO_RECEIVABLE = ["FOR_PICKUP", "IN_TRANSIT", "PICKED_UP", "DELIVERED"];
export const MOVE_NAMES = { OPENING: "Opening stock", ADJUST: "Count correction", WASTE: "Waste", PO_RECEIVE: "Received from HQ", SALE: "Used in sales" };

/** Stock is counted in thousandths of a unit (1 bag = 1000). 1500 -> "1.5", -36 -> "-0.036". */
export function fmtQty(thousandths) {
  const n = thousandths / 1000;
  return String(Number(n.toFixed(3)));
}

/** CSS tag colour for a PO status: green = done or arriving, yellow = waiting on someone, red = stopped. */
export function poTone(status) {
  if (status === "COMPLETED" || status === "DELIVERED") return "g";
  if (status === "REJECTED" || status === "CANCELLED") return "r";
  return "y";
}
export const poNo = (id) => `PO-${String(id).padStart(4, "0")}`;
export const invNo = (id) => `INV-${String(id).padStart(4, "0")}`;
export const PAY_METHODS = { CASH: "Cash", BANK: "Bank transfer", GCASH: "GCash", MAYA: "Maya", CHECK: "Check" };

/** Plain-language status and colour for an invoice row from the API. */
export function invStatus(inv) {
  if (inv.status === "PAID") return { label: "Paid", tone: "g" };
  if (inv.overdue) return { label: `Overdue ${inv.daysOverdue} ${inv.daysOverdue === 1 ? "day" : "days"}`, tone: "r" };
  return { label: inv.status === "PARTIAL" ? "Part paid" : "Unpaid", tone: "y" };
}

export function dateOf(ymd) {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-PH", { month: "short", day: "numeric", year: "numeric" });
}

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
