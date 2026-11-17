import { describe, it, expect } from "vitest";
import { csvCell, toCsv, pesoCell } from "./csv.js";
import { addDays, daysBetween, presetRange } from "./dates.js";

describe("csv", () => {
  it("quotes commas, quotes and newlines", () => {
    expect(csvCell("Mocha, large")).toBe('"Mocha, large"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
  });
  it("defuses spreadsheet formulas but leaves real negative numbers alone", () => {
    expect(csvCell("=SUM(A1:A9)")).toBe("'=SUM(A1:A9)");
    expect(csvCell("-cmd|calc")).toBe("'-cmd|calc");
    expect(csvCell(-9)).toBe("-9");
    expect(csvCell("-9.00")).toBe("-9.00");
  });
  it("builds rows and formats centavos", () => {
    expect(toCsv([["a", "b"], [1, "x,y"]])).toBe('a,b\r\n1,"x,y"\r\n');
    expect(pesoCell(12550)).toBe("125.50");
  });
});

describe("dates", () => {
  it("adds days across month ends", () => {
    expect(addDays("2026-11-30", 1)).toBe("2026-12-01");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
  });
  it("counts days inclusively", () => expect(daysBetween("2026-11-01", "2026-11-07")).toBe(7));
  it("builds presets", () => {
    expect(presetRange("7d", "2026-11-17")).toEqual({ from: "2026-11-11", to: "2026-11-17" });
    expect(presetRange("month", "2026-11-17")).toEqual({ from: "2026-11-01", to: "2026-11-17" });
    expect(presetRange("lastmonth", "2026-11-17")).toEqual({ from: "2026-10-01", to: "2026-10-31" });
    expect(presetRange("lastmonth", "2026-01-10")).toEqual({ from: "2025-12-01", to: "2025-12-31" });
  });
});
