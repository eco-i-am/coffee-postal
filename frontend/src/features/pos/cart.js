// Cart helpers. A line is one product + size + add-ons; the same combination merges into one line.
export function lineKey(productId, size, addons) {
  return `${productId}|${size ? size.id : ""}|${addons.map((a) => a.id).sort((a, b) => a - b).join(",")}`;
}

export function addToCart(cart, item, size, addons) {
  const unit = item.price + (size ? size.price : 0) + addons.reduce((s, a) => s + a.price, 0);
  const key = lineKey(item.id, size, addons);
  const existing = cart.find((l) => l.key === key);
  if (existing) return cart.map((l) => (l.key === key ? { ...l, qty: l.qty + 1 } : l));
  const opt = [size ? size.name : null, ...addons.map((a) => a.name)].filter(Boolean).join(", ");
  return [
    ...cart,
    { key, productId: item.id, variantId: size ? size.id : null, addonIds: addons.map((a) => a.id), name: item.name, opt, unit, qty: 1 },
  ];
}

export function changeQty(cart, key, delta) {
  return cart.map((l) => (l.key === key ? { ...l, qty: l.qty + delta } : l)).filter((l) => l.qty > 0);
}

export const cartGross = (cart) => cart.reduce((s, l) => s + l.unit * l.qty, 0);

export function newOrderId() {
  if (globalThis.crypto && crypto.randomUUID) return crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === "x" ? r : (r & 3) | 8).toString(16);
  });
}
