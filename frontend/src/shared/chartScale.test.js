import { describe, expect, it } from "vitest";
import { axisLabel, labelEvery, niceTicks } from "./chartScale.js";

describe("niceTicks", () => {
  it("rounds the top up to a clean number that holds the tallest bar", () => {
    const { ticks, top } = niceTicks(123400); // 1,234.00 pesos
    expect(top).toBeGreaterThanOrEqual(123400);
    expect(ticks[0]).toBe(0);
    expect(ticks.at(-1)).toBe(top);
    // every step is the same size
    const steps = ticks.slice(1).map((t, i) => t - ticks[i]);
    expect(new Set(steps).size).toBe(1);
  });

  it("uses whole round pesos", () => {
    expect(niceTicks(100000).ticks).toEqual([0, 25000, 50000, 75000, 100000]); // 0, 250, 500, 750, 1,000
    expect(niceTicks(45000).ticks).toEqual([0, 20000, 40000, 60000]); // a 450-peso peak: 0, 200, 400, 600
  });

  it("copes with no sales or tiny amounts", () => {
    expect(niceTicks(0).ticks.length).toBeGreaterThan(1);
    expect(niceTicks(0).top).toBeGreaterThan(0);
    expect(niceTicks(5).top).toBeGreaterThan(0);
  });
});

describe("axisLabel", () => {
  it("writes whole pesos while the axis stays under 10,000", () => {
    expect(axisLabel(50000, 500000)).toBe("₱500");
    expect(axisLabel(0, 500000)).toBe("₱0");
    expect(axisLabel(250000, 500000)).toBe("₱2,500");
  });
  it("goes compact once the axis reaches 10,000", () => {
    expect(axisLabel(1500000, 4000000)).toBe("₱15K");
    expect(axisLabel(0, 4000000)).toBe("₱0");
    expect(axisLabel(250000000, 400000000)).toBe("₱2.5M");
  });
});

describe("labelEvery", () => {
  it("labels every column when there are few and thins them when there are many", () => {
    expect(labelEvery(7)).toBe(1);
    expect(labelEvery(12)).toBe(1);
    expect(labelEvery(31)).toBe(3);
    expect(labelEvery(366)).toBe(31);
  });
});
