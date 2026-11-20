// Practice mode for the Point of Sale: a made-up menu and a made-up result, so a new cashier can ring up a pretend sale.
// Nothing here is sent to the server or saved on the device, and no real order, stock or money is touched.
import { totals } from "../../shared/money.js";
import { cartGross } from "./cart.js";

const SHOTS = [{ id: -21, name: "Extra shot", price: 2500 }, { id: -22, name: "Oat milk", price: 3000 }];

export const PRACTICE_MENU = {
  vatRegistered: true,
  categories: ["Coffee", "Non-coffee", "Pastry"],
  tags: [],
  discounts: [
    { code: "SENIOR_PWD", name: "Senior / PWD", kind: "SENIOR_PWD", value: 0, requiresId: true, approvalOver: null },
    { code: "PROMO10", name: "10% off promo", kind: "PERCENT", value: 1000, requiresId: false, approvalOver: null },
  ],
  items: [
    { id: -1, name: "Iced Latte", price: 12000, art: "iced", color: "#FFC74A", category: "Coffee", tags: [], sizes: [{ id: -11, name: "Regular", price: 0 }, { id: -12, name: "Large", price: 2000 }], addons: SHOTS },
    { id: -2, name: "Hot Americano", price: 9000, art: "hot", color: "#FDCDC1", category: "Coffee", tags: [], sizes: [], addons: SHOTS.slice(0, 1) },
    { id: -3, name: "Caramel Frappe", price: 14500, art: "frap", color: "#8DCCC0", category: "Non-coffee", tags: [], sizes: [{ id: -13, name: "Regular", price: 0 }, { id: -14, name: "Large", price: 2000 }], addons: [] },
    { id: -4, name: "Butter Croissant", price: 8500, art: "pastry", color: "#FFA076", category: "Pastry", tags: [], sizes: [], addons: [] },
    { id: -5, name: "Choco Chip Cookie", price: 6000, art: "cookie", color: "#FFC74A", category: "Pastry", tags: [], sizes: [], addons: [] },
    { id: -6, name: "Ham & Cheese Sandwich", price: 11000, art: "sand", color: "#8DCCC0", category: "Pastry", tags: [], sizes: [], addons: [] },
  ],
};

// the pretend staff on the practice time clock. The PIN stays here: the real tablet never has PINs.
export const PRACTICE_STAFF = [
  { id: -1, name: "Ana Reyes", position: "Barista", pin: "123456" },
  { id: -2, name: "Ben Cruz", position: "Cashier", pin: "654321" },
];
export const practiceRoster = () => ({ photoDays: 90, employees: PRACTICE_STAFF.map(({ pin, ...e }) => ({ ...e, in: false, face: false })) });

/** What the server would send back for a paid order, worked out here from the pretend cart. */
export function practiceResult(cart, rule, method, tendered) {
  const t = totals(cartGross(cart), rule, PRACTICE_MENU.vatRegistered);
  return { offline: false, orderNo: "PRACTICE", subtotal: t.gross, discount: t.discount, total: t.pay, vatable: t.vatable, vat: t.vat, change: method === "CASH" ? Math.max(tendered - t.pay, 0) : 0 };
}
