import { describe, expect, it } from "vitest";
import { stepsFor } from "./tutorialSteps.js";

const canOf = (...perms) => (p) => perms.includes(p);
const ids = (me, can) => stepsFor(me, can).map((s) => s.id);

describe("first sign-in tour", () => {
  it("always opens with welcome and password, and ends with done", () => {
    const got = ids({ scope: "BRANCH", role: "Cashier", username: "c" }, canOf());
    expect(got[0]).toBe("welcome");
    expect(got[1]).toBe("password");
    expect(got[got.length - 1]).toBe("done");
  });

  it("shows a cashier the till steps and nothing from HQ", () => {
    const got = ids({ scope: "BRANCH", role: "Cashier", username: "c", branchName: "Cebu City" }, canOf("pos_view", "cash_inout_view", "time_clock_view", "orders_view"));
    expect(got).toEqual(expect.arrayContaining(["clock", "shift", "pos", "offline", "orders"]));
    expect(got.some((id) => id.startsWith("hq-"))).toBe(false);
    expect(got).not.toContain("branch-po");
  });

  it("shows a clock-in-only account just the time clock", () => {
    const got = ids({ scope: "BRANCH", role: "Staff", username: "s" }, canOf("time_clock_view"));
    expect(got).toEqual(["welcome", "password", "clock", "done"]);
  });

  it("shows HQ steps only for what the admin may open", () => {
    const got = ids({ scope: "ADMIN", role: "Super Admin", username: "admin" }, canOf("branches_view", "reports_admin_view"));
    expect(got).toEqual(["welcome", "password", "hq-overview", "hq-branches", "hq-reports", "done"]);
  });

  it("greets by first name and names the branch", () => {
    const [welcome] = stepsFor({ scope: "BRANCH", role: "Manager", username: "m", fullName: "Ana Reyes", branchName: "Cebu City" }, canOf());
    expect(welcome.title).toBe("Welcome, Ana!");
    expect(welcome.body[0]).toContain("Manager at Cebu City");
  });

  it("every step that links somewhere has a path", () => {
    const all = stepsFor({ scope: "ADMIN", role: "Super Admin", username: "a" }, () => true);
    for (const s of all) if ("to" in s) expect(s.to).toMatch(/^\//);
  });
});

describe('page-by-page tour', () => {
  it('names the page and its buttons on the key pages', () => {
    const all = stepsFor({ scope: 'ADMIN', role: 'Super Admin', username: 'a' }, () => true);
    const by = Object.fromEntries(all.map((x) => [x.id, x]));
    for (const id of ['hq-branches', 'hq-products', 'hq-discounts', 'hq-items', 'hq-employees', 'hq-expenses']) {
      expect(by[id].page).toBeTruthy();
      expect(by[id].buttons.length).toBeGreaterThan(0);
      for (const b of by[id].buttons) { expect(b.label).toBeTruthy(); expect(b.does).toBeTruthy(); }
    }
  });
  it('every step has a unique id', () => {
    const all = stepsFor({ scope: 'ADMIN', role: 'Super Admin', username: 'a' }, () => true);
    expect(new Set(all.map((x) => x.id)).size).toBe(all.length);
  });
});

describe("purchase order step by role", () => {
  const me = { scope: "BRANCH", role: "Cashier", username: "c" };
  const step = (can) => stepsFor(me, can).find((x) => x.id === "branch-po");

  it("tells staff who only receive stock that they do not order it", () => {
    const s = step(canOf("branch_po_receive_view"));
    expect(s.title).toBe("Receiving stock from HQ");
    expect(s.body.join(" ")).not.toMatch(/create a purchase order/i);
    expect(s.action).toBeUndefined();
  });

  it("tells managers how to order, and offers the practice", () => {
    const s = step(canOf("branch_po_view", "branch_po_add", "branch_po_receive_view"));
    expect(s.title).toBe("Ordering stock from HQ");
    expect(s.body.join(" ")).toMatch(/create a purchase order/i);
    expect(s.action.kind).toBe("practiceOrders");
  });
});
