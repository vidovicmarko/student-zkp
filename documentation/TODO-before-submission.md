# Documentation — TODO Before Submission

Everything here should be done before exporting to PDF and submitting.

---

## 1. Architecture Diagram (highest priority)

The current diagram in `technical-documentation.md` is ASCII art — it will look bad in a PDF.

**What to do:**
1. Go to **https://draw.io** (free, browser-based, no account needed)
2. Draw the three-party flow: Issuer → Holder → Verifier
3. Show the arrows between them with labels:
   - Issuer → Holder: "OID4VCI credential offer (QR)"
   - Holder → Verifier: "OID4VP presentation (QR + KB-JWT)"
   - Verifier → Issuer: "fetch JWKS + status list (cached)"
4. Inside each box list the key components (e.g. Issuer: Spring Boot, PostgreSQL, Rust BBS+)
5. Export as PNG
6. Replace the ASCII block in `technical-documentation.md` with the image:
   ```markdown
   ![System Architecture](./architecture-diagram.png)
   ```
7. Place the PNG in the `documentation/` folder

---

## 2. Screenshots

No screenshots exist in any document. A jury reading a PDF should be able to see the app.

**What to add and where:**
- Android wallet — credential list screen → add to `technical-documentation.md` Section 3.1
- Android wallet — credential detail / Present screen → add to `technical-documentation.md` Section 3.2
- Verifier web app — green "Credential verified" result → add to `technical-documentation.md` Section 3.3
- Verifier web app — unlinkability demo (two different BBS hashes) → add to `technical-documentation.md` Section 3.3

**How to take them:**
- Android: Android Studio emulator → screenshot button on the side panel, or physical device screenshot
- Verifier: browser screenshot (F12 → device toolbar for a clean look, or just a regular screenshot)

Add them to the `documentation/` folder and reference them in the markdown:
```markdown
![Verifier result](./screenshot-verifier-verified.png)
```

---

## 3. Merge overview.md into technical-documentation.md for the PDF

`technical-documentation.md` currently jumps straight into architecture with no intro. A jury reading just that PDF won't know what the project is.

**What to do:**
- Copy the contents of `overview.md` (Sections 1, 2, 3 — The Problem, The Solution, Competitive Landscape) and paste them at the top of `technical-documentation.md` as a new Section 0 or intro block
- The document map at the bottom of `overview.md` can be skipped

---

## 4. Add disclaimer to the competitive landscape table

The table in `overview.md` makes specific claims about competitors. Add one line above it:

> *Based on publicly available documentation, app store listings, and product websites as of May 2026.*

This protects you if a jury challenges a specific checkmark.

---

## 5. Export to PDF

**Two PDFs to produce for submission:**

| PDF | Source files |
|---|---|
| Tehnička dokumentacija | `overview.md` + `technical-documentation.md` (merged, see point 3) |
| Poslovni model | `business-model.md` |

**Easiest way to export (VS Code):**
1. Install the **"Markdown PDF"** extension in VS Code (search in Extensions panel)
2. Open the `.md` file
3. Right-click anywhere in the file → **"Markdown PDF: Export (pdf)"**
4. PDF appears in the same folder

**Alternative (Pandoc, if installed):**
```powershell
pandoc technical-documentation.md -o technical-documentation.pdf
pandoc business-model.md -o business-model.pdf
```

---

## 6. Do NOT include in the submission PDF

- `setup-guide.md` — developer manual, not for the jury
- `overview.md` — its content should be merged into the technical doc (see point 3), not submitted separately
- `TODO-before-submission.md` — this file

---

## Quick checklist

- [ ] Architecture diagram created and inserted into `technical-documentation.md`
- [ ] Screenshots taken and inserted into `technical-documentation.md`
- [ ] `overview.md` intro merged into top of `technical-documentation.md`
- [ ] Disclaimer added above competitive landscape table
- [ ] `technical-documentation.md` exported to PDF
- [ ] `business-model.md` exported to PDF
