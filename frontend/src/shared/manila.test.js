import { describe, it, expect } from "vitest";
import { manilaInput, fromManilaInput, hoursText } from "./dates.js";

describe("Manila time for the correction form", () => {
  it("shows an instant in Manila time", () => {
    expect(manilaInput("2026-11-18T00:30:00Z")).toBe("2026-11-18T08:30");
    expect(manilaInput("2026-11-18T17:00:00Z")).toBe("2026-11-19T01:00");
  });
  it("reads a typed time as Manila time", () => {
    expect(fromManilaInput("2026-11-18T08:30")).toBe("2026-11-18T00:30:00.000Z");
    expect(fromManilaInput("not a date")).toBeNull();
  });
  it("round trips", () => {
    expect(manilaInput(fromManilaInput("2026-03-01T23:45"))).toBe("2026-03-01T23:45");
  });
  it("writes hours and minutes", () => {
    expect(hoursText(450)).toBe("7h 30m");
    expect(hoursText(480)).toBe("8h");
    expect(hoursText(45)).toBe("45m");
    expect(hoursText(null)).toBe("");
  });
});
