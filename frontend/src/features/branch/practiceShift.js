// A pretend cash drawer for the shift practice screen. It answers the same calls the real shift screens make, in memory only:
// nothing goes to the server, nothing is saved on the device, and it starts fresh each time.
import { ApiError } from "../../shared/api.js";

const centavos = (v) => {
  const n = Number.parseFloat(String(v ?? "").replace(/,/g, ""));
  return Number.isFinite(n) && n >= 0 ? Math.round(n * 100) : null;
};

// the pretend sales already rung up this shift: 3 orders, 2 paid in cash
const SALES = { orders: 3, cash: 48000, other: 13000, gross: 65000, discount: 4000 };

export function createPracticeShift(onChange = () => {}) {
  let shift = null;
  let closed = null;
  const calls = { opened: false, moved: 0, xread: false, closed: false };
  const touch = () => onChange({ ...calls });

  const expected = () => shift.openingCash + SALES.cash + shift.cashIn - shift.cashOut;
  const snapshot = () => ({ ...shift, expected: expected(), cashSales: SALES.cash, orders: SALES.orders, sales: SALES.cash + SALES.other, movements: [...shift.movements] });
  const reading = (kind, extra = {}) => ({
    kind, branch: "Practice Branch", cashier: "You (practice)", openedAt: shift.openedAt, orders: SALES.orders, firstOrderNo: 1, lastOrderNo: 3,
    gross: SALES.gross, discounts: SALES.discount, discountLines: [{ label: "10% off promo", count: 1, amount: SALES.discount }], net: SALES.cash + SALES.other,
    vatRegistered: true, vatable: Math.floor(((SALES.cash + SALES.other) * 100) / 112), vat: SALES.cash + SALES.other - Math.floor(((SALES.cash + SALES.other) * 100) / 112),
    refundCount: 0, refunded: 0, payments: [{ label: "CASH", count: 2, amount: SALES.cash }, { label: "GCASH", count: 1, amount: SALES.other }],
    openingCash: shift.openingCash, cashSales: SALES.cash, cashIn: shift.cashIn, cashOut: shift.cashOut, expected: expected(), ...extra,
  });

  async function api(path, { method = "GET", body } = {}) {
    if (method === "GET" && path === "/api/branch/shift") return { shift: shift ? snapshot() : null };
    if (method === "POST" && path === "/api/branch/shift/open") {
      const c = centavos(body?.openingCash);
      if (c === null) throw new ApiError(400, "Type the cash in the drawer, like 1000.");
      shift = { id: -1, openingCash: c, cashIn: 0, cashOut: 0, openedAt: new Date().toISOString(), movements: [] };
      calls.opened = true; touch();
      return {};
    }
    if (!shift && !path.startsWith("/api/branch/shifts/")) throw new ApiError(409, "No shift is open.");
    if (method === "POST" && path === "/api/branch/shift/cash") {
      const c = centavos(body?.amount);
      if (!c) throw new ApiError(400, "Type an amount.");
      if (!String(body?.reason || "").trim()) throw new ApiError(400, "Give a reason.");
      const kind = body.kind === "IN" ? "IN" : "OUT";
      shift.movements.unshift({ id: shift.movements.length + 1, kind, amount: c, reason: body.reason.trim(), at: new Date().toISOString() });
      if (kind === "IN") shift.cashIn += c; else shift.cashOut += c;
      calls.moved += 1; touch();
      return {};
    }
    if (method === "GET" && path === "/api/branch/shift/reading") { calls.xread = true; touch(); return reading("X"); }
    if (method === "POST" && path === "/api/branch/shift/close") {
      const counted = centavos(body?.countedCash);
      if (counted === null) throw new ApiError(400, "Type the cash you counted.");
      const exp = expected();
      closed = { ...reading("Z", { closedAt: new Date().toISOString(), zNo: 1, counted, variance: counted - exp }), id: -1 };
      const result = { id: -1, openedAt: shift.openedAt, expected: exp, counted, variance: counted - exp, zNo: 1 };
      shift = null;
      calls.closed = true; touch();
      return result;
    }
    if (method === "GET" && path === "/api/branch/shifts/-1/reading" && closed) return closed;
    throw new ApiError(404, "That is not part of the practice.");
  }

  return {
    api,
    calls: () => ({ ...calls }),
    reset() { shift = null; closed = null; calls.opened = false; calls.moved = 0; calls.xread = false; calls.closed = false; touch(); },
  };
}
