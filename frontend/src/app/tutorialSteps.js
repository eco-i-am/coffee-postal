// The sign-in tour. It walks through the pages in the left menu: what each page is for, then the buttons on it and what they do.
// A step shows only when the account can open that page, so a cashier never reads about HQ screens and a
// clock-in-only account just sees time in / out. Button names must match the screens as built; keep it short, plain words.
//   step = { id, title, page (menu name), body: [..], buttons: [{ label, does }], to (path), action }

export function stepsFor(me, can) {
  const admin = me.scope === "ADMIN";
  const name = (me.fullName || me.username || "").split(" ")[0];
  const where = admin ? "HQ" : me.branchName || "your branch";
  const s = [];
  const B = (label, does) => ({ label, does });

  s.push({
    id: "welcome",
    title: `Welcome${name ? `, ${name}` : ""}!`,
    body: [
      `This is the Coffee Postal Operating System. You are signed in as ${me.role} at ${where}.`,
      "This tour goes page by page through the left menu: what each page is for, and what its buttons do. Open it again any time from Quick tour at the bottom of the menu.",
    ],
  });

  s.push({
    id: "password",
    title: "Make your password your own",
    page: "Bottom of the left menu",
    body: [
      "If someone gave you a starter password, change it now. Pick at least 8 characters with a letter and a number.",
    ],
    buttons: [
      B("Change password", "Sets a new password for your account."),
      B("Quick tour", "Opens this tour again."),
      B("Sign out", "Logs you out. Do this when you hand over the tablet."),
    ],
    action: { label: "Change password now", kind: "password" },
  });

  // ------------------------------------------------------------------ branch pages
  if (!admin && can("time_clock_view")) {
    s.push({
      id: "clock",
      title: "Time in and time out",
      page: "Time In / Out",
      body: [
        "Punch in when you start and out when you finish, using your 6-digit PIN. A photo is taken with each punch.",
        "No internet? The punch is saved on the tablet and sent when the connection returns. Forgot to time out? Only a manager can correct a punch.",
      ],
      buttons: [
        B("Number keys", "Type your 6-digit PIN."),
        B("Time in / Time out", "Records the punch with your photo."),
        B("Clear", "Wipes the PIN if you mistyped."),
      ],
      to: "/branch/clock",
    });
  }

  if (!admin && can("cash_inout_view")) {
    s.push({
      id: "shift",
      title: "Cash Drawer and your shift",
      page: "Cash Drawer",
      body: [
        "Open your shift before the first sale. Cash sales are counted against the drawer, and at the end you see whether it is over or short.",
      ],
      buttons: [
        B("Open shift", "Starts your shift with the cash you begin with."),
        B("Cash in / Cash out", "Records money put in or taken out of the drawer, with a reason."),
        B("X reading", "A mid-shift sales summary. It does not close anything."),
        B("Close my shift / Close shift", "Count the drawer and end the shift. Not allowed while offline sales are still waiting to upload."),
      ],
      to: "/branch/cash",
    });
  }

  if (!admin && can("pos_view")) {
    s.push({
      id: "pos",
      title: "Ringing up a sale",
      page: "Point of Sale",
      body: [
        "Tap what the customer wants. Pick the size and any add-ons, choose Dine-in, Takeout or Delivery, then take payment.",
      ],
      buttons: [
        B("Product tiles", "Tap to add a drink or food item to the order on the right."),
        B("+ / Clear", "Change the quantity of a line, or empty the order."),
        B("Discount (dropdown)", "Picks a discount for the order. One over its limit asks for a manager PIN."),
        B("Charge", "Opens payment: Cash, GCash, Maya or Card, then prints or shows the receipt."),
      ],
      to: "/pos",
    });
    s.push({
      id: "offline",
      title: "If the internet drops, keep selling",
      page: "Left menu",
      body: [
        "The till keeps working with no internet. Orders wait on the tablet and upload by themselves when the connection is back.",
        "The Offline note in the left menu shows how many are waiting.",
      ],
    });
  }

  if (!admin && can("orders_view")) {
    s.push({
      id: "orders",
      title: "Order list and refunds",
      page: "Order List",
      body: ["Find any past order. Nothing is ever deleted: a refund or void needs a reason and a manager PIN and stays in the history."],
      buttons: [
        B("Reprint receipt", "Prints a copy of the receipt. It is marked as a reprint."),
        B("Refund / Refund this order", "Gives money back for an order, with a reason and a manager PIN."),
      ],
      to: "/branch/orders",
    });
  }

  if (!admin && can("branch_inventory_view")) {
    s.push({
      id: "stock",
      title: "Stock",
      page: "Item Inventory",
      body: ["What the branch has now. Stock goes down as you sell and up when deliveries or transfers arrive."],
      buttons: [
        B("Adjust", "Add or remove stock by hand."),
        B("Count correction", "Set the real counted amount when the system and the shelf disagree."),
        B("Waste / spoiled", "Remove stock that was thrown away, with a reason."),
        B("History", "Every movement of that item, newest first."),
      ],
      to: "/branch/inventory",
    });
  }

  if (!admin && can("branch_po_view")) {
    s.push({
      id: "branch-po",
      title: "Ordering stock from HQ",
      page: "Purchase Orders",
      body: [
        "Create a purchase order for the items you need. HQ approves it, then it moves along to Delivered and Completed.",
        "When goods arrive, use Delivered Items to record what actually came, in parts if needed. The invoice follows what you received.",
      ],
      to: "/branch/purchase-orders",
    });
  }

  if (!admin && can("payables_view")) {
    s.push({
      id: "payables",
      title: "Paying HQ",
      page: "Payables",
      body: ["Invoices from HQ for the stock you received. Open one to record a payment, in parts if needed."],
      to: "/branch/payables",
    });
  }

  if (!admin && can("transfers_view")) {
    s.push({
      id: "branch-transfers",
      title: "Sending stock to another branch",
      page: "Transfers",
      body: ["Move stock between branches without going through HQ. The other branch confirms what it got."],
      buttons: [
        B("Send", "Sends the items you added and takes them off your stock."),
        B("Mark received", "Confirms a transfer that arrived and adds it to your stock."),
      ],
      to: "/branch/transfers",
    });
  }

  if (!admin && can("outsource_view")) {
    s.push({
      id: "outsource",
      title: "Buying locally",
      page: "Outsource",
      body: ["Record things you bought yourself outside the HQ order flow, like ice or milk from a nearby store. Stocked items add to your inventory."],
      to: "/branch/outsource",
    });
  }

  if (!admin && can("branch_expenses_view")) {
    s.push({
      id: "branch-expenses",
      title: "Branch expenses",
      page: "Expenses",
      body: ["What this branch spent. Keep the receipt. A wrong entry is voided, never deleted."],
      buttons: [
        B("+ Record expense", "Opens a form: category, amount, date, how it was paid, receipt number and what it was for."),
        B("Void", "Strikes out a wrong entry with a reason. It stops counting but stays in the list."),
        B("CSV / Excel / PDF", "Downloads the list for the dates shown."),
      ],
      to: "/branch/expenses",
    });
  }

  if (!admin && can("branch_employees_view")) {
    s.push({
      id: "branch-employees",
      title: "Your team and their hours",
      page: "Employees",
      body: ["Staff has everyone who works here and their time clock PIN. Attendance shows who was in and for how long."],
      buttons: [
        B("+ Add employee", "Adds a person and gives them a 6-digit clock PIN."),
        B("Edit / New PIN / Left", "Change their details, set a new PIN, or mark them as no longer working here."),
        B("Fix in / Fix out / Add in / Add out", "Corrects a missed punch. Needs a reason and a manager PIN."),
      ],
      to: "/branch/employees",
    });
  }

  if (!admin && can("branch_reports_view")) {
    s.push({
      id: "branch-reports",
      title: "Branch reports",
      page: "Reports",
      body: ["Sales, products, profit and loss, stock and more for this branch. Pick a date range, then download as CSV, Excel or PDF."],
      to: "/branch/reports",
    });
  }

  if (!admin && can("branch_users_view")) {
    s.push({
      id: "branch-staff",
      title: "Who can log in",
      page: "Staff",
      body: ["Logins for this branch. Each person gets a role that decides what they can see and do."],
      to: "/branch/users",
    });
  }

  // ------------------------------------------------------------------ HQ pages
  if (admin) {
    s.push({
      id: "hq-overview",
      title: "Your HQ dashboard",
      page: "Dashboard",
      body: ["Sales across all branches at a glance. Use the left menu to manage branches, products, stock, staff and reports."],
      to: "/admin",
    });
    if (can("branches_view")) {
      s.push({
        id: "hq-branches",
        title: "Branches",
        page: "Branches",
        body: ["Every branch in the chain. Branch staff only ever see their own branch."],
        buttons: [
          B("+ Add branch", "Opens a form for the branch code, name, address and phone."),
          B("Close / Reopen", "A closed branch can no longer sell. Reopen it any time."),
          B("Search", "Finds a branch by code or name."),
        ],
        to: "/admin/branches",
      });
    }
    if (can("products_view")) {
      s.push({
        id: "hq-products",
        title: "Your menu",
        page: "Products",
        body: ["What every branch sells and the price. Branches see changes on their till."],
        buttons: [
          B("+ Add product", "Opens a form: name, category, price, cost, picture and sizes."),
          B("Menu setup", "Headers, categories and add-ons: how the menu is grouped and what extras can be added."),
          B("Edit", "Change a product's details. Untick Published to keep it as a draft."),
          B("Branches", "Choose the add-ons it offers, and for each branch turn it on or off and optionally set that branch's own price."),
          B("Recipe", "Say how much of each ingredient one sale uses, so stock goes down by itself."),
          B("Switch off / Switch on", "Takes the product off the POS, or puts it back."),
        ],
        to: "/admin/products",
      });
    }
    if (can("discounts_view")) {
      s.push({
        id: "hq-discounts",
        title: "Discounts",
        page: "Discounts",
        body: ["What cashiers can apply at the till. A discount over its limit needs a manager PIN."],
        buttons: [
          B("+ Add discount", "Opens a form: code, receipt name, percent or fixed amount, the PIN limit, and whether it asks for an ID."),
          B("Edit", "Change a discount. Senior / PWD always removes VAT then takes 20% off."),
          B("Switch off / Switch on", "Hides it from the POS, or shows it again."),
        ],
        to: "/admin/discounts",
      });
    }
    if (can("branch_po_admin_view")) {
      s.push({
        id: "hq-po",
        title: "Branch orders",
        page: "Branch PO",
        body: [
          "Purchase orders from branches arrive here. Approve them (for fewer items if needed) or reject them, then move each one along: pay, process, pick-up, in transit, delivered.",
          "Once a branch confirms what it received, an invoice is made.",
        ],
        to: "/admin/branch-po",
      });
    }
    if (can("receivables_view")) {
      s.push({
        id: "hq-receivables",
        title: "Money owed to you",
        page: "Receivables",
        body: ["What each branch owes, and what has been paid. Open an invoice to record a payment."],
        to: "/admin/receivables",
      });
    }
    if (can("items_master_view")) {
      s.push({
        id: "hq-items",
        title: "Ingredients and supplies",
        page: "Items",
        body: ["The list of things branches stock and order. The price is what a branch pays per unit."],
        buttons: [
          B("+ Add item", "Opens a form: name, unit, cost, category and low-stock warning level."),
          B("Categories", "Add or review item categories."),
          B("Edit", "Change cost, unit or category, and add other units, like 1 box = 12 pcs."),
          B("Orderable Yes / No", "Chooses whether branches can order the item from HQ."),
          B("Hide / Show", "Removes the item from lists without deleting it."),
        ],
        to: "/admin/items",
      });
    }
    if (can("reports_admin_view")) {
      s.push({
        id: "hq-reports",
        title: "Reports",
        page: "Reports",
        body: ["Sales, stock, profit and loss and more, for one branch or all. Every report downloads as CSV, Excel or PDF."],
        to: "/admin/reports",
      });
    }
    if (can("admin_employees_view")) {
      s.push({
        id: "hq-employees",
        title: "Employees",
        page: "Employees",
        body: ["Every employee in the chain, and the hours each branch's staff were in. Attendance is read only here."],
        buttons: [
          B("Staff / Attendance", "Switch between the people list and the time records."),
          B("Branch filter", "Show one branch, or all."),
          B("+ Add employee", "Adds a person. Pick a branch to give them a time clock PIN."),
        ],
        to: "/admin/employees",
      });
    }
    if (can("admin_transfers_view")) {
      s.push({
        id: "hq-transfers",
        title: "Transfers",
        page: "Transfers",
        body: ["Stock moving between branches and HQ. Send it, then the receiver confirms."],
        to: "/admin/transfers",
      });
    }
    if (can("accounting_view")) {
      s.push({
        id: "hq-expenses",
        title: "Expenses",
        page: "Expenses",
        body: ["What the chain spent, by branch and category. Pick a date range and a branch."],
        buttons: [
          B("+ Record expense", "Records an expense for head office or for a branch."),
          B("Void", "Strikes out a wrong entry with a reason. Nothing is deleted."),
          B("CSV / Excel / PDF", "Downloads what you see."),
        ],
        to: "/admin/expenses",
      });
    }
    if (can("suppliers_view") || can("supplier_po_view")) {
      s.push({
        id: "hq-suppliers",
        title: "Suppliers",
        page: "Suppliers",
        body: ["Who HQ buys from, and HQ's own purchase orders to them."],
        buttons: [
          B("Add supplier", "Saves a supplier's details."),
          B("New order", "Starts a purchase order to a supplier."),
          B("Record delivery", "Logs what arrived, which adds to HQ stock."),
          B("Record payment", "Logs a payment to the supplier."),
        ],
        to: "/admin/suppliers",
      });
    }
    if (can("users_admin_view")) {
      s.push({
        id: "hq-staff",
        title: "Staff logins",
        page: "Staff",
        body: ["Who can sign in, and to which branch. Each person has a role."],
        to: "/admin/users",
      });
    }
    if (can("roles_view")) {
      s.push({
        id: "hq-roles",
        title: "Roles",
        page: "Roles",
        body: ["A role is a set of permissions: which pages a person can open, and whether they can add, edit or delete."],
        to: "/admin/roles",
      });
    }
    if (can("logs_view")) {
      s.push({
        id: "hq-logs",
        title: "Everything is recorded",
        page: "Logs",
        body: ["Who did what and when, including prices, stock and money. Orders and payments are never deleted, only voided with a reason."],
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
