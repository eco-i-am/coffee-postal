/* Cashier screen. Talks to /api/pos/menu and /api/pos/orders.
   The browser only says WHAT was sold; the server prices the order and applies discount and VAT.
   The order id is created once per sale, so a retry after a dropped connection cannot charge twice. */
(function () {
  "use strict";
  var $ = function (s) { return document.querySelector(s); };
  var csrfToken = document.querySelector('meta[name="_csrf"]').content;
  var csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;

  function peso(c) { return (c < 0 ? "-" : "") + "₱" + (Math.abs(c) / 100).toLocaleString("en-PH", { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }

  var DISCOUNTS = [["NONE", "None"], ["SENIOR_PWD", "Senior / PWD"], ["PROMO10", "Promo 10%"]];
  var TYPES = [["DINE_IN", "Dine-in"], ["TAKEOUT", "Takeout"], ["DELIVERY", "Delivery"]];
  var METHODS = [["CASH", "Cash"], ["GCASH", "GCash"], ["MAYA", "Maya"], ["CARD", "Card"]];

  var menu = { categories: [], items: [] };
  var state = { cat: "All", cart: [], otype: "DINE_IN", disc: "NONE", orderId: null };

  /* ---- drawings for the tiles ---- */
  var BROWN = "#6B4333", CREMA = "#C99A6B";
  function icon(kind) {
    var s = 'stroke="#205966" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round"';
    var g = {
      hot: '<ellipse cx="22" cy="43" rx="17" ry="3" fill="#205966" opacity=".16"/><path d="M8 21h28v8c0 8-6 13-14 13S8 37 8 29z" fill="#FFFEF8" ' + s + '/><ellipse cx="22" cy="21" rx="14" ry="4.2" fill="' + BROWN + '" ' + s + '/><path d="M22 24c-5-2-7-3-7-5 0-2 3-2 4-.6 1-1.4 2-1.4 3 0 1-1.4 4-1.4 4 .6 0 2-2 3-4 5z" fill="' + CREMA + '"/><path d="M36 24h2a3.6 3.6 0 010 7.2h-3" fill="none" ' + s + '/><path d="M16 11c-2.4 3 2.4 4 0 7.5M24 8c-2.4 3 2.4 4 0 8" fill="none" ' + s + '/>',
      iced: '<path d="M12 14h24l-3.2 28H15.2z" fill="#FFFEF8"/><path d="M13.2 23h21.6l-2 19H15.2z" fill="' + BROWN + '"/><rect x="16.5" y="25" width="7" height="7" rx="1.5" fill="#F9F6E3" opacity=".85" transform="rotate(-8 20 28)"/><rect x="26" y="31" width="6" height="6" rx="1.5" fill="#F9F6E3" opacity=".85"/><path d="M12 14h24l-3.2 28H15.2z" fill="none" ' + s + '/><path d="M26 14l6-11" fill="none" ' + s + '/>',
      frap: '<path d="M13 22h22l-3.2 20H16.2z" fill="' + CREMA + '"/><path d="M13 22h22l-3.2 20H16.2z" fill="none" ' + s + '/><path d="M10.5 22c0-7.5 6-11 13.5-11s13.5 3.5 13.5 11z" fill="#FFFEF8" ' + s + '/><path d="M15 17.5c5 2.4 13 2.4 18 0" stroke="' + BROWN + '" stroke-width="2.6" fill="none" stroke-linecap="round"/><path d="M24 11c-1.6-3 2-4 0-8" fill="none" ' + s + '/>',
      pastry: '<path d="M5 31c2-13 10-19 19-19s17 6 19 19c-4 4-8 2-12 4-3 2-9 2-14 0-4-2-8 0-12-4z" fill="#FFC74A" ' + s + '/><path d="M16 17l3 16M24 14v20M32 17l-3 16" fill="none" ' + s + '/>',
      cookie: '<circle cx="24" cy="25" r="16" fill="#FFC74A" ' + s + '/><circle cx="18" cy="20" r="2.8" fill="' + BROWN + '"/><circle cx="29" cy="23" r="2.8" fill="' + BROWN + '"/><circle cx="22" cy="31" r="2.8" fill="' + BROWN + '"/><circle cx="32" cy="32" r="2.2" fill="' + BROWN + '"/>',
      sand: '<path d="M5 21l19-9 19 9v4H5z" fill="#FFC74A" ' + s + '/><path d="M5 25h38l-3 8H8z" fill="#F9F6E3" ' + s + '/><path d="M5 33h38v4H5z" fill="#FFA076" ' + s + '/>'
    };
    return '<svg width="56" height="56" viewBox="0 0 48 48" aria-hidden="true">' + (g[kind] || g.hot) + '</svg>';
  }

  /* ---- same discount and VAT rules as the server, for the on-screen total ---- */
  function vatEx(v) { return Math.round(v / 1.12); }
  function totals() {
    var gross = state.cart.reduce(function (s, l) { return s + l.unit * l.qty; }, 0), disc = 0, pay = gross, vatable, vat;
    if (state.disc === "SENIOR_PWD") { var net = vatEx(gross); disc = Math.round(net * 0.2) + (gross - net); pay = gross - disc; vatable = 0; vat = 0; }
    else { if (state.disc === "PROMO10") { disc = Math.round(gross * 0.1); pay = gross - disc; } vatable = vatEx(pay); vat = pay - vatable; }
    return { gross: gross, disc: disc, pay: pay, vatable: vatable, vat: vat };
  }

  function showMsg(m) { var el = $("#posmsg"); el.textContent = m; el.hidden = !m; }
  function api(url, opts) {
    opts = opts || {};
    opts.credentials = "same-origin";
    opts.headers = Object.assign({ "Accept": "application/json" }, opts.headers || {});
    if (opts.method && opts.method !== "GET") opts.headers[csrfHeader] = csrfToken;
    return fetch(url, opts).then(function (r) {
      if (r.ok) return r.json();
      return r.json().catch(function () { return {}; }).then(function (b) {
        var e = new Error(b.message || ("Something went wrong (" + r.status + ")."));
        e.status = r.status; throw e;
      });
    });
  }

  /* ---- menu ---- */
  function drawCats() {
    var cats = ["All"].concat(menu.categories);
    $("#cats").innerHTML = cats.map(function (c) { return '<button class="chip" data-cat="' + esc(c) + '" aria-pressed="' + (c === state.cat) + '">' + esc(c) + '</button>'; }).join("");
  }
  function drawMenu() {
    var items = menu.items.filter(function (m) { return state.cat === "All" || m.category === state.cat; });
    $("#menu").innerHTML = items.length ? items.map(function (m) {
      return '<button class="tile" data-add="' + m.id + '" style="--c:' + esc(m.color) + '"><span class="art">' + icon(m.art) + '</span><b>' + esc(m.name) + '</b><span class="pr"><small>₱</small>' + Math.floor(m.price / 100) + '</span></button>';
    }).join("") : '<div class="empty">No items on the menu yet. Ask HQ to add products.</div>';
  }

  /* ---- cart ---- */
  function drawCart() {
    var T = totals();
    var lines = state.cart.length ? state.cart.map(function (l, i) {
      return '<div class="line"><div><b>' + esc(l.name) + '</b></div><div><b>' + peso(l.unit * l.qty) + '</b></div><small>' + esc(l.opt || "") + '</small>' +
        '<div class="qty"><button data-q="' + i + '|-1" aria-label="Less">&minus;</button>' + l.qty + '<button data-q="' + i + '|1" aria-label="More">+</button></div></div>';
    }).join("") : '<div class="empty">Nothing on this order yet.<br>Tap a drink or snack to add it.</div>';
    $("#cart").innerHTML = '<div class="hd"><h2>New order</h2><button class="btn ghost sm" data-clear="1">Clear</button></div>' +
      '<div class="seg" role="group" aria-label="Order type">' + TYPES.map(function (o) { return '<button class="chip" data-ot="' + o[0] + '" aria-pressed="' + (state.otype === o[0]) + '">' + o[1] + '</button>'; }).join("") + '</div>' +
      '<div>' + lines + '</div>' +
      '<label style="display:flex;gap:8px;align-items:center;justify-content:space-between;font-weight:700">Discount <select id="disc">' + DISCOUNTS.map(function (d) { return '<option value="' + d[0] + '"' + (d[0] === state.disc ? " selected" : "") + '>' + d[1] + '</option>'; }).join("") + '</select></label>' +
      (T.disc ? '<div class="tot"><span>Discount</span><span>&minus;' + peso(T.disc) + '</span></div>' : '') +
      '<div class="tot" style="color:var(--reseda);font-size:.88rem"><span>incl. VAT 12%</span><span>' + peso(T.vat) + '</span></div>' +
      '<div class="tot big"><span>Total</span><span>' + peso(T.pay) + '</span></div>' +
      '<button class="btn brand" style="min-height:58px;font-size:1.1rem" data-pay="1"' + (state.cart.length ? "" : " disabled") + '>Charge ' + peso(T.pay) + '</button>';
  }
  function addLine(m, size, addons) {
    var unit = m.price + (size ? size.price : 0) + addons.reduce(function (s, a) { return s + a.price; }, 0);
    var opt = [size ? size.name : null].concat(addons.map(function (a) { return a.name; })).filter(Boolean).join(", ");
    var key = m.id + "|" + (size ? size.id : "") + "|" + addons.map(function (a) { return a.id; }).sort().join(",");
    var ex = state.cart.filter(function (l) { return l.key === key; })[0];
    if (ex) ex.qty++; else state.cart.push({ key: key, pid: m.id, vid: size ? size.id : null, aids: addons.map(function (a) { return a.id; }), name: m.name, opt: opt, unit: unit, qty: 1 });
    state.orderId = null;
    drawCart();
  }

  /* ---- modals ---- */
  function openModal() { $("#scrim").hidden = false; }
  function closeModal() { $("#scrim").hidden = true; }
  $("#scrim").addEventListener("click", function (e) { if (e.target === this) closeModal(); });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") closeModal(); });

  function openItem(id) {
    var m = menu.items.filter(function (x) { return x.id === id; })[0];
    if (!m) return;
    if (!m.sizes.length && !m.addons.length) { addLine(m, null, []); return; }
    var sel = { size: m.sizes.length ? m.sizes[0] : null, add: [] };
    function body() {
      var unit = m.price + (sel.size ? sel.size.price : 0) + sel.add.reduce(function (s, a) { return s + a.price; }, 0);
      $("#modal").innerHTML = '<h2>' + esc(m.name) + '</h2>' +
        (m.sizes.length ? '<div><h3 style="margin-bottom:8px">Size</h3><div class="opts">' + m.sizes.map(function (s) { return '<button class="chip" data-size="' + s.id + '" aria-pressed="' + (sel.size && sel.size.id === s.id) + '">' + esc(s.name) + (s.price ? " +" + peso(s.price) : "") + '</button>'; }).join("") + '</div></div>' : '') +
        (m.addons.length ? '<div><h3 style="margin-bottom:8px">Add-ons</h3><div class="opts">' + m.addons.map(function (a) { return '<button class="chip" data-addon="' + a.id + '" aria-pressed="' + sel.add.some(function (x) { return x.id === a.id; }) + '">' + esc(a.name) + ' +' + peso(a.price) + '</button>'; }).join("") + '</div></div>' : '') +
        '<div class="opts"><button class="btn ghost" data-close="1">Cancel</button><button class="btn warm" style="flex:1" data-confirm="1">Add to order &middot; ' + peso(unit) + '</button></div>';
      $("#modal").onclick = function (e) {
        var b = e.target.closest("button"); if (!b) return;
        if (b.dataset.size) { sel.size = m.sizes.filter(function (s) { return String(s.id) === b.dataset.size; })[0]; body(); }
        else if (b.dataset.addon) { var a = m.addons.filter(function (x) { return String(x.id) === b.dataset.addon; })[0], k = sel.add.indexOf(a); if (k > -1) sel.add.splice(k, 1); else sel.add.push(a); body(); }
        else if (b.dataset.close) closeModal();
        else if (b.dataset.confirm) { addLine(m, sel.size, sel.add); closeModal(); }
      };
    }
    body(); openModal();
  }

  function uuid() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) { var r = Math.random() * 16 | 0; return (c === "x" ? r : (r & 3 | 8)).toString(16); });
  }

  function openPay() {
    var T = totals(), method = "CASH", tender = T.pay, busy = false, err = "";
    if (!state.orderId) state.orderId = uuid();
    function body() {
      var chg = tender - T.pay, ok = method !== "CASH" || tender >= T.pay;
      var quick = [T.pay, Math.ceil(T.pay / 10000) * 10000, Math.ceil(T.pay / 50000) * 50000, Math.ceil(T.pay / 100000) * 100000].filter(function (v, i, a) { return a.indexOf(v) === i; });
      $("#modal").innerHTML = '<h2>Charge ' + peso(T.pay) + '</h2><div class="opts">' + METHODS.map(function (p) { return '<button class="chip" data-m="' + p[0] + '" aria-pressed="' + (p[0] === method) + '">' + p[1] + '</button>'; }).join("") + '</div>' +
        (method === "CASH" ? '<div><h3 style="margin-bottom:8px">Cash received</h3><div class="opts">' + quick.map(function (v) { return '<button class="chip" data-t="' + v + '" aria-pressed="' + (v === tender) + '">' + peso(v) + '</button>'; }).join("") + '</div><div class="tot big" style="margin-top:12px"><span>Change</span><span>' + peso(Math.max(chg, 0)) + '</span></div></div>'
          : '<p style="margin:0;color:var(--reseda)">' + (method === "CARD" ? "Run the card on the terminal, then enter the approval code." : "Customer scans the QR. Enter the reference number.") + '</p><div class="addr"><label for="ref">Reference no.</label><input id="ref" style="border:0;border-bottom:2px solid var(--midnight);min-height:44px"></div>') +
        '<p class="err" role="alert">' + esc(err) + '</p>' +
        '<div class="opts"><button class="btn ghost" data-close="1">Back</button><button class="btn" style="flex:1" data-done="1"' + (ok && !busy ? "" : " disabled") + '>' + (busy ? "Saving..." : "Complete order") + '</button></div>';
      $("#modal").onclick = function (e) {
        var b = e.target.closest("button"); if (!b) return;
        if (b.dataset.m) { method = b.dataset.m; tender = T.pay; err = ""; body(); }
        else if (b.dataset.t) { tender = +b.dataset.t; body(); }
        else if (b.dataset.close) closeModal();
        else if (b.dataset.done) {
          var refEl = $("#ref"); busy = true; err = ""; body();
          var req = { id: state.orderId, orderType: state.otype, discountType: state.disc, paymentMethod: method,
            tendered: method === "CASH" ? tender : null, reference: refEl ? refEl.value.trim() || null : null,
            lines: state.cart.map(function (l) { return { productId: l.pid, variantId: l.vid, addonIds: l.aids, qty: l.qty }; }) };
          api("/api/pos/orders", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(req) })
            .then(function (res) { receipt(res, method, tender); })
            .catch(function (e2) {
              busy = false;
              err = e2.status ? e2.message : "Could not reach the server. Your order is still here. Try again.";
              body();
            });
        }
      };
    }
    body(); openModal();
  }

  function receipt(res, method, tender) {
    var time = new Date().toLocaleTimeString("en-PH", { hour: "numeric", minute: "2-digit" });
    var typeName = TYPES.filter(function (t) { return t[0] === state.otype; })[0][1];
    var discName = DISCOUNTS.filter(function (d) { return d[0] === state.disc; })[0][1];
    var rows = state.cart.map(function (l) { return '<div class="r"><span>' + l.qty + ' x ' + esc(l.name) + '</span><span>' + peso(l.unit * l.qty) + '</span></div>' + (l.opt ? '<div style="color:#666;padding-left:12px">' + esc(l.opt) + '</div>' : ""); }).join("");
    $("#modal").innerHTML = '<h2>Order #' + res.orderNo + ' paid</h2><div class="receipt"><div class="c"><b>COFFEE POSTAL</b><br>' + time + ' &middot; ' + typeName + '<hr></div>' + rows + '<hr>' +
      '<div class="r"><span>Subtotal</span><span>' + peso(res.subtotal) + '</span></div>' + (res.discount ? '<div class="r"><span>' + discName + '</span><span>-' + peso(res.discount) + '</span></div>' : '') +
      '<div class="r"><b>TOTAL</b><b>' + peso(res.total) + '</b></div><div class="r"><span>' + method + '</span><span>' + peso(method === "CASH" ? tender : res.total) + '</span></div>' +
      (method === "CASH" ? '<div class="r"><span>Change</span><span>' + peso(res.change) + '</span></div>' : '') +
      '<hr><div class="r"><span>VATable</span><span>' + peso(res.vatable) + '</span></div><div class="r"><span>VAT 12%</span><span>' + peso(res.vat) + '</span></div>' +
      '<div class="c"><hr>Thank you! Your affordable freshly brewed coffee destination.<br><i>Not an official receipt.</i></div></div>' +
      '<button class="btn warm" data-close="1">New order</button>';
    $("#modal").onclick = function (e) { if (e.target.closest("[data-close]")) closeModal(); };
    state.cart = []; state.disc = "NONE"; state.orderId = null; drawCart();
  }

  /* ---- events ---- */
  document.addEventListener("click", function (e) {
    var b = e.target.closest("button"); if (!b || b.closest("#modal")) return;
    if (b.dataset.cat) { state.cat = b.dataset.cat; drawCats(); drawMenu(); }
    else if (b.dataset.add) openItem(+b.dataset.add);
    else if (b.dataset.q) { var p = b.dataset.q.split("|"), l = state.cart[+p[0]]; l.qty += +p[1]; if (l.qty < 1) state.cart.splice(+p[0], 1); state.orderId = null; drawCart(); }
    else if (b.dataset.ot) { state.otype = b.dataset.ot; drawCart(); }
    else if (b.dataset.clear) { state.cart = []; state.disc = "NONE"; state.orderId = null; drawCart(); }
    else if (b.dataset.pay) openPay();
  });
  document.addEventListener("change", function (e) { if (e.target.id === "disc") { state.disc = e.target.value; drawCart(); } });

  drawCart();
  api("/api/pos/menu").then(function (m) { menu = m; drawCats(); drawMenu(); })
    .catch(function (e) { showMsg(e.status === 403 ? e.message : "Could not load the menu. Check the connection and refresh."); drawMenu(); });
})();
