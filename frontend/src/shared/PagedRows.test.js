import { describe, expect, it } from "vitest";
import { pageList } from "./PagedRows.jsx";

describe("pageList", () => {
  it("shows every page when there are few", () => {
    expect(pageList(0, 3)).toEqual([0, 1, 2]);
  });
  it("shows first, last and the neighbours of the current page, with gaps", () => {
    expect(pageList(0, 20)).toEqual([0, 1, null, 19]);
    expect(pageList(10, 20)).toEqual([0, null, 9, 10, 11, null, 19]);
    expect(pageList(19, 20)).toEqual([0, null, 18, 19]);
  });
});
