# Antigravity CLI delegation workflow

Observed on 2026-10-26 during the Phase 1 structural-knowledge audit.

Use this with the delegation rules in [AGENTS.md](../AGENTS.md). The primary agent
owns scope, permissions, integration, review and verification.

## Start and observe

Run `agy --help` and `agy models` before choosing flags or a model. Save the exact
prompt, baseline revision, stream and preliminary reports under ignored
`build/agy-tasks/<task>/`. Specify allowed edit paths, commands and report paths.
Separate a read-only audit from implementation so its proposed boundaries can be
reviewed first. Check the remote main revision before starting the audit.

**User rule (2026-10-26): always launch Antigravity with a 40-minute timeout.**
Use `--mode accept-edits --print <prompt> --output-format stream-json
--print-timeout 40m` for every invocation, including audits, implementation,
reviews and resumed conversations. Do not shorten this timeout for bounded tasks;
bound the task's scope in the prompt instead. Any outer process timeout must
allow at least the same forty-minute execution window.

Retain the actual process/session until it exits. An `init`
event proves only startup. Inspect subsequent tool events for substantive work.

## Headless permission recovery

**User rule (2026-10-26): whenever Antigravity encounters a permission denial,
update the global Antigravity user settings before resuming.** On this host the
settings file is `%USERPROFILE%/.gemini/antigravity-cli/settings.json`.
Inspect the denied action and exact target/command, add the narrow corresponding
`permissions.allow` rule there, and preserve unrelated settings. This applies to
file reads/writes and command permissions. Verify fresh execution after resuming
the same conversation. Do not bypass the denial through another command/tool,
disable permission checks, or commit the global settings to the repository.

In the observed run, `accept-edits` still initialized with
`permission_mode: request-review`. Reading an attachment outside the repository
was denied because headless mode could not ask. The final event said `SUCCESS`,
but had an empty response and nonempty `denied_actions`; no audit was produced.
Neither the status word nor process startup establishes completion.

Identify the exact denied action and target from the stream. Resolve authorization
through the user's local Antigravity settings, using narrowly scoped
`permissions.allow` rules such as `read_file(<authorized path>)`. Do not bypass a
denial with a different tool or spelling. Do not use
`--dangerously-skip-permissions`. Preserve unrelated settings and never commit
machine-specific permission files or credentials.

Resume using `--conversation <conversation_id>` and explain which authorization
was resolved and which scope remains unchanged. Inspect fresh tool events to
confirm progress. Check every final result's `denied_actions`, report existence,
actual edits and independent verification before accepting the work.

Command wrappers are part of the permission identity. A later delegate requested
a filtered Gradle command with `cd`, redirection and a `Select-Object` pipe,
although the plain Gradle command was already authorized. That wrapped command
was denied and ended the headless turn. Resolve the exact requested command
before resuming; instruct subsequent work to use the authorized command verbatim
without adding wrappers or flags. Do not treat an allow-rule for one spelling as
authorization for every variant.

## Bound output and review claims

Ask the delegate to put detailed evidence in its report and keep its final response
short. The initial audit repeated a large matrix in the streamed final response,
adding time without adding evidence. A useful implementation prompt sets an
explicit final-response limit and exact report paths.

Review audit prose against source and command logs before using it as a design.
The initial report described an `UP-TO-DATE` Gradle run as fresh test evidence and
overstated identifier normalization and synced-block endpoint behavior. Correct
such claims before promoting preliminary reports into tracked documentation.
Generated designs also need semantic review: this audit initially conflated
database and data-source parents, proposed durable opaque cursors, and mapped
ambiguous not-found responses to confirmed trash. Official API constraints and
repository contracts take precedence over delegated confidence.

For JVM outcomes, aggregate the `tests`, `failures`, `errors` and `skipped`
attributes from every `TEST-*.xml` in the Gradle task's result directory. Asking
the delegate to print every matching XML line produced truncated tool output in
this round. Verify the test task actually executed and preserve each red proof
before the next run overwrites its reports.

The first implementation assignment exceeded its 15-minute print limit. The CLI
printed `print timeout ... with turn in progress; returning partial output`,
exited zero and emitted another `SUCCESS` result without the required report.
Treat this combination as incomplete. Verify the actual launched process exited,
inspect any in-flight build and source state, then resume with a smaller bounded
assignment using the required `--print-timeout 40m`. Do not infer completion from
exit zero, or start overlapping writers.
Prefer separate storage, extraction/API and integration passes over assigning all
three plus documentation and proofs in one timed turn.

An idle root agent can still leave the CLI waiting for background tasks. In this
round, `rg ... | tail -5` spawned the Windows coreutils `tail.cmd`, which remained
running after the report was written. The stream printed `root agent idle;
waiting ... for 1 background task(s)`. Inspect the actual process tree and task
logs before diagnosing an inference stall. The primary identified and stopped
only that hung read-only subprocess; the CLI then completed normally. Prefer
PowerShell `Select-Object -Last` or bounded file-read tools over `tail` on this
Windows host. A report file can appear before the CLI exits.

## Durable evidence

Keep accepted capability matrices, design decisions and reproducible verification
in tracked documentation or tooling. Raw streams and temporary prompts alone are
not repository infrastructure. Do not include private Note content in a delegated
prompt, logs or external evaluation input.
