# Contract: after-tax source (version 1)

The dashboard does not calculate taxes. It reads one number from an external bookkeeping tool:
what is available on the business side after estimated taxes. Any tool can be that source by
offering one endpoint that answers as below. The dashboard knows nothing else about the tool.

This file is a public interface (`AGENTS.md`, hard rule 4): change it only backwards-compatibly,
or add a version 2 next to it.

## Request

```
GET <configured URL>
Authorization: Bearer <configured token>      (left out when no token is configured)
Accept: application/json
```

The dashboard calls it at most once per sync (daily, and when the owner presses *Sync now*), with
a 10 second timeout. It sends no body and no query parameters.

## Response

`200 OK`, `Content-Type: application/json`:

```json
{
  "contract_version": 1,
  "as_of": "2026-01-15T06:00:00Z",
  "currency": "EUR",
  "available_cents": 1234500,
  "gross_balance_cents": 2000000,
  "reserved": [
    { "label": "VAT", "amount_cents": 420000 },
    { "label": "Income tax", "amount_cents": 345500 }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `contract_version` | yes | The integer `1`. |
| `as_of` | yes | When the tool calculated the amounts: ISO 8601 with offset or `Z`. Not the time of the request, unless the tool calculates on request. |
| `currency` | yes | `"EUR"`. Anything else is refused. |
| `available_cents` | yes | Integer euro cents available after estimated taxes. May be negative. |
| `gross_balance_cents` | no | Integer cents: the balance the tool started from, before reserving. Shown in the build-up only. |
| `reserved` | no | What was held back, as labels with integer cents of zero or more. Shown in the build-up only. When `gross_balance_cents` is also present, gross minus the sum of `reserved` should equal `available_cents`; if it does not, the dashboard shows `available_cents` and a warning. |

Unknown extra fields are ignored. Amounts are JSON integers, never strings or decimals.

## What the dashboard does with it

- A valid answer replaces the stored value and its `as_of`.
- Anything else (no answer, a status other than 200, invalid JSON, a missing required field, a
  decimal amount, another currency or version) changes nothing: the last good value stays and is
  shown as **stale**, with the reason on the build-up page. The token and the URL never appear in
  logs or error messages.
- A value whose `as_of` is older than the configured limit (default 48 hours) is shown as stale
  even when the request succeeded.

## Trying it without a bookkeeping tool

A static file served by any web server on the same machine is a valid source.

## Clarification of version 1: an `as_of` in the future

*Proposed in issue #18; it clarifies a case version 1 is silent on and changes none of its other rules, so the version stays 1.
The owner decides on it when the pull request is merged.*

A source and the dashboard may be two machines whose clocks differ a little. An `as_of` up to **5 minutes** after the
dashboard's own clock (exactly 5 minutes included) is accepted and counts as zero seconds old. An `as_of` more than 5
minutes ahead is refused like any other invalid answer: the last good value stays and is shown as stale, with the reason
*dated in the future*. A source that stamps its answers in local time without an offset is refused already
(`as_of` must carry an offset or `Z`).

Also applied by the dashboard, without changing what a source must send: the answer is read up to 64 KiB (a larger body is
refused), the exchange as a whole takes at most 10 seconds, a redirect is not followed and counts as a status other than
200, and a duplicate key in the JSON object refuses the answer.
