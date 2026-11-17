import { describe, it, expect } from "vitest";
import { invNo, invStatus } from "./format.js";

describe("invoice helpers", () => {
  it("pads the invoice number", () => expect(invNo(12)).toBe("INV-0012"));
  it("labels paid, part paid, unpaid and overdue", () => {
    expect(invStatus({ status: "PAID", overdue: false })).toEqual({ label: "Paid", tone: "g" });
    expect(invStatus({ status: "PARTIAL", overdue: false }).label).toBe("Part paid");
    expect(invStatus({ status: "UNPAID", overdue: false })).toEqual({ label: "Unpaid", tone: "y" });
    expect(invStatus({ status: "UNPAID", overdue: true, daysOverdue: 1 })).toEqual({ label: "Overdue 1 day", tone: "r" });
    expect(invStatus({ status: "PARTIAL", overdue: true, daysOverdue: 4 }).label).toBe("Overdue 4 days");
  });
});
