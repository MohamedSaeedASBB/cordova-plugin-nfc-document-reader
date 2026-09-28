# Security assessment pack

`NFC-Plugin-Security-Assessment.pdf` — the document to send to Information Security.

Ten pages: what the component is, a component-architecture diagram, the full third-party framework
inventory with versions and licences, the processing flow, the network-egress analysis, a data
classification table, cryptography, the document trust model, what each security control does and
does not prove, permissions, and the open items.

## Regenerating it

The PDF is rendered from `assessment.html`, which is the source of truth — edit that, not the PDF:

```
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$PWD/docs/security-assessment/NFC-Plugin-Security-Assessment.pdf" \
  "file://$PWD/docs/security-assessment/assessment.html"
```

Headless Chrome is used because it renders the inline SVG diagrams properly; `textutil` and
`cupsfilter` do not.

## Keeping it honest

The document states a commit hash and a date on its cover, and §5.1 describes a removed
exfiltration path as a finding against earlier builds. **Both must be updated whenever the
plugin changes**, or an assessor will be reading conclusions drawn from code that no longer
matches. The verification-status box at the end says exactly what was and was not checked; keep
it accurate rather than reassuring.

Companion documents, which the PDF cites: `docs/DATA-FLOW-AND-EGRESS.md`,
`docs/TECHNICAL-DOSSIER.md`, `docs/VIDEO-CAPTURE-FEASIBILITY.md`, `src/csca/README.md`.
