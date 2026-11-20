import { describe, expect, it } from "vitest";
import { alternatives } from "./tourTarget.js";
import { stepsFor } from "./tutorialSteps.js";

describe("guided tour targets", () => {
  it("splits a label into the button names on screen", () => {
    expect(alternatives("Fix in / Fix out / Add in / Add out")).toEqual(["fix in", "fix out", "add in", "add out"]);
    expect(alternatives("Sales, Products, Profit & loss, Stock")).toEqual(["sales", "products", "profit & loss", "stock"]);
    expect(alternatives("+ Add product")).toEqual(["add product"]);
    expect(alternatives("+ / Clear")).toEqual(["clear"]);
  });

  it("every button in the tour has a name to look for, or a selector", () => {
    const all = stepsFor({ scope: "ADMIN", role: "Super Admin", username: "a" }, () => true)
      .concat(stepsFor({ scope: "BRANCH", role: "Manager", username: "m" }, () => true));
    for (const s of all) for (const b of s.buttons || []) expect(b.sel || alternatives(b.label).length > 0).toBeTruthy();
  });
});
