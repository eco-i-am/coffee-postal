import { describe, it, expect } from "vitest";
import { exportFilename, titleOf } from "./export.js";

describe("export helpers", () => {
  it("makes a readable title from a file name", () => {
    expect(titleOf("profit_loss")).toBe("Profit loss");
    expect(titleOf("branch-pos")).toBe("Branch pos");
    expect(titleOf("")).toBe("Report");
    expect(titleOf(undefined)).toBe("Report");
  });
  it("names the file with the date range and format", () => {
    expect(exportFilename("stock", { from: "2026-01-01", to: "2026-01-31" }, "xlsx")).toBe("stock_2026-01-01_to_2026-01-31.xlsx");
    expect(exportFilename("prices", undefined, "pdf")).toBe("prices.pdf");
  });
});
