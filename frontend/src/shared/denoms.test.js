import { describe, expect, it } from "vitest";
import { countTotal, denomLabel, pesoText, pieceCount } from "./denoms.js";

describe("bill and coin count", () => {
  it("adds bills and coins exactly, in centavos", () => {
    // 2 x 1,000 + 500 + 3 x 20 (bills) + 4 x 20 (coins) + 5 x 0.25 + 3 x 0.05
    const counts = { b100000: "2", b50000: "1", b2000: "3", c2000: "4", c25: "5", c5: "3" };
    expect(countTotal(counts)).toBe(200000 + 50000 + 6000 + 8000 + 125 + 15);
    expect(pesoText(countTotal(counts))).toBe("2641.40");
  });

  it("a bill and a coin of the same value are counted separately", () => {
    expect(countTotal({ b2000: 1, c2000: 1 })).toBe(4000);
  });

  it("has no floating point drift on small coins", () => {
    expect(pesoText(countTotal({ c10: 3, c5: 1 }))).toBe("0.35");
    expect(pesoText(countTotal({ c25: 3 }))).toBe("0.75");
  });

  it("ignores blanks, decimals and nonsense", () => {
    expect(countTotal({})).toBe(0);
    expect(countTotal(null)).toBe(0);
    expect(countTotal({ b50000: "", b10000: "1.5", c100: "abc", c500: "-2" })).toBe(0);
    expect(pieceCount({ b50000: "2", c100: "3", c10: "" })).toBe(5);
  });

  it("labels whole pesos and centavos", () => {
    expect(denomLabel(100000)).toBe("₱1,000");
    expect(denomLabel(25)).toBe("₱0.25");
  });
});
