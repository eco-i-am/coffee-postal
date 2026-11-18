import { describe, it, expect } from "vitest";
import { matchPin, nextKind, whoIsIn, makePunch, uploadPunches } from "./punches.js";

// a stand-in for SHA-256 so the tests do not depend on the browser's crypto
const fakeHash = async (s) => "h(" + s + ")";
const roster = { employees: [
  { id: 1, name: "Ana", salt: "s1", check: "h(s1:482913)", in: false },
  { id: 2, name: "Ben", salt: "s2", check: "h(s2:731946)", in: true },
] };

describe("punches", () => {
  it("recognises a PIN from the saved staff list", async () => {
    expect((await matchPin(roster, "731946", fakeHash)).name).toBe("Ben");
    expect(await matchPin(roster, "000000", fakeHash)).toBeNull();
    expect(await matchPin(null, "731946", fakeHash)).toBeNull();
  });

  it("works out the next punch from the saved state and what is waiting to upload", () => {
    const ana = roster.employees[0], ben = roster.employees[1];
    expect(nextKind(ana, [])).toBe("IN");
    expect(nextKind(ben, [])).toBe("OUT");
    const q = [makePunch({ employee: ana, check: "c", kind: "IN", id: "p1" })];
    expect(nextKind(ana, q)).toBe("OUT");
    expect(nextKind(ben, q)).toBe("OUT");
    // a rejected punch changes nothing
    expect(nextKind(ana, [{ ...q[0], status: "rejected" }])).toBe("IN");
  });

  it("lists who is in, counting punches still waiting", () => {
    expect(whoIsIn(roster, []).map((e) => e.name)).toEqual(["Ben"]);
    const q = [makePunch({ employee: roster.employees[0], check: "c", kind: "IN", id: "p1" }), makePunch({ employee: roster.employees[1], check: "c", kind: "OUT", id: "p2" })];
    expect(whoIsIn(roster, q).map((e) => e.name)).toEqual(["Ana"]);
  });

  const punch = (id) => ({ id, employeeId: 1, status: "pending" });
  const err = (status, message) => Object.assign(new Error(message), { status });

  it("uploads in order and drops what was sent", async () => {
    const seen = [];
    const r = await uploadPunches([punch("a"), punch("b")], async (p) => { seen.push(p.id); });
    expect(seen).toEqual(["a", "b"]);
    expect(r.queue).toEqual([]);
    expect(r.uploaded).toHaveLength(2);
    expect(r.stopped).toBeNull();
  });

  it("stops and keeps everything when there is no connection", async () => {
    const r = await uploadPunches([punch("a"), punch("b")], async () => { throw err(0, "offline"); });
    expect(r.queue.map((p) => p.id)).toEqual(["a", "b"]);
    expect(r.queue.every((p) => p.status === "pending")).toBe(true);
    expect(r.stopped).toBe("network");
  });

  it("marks a refused punch as rejected and carries on with the next", async () => {
    const r = await uploadPunches([punch("a"), punch("b")], async (p) => { if (p.id === "a") throw err(403, "PIN changed"); });
    expect(r.queue).toHaveLength(1);
    expect(r.queue[0]).toMatchObject({ id: "a", status: "rejected", error: "PIN changed" });
    expect(r.uploaded.map((p) => p.id)).toEqual(["b"]);
  });

  it("leaves rejected punches alone on the next pass", async () => {
    let calls = 0;
    const r = await uploadPunches([{ ...punch("a"), status: "rejected", error: "x" }], async () => { calls++; });
    expect(calls).toBe(0);
    expect(r.queue).toHaveLength(1);
  });
});
