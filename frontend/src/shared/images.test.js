import { describe, expect, it } from "vitest";
import { fitSize, imageUrl, squareCrop } from "./images.js";

describe("photo helpers", () => {
  it("shrinks a big photo to fit, keeping its shape", () => {
    expect(fitSize(4000, 3000, 480)).toEqual({ w: 480, h: 360 });
    expect(fitSize(3000, 4000, 480)).toEqual({ w: 360, h: 480 });
  });

  it("never makes a small photo bigger", () => {
    expect(fitSize(200, 100, 480)).toEqual({ w: 200, h: 100 });
  });

  it("crops the centre square", () => {
    expect(squareCrop(400, 300)).toEqual({ x: 50, y: 0, side: 300 });
    expect(squareCrop(300, 400)).toEqual({ x: 0, y: 50, side: 300 });
    expect(squareCrop(300, 300)).toEqual({ x: 0, y: 0, side: 300 });
  });

  it("puts the version in the URL so a changed photo is fetched again", () => {
    expect(imageUrl("product", 7, 123)).toBe("/api/pos/images/product/7?v=123");
  });
});
