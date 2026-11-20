import { describe, expect, it } from "vitest";
import { PRACTICE_MENU, practiceResult } from "./practice.js";
import { addToCart } from "./cart.js";

describe("practice sale", () => {
  const latte = PRACTICE_MENU.items[0];
  const cart = addToCart([], latte, latte.sizes[1], [latte.addons[0]]);   // large + extra shot = 120 + 20 + 25 = 165 pesos

  it("works out the total, VAT and change like a real sale", () => {
    const r = practiceResult(cart, null, "CASH", 20000);
    expect(r.total).toBe(16500);
    expect(r.vatable + r.vat).toBe(16500);
    expect(r.change).toBe(3500);
  });

  it("takes 20% off VAT-exempt for Senior / PWD", () => {
    const rule = PRACTICE_MENU.discounts.find((d) => d.code === "SENIOR_PWD");
    const r = practiceResult(cart, rule, "CASH", 20000);
    expect(r.discount).toBeGreaterThan(0);
    expect(r.total).toBe(16500 - r.discount);
  });

  it("uses made-up ids that can never match a real product", () => {
    for (const i of PRACTICE_MENU.items) expect(i.id).toBeLessThan(0);
  });
});
