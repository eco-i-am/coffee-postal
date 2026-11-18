import { describe, it, expect } from "vitest";
import { poNo, poTone, PO_STATUS, PO_STAGES, PO_NEXT, PO_RECEIVABLE, PO_REJECTABLE } from "./format.js";

describe("purchase order helpers", () => {
  it("pads the PO number", () => {
    expect(poNo(7)).toBe("PO-0007");
    expect(poNo(12345)).toBe("PO-12345");
  });
  it("colours waiting orders yellow, finished green, stopped red", () => {
    expect(poTone("REQUESTED")).toBe("y");
    expect(poTone("IN_TRANSIT")).toBe("y");
    expect(poTone("COMPLETED")).toBe("g");
    expect(poTone("REJECTED")).toBe("r");
    expect(poTone("CANCELLED")).toBe("r");
  });
  it("has a plain-language label for every status", () => {
    for (const s of [...PO_STAGES, "REJECTED", "CANCELLED"]) expect(PO_STATUS[s]).toBeTruthy();
  });
  it("has the nine stages in order", () => {
    expect(PO_STAGES).toEqual(["REQUESTED", "PENDING", "TO_PAY", "PROCESSING", "FOR_PICKUP", "IN_TRANSIT", "PICKED_UP", "DELIVERED", "COMPLETED"]);
  });
  it("only ever moves an order forward, and every step is a real stage", () => {
    for (const [from, tos] of Object.entries(PO_NEXT)) {
      for (const to of tos) expect(PO_STAGES.indexOf(to)).toBeGreaterThan(PO_STAGES.indexOf(from));
    }
    expect(PO_NEXT.DELIVERED).toBeUndefined();   // after Delivered the branch takes over
    expect(PO_NEXT.COMPLETED).toBeUndefined();
  });
  it("lets the branch count goods once HQ has them ready, and HQ reject only before processing", () => {
    expect(PO_RECEIVABLE).toContain("FOR_PICKUP");
    expect(PO_RECEIVABLE).not.toContain("PROCESSING");
    expect(PO_REJECTABLE).not.toContain("PROCESSING");
  });
});
