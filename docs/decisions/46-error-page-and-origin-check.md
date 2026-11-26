# #46 The error page says what went wrong; a change without Sec-Fetch-Site is checked by Origin

Two open findings of the review of #25 (accounts), both in `finances.web`.

## Error page (`templates/error.html`)

- The text depends on the status Spring Boot puts in the error model: **404** "Page not found",
  **403** "Not allowed", **anything else** "Something went wrong". Each has a link back to the
  overview (`/`). Texts are `error.notfound.*`, `error.forbidden.*`, `error.general.*` and
  `error.page.back` in `messages.properties`.
- No detail of the failure is shown: no exception message, path, parameter or amount
  (`server.error.include-message=never` stays; the template reads only `status`). A test forces a
  500 with a message that contains an amount-like text and checks that none of it appears.
- **Existing assertion changed** (the spec changes the behaviour): `AccountsPagesTest.anUnknownAccountShowsAPageWithAWayBackNotRawJson`
  expected the link text "Back to the accounts"; the link now goes to the overview and says
  "Back to the overview". The "Page not found" assertion is unchanged.

## Same-origin filter (`SameOriginFilter`)

For a request that changes something (any method but GET, HEAD, OPTIONS, TRACE):

1. `Sec-Fetch-Site` present: as before. `same-origin` and `none` pass, everything else is 403.
2. `Sec-Fetch-Site` absent, `Origin` present: the origin's **host and port** must equal the request's
   `Host` header (host case-insensitive, a missing port meaning 80 for `http` and 443 for `https`).
   Otherwise 403. `Origin: null` and an `Origin` that is not a URL are refused too.
3. Neither header: let through, as before.

- **Scheme is not compared** (the spec says scheme, host and port; this is the one deviation). The
  application does not trust forwarded headers (`server.forward-headers-strategy` is not set), so behind a
  TLS-terminating reverse proxy such as a private-network `serve` it sees `http` while the browser's
  `Origin` says `https`. Comparing schemes would refuse every genuine form in that setup. Host and port
  are what the browser sent in the `Host` header and, unless the proxy rewrites them, what it put in
  `Origin`. No configuration property was added.
- A proxy that rewrites `Host` to its upstream (`127.0.0.1:8080`) makes every browser `Origin` differ
  from it, and the check would refuse genuine forms. The reference setup keeps the host; this is the
  known cost of not trusting forwarded headers. The owner can see it as "Not allowed" on a form.

### What it protects against, and what it does not

- Protects: a page in the owner's browser (another site, an old tab) posting a form to the dashboard in
  a browser that sends `Origin` but not `Sec-Fetch-Site`.
- Known limit: a request with **neither** header passes. That is command-line use (curl) and very old
  browsers, and it is also what an attacker who is not a browser can send. This is a mitigation for
  forged cross-site browser requests, not authentication and not CSRF tokens.
- A host name with an underscore (`http://my_box:8080`) is not a valid URI host for `java.net.URI`, so a browser that sends `Origin` but no `Sec-Fetch-Site` gets 403 there. It fails safe, is rare, and current browsers send `Sec-Fetch-Site`.
- DNS rebinding (a name that resolves to this machine, with a matching `Host` and `Origin`) is not stopped, before or after this change.
- Not in this issue: a login, CSRF tokens, rate limiting.

Backwards compatible: no property, migration, route or file format changed.
