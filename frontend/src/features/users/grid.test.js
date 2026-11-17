import { describe, it, expect } from "vitest";
import { gridFrom, toggle, gridToPermissions, setAll } from "./grid.js";

describe("permission grid", () => {
  it("ticking add also ticks view", () => {
    const g = toggle({}, "pos", "add", true);
    expect(g.pos).toEqual({ view: true, add: true, edit: false, delete: false });
  });
  it("unticking view clears everything for that module", () => {
    const g = toggle(setAll({}, "pos", true), "pos", "view", false);
    expect(g.pos).toEqual({ view: false, add: false, edit: false, delete: false });
  });
  it("unticking edit keeps view", () => {
    const g = toggle(setAll({}, "pos", true), "pos", "edit", false);
    expect(g.pos.view).toBe(true);
    expect(g.pos.edit).toBe(false);
  });
  it("only sends modules with something ticked", () => {
    const g = toggle(toggle({}, "pos", "view", true), "orders", "view", false);
    expect(gridToPermissions(g)).toEqual([{ module: "pos", view: true, add: false, edit: false, delete: false }]);
  });
  it("round-trips from the API shape", () => {
    const perms = [{ module: "pos", view: true, add: true, edit: false, delete: false }];
    expect(gridToPermissions(gridFrom(perms))).toEqual(perms);
  });
});
