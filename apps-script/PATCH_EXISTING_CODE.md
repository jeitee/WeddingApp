# Two small edits to your existing Code.gs

Nothing else in the website's behaviour changes.

## 1. `doPost` — route admin calls (insert right after `const payload = JSON.parse(e.postData.contents);`)

```js
    if (payload.action === "admin_list")     return adminList(payload.key);
    if (payload.action === "admin_set_sent") return adminSetSent(payload);
```

## 2. `getGuest` — record the visit (insert just before the `return jsonResponse({ status: "success", token: token, ...` inside the `for` loop, after the `if (rowToken !== token) { continue; }` check)

```js
    trackVisit(guestSheet, i + 1);
```

`getGuest` is what `index.html` calls on every load (`?action=guest&token=…`), and it runs
before the envelope animation, so a successful call means the invitation page really loaded
in a browser. Link-preview bots (WhatsApp, Messenger) don't run the page's JavaScript, so they
are not counted.
