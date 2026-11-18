// The first-sign-in tour. A step shows only when the person's account can use that part of the app,
// so a cashier never reads about HQ screens and a clock-in-only account just sees time in / out.
// Wording follows the screens as built; keep it short and in plain words.

export function stepsFor(me, can) {
  const admin = me.scope === "ADMIN";
  const name = (me.fullName || me.username || "").split(" ")[0];
  const where = admin ? "HQ" : me.branchName || "your branch";
  const s = [];

  s.push({
    id: "welcome",
    title: `Welcome${name ? `, ${name}` : ""}!`,
    body: [
      `This is Coffee Postal. You are signed in as ${me.role} at ${where}.`,
      "This quick tour shows what you can do here. It takes about a minute, and you can open it again any time from the left menu (Quick tour).",
    ],
  });

  s.push({
    id: "password",
    title: "Make your password your own",
    body: [
      "If someone gave you a starter password, change it now. Pick at least 8 characters with a letter and a number.",
      "You can find Change password at the bottom of the left menu.",
    ],
    action: { label: "Change password now", kind: "password" },
  });

  if (!admin && can("time_clock_view")) {
    s.push({
      id: "clock",
      title: "Time in and time out",
      body: [
        "Punch in when you start and out when you finish, using your 6-digit PIN. A photo is taken with each punch.",
        "No internet? The punch is saved on the tablet and sent when the connection returns.",
        "Forgot to time out? Tell your manager. Only a manager can correct a punch.",
      ],
      to: "/branch/clock",
    });
  }

  if (!admin && can("pos_view")) {
    s.push({
      id: "shift",
      title: "Start your shift",
      body: [
        "Before your first sale, open Cash Drawer and enter the cash you start with. Sales paid in cash are counted against it.",
        "At the end of your shift, count the drawer and close the shift. You will see whether the drawer is over or short.",
      ],
      to: "/branch/cash",
    });
    s.push({
      id: "pos",
      title: "Ringing up a sale",
      body: [
        "Open Point of Sale, tap what the customer wants, and choose Dine-in, Takeout or Delivery.",
        "Add a discount if one applies. A discount over the limit asks for a manager PIN.",
        "Take payment, then print or show the receipt.",
      ],
      to: "/pos",
    });
    s.push({
      id: "offline",
      title: "If the internet drops, keep selling",
      body: [
        "The till keeps working with no internet. Orders wait on the tablet and upload by themselves when the connection is back.",
        "The Offline note in the left menu shows how many are waiting. A shift cannot be closed until they have all uploaded.",
      ],
    });
  }

  if (!admin && can("orders_view")) {
    s.push({
      id: "orders",
      title: "Order list and refunds",
      body: [
        "Find any past order here. A refund or void needs a reason and a manager PIN, and it is kept in the history. Nothing is deleted.",
      ],
      to: "/branch/orders",
    });
  }

  if (!admin && can("branch_inventory_view")) {
    s.push({
      id: "stock",
      title: "Stock",
      body: [
        "Item Inventory shows what the branch has. Stock goes down as you sell and goes up when deliveries or transfers arrive.",
      ],
      to: "/branch/inventory",
    });
  }

  if (!admin && can("branch_po_view")) {
    s.push({
      id: "branch-po",
      title: "Ordering stock from HQ",
      body: [
        "Create a purchase order in Purchase Orders. HQ approves it, and it moves through to Delivered and Completed.",
        "When the goods arrive, record what actually came. The invoice follows what you received, and you pay it under Payables, in parts if needed.",
      ],
      to: "/branch/purchase-orders",
    });
  }

  if (admin) {
    s.push({
      id: "hq-overview",
      title: "Your HQ dashboard",
      body: [
        "The Dashboard shows sales across all branches. Use the left menu to manage branches, products, staff and reports.",
      ],
      to: "/admin",
    });
    if (can("branches_view")) {
      s.push({
        id: "hq-branches",
        title: "Branches and staff",
        body: [
          "Add your branches, then add staff. Each person gets a role that decides what they can see and do. Branch staff only ever see their own branch.",
        ],
        to: "/admin/branches",
      });
    }
    if (can("products_view")) {
      s.push({
        id: "hq-products",
        title: "Your menu",
        body: [
          "Add categories, products and add-ons here, and set the prices. Choose which branches sell each product. Branches see changes on their till.",
        ],
        to: "/admin/products",
      });
    }
    if (can("branch_po_admin_view")) {
      s.push({
        id: "hq-po",
        title: "Branch orders and money owed",
        body: [
          "Branch purchase orders arrive under Branch PO. Approve them and move them along.",
          "Once a branch confirms what it received, an invoice is made. Track what each branch owes, and record payments, under Receivables.",
        ],
        to: "/admin/branch-po",
      });
    }
    if (can("reports_admin_view")) {
      s.push({
        id: "hq-reports",
        title: "Reports",
        body: [
          "Sales, stock, profit and loss, and more, for one branch or all of them. Every report downloads as CSV, Excel or PDF.",
        ],
        to: "/admin/reports",
      });
    }
    if (can("logs_view")) {
      s.push({
        id: "hq-logs",
        title: "Everything is recorded",
        body: [
          "Logs shows who did what and when, including prices, stock and money. Orders and payments are never deleted, only voided with a reason.",
        ],
        to: "/admin/logs",
      });
    }
  }

  s.push({
    id: "done",
    title: "You are all set",
    body: [
      "Need this again? Choose Quick tour in the left menu.",
      "Stuck on something? Ask your manager or HQ.",
    ],
  });

  return s;
}

const key = (me) => `cp-tour-done:${me.username}`;

/** Has this person already seen the tour on this device? Treated as seen if storage is blocked. */
export function tourSeen(me) {
  try { return localStorage.getItem(key(me)) === "1"; } catch { return true; }
}
export function markTourSeen(me) {
  try { localStorage.setItem(key(me), "1"); } catch { /* the tour just shows again next time */ }
}
