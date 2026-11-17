import { describe, it, expect } from "vitest";
import { fmtQty } from "./format.js";

describe("fmtQty (stock is counted in thousandths)", () => {
  it("shows whole units without decimals", () => expect(fmtQty(5000)).toBe("5"));
  it("shows fractions", () => {
    expect(fmtQty(1500)).toBe("1.5");
    expect(fmtQty(18)).toBe("0.018");
  });
  it("keeps the sign when stock goes negative", () => expect(fmtQty(-36)).toBe("-0.036"));
});
