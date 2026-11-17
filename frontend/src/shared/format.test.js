import { describe, it, expect } from "vitest";
import { hourLabel, prettyDate } from "./format.js";

describe("format", () => {
  it("labels hours on a 12-hour clock", () => {
    expect(hourLabel(6)).toBe("6a");
    expect(hourLabel(12)).toBe("12p");
    expect(hourLabel(15)).toBe("3p");
  });
  it("writes a readable date", () => {
    expect(prettyDate("2026-11-17")).toContain("November 17, 2026");
  });
});
