import { describe, it, expect } from "vitest";
import { peso, totals } from "./money.js";

describe("money", () => {
  it("formats centavos as pesos", () => expect(peso(12550)).toBe("₱125.50"));
  it("splits VAT out of a VAT-inclusive price", () => {
    const t = totals(11200, "NONE");
    expect(t.vatable).toBe(10000); expect(t.vat).toBe(1200); expect(t.pay).toBe(11200);
  });
  it("senior/PWD removes VAT then 20%", () => {
    const t = totals(11200, "SENIOR_PWD");
    expect(t.pay).toBe(8000); expect(t.discount).toBe(3200); expect(t.vat).toBe(0);
  });
});
