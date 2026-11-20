import { describe, expect, it } from "vitest";
import { EXPECTED } from "./code128.fixtures.js";
import { canEncode, code128Bars, code128Modules, code128Values } from "./code128.js";

describe("Code 128 barcodes", () => {
  it("matches an independent implementation for every sample", () => {
    for (const [text, modules] of Object.entries(EXPECTED)) {
      expect(code128Modules(text), text).toBe(modules);
    }
  });

  it("computes the check digit as the weighted sum modulo 103", () => {
    // start B (104) + 'A' (33) x 1 = 137 -> 137 % 103 = 34
    expect(code128Values("A")).toEqual([104, 33, 34, 106]);
  });

  it("is 11 modules per symbol and 13 for the stop", () => {
    expect(code128Modules("Hello").length).toBe(11 * (1 + 5 + 1) + 13);
  });

  it("only accepts printable ASCII up to 40 characters", () => {
    expect(canEncode("SKU-001")).toBe(true);
    expect(canEncode("")).toBe(false);
    expect(canEncode("café")).toBe(false);
    expect(canEncode("x".repeat(41))).toBe(false);
    expect(() => code128Modules("café")).toThrow();
  });

  it("gives bars that add up to the same width", () => {
    const { bars, width } = code128Bars("Hello");
    expect(width).toBe(code128Modules("Hello").length);
    expect(bars[0][0]).toBe(0);
    expect(bars.reduce((s, [, w]) => s + w, 0)).toBe([...code128Modules("Hello")].filter((c) => c === "1").length);
  });
});
