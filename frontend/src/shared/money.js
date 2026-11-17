// Money is integer centavos everywhere. These only format for display.
export function peso(c) {
  const n = Math.abs(c) / 100;
  const s = n.toLocaleString("en-PH", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return (c < 0 ? "-" : "") + "₱" + s;
}

// Same VAT/discount rules as the server (the server is the one that counts).
const vatEx = (v) => Math.round(v / 1.12);
export function totals(gross, disc) {
  let discount = 0, pay = gross, vatable, vat;
  if (disc === "SENIOR_PWD") {
    const net = vatEx(gross);
    discount = Math.round(net * 0.2) + (gross - net);
    pay = gross - discount; vatable = 0; vat = 0;
  } else {
    if (disc === "PROMO10") { discount = Math.round(gross * 0.1); pay = gross - discount; }
    vatable = vatEx(pay); vat = pay - vatable;
  }
  return { gross, discount, pay, vatable, vat };
}
