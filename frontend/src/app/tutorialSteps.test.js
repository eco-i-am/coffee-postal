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
    const got = ids({ scope: "BRANCH", role: "Cashier", username: "c", branchName: "Cebu City" }, canOf("pos_view", "time_clock_view", "orders_view"));
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
