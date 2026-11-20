import { describe, expect, it } from "vitest";
import { createPracticeShift } from "./practiceShift.js";

describe("practice shift", () => {
  it("runs open, cash out, X reading and close in memory", async () => {
    const seen = [];
    const sim = createPracticeShift((c) => seen.push(c));
    expect((await sim.api("/api/branch/shift")).shift).toBeNull();
    await sim.api("/api/branch/shift/open", { method: "POST", body: { openingCash: "1000.00" } });
    await sim.api("/api/branch/shift/cash", { method: "POST", body: { kind: "OUT", amount: "200", reason: "Ice" } });
    const { shift } = await sim.api("/api/branch/shift");
    expect(shift.expected).toBe(100000 + 48000 - 20000);   // opening + pretend cash sales - cash out
    const x = await sim.api("/api/branch/shift/reading");
    expect(x.kind).toBe("X");
    const r = await sim.api("/api/branch/shift/close", { method: "POST", body: { countedCash: "1270" } });
    expect(r.variance).toBe(-1000);   // P10 short
    expect((await sim.api("/api/branch/shifts/-1/reading")).kind).toBe("Z");
    expect((await sim.api("/api/branch/shift")).shift).toBeNull();
    expect(sim.calls()).toEqual({ opened: true, moved: 1, xread: true, closed: true });
  });

  it("refuses things a real drawer would refuse, and unknown calls", async () => {
    const sim = createPracticeShift();
    await expect(sim.api("/api/branch/shift/cash", { method: "POST", body: { kind: "OUT", amount: "5", reason: "x" } })).rejects.toMatchObject({ status: 409 });
    await expect(sim.api("/api/pos/orders", { method: "POST", body: {} })).rejects.toBeTruthy();   // never reaches a real order
  });
});
