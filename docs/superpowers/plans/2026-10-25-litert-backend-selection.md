# LiteRT backend selection

User choice: Automatic plus a Settings selector. Existing migration committed as
d986c12. Preserve the published model, bundled tokenizer, one process-owned worker,
compatible index, interests and listening state. Governing sources amended in Notion.

Design: persist Automatic/CPU/GPU/NPU; display requested and effective execution.
Prefer a validated accelerator in Automatic. Validate fixed public probe vectors
against CPU before sharing its embedding identity. Close before switching and fall
back on accelerator initialization, validation or execution failure. Never swallow
cancellation or CPU failure. Retain rejection until preference changes. A missing
matching NPU artifact/runtime is reported rather than treated as supported hardware.
No selection triggers a download. Extra NPU acquisition remains explicit.

Alternatives considered: fixed CPU leaves the requested accelerator work undone;
blind hardware detection can select an unusable backend. Runtime validation with
truthful fallback preserves the existing index while testing the actual driver.

- [ ] Add failing-first runtime selection and preference tests.
- [ ] Implement serialized backend lifecycle, status, persistence and Settings.
- [ ] Verify CPU/GPU and investigate official NPU prerequisites on S25.
- [ ] Run JVM/build/androidTest compilation, targeted Settings/device journeys.
- [ ] Review, record exact evidence and remaining limitations.
