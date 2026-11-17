import { describe, it, expect } from "vitest";
import { poNo, poTone, PO_STATUS } from "./format.js";

describe("purchase order helpers", () => {
  it("pads the PO number", () => {
    expect(poNo(7)).toBe("PO-0007");
    expect(poNo(12345)).toBe("PO-12345");
  });
  it("colours waiting orders yellow, finished green, stopped red", () => {
    expect(poTone("REQUESTED")).toBe("y");
    expect(poTone("RECEIVED")).toBe("g");
    expect(poTone("REJECTED")).toBe("r");
    expect(poTone("CANCELLED")).toBe("r");
  });
  it("has a plain-language label for every status", () => {
    for (const s of ["REQUESTED", "APPROVED", "SHIPPED", "RECEIVED", "REJECTED", "CANCELLED"]) expect(PO_STATUS[s]).toBeTruthy();
  });
});
