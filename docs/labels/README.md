# Labels

Every string the plugin's screens show, in three bundles. 89 keys each.

| | |
|---|---|
| [`en.json`](en.json) | Generated from the plugin's own defaults. **Do not edit** — regenerate it instead. |
| [`ar.json`](ar.json) | Arabic. **Draft — needs review.** |
| [`fr.json`](fr.json) | French. **Draft — needs review.** |

## The Arabic and French are drafts

They were written to give you a complete, structurally correct starting point, not to be shipped
as they stand. **Nobody at the bank has reviewed them**, and identity-verification wording is
read by customers under pressure and by regulators afterwards — the terminology, the register and
the instructions deserve the same review as any other customer-facing copy.

They are deliberately not compiled into the plugin for the same reason. Pass them from the app,
where you control them, and they change without a new plugin release.

## Using one

```javascript
var ar = /* docs/labels/ar.json, loaded or inlined */;
NfcDocumentReader.setLabels(ar);
NfcDocumentReader.setDirection("rtl");
```

Call both again whenever the application's language changes. A partial bundle is fine: keys you
leave out keep their current value, so a half-finished translation shows the rest in English
rather than blank.

## Checking a bundle

```
node tools/labels/check.js docs/labels/ar.json
```

It reports three things that are invisible when reading the file and obvious to a customer:

- **a missing key** — that one label silently stays English
- **a dropped placeholder** — `{format}`, `{index}`, `{seen}`; the screen shows a sentence with a
  hole in it
- **an unknown key** — a typo, which simply never applies

Placeholders are matched by name, not position, so a translation may reorder them. Arabic
frequently needs to.

## Direction is not optional for Arabic

`setDirection("rtl")` is separate from the bundle and has to be set as well. Arabic strings in a
left-to-right layout still read wrong: the close button, the step counter and the action buttons
sit where the reader finishes rather than where they start. Translating without it produces a
screen that reads correctly and behaves backwards.

It is explicit rather than inferred from the characters in the bundle, because which way a
customer's screen should flow is the application's decision, not a guess the plugin should make.

## Adding a key

The canonical list lives in `DEFAULT_LABELS` in `www/NfcDocumentReader.js` — one copy, which both
platforms are handed at call time. The native code holds no defaults at all, so a new key is added
in one place and a key that is missing renders as its own name rather than as English. Silent
English in an Arabic screen is the failure that reaches production; a visible `mrz.title` is the
one that gets fixed on the first run.

After adding one, regenerate `en.json` and re-run the checker over `ar.json` and `fr.json` — it
will tell you exactly which bundles now have a gap.
