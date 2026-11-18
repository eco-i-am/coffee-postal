import { describe, it, expect } from "vitest";
import { peso, totals } from "./money.js";

const SENIOR = { kind: "SENIOR_PWD", value: 0 };

describe("money", () => {
  it("formats centavos as pesos", () => expect(peso(12550)).toBe("\u20b1125.50"));
  it("splits VAT out of a VAT-inclusive price", () => {
    const t = totals(11200, null);
    expect(t.vatable).toBe(10000); expect(t.vat).toBe(1200); expect(t.pay).toBe(11200);
  });
  it("shows no VAT for a branch that is not VAT registered", () => {
    const t = totals(11200, null, false);
    expect(t.vatable).toBe(0); expect(t.vat).toBe(0); expect(t.pay).toBe(11200);
  });
  it("senior/PWD removes VAT then 20%", () => {
    const t = totals(11200, SENIOR);
    expect(t.pay).toBe(8000); expect(t.discount).toBe(3200); expect(t.vat).toBe(0);
  });
  it("senior/PWD at a non-VAT branch is 20% of the price", () => {
    expect(totals(10000, SENIOR, false).pay).toBe(8000);
  });
  it("percent discount rounds half up", () => {
    expect(totals(10000, { kind: "PERCENT", value: 1000 }).pay).toBe(9000);
    expect(totals(1004, { kind: "PERCENT", value: 1250 }, false).discount).toBe(126);
    expect(totals(1001, { kind: "PERCENT", value: 1250 }, false).discount).toBe(125);
  });
  it("fixed discount never goes below zero", () => {
    expect(totals(3000, { kind: "FIXED", value: 5000 }).pay).toBe(0);
    expect(totals(10000, { kind: "FIXED", value: 5000 }).pay).toBe(5000);
  });
});
