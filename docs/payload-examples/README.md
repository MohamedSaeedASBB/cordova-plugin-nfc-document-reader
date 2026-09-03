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
