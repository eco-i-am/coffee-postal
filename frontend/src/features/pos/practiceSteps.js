// The coaching steps for each practice screen. A step points at the first of its `alts` that is on screen (so a dialog that
// opens takes over from the button that opened it), and moves on by itself when done(now, atStart) is true.
//   alt  = { sel | label, text }   sel: CSS selector. label: the button's text.   A target ending in ".modal" means a dialog is in front.
//   step = { id, title, alts, done?, manual?, optional? }   manual: shows a Next button instead of waiting for a tap.

const MODAL = ".modal";

// state = { cat, cartCount, otype, disc, discReady, discOpen, modal }   (modal: "item" | "pay" | "receipt" | null)
export const POS_STEPS = [
  { id: "cat", title: "Pick a category", alts: [{ sel: ".catrail", text: "Tap a group on the left, like Coffee." }], done: (s, b) => s.cat !== b.cat },
  { id: "item", title: "Tap a drink", alts: [{ sel: MODAL, text: "Choose a size and an add-on if you like, then tap Add to order." }, { sel: ".pos .menu", text: "Tap Iced Latte to start the order." }], done: (s, b) => s.cartCount > b.cartCount },
  { id: "qty", title: "Change the amount", alts: [{ sel: ".cartlines .line .qty", text: "The customer wants one more. Tap + on the order line." }], done: (s, b) => s.cartCount > b.cartCount },
  { id: "type", title: "Dine-in or takeout", alts: [{ sel: ".bigseg", text: "Tap Takeout. Every order needs a type." }], done: (s) => s.otype === "TAKEOUT" },
  { id: "disc", title: "Add a discount", optional: true, alts: [{ sel: MODAL, text: "Pick Senior / PWD, type any ID number like 1234, then tap Done." }, { sel: ".discbtn", text: "Tap Discount and pick Senior / PWD. Not needed for every order, so you can skip this." }], done: (s) => s.disc !== "NONE" && s.discReady && !s.discOpen },
  { id: "charge", title: "Charge", alts: [{ sel: ".chargebtn", text: "The order is ready. Tap Charge." }], done: (s) => s.modal === "pay" },
  { id: "pay", title: "Take payment", alts: [{ sel: MODAL, text: "Tap Cash, tap the amount the customer gave, then tap Complete order. Nothing is really charged." }], done: (s) => s.modal === "receipt" },
  { id: "receipt", title: "The receipt", alts: [{ sel: MODAL, text: "This is what the customer gets. Tap New order to finish." }], done: (s) => s.modal === null },
];

// state = { result }   result: null | "IN" | "OUT" | "error"
export const CLOCK_STEPS = [
  { id: "in", title: "Time in", alts: [{ sel: ".keypad", text: "Your PIN is 6 digits. For practice, tap 1 2 3 4 5 6 (Ana Reyes)." }], done: (s) => s.result === "IN" },
  { id: "seen", title: "The result", manual: true, alts: [{ sel: ".result", text: "Green means your time in is recorded, with the time. The screen clears for the next person after a few seconds." }] },
  { id: "who", title: "Who is in", manual: true, alts: [{ sel: ".whoin", text: "Ana now shows under Clocked in now. A manager can see this list too." }] },
  { id: "out", title: "Time out", alts: [{ sel: ".keypad", text: "When your shift ends, type the same PIN again: 1 2 3 4 5 6. That records your time out." }], done: (s) => s.result === "OUT" },
  { id: "wrong", title: "A wrong PIN", optional: true, alts: [{ sel: ".keypad", text: "Try a wrong one, like 1 1 1 1 1 1, to see what the message says. You can skip this." }], done: (s) => s.result === "error" },
];

// state = { open, opened, moves, xread, closed }
export const SHIFT_STEPS = [
  { id: "open", title: "Start your shift", alts: [{ sel: "#oc", text: "Count the drawer and type the cash, like 1000. Or tap Count bills and coins. Then tap Take photo and open shift." }], done: (s) => s.opened },
  { id: "kpi", title: "What should be in the drawer", manual: true, alts: [{ sel: ".kpis", text: "Should be in drawer = opening cash + cash sales + cash in - cash out. The pretend shift already has some sales." }] },
  { id: "out", title: "Cash out", alts: [{ sel: "#ca", text: "Say P200 was taken out for ice. Keep Cash out selected, type 200, give a reason, then tap Save." }], done: (s, b) => s.moves > b.moves },
  { id: "x", title: "X reading", optional: true, alts: [{ sel: MODAL, text: "This is a mid-shift summary. Nothing is closed. Tap Close to go back." }, { label: "X reading", text: "Tap X reading to see the sales so far. You can skip this." }], done: (s) => s.xread },
  { id: "close", title: "Close your shift", alts: [{ sel: "form.modal", text: "Count all the cash and type it in. Try a little less than expected to see Short. Then tap Close shift." }, { label: "Close my shift", text: "Tap Close my shift when you finish." }], done: (s) => s.closed },
  { id: "result", title: "Over or short", alts: [{ sel: "form.modal", text: "This tells you if the drawer matches: Exact, Short or Over. Tap Done." }], done: (s) => s.closed && !s.open },
];
