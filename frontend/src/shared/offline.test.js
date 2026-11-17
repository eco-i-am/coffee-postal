import { describe, it, expect, beforeEach } from "vitest";
import { addEntry, classify, discardEntry, loadMenu, loadQueue, makeEntry, offlineRef, pendingFor, rejectedFor, retryEntry, saveMenu, saveQueue, syncQueue } from "./offline.js";

// a tiny stand-in for the browser's localStorage
beforeEach(() => {
  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
  };
});

const req = (n) => ({ id: `0000000${n}-aaaa-4bbb-8ccc-dddddddddddd`, orderType: "TAKEOUT", lines: [] });
const entry = (n, by = "cashier") => makeEntry({ request: req(n), by, expectedTotal: 5000, receipt: {}, now: new Date(`2026-11-18T0${n}:00:00Z`) });
const fail = (status, message = "x") => Object.assign(new Error(message), { status });

describe("queue", () => {
  it("stamps the real sale time on the request and gives a readable ref", () => {
    const e = entry(1);
    expect(e.request.soldAt).toBe("2026-11-18T01:00:00.000Z");
    expect(e.ref).toBe(offlineRef(e.id));
    expect(e.ref).toMatch(/^OFF-[0-9A-F]{5}$/);
  });
  it("saves, reloads and ignores a duplicate", () => {
    let q = addEntry([], entry(1));
    q = addEntry(q, entry(1));
    expect(q).toHaveLength(1);
    expect(loadQueue()).toHaveLength(1);
  });
  it("says so when the browser will not save", () => {
    globalThis.localStorage.setItem = () => { throw new Error("full"); };
    expect(addEntry([], entry(1))).toBeNull();
  });
  it("survives corrupt storage", () => {
    localStorage.setItem("cp.queue.v1", "{not json");
    expect(loadQueue()).toEqual([]);
  });
  it("keeps one menu per person", () => {
    saveMenu("a", { items: [1] });
    expect(loadMenu("a").menu).toEqual({ items: [1] });
    expect(loadMenu("b")).toBeNull();
  });
});

describe("classify", () => {
  it("sorts failures", () => {
    expect(classify(fail(0))).toBe("network");
    expect(classify(fail(401))).toBe("auth");
    expect(classify(fail(503))).toBe("server");
    expect(classify(fail(429))).toBe("server");
    expect(classify(fail(400))).toBe("reject");
    expect(classify(fail(403))).toBe("reject");
  });
});

describe("syncQueue", () => {
  it("uploads in order and clears what went through", async () => {
    const sent = [];
    const q = [entry(1), entry(2)];
    const r = await syncQueue(q, "cashier", async (body) => { sent.push(body.id); return { orderNo: sent.length + 100, total: 5000 }; });
    expect(sent).toEqual([q[0].id, q[1].id]);
    expect(r.queue).toEqual([]);
    expect(r.uploaded.map((u) => u.orderNo)).toEqual([101, 102]);
    expect(r.stopped).toBeNull();
  });
  it("stops on a lost connection and keeps everything left", async () => {
    let n = 0;
    const r = await syncQueue([entry(1), entry(2), entry(3)], "cashier", async () => { if (++n === 2) throw fail(0); return { orderNo: 1, total: 5000 }; });
    expect(r.stopped).toBe("network");
    expect(r.queue.map((e) => e.ref)).toEqual([entry(2).ref, entry(3).ref]);
    expect(n).toBe(2);
  });
  it("stops when signed out, and again on a server error", async () => {
    expect((await syncQueue([entry(1)], "cashier", async () => { throw fail(401); })).stopped).toBe("auth");
    expect((await syncQueue([entry(1)], "cashier", async () => { throw fail(500); })).stopped).toBe("server");
  });
  it("marks an order the server refused and carries on with the rest", async () => {
    const r = await syncQueue([entry(1), entry(2)], "cashier", async (b) => {
      if (b.id === entry(1).id) throw fail(400, "An item on this order is no longer on the menu.");
      return { orderNo: 7, total: 5000 };
    });
    expect(r.queue).toHaveLength(1);
    expect(r.queue[0].status).toBe("rejected");
    expect(r.queue[0].error).toContain("no longer on the menu");
    expect(r.uploaded).toHaveLength(1);
  });
  it("never uploads another cashier's orders or a refused one", async () => {
    let calls = 0;
    const q = [entry(1, "someone.else"), { ...entry(2), status: "rejected" }];
    const r = await syncQueue(q, "cashier", async () => { calls++; return {}; });
    expect(calls).toBe(0);
    expect(r.queue).toEqual(q);
  });
  it("reports a total that differs from what the customer was shown", async () => {
    const r = await syncQueue([entry(1)], "cashier", async () => ({ orderNo: 9, total: 5500 }));
    expect(r.uploaded[0]).toMatchObject({ total: 5500, expectedTotal: 5000 });
  });
});

describe("review helpers", () => {
  it("retries, discards and counts", () => {
    const q = [entry(1), { ...entry(2), status: "rejected", error: "no" }, entry(3, "other")];
    expect(pendingFor(q, "cashier")).toHaveLength(1);
    expect(rejectedFor(q, "cashier")).toHaveLength(1);
    expect(retryEntry(q, q[1].id)[1]).toMatchObject({ status: "pending", error: null });
    expect(discardEntry(q, q[0].id)).toHaveLength(2);
    saveQueue(q);
    expect(loadQueue()).toHaveLength(3);
  });
});
