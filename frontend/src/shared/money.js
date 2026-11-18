// Money is integer centavos everywhere. These only format for display.
export function peso(c) {
  const n = Math.abs(c) / 100;
  const s = n.toLocaleString("en-PH", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (c < 0 ? "-" : "") + "₱" + s;
}

// Same VAT/discount rules as the server (the server is the one that counts). All integer centavos, half up.
// rule: { kind: "SENIOR_PWD" | "PERCENT" | "FIXED", value } from the menu's discount list, or null for none.
// PERCENT value is basis points (1000 = 10%); FIXED value is centavos.
const vatEx = (v) => Math.floor((v * 100 + 56) / 112);
const pct = (amount, bp) => Math.floor((amount * bp + 5000) / 10000);
export function totals(gross, rule, vatRegistered = true) {
  const ex = (v) => (vatRegistered ? vatEx(v) : v);
  const split = (pay) => (vatRegistered ? { vatable: vatEx(pay), vat: pay - vatEx(pay) } : { vatable: 0, vat: 0 });
  let discount = 0, pay = gross;
  if (rule && rule.kind === "SENIOR_PWD") {
    const net = ex(gross);
    discount = pct(net, 2000) + (gross - net);
    return { gross, discount, pay: gross - discount, vatable: 0, vat: 0 };
  }
  if (rule && rule.kind === "PERCENT") discount = pct(gross, rule.value);
  if (rule && rule.kind === "FIXED") discount = Math.min(rule.value, gross);
  pay = gross - discount;
  return { gross, discount, pay, ...split(pay) };
}
