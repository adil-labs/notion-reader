# Discovery power eligibility and device time estimates

User direction: slower Gemma processing is acceptable if quality improves. Speed
alone does not reject a model. Heavy work should be explicitly requested, wait for
sufficient battery or external power, and expose time based on remaining work.

1. Amend live Product/UI/Integration contracts, regenerate mirrors and inspect.
   Keep existing explicit model acquisition and Resume/Rebuild request controls;
   retain automatic discovery within that requested indexing lifecycle. Add a
   30% unplugged battery threshold; unknown charge waits for power. Preserve the
   foreground/playback/resource gates and mandatory source withdrawal.
2. Add a pure power policy and bounded device inference-rate estimator with
   failing-first tests. Estimate active embedding time from remaining non-reused
   passage work; exclude pauses/query waits. A cold/unknown estimate says so.
3. Wire a process-owned battery listener into the existing single worker gate.
   Plan pending changed Notes without retaining another corpus of vectors; update
   remaining passages and Note coverage, sampling only actual provider calls.
4. Render power waiting and a coarse estimated embedding-time range in Discover
   and Settings, explicitly stating that subject/connection analysis is additional.
   Do not pretend an unknown formation/connection duration is zero or whole-job ETA.
5. Run JVM/build/androidTest compilation, affected accessibility wrapper tests and
   an emulator power-transition probe; restore device battery simulation. Append
   decisions evidence and keep unobserved human/device/model gates open.

No production model/threshold change follows from accepting longer runtime. The
WOS category diagnostics are not independent human app-quality acceptance.

Implementation checkpoint (§91–92): live contracts regenerated; power gating,
remaining non-reused passage planning, bounded device throughput and passive scoped
ETA UI implemented. 954 JVM tests/build checks pass; real battery broadcast test
passes through wrapper. A query-wait race and stale-source estimate were proven
failing first and fixed; focused review confirms the race fix. Whole-job time and
physical/human acceptance are not inferred from the embedding-time estimate.
