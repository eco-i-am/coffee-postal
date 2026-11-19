import { describe, expect, it } from "vitest";
import { clockOnlyAccount, inKiosk } from "./kiosk.js";

const canOf = (...perms) => (p) => perms.includes(p);
const branchMe = { username: "tablet1", scope: "BRANCH" };

describe("clock-only mode", () => {
  it("a Time Clock account (clock and nothing else) is always clock-only", () => {
    expect(clockOnlyAccount(branchMe, canOf("time_clock_view"))).toBe(true);
    expect(inKiosk(branchMe, canOf("time_clock_view"))).toBe(true);
  });

  it("a till or manager account is not, unless switched on", () => {
    expect(clockOnlyAccount(branchMe, canOf("time_clock_view", "pos_view"))).toBe(false);
    expect(clockOnlyAccount(branchMe, canOf("time_clock_view", "orders_view"))).toBe(false);
    expect(inKiosk(branchMe, canOf("time_clock_view", "pos_view"))).toBe(false);   // no storage in this test: off
  });

  it("an account that cannot use the clock, and HQ, are never clock-only", () => {
    expect(clockOnlyAccount(branchMe, canOf())).toBe(false);
    expect(clockOnlyAccount({ username: "a", scope: "ADMIN" }, canOf("time_clock_view"))).toBe(false);
    expect(clockOnlyAccount(null, canOf("time_clock_view"))).toBe(false);
  });
});
