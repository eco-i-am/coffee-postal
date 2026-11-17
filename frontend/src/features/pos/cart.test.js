import { describe, it, expect } from "vitest";
import { addToCart, changeQty, cartGross } from "./cart.js";

const latte = { id: 1, name: "Cafe Latte", price: 9900 };
const large = { id: 7, name: "Large", price: 2000 };
const shot = { id: 3, name: "Extra shot", price: 2500 };

describe("cart", () => {
  it("prices size and add-ons into the unit price", () => {
    const c = addToCart([], latte, large, [shot]);
    expect(c[0].unit).toBe(14400);
    expect(c[0].opt).toBe("Large, Extra shot");
  });
  it("merges the same combination and keeps different ones apart", () => {
    let c = addToCart([], latte, large, []);
    c = addToCart(c, latte, large, []);
    c = addToCart(c, latte, null, []);
    expect(c).toHaveLength(2);
    expect(c[0].qty).toBe(2);
    expect(cartGross(c)).toBe(2 * 11900 + 9900);
  });
  it("removes a line when quantity reaches zero", () => {
    const c = addToCart([], latte, null, []);
    expect(changeQty(c, c[0].key, -1)).toEqual([]);
  });
});
