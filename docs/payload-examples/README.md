# Payload examples

One file per capture type, for anyone writing a parser against this plugin. Load them directly
into a test — they are valid JSON.

| File | Produced by |
|---|---|
| [`captureDocument-id.json`](captureDocument-id.json) | `captureDocument({ documentType: "id" })` — front and back |
| [`captureDocument-passport.json`](captureDocument-passport.json) | `captureDocument({ documentType: "passport" })` — photo page only |
| [`captureProofOfAddress.json`](captureProofOfAddress.json) | `captureProofOfAddress()` — one page, with OCR |
| [`captureDocumentAndLiveness.json`](captureDocumentAndLiveness.json) | `captureDocumentAndLiveness()` — MRZ, both sides, liveness; no chip |
| [`captureAndReadNFC.json`](captureAndReadNFC.json) | `captureAndReadNFC()` — the full chip read plus photographs |

## Every payload starts the same way

Whichever function produced it, the first keys are the same. A backend can map these once and
branch on them, without knowing which call it came from:

```json
{
  "schemaVersion": 1,
  "producedBy": "captureAndReadNFC",
  "captureType": "captureAndReadNFC",
  "capturedAt": "2026-09-30T08:00:00.000Z",
  "completed": true,
  "verification": { "outcome": "review", "checksPerformed": ["chipAccess", "..."], "...": "..." },
  "document": { "source": "chip", "documentNumber": "...", "surname": "...", "...": "..." }
}
```

| Field | Always present | What it is |
|---|---|---|
| `schemaVersion` | yes | `1`. Changes only if the shape does. |
| `producedBy` | yes | **The function that produced this**, spelled as it is called. Branch on this. |
| `captureType` | yes | Legacy. The native layer's own name for the flow — `"document"`, `"proofOfAddress"`, `"documentAndLiveness"` — which matches neither the functions nor itself. Kept because backends already read it; use `producedBy` instead. |
| `capturedAt` | yes | ISO 8601, UTC. |
| `completed` | yes | Whether the flow finished every step. `false` means a step was abandoned — see `cancelledAt`. |
| `verification` | yes | The verdict, same shape on every payload. `outcome` is `"pass"`, `"review"` or `"fail"`. |
| `document` | when known | The holder's identity. Absent when the payload carries none — a bare `captureDocument` photographs a card without reading it. |

### `document`, and why `source` matters

`document` holds the identity from the best source this payload had: the chip where there was one,
the printed MRZ otherwise. **`source` says which, and they are not equally trustworthy.** Chip data
is signed by the issuing state; MRZ data is optical character recognition of printed text, which
can be misread and can be forged without breaking anything. A backend that treats them alike is
treating an OCR guess as a signed assertion.

Fields are named plainly rather than in ICAO's vocabulary: `surname` and `givenNames`, not
`primaryIdentifier` and `secondaryIdentifier`. Anything unavailable is `null`, never absent, so a
mapping never has to test for a missing key.

For an MRZ-sourced identity the name, nationality and gender are parsed from `rawMrzLines`, because
the MRZ scan itself returns only the three fields needed to open a chip.

### What did not move

Nothing. Every field is still where it was — this release only adds. `document` repeats about
twenty short strings that also appear at the root; the **images are never duplicated**, they stay
where they already were. The root-level copies can be removed in a `schemaVersion` 2, once
backends have moved across.

## What is real and what is not

`captureAndReadNFC.json` is **an actual payload from a device**, read from a Bahraini ID card. Only
the holder's data has been replaced — name, document number, dates, personal number — and the
base64 images truncated. Everything else, including the awkward parts, is exactly what the device
produced.

The others are assembled from the same real capture and liveness blocks, with the fields that
differ per capture type set from the code. Treat the shapes as authoritative and the values as
illustrative.

## Read these before writing the parser

**Not every field is present every time.** Code defensively against all of these:

- `sides.back` — absent for a passport
- `documentType` — absent on `proofOfAddress`
- `capture` — absent from `captureAndReadNFC` when the user cancelled the photographs; look for
  `captureCancelled: true` instead. The chip read still succeeded, so the result still arrives on
  the success callback
- `ocr` — only on `captureProofOfAddress`
- `signatureImageBase64`, `textEncoding` — null on documents that do not carry them

**The empty text fields in `captureAndReadNFC.json` are not a bug.** That card has no DG7, DG11 or
DG12 — the chip answered FILE NOT FOUND, which is recorded in `readErrors`. So `fullNameOfHolder`,
`placeOfBirth` and `permanentAddress` are empty and the names come from DG1 instead. An Algerian ID
tested alongside it *did* carry those files, so do not assume either shape across issuers.

**The face match in that file shows the failure case.** `documentPortrait.faceDetected` is false and
`screening.passed` is false, because no face was found in the chip portrait. The `similarity` of
`0.1731` in this file was produced before that was caught: with no face box the matcher compared the
whole chip image against a cropped selfie, which is not a face comparison at all. **The plugin now
returns `{"status": "error", "reason": "NO_FACE_DETECTED"}` in this situation**, and the file is
kept as it was to show what the rest of the payload looks like when a match cannot be made.

A successful comparison looks like this instead:

```json
"match": { "status": "review", "similarity": 0.7474, "reason": null, "onDevice": true }
```

`"review"` is the only successful status — the device measures, the backend applies the threshold.

**`verification` is the block to build logic on.** One `outcome` of `pass`, `review` or `fail`, plus
flat fields and plain-language `issues`. See the main [README](../../README.md#verification--the-block-to-build-logic-on).
