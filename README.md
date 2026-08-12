# AI QA Platform

Automatically generates JUnit test cases from a git merge, following this pipeline:

```
Merge to Release Branch
        │
        ▼
     Git Diff                  (git.GitDiffService)
        │
        ▼
Parse Changed Java Files       (parser.JavaParserService)
        │
        ▼
Find Dependencies              (dependency.DependencyService)
        │
        ▼
  Impact Analysis              (impact.ImpactAnalysisService)
        │
        ▼
Send Context to LLM            (ai.PromptBuilder + ai.OpenAIService)
        │
        ▼
Generate Test Cases            (testcase.TestCaseGenerator)
```

## Package layout

```
src/main/java/com/company/aiqa
├── config       OpenAIProperties, PipelineProperties, AppConfig
├── controller   QaController — REST entry point
├── service      QaPipelineService — orchestrates the whole pipeline
├── git          GitDiffService
├── parser       JavaParserService
├── dependency   DependencyService
├── impact       ImpactAnalysisService
├── ai           OpenAIService, PromptBuilder
├── testcase     TestCaseGenerator
├── model        DTOs shared across stages
└── util         FileUtils
```

## Backfilling an existing merge history

If a repo already has a long merge history (hundreds of merges) and you want
test cases generated for all of them, then have the platform only pick up
new merges from that point on — this is what `/generate-tests-from-branch/backfill`
is for.

**1. Run the backfill once per branch:**
```bash
curl -X POST http://localhost:8080/api/v1/generate-tests-from-branch/backfill \
  -H "Content-Type: application/json" \
  -d '{
        "repoUrl": "https://github.com/org/repo.git",
        "branch": "main",
        "accessToken": "ghp_...",
        "testCaseMode": "MANUAL"
      }'
```
This walks **every** merge commit reachable from `branch` (oldest first),
skips any already recorded as processed, and runs the full pipeline against
the rest — recording each one as it succeeds. For a large history (e.g.
hundreds of merges), this call will take a while since each merge is one
(or more) LLM calls; let it run to completion.

**2. Check the response:**
```json
{
  "totalMergesOnBranch": 457,
  "alreadyProcessed": 0,
  "newlyProcessed": 457,
  "failed": 0,
  "caughtUp": true,
  "summary": "Branch 'main': 457 merge commit(s) total, 0 already processed, 457 newly processed, 0 failed. Fully caught up."
}
```
`caughtUp` is only `true` when **every** merge succeeded. If `failed > 0`,
those specific merges were deliberately **not** recorded as processed —
just call the same backfill request again and it'll retry only the
failures (everything already-successful is skipped, so this is always
safe to re-run).

**3. From here on, only new merges get processed.** Once `caughtUp: true`,
both `/generate-tests-from-branch` (single latest merge) and the GitHub/GitLab
webhooks check the same history before doing any work — so a webhook firing
on merge #458 processes just that one merge, not the whole history again.

**4. Audit what's been processed:**
```bash
curl "http://localhost:8080/api/v1/merge-history?repoUrl=https://github.com/org/repo.git&branch=main"
```
Returns every recorded merge (sha, pre-merge sha, when it was processed,
and its summary) — useful for confirming coverage or investigating a
specific merge's result.

History is stored as plain JSON files under
`<aiqa.git.workspace-dir>/<aiqa.git.history-dir>/`, one file per
`repoUrl`+`branch` pair — no database required.

## Running it

```bash
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

## Usage

### Option A — you already have a local clone
```bash
curl -X POST http://localhost:8080/api/v1/generate-tests \
  -H "Content-Type: application/json" \
  -d '{
        "repoPath": "/path/to/local/checkout",
        "baseRef": "origin/main",
        "headRef": "origin/release/1.2"
      }'
```

`repoPath` must be a local clone with both refs fetched. `baseRef`/`headRef`
accept anything `git` can resolve: branch names, tags, or commit SHAs.

### Option B — let the server clone it (private repos included)
No local clone or manual ref lookup needed. Give it a repo URL, a credential,
and just the branch name where the merge landed — it clones/fetches the repo,
automatically finds the **most recent merge commit on that branch**, and
diffs exactly that merge's before/after state:

```bash
curl -X POST http://localhost:8080/api/v1/generate-tests-from-branch \
  -H "Content-Type: application/json" \
  -d '{
        "repoUrl": "https://github.com/rano5604/FinFlow.git",
        "branch": "Release",
        "accessToken": "ghp_your_personal_access_token"
      }'
```

- `accessToken` is optional if the server already has `GITHUB_TOKEN` configured
  (see the GitHub config table below); if supplied, it's used for this call
  only and is never logged or persisted anywhere.
- `branch` accepts either the plain name (`"Release"`) or the fully-qualified
  remote form (`"origin/Release"`) — both are tried automatically.
- Repeated calls reuse the same local clone under `aiqa.github.workspace-dir`,
  fetching fresh commits each time rather than re-cloning from scratch.

Response shape is identical for both endpoints:

```json
{
  "changedFilesProcessed": 3,
  "classesParsed": 4,
  "impactedClassCount": 9,
  "generatedTests": [
    {
      "targetClassName": "OrderService",
      "testFileName": "OrderServiceTest.java",
      "testCode": "package ...",
      "writtenPath": "/abs/path/generated-tests/OrderServiceTest.java"
    }
  ],
  "summary": "Processed 3 changed file(s), parsed 4 class(es), impact radius 9 class(es), generated 2 test file(s)."
}
```

Generated `.java` test files are written under `aiqa.pipeline.output-dir`
(default `generated-tests/`, overridable per-request via `outputDir`), nested
one level deeper under a project-name folder — see "Project-scoped output"
below.

## Automatic triggering on PR approval

Instead of calling `/generate-tests` by hand, register a GitHub webhook so the
pipeline runs automatically the moment a pull request is approved:

1. **Set secrets:**
   ```bash
   export GITHUB_TOKEN=ghp_...             # PAT with read access to the repo (needed since it's private)
   export GITHUB_WEBHOOK_SECRET=some-long-random-string
   ```
2. **Expose your server publicly.** GitHub can't reach `localhost` directly.
   For local testing:
   ```bash
   ngrok http 8080
   ```
3. **Add the webhook** in GitHub: repo → Settings → Webhooks → Add webhook
   - Payload URL: `https://<your-ngrok-or-real-host>/api/v1/webhooks/github`
   - Content type: `application/json`
   - Secret: same value as `GITHUB_WEBHOOK_SECRET`
   - Events: select **"Pull request reviews"** only (not the default "just push")
4. Approve a PR. GitHub sends a `pull_request_review` event with
   `action: "submitted"` and `review.state: "approved"`; `GitHubWebhookController`
   verifies the HMAC signature, and if it's a genuine approval,
   `GitHubWebhookService` clones/fetches the repo into `aiqa.github.workspace-dir`
   and runs the same pipeline as the manual endpoint, diffing the PR's
   `base.sha` against `head.sha`.

Optionally restrict this to specific target branches (e.g. only fire for PRs
merging into `Release`) via `aiqa.github.watched-base-branches` in
`application.yml`.

**Timing nuance:** this fires on *review approval*, which happens before the
actual merge commit exists — so it generates tests for the diff that's about
to land, using the PR's current base/head SHAs. If you'd rather trigger only
after the merge is actually complete, listen for `pull_request` events with
`action == "closed"` and `pull_request.merged == true` instead; the same
`GitHubWebhookService.handleApprovedPullRequest` logic applies, just swap
what triggers it in `GitHubWebhookController`.

## Merge lifecycle: finding and finishing incomplete work

A merge is no longer just "processed / not processed". Every run records
**how complete it was**, so partial and failed generation is discoverable and
gets finished automatically instead of being skipped forever.

| Status | Meaning | On the next backfill |
|---|---|---|
| `SUCCESS` | Every expected category generated | Skipped — nothing to do |
| `PARTIAL` | Some categories generated, some failed | **Resumed — only the missing categories are regenerated** |
| `FAILED` | Nothing usable produced, or the run threw | Retried from scratch |

Each history entry now carries `status`, `attemptCount`, `lastAttemptAt`,
`failureReason`, `expectedCategories`, `generatedCategories`,
`missingCategories`, `testCaseCount` and `coveragePercent`. The same fields
appear on every generation response.

```bash
# See what's incomplete and why
curl "http://localhost:8080/api/v1/merge-history/incomplete?repoUrl=https://github.com/org/repo.git&branch=main"

# Finish it - regenerates ONLY the missing categories
curl -X POST http://localhost:8080/api/v1/generate-tests-from-branch/backfill \
  -H "Content-Type: application/json" \
  -d '{"repoUrl":"https://github.com/org/repo.git","branch":"main","llmKeys":{"groq":"gsk_..."}}'
```

**Completed work is never regenerated.** Resuming a `PARTIAL` merge passes
only its `missingCategories` to the pipeline, and category lists accumulate
across attempts — so a resume that closes the last gap flips the entry to
`SUCCESS` with full coverage, while the categories that already succeeded are
left untouched (no duplicate test cases, no wasted LLM calls).

Failures are now *recorded* rather than left absent from history. That
includes webhook-triggered runs, which previously vanished silently on error
and were only retried if a redelivery happened to arrive.

### Upgrading from the previous version

Existing history files are read as-is — no migration step, no data loss.
Entries written before status tracking default to `SUCCESS`, so upgrading
does **not** re-run history that was already considered done.

The one deliberate exception: entries whose summary records
`WARNING: N categories failed and produced no test cases: [...]` are
recovered as `PARTIAL` with those categories marked missing. Those rows are
precisely the blind spot this feature exists to close — marked "processed"
while genuinely incomplete, and therefore skipped forever. On a real history
of 44 merges, 19 turned out to be in this state. They'll be finished on the
next backfill, regenerating only what's missing.

## Supplying LLM keys per request (`llmKeys`)

Rather than baking credentials into config, pass them in the request body.
**A model is used only if you supply its key — anything you leave out is
treated as inactive and is never called.**

```bash
curl -X POST http://localhost:8080/api/v1/generate-tests \
  -H "Content-Type: application/json" \
  -d '{
        "repoPath": "/path/to/local/checkout",
        "baseRef": "origin/main",
        "headRef": "origin/release/1.2",
        "llmKeys": {
          "gemini": "AIza...",
          "groq": "gsk_..."
        }
      }'
```

That request tries **Gemini first, then Groq** if Gemini fails — and never
touches Mistral, GitHub Models, or Ollama. The server log states exactly
what was activated:

```
AI router (per-request keys): active provider(s) [gemini, groq] - all others treated as inactive and skipped.
```

### Supported providers

Any of these names can be used as a key in `llmKeys`:

| Wire format | Providers |
|---|---|
| OpenAI-compatible | `openai`, `groq`, `mistral`, `deepseek`, `openrouter`, `together`, `fireworks`, `perplexity`, `xai`, `cerebras`, `nvidia`, `cohere`, `github`, `lmstudio`, `vllm` |
| Gemini | `gemini` |
| Anthropic | `anthropic` |
| Ollama | `ollama` — value is the **host URL**, not a key (e.g. `http://localhost:11434`) |

Anything not listed — including a private gateway or a provider added to the
market tomorrow — works via `custom`, with no code change:

```json
"llmKeys": {
  "anthropic": "sk-ant-...",
  "custom": [
    { "name": "internal-gw", "baseUrl": "https://llm.internal/v1",
      "model": "llama-3.3-70b", "apiKey": "..." }
  ]
}
```

Adding a provider to the built-in list is a **one-line addition** to
`LlmProviderCatalog` — no new class, no router change — because most of the
market speaks OpenAI's `/chat/completions` shape. Only genuinely different
formats need an adapter, and there are just three (Gemini, Anthropic, Ollama).

> **AWS Bedrock and Google Vertex AI are not directly supported.** Both
> authenticate with request signing (SigV4) / GCP service-account credentials
> rather than an API-key string, so they can't be driven by a key. Put an
> OpenAI-compatible gateway (LiteLLM, Bedrock Access Gateway) in front and
> register it as a `custom` provider.

### Rotation when a model is exhausted

The first supplied model that answers wins. When one fails, the next supplied
model is tried — and the failed one is **benched**, so subsequent requests
skip it instead of repeatedly making the same dead call:

| Failure | Bench duration |
|---|---|
| Quota / rate limit (HTTP 429, `insufficient_quota`, `RESOURCE_EXHAUSTED`) | 60 min, or the provider's `Retry-After` when supplied |
| Anything else (5xx, network blip, malformed response) | 5 min |

Benches expire on their own, so a model returns to rotation with no restart
and no intervention. A successful call clears any bench immediately. If
*every* model happens to be benched, they're all attempted anyway for one
pass rather than failing outright.

This matters most on a per-category run: without benching, a model that
burned its daily quota would be re-tried once per category, per merge,
forever.

Cooldown state is in-memory and per-instance — a restart clears it (worst
case, one wasted retry), and in a multi-instance deployment each node learns
independently.

**Ordering.** Models run in the order set by `aiqa.router.chain`, filtered to
the ones you supplied — so a deployment's preferred fallback order still
applies. A model you supply that isn't in that chain is appended at the end,
so providing a key is always enough to get it used.

**Failure handling is unchanged:** the first supplied model that answers
wins; if one fails (bad key, rate limit, network error) the next supplied
model is tried. Only when *all* of them fail does the run report an error,
and each model's individual reason is listed.

**Omitting `llmKeys` entirely** falls back to server-side config
(`aiqa.router.*`, fed from environment variables) — which is what the
GitHub/GitLab webhooks use, since they have no request body of their own to
carry keys. If nothing is configured there either, the error tells you
exactly what to add rather than listing five "not configured" lines.

**These are secrets.** They travel in the request body, so serve this
endpoint over HTTPS and keep it on a trusted network. They're held only for
the duration of the request — never logged (only the *names* of activated
models are), never written to generated output, and never stored in the
merge history.

## LLM provider

Choose which provider sends the actual test-generation prompt via
`aiqa.llm.provider` (or the `LLM_PROVIDER` env var): `openai` (default),
`gemini`, or `router`. All three implement the same `ai.LlmClient`
interface, so the rest of the pipeline doesn't care which one is active.
Per-request `llmKeys` (above) works with all three — `router` picks the
chain from the keys you supply, while `openai`/`gemini` use `llmKeys.openai`
/ `llmKeys.gemini` in preference to their configured key.

```bash
# OpenAI (default)
export OPENAI_API_KEY=sk-...

# or Gemini
export LLM_PROVIDER=gemini
export GEMINI_API_KEY=AIza...

# or the multi-provider router (see below)
export LLM_PROVIDER=router
```

**This must be exactly one of `openai` / `gemini` / `router`.** Anything
else means Spring finds no matching `LlmClient` bean and the app fails to
start — no test cases, no error obviously pointing at the cause. This
already happened once: `aiqa.llm.provider` was set to `groq` (a *chain
entry*, not a provider selector) instead of `router`, silently breaking
generation entirely.

**Note on Gemini model names:** Gemini's available models change over time —
both the 1.5 and 2.0 series have been retired as of mid-2026. If
`aiqa.gemini.model` ever 404s, list what your key actually has access to:
```bash
curl "https://generativelanguage.googleapis.com/v1beta/models?key=$GEMINI_API_KEY"
```
and update `aiqa.gemini.model` in `application.yml` to a model that supports
`generateContent`.

### Multi-provider fallback (`provider: router`)

`ai.router.AiRouterService` — ported from the standalone `ai-router` Python
package — tries every provider listed in `aiqa.router.chain`, in order, and
returns the first one that succeeds. A provider is skipped if it isn't
configured (no API key) and skipped-then-retried-next if it throws for any
other reason (network error, rate limit, malformed response), so as long as
one candidate in the chain is up, a merge still gets processed:

```yaml
aiqa:
  router:
    chain: [gemini, groq, mistral, github, ollama]
    gemini-api-key: ${GEMINI_API_KEY:}
    groq-api-key: ${GROQ_API_KEY:}
    mistral-api-key: ${MISTRAL_API_KEY:}
    github-token: ${GITHUB_MODELS_TOKEN:}        # a GitHub personal-access token
    ollama-host: ${OLLAMA_HOST:http://localhost:11434}   # local model, no key needed
```

You don't need every key filled in — an unconfigured provider is simply
skipped. `ollama` needs no key at all: install [Ollama](https://ollama.com),
run `ollama serve`, and it becomes a free last-resort candidate once every
hosted free tier is exhausted for the day.

Reorder or trim `chain` to change priority/cost. Free-tier model IDs change
over time — double-check each `*-model` value against the provider's current
docs before relying on it long-term:
- Gemini: https://ai.google.dev/api/models
- Groq: https://console.groq.com/docs/models
- Mistral: https://docs.mistral.ai/getting-started/models/
- GitHub Models: https://github.com/marketplace/models

**Adding a fourth chain provider:** add a class in `ai.router` implementing
`AiProvider` (or extend `AbstractOpenAiCompatProvider` if it's OpenAI-shaped —
~5 lines, like `GroqProvider`), register it in `AiRouterService.REGISTRY`,
and add its config to `RouterProperties`.

**Adding an entirely separate provider** (a fourth top-level
`aiqa.llm.provider` value, not a router chain entry): implement
`ai.LlmClient`, annotate it `@ConditionalOnProperty(prefix = "aiqa.llm", name = "provider", havingValue = "yourprovider")`,
and add its config properties the same way `GeminiProperties` does — no
changes needed anywhere else in the pipeline.

## Commit log

Every response includes `commitLog` — the actual commits between `baseRef`
(exclusive) and `headRef` (inclusive), newest first. For
`/generate-tests-from-branch`, this is exactly the set of commits the
detected merge brought in:

```json
"commitLog": [
  {
    "sha": "6ae0926e0bcfb0226c9fd634f21a3c026cfbe270",
    "shortSha": "6ae0926",
    "authorName": "rano5604",
    "authorEmail": "rano5604@users.noreply.github.com",
    "commitDate": "2026-07-17T10:04:33Z",
    "message": "Merge pull request #1 from rano5604/cursor/development-environment-setup-39c9"
  },
  {
    "sha": "ef98f075b260dcb321152743edb7d26b49bc1739",
    "shortSha": "ef98f07",
    "authorName": "cursoragent",
    "authorEmail": "cursoragent@users.noreply.github.com",
    "commitDate": "2026-04-02T09:12:01Z",
    "message": "Redesign LOGS tab to match target design"
  }
]
```

If ref resolution fails, `commitLog` comes back as an empty list rather than
failing the whole request — it's supplementary traceability info, not a
required part of the pipeline.

## Test case modes

Set `testCaseMode` on either endpoint (or leave it unset to use the
server default, `aiqa.pipeline.default-test-case-mode`, which is `MANUAL`):

| Mode | What it produces | Who it's for |
|---|---|---|
| `MANUAL` (default) | `manual_test_cases.csv` — functional/business test cases in plain language, generated by the LLM directly from the diff and business logic. No code, no test-framework knowledge needed to execute them. | QA testers validating feature behavior |
| `AUTOMATED` | JUnit/Jest/pytest/etc. source files, matched to each changed file's language | Developers/CI |
| `BOTH` | Both of the above (two separate LLM calls) | Teams that want both artifacts from one run |

**Business test cases are not derived from the automated test code** — they
come from a dedicated prompt (`PromptBuilder.businessTestCaseSystemPrompt`)
that asks the LLM to reason about the *feature* implied by the diff (e.g. a
balance calculation, a validation rule) and write out normal, invalid, and
boundary scenarios a human can follow without touching code.

**Coverage is generated one category at a time, not in one shot.** A single
prompt asking for "positive, negative, boundary, and security cases
together" reliably front-loads a couple of happy-path cases and tapers off —
it's never forced to commit to full coverage of any one dimension. Instead,
`QaPipelineService` makes one dedicated LLM call per category
(`aiqa.pipeline.test-case-categories`, default `POSITIVE, NEGATIVE, BOUNDARY,
SECURITY`), each with tailored guidance:
- **Positive** — the normal/expected path, kept deliberately small (2-5 cases)
- **Negative** — missing/invalid/malformed input, disallowed state transitions, unauthorized calls
- **Boundary** — zero, empty, min/max, off-by-one, oversized input, date/time edges
- **Security** — reasons about the diff's *actual* attack surface (injection points, auth/IDOR, sensitive data exposure, token/credential handling, input validation gaps) rather than padding with generic checks that don't apply; returns an empty array if the diff genuinely has no security-relevant surface

Results are merged into one list with globally unique IDs before being
written out, so the categories are invisible in the output format — just
much better represented in it. Trim the category list in
`application.yml` to cut cost (fewer LLM calls per run) if full coverage on
every category isn't needed for a given repo.

The CSV uses TestRail's standard case-field column names so it auto-maps in
TestRail's import wizard without manual remapping:

| Title | Section | Type | Priority | Preconditions | Steps | Expected Result | References |
|---|---|---|---|---|---|---|---|
| Bank total updates correctly after an expense | Transaction Totals Calculation | Positive | High | User has an existing bank transaction | 1. Add expense of 100 from Bank 2. Open totals summary  Test Data: Expense: 100, Source: Bank | Bank total decreases by 100 | TC-001 |

**Importing into TestRail:**
1. In TestRail, go to your project → **Test Cases** → **Add/Import** → **Import from File**
2. Upload `manual_test_cases.csv`
3. TestRail should auto-detect the columns via the header row; confirm the
   mapping screen shows Title→Title, Section→Section, etc.
4. `Type` and `Priority` are freeform text values (`Positive`/`Negative`/
   `Boundary`/`Edge Case` and `High`/`Medium`/`Low`) — if your TestRail
   project's dropdown lists don't already contain these exact values, either
   enable "allow new values" during import (if your TestRail plan supports
   it) or ask your admin to add them under **Administration → Customizations**
   before importing.
5. `Section` becomes the folder each case is filed under in TestRail — if
   you'd rather have a flat list, you can blank that column out before
   import, or leave it to get automatic feature-based grouping.

Example request:
```json
{
  "repoUrl": "https://github.com/org/repo.git",
  "branch": "main",
  "accessToken": "ghp_...",
  "testCaseMode": "MANUAL"
}
```

The response includes `businessTestCases` (the parsed list) and
`manualTestCasesCsvPath` (where **that run's** CSV was written); `generatedTests`
is only populated when `testCaseMode` is `AUTOMATED` or `BOTH`.

**Output layout:** every run writes into its own subfolder keyed by
`headRef`, e.g. `generated-tests/<project>/8cf0fcf.../manual_test_cases.csv`
- this is what stops a backfill covering many merges (or repeated
single-merge calls) from overwriting each previous run's output, which is
what was happening before. Alongside that, every run also **appends** to
`generated-tests/<project>/all_manual_test_cases.csv` - one accumulating
file with every test case from every run, Test Case IDs prefixed with a
short source SHA to stay globally unique, plus a trailing `Source Ref`
column for traceability. That's the single file to hand to TestRail after a
full backfill, rather than importing dozens of per-merge CSVs one at a time.

### Project-scoped output

`<project>` above is derived automatically from the repo itself
(`GitDiffService.resolveProjectName`) - it reads the working copy's `origin`
remote URL (set by `RepoSyncService` on every clone/fetch, so this works
for `/generate-tests-from-branch`, the backfill endpoint, and the webhooks
with no extra input) and takes its last path segment, e.g.
`https://github.com/org/ai-qa-platform.git` → `ai-qa-platform`. For
`/generate-tests` against a repo with no configured remote, it falls back to
the local folder name.

This exists so pointing this platform at more than one repo doesn't mix
their test cases into one shared master catalog - each project gets its own
`generated-tests/<project>/` tree, entirely independent of every other
project's.

## Language support

This pipeline works across languages, not just Java — it detects each
changed file's language from its extension and routes it accordingly:

| Language | Extensions | Parsing | Test framework used |
|---|---|---|---|
| Java | `.java` | Full AST (JavaParser) — accurate methods, call graph, imports | JUnit 5 + Mockito |
| Kotlin | `.kt` | Regex-based (best-effort) | JUnit 5 + Kotest/MockK |
| Dart | `.dart` | Regex-based (best-effort) | Dart `test` / `flutter_test` + `mocktail` |
| JavaScript | `.js`, `.jsx` | Regex-based (best-effort) | Jest |
| TypeScript | `.ts`, `.tsx` | Regex-based (best-effort) | Jest (ts-jest) |
| Python | `.py` | Regex-based (best-effort) | pytest |
| Go | `.go` | Regex-based (best-effort) | Go's built-in `testing` |
| C# | `.cs` | Regex-based (best-effort) | xUnit |
| Swift | `.swift` | Regex-based (best-effort) | XCTest |
| Ruby | `.rb` | Regex-based (best-effort) | RSpec |

## Configuration file change detection

A merge that only touches configuration — `application.yml`, a
`.properties` file, `.env`, `Dockerfile`, `pom.xml`/`build.gradle`, JSON/XML/
TOML/INI config, Terraform, a SQL migration — used to be silently skipped
("no changed source files found") and produce zero test cases, even though
a flipped feature flag, a changed timeout, or a bumped dependency version is
exactly the kind of change QA wants a regression checklist for.

`model.ConfigType` classifies each changed file by extension/filename (never
by opening it) and pairs it with a short "what to check" hint specific to
that format — e.g. a `pom.xml` change gets guidance about dependency/version
bumps and transitive conflicts, a Dockerfile change gets guidance about base
image and `ENV`/`ENTRYPOINT` changes. `GitDiffService.computeChangedConfigFiles`
finds these alongside (not instead of) the normal source-file diff, and they
get their own dedicated "Configuration" test-case category — same NEW-vs-
UPDATE treatment against the master catalog as POSITIVE/NEGATIVE/BOUNDARY/
SECURITY, just filtered to configuration risk instead of code behavior.

Toggle with `aiqa.pipeline.config-change-detection-enabled` (default `true`).
A merge that changes only config (no source files) still produces a
response — automated test-code generation just doesn't run, since there's no
source to generate tests against, but manual/business test cases do.

**Only Java gets true AST-based parsing** (via the `javaparser` library
already in `pom.xml`), which is what gives it the most accurate method
signatures and dependency graph. Every other language goes through
`GenericSourceParser` — a lightweight regex-based extractor
(`dependency.DependencyService` / `parser.GenericSourceParser`) that
recognizes common `class Foo` / `function bar(...)` shaped declarations well
enough to give the LLM useful structure, but it can't tell a real reference
from a same-named local variable or a mention in a comment. For a mixed-repo
merge (e.g. a Dart app with a Java backend), each file is still routed to
its own correct test framework — the LLM is explicitly told which framework
applies to which file in the same request.

**Adding a new language:** add one line to the `SourceLanguage` enum
(`model/SourceLanguage.java`) with its extensions and test-framework hint —
`GenericSourceParser` picks it up automatically. If you need AST-level
accuracy for a specific language, add a dedicated parser (e.g. via that
language's own AST library or ANTLR) and route it in `SourceParsingService`
the same way Java is routed today.

## API-only projects (no UI)

A manual/business test case normally describes clicking through a UI - but
that makes no sense for a backend/API service that doesn't have one. This
platform detects that case and writes steps as direct API calls instead:

1. **Endpoint extraction.** `JavaParserService` recognizes Spring MVC
   controllers - `@RestController`/`@Controller` classes with
   `@RequestMapping` as a class-level base path, combined with each method's
   `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`/`@PatchMapping`
   (or a generic `@RequestMapping`), plus `@RequestBody` (the payload type),
   `@PathVariable`, and `@RequestParam` on its parameters — see
   `model/ApiEndpointInfo`. For non-Java languages, `GenericSourceParser`
   does a best-effort regex match on common Express (`app.get(...)`,
   `router.post(...)`) and Flask/FastAPI (`@app.route(...)`,
   `@router.get(...)`) route shapes — no request-body shape is inferred
   there, since that needs real parsing to do reliably.

2. **UI-vs-API-only detection.** `project.ProjectShapeAnalyzer` scans the
   whole working copy (not just the diff) for UI signals — `.jsx`/`.tsx`/
   `.vue` files, Android `res/layout/*.xml` or `AndroidManifest.xml`, iOS
   `.storyboard`/`.xib`, a `package.json` depending on react/vue/@angular/
   svelte/next/nuxt, or a Flutter widget (`StatelessWidget`/`StatefulWidget`/
   `MaterialApp`) in a `.dart` file. A project is **API-only** when it has at
   least one detected endpoint AND none of these UI signals — a full-stack
   repo with both a UI and a controller stays in normal UI-wording mode,
   since there IS a screen to test through.

3. **Prompt adjustment.** When API-only, `PromptBuilder` switches the
   business test-case prompt's execution mode: `steps` reference the exact
   HTTP method + path from the `## API endpoints` context section (never an
   invented path), `testData` carries a concrete example JSON payload
   matching the request body type, and `expectedResult` describes the HTTP
   response (status code + relevant fields) instead of a UI outcome.

## API automation from a commit (`/generate-automation`)

Once a commit's manual test cases exist, turn the **API-testable** ones into
runnable REST Assured scripts. The commit hash is the folder name under
`generated-tests/<project>/`:

```bash
curl -X POST http://localhost:8080/api/v1/generate-automation \
  -H "Content-Type: application/json" \
  -d '{
        "repoUrl": "https://github.com/org/repo.git",
        "branch": "main",
        "commitHash": "4df458deab5a8d8d4748696d753b4aa54fdcf304",
        "baseUri": "http://localhost:8080",
        "llmKeys": { "gemini": "AIza..." }
      }'
```

This is a **second pass over existing output** — it does not regenerate manual
cases, touch merge history, or re-analyse the branch. Run it whenever you like,
as many times as you like.

**API only, by design.** Each case is checked against the endpoints actually
detected in that commit; anything describing UI steps or configuration checks
is skipped and counted in `skippedNonApi` rather than becoming a script that
couldn't run:

```json
{
  "projectName": "TestProject",
  "commitHash": "4df458d...",
  "manualCasesFound": 21,
  "apiCasesSelected": 13,
  "skippedNonApi": 8,
  "endpointsDetected": ["POST /api/greet", "GET /api/v1/status"],
  "generatedTests": [ { "testFileName": "GreetingApiTest.java", "writtenPath": "..." } ],
  "outputPath": "generated-tests/TestProject/4df458d..."
}
```

A case qualifies as API-testable when it isn't a `Configuration` case **and**
it either names an HTTP verb or references one of the detected endpoint paths.
The bias is deliberately toward exclusion — a wrongly-included case forces the
model to invent an endpoint, which is exactly what the prompt is built to avoid.

**Generated scripts** land in the same commit folder as the manual cases, in
package `com.company.aiqa.generated`, as **TestNG** classes reading their target
from `System.getProperty("baseUri", "<your baseUri>")` — so you can retarget
them at run time without regenerating. Each `@Test` carries its source Test
Case ID in a comment for traceability. Add `io.rest-assured:rest-assured` and
`org.testng:testng` to the consuming project to compile and run them.

**If `apiCasesSelected` is 0**, the commit genuinely had no HTTP surface — e.g.
a dependency or SDK-version bump produces only `Configuration` cases. The
response says so explicitly rather than silently generating nothing.

This endpoint **only generates**. It sends no HTTP traffic at `baseUri` and
cannot mutate anything. Running is a separate endpoint — see below.

## Running the generated automation (`/execute-automation`)

Runs automation that **already exists** for a commit. No LLM call, no
credential, no clone: the scripts on disk are compiled and executed as they are.

```bash
curl -X POST http://localhost:8080/api/v1/execute-automation \
  -H "Content-Type: application/json" \
  -d '{
        "projectName": "QueueManagement",
        "commitHash": "01b536396747b31a1502ad1b67806235b540482c",
        "baseUri": "http://localhost:8082"
      }'
```

If the commit has no script on disk, that is reported — nothing is generated
here. Generate first with `/generate-automation`.

**Why it's a separate endpoint, not a flag.** Generating costs LLM tokens and
touches nothing outside the output folder. Executing costs nothing, needs no
credential, and fires real POST/PUT/DELETE traffic at a live system. As an
`executeTests` option on the generate call, every generation request was one
mistyped boolean away from mutating whatever `baseUri` pointed at. (That flag
is gone; requests still sending it are accepted and it is ignored.)

### Request and response of every test

Each result carries the HTTP traffic that produced it, so a failure is
triageable without re-running anything by hand:

```json
"results": [{
  "testClassName": "com.company.aiqa.generated.AutomationTest_01b5363",
  "testMethodName": "deliberatelyWrongStatusExpectation",
  "status": "FAILED",
  "failureType": "java.lang.AssertionError",
  "failureMessage": "1 expectation failed.\nExpected status code <418> but was <200>.",
  "durationMillis": 361,
  "exchanges": [{
    "requestMethod": "GET",
    "requestUri": "http://localhost:8082/api/v1/shop-slots?shopId=7",
    "requestHeaders": { "Authorization": "<redacted>", "Accept": "*/*" },
    "requestBody": null,
    "responseStatusCode": 200,
    "responseStatusLine": "HTTP/1.1 200 ",
    "responseHeaders": { "Content-Type": "application/json" },
    "responseBody": "[]",
    "durationMillis": 361
  }]
}]
```

Capture is installed by the **runner**, not by the generated code — a global
REST Assured filter attached from a TestNG listener. Asking the LLM to add a
logging filter to every script would make the evidence only as reliable as the
model's memory of one more contract rule.

- **Credential headers are redacted** (`Authorization`, `Cookie`, `X-API-Key`, …).
  These records go out over the API and land on disk.
- **Bodies are truncated** past `max-captured-body-chars`, with the flag set and
  the full length noted.
- **A call that never got a response is still recorded**, with
  `responseStatusCode: 0` and the transport exception as the body — otherwise
  "Connection refused" never tells you which URI was attempted.
- An empty `exchanges` on an API test means it failed before reaching the wire.

### FAILED vs ERROR

TestNG reports only PASS/FAIL/SKIP; the platform splits FAIL in two:

| Status | Means |
|---|---|
| `FAILED` | An **assertion** failed — the API answered and the answer was wrong. A likely defect. |
| `ERROR` | Any other exception — connection refused, bad URI, a `@BeforeClass` blowing up. The test never got to judge anything. |

Conflating them sends people hunting for a bug when the target simply wasn't
running. A failing setup method is reported as an `ERROR` naming that method,
and its captured traffic comes with it.

### Reports

TestNG's own reports are written to `<commit folder>/test-report/` and **kept**
(the path comes back as `testExecutionSummary.reportPath`):

```
test-report/index.html             # browsable
test-report/emailable-report.html  # single-file summary
test-report/testng-results.xml     # machine-readable
test-report/http-exchanges.json    # the captured traffic
```

Stale reports are cleared before each run — otherwise a subprocess that dies on
startup would leave the previous run's XML in place and report those verdicts as
if they were this run's.

**How it runs:** scripts are compiled in-process with the JDK's own compiler
(so the platform must run on a **JDK, not a JRE**), then executed in an
**isolated subprocess** via `org.testng.TestNG` with `-DbaseUri=…`.
The subprocess matters: a crash, hang, or stray `System.exit()` in generated
code can't take the server down with it. That's process containment, not a
security sandbox. Killed after `execution-timeout-seconds`. A script that fails
to compile is reported in `errors` and skipped — the ones that did compile still run.

> **⚠️ This fires real HTTP requests**, including whatever POST/PUT/DELETE the
> cases need. It will create and modify data at `baseUri`. Use a disposable
> environment, never production.

**To run them yourself instead**, copy the `.java` files into any project with
`io.rest-assured:rest-assured` and `org.testng:testng`, then:

```bash
mvn test -DbaseUri=http://localhost:8080
```

The `System.getProperty("baseUri", …)` contract means you retarget without
regenerating.

## Configuration (`application.yml`)

| Property | Default | Notes |
|---|---|---|
| `aiqa.openai.base-url` | `https://api.openai.com/v1` | Point this at any OpenAI-compatible gateway |
| `aiqa.openai.model` | `gpt-4o-mini` | Chat-completions model name |
| `aiqa.openai.api-key` | `${OPENAI_API_KEY}` | Never hardcode — always via env var |
| `aiqa.pipeline.impact-depth` | `2` | BFS hops over the reverse dependency graph |
| `aiqa.pipeline.max-changed-files` | `50` | Guardrail against huge merges |
| `aiqa.pipeline.output-dir` | `generated-tests` | Where generated test files land, nested under a project-name subfolder — see "Project-scoped output" |
| `aiqa.pipeline.default-test-case-mode` | `MANUAL` | Used when a request doesn't specify `testCaseMode` |
| `aiqa.pipeline.execution-timeout-seconds` | `300` | `/execute-automation`: kills a **hung** TestNG subprocess. The whole suite shares this budget, not each test |
| `aiqa.pipeline.max-captured-body-chars` | `8000` | Per-body cap on captured request/response payloads |
| `aiqa.pipeline.max-captured-exchanges` | `500` | Ceiling on recorded exchanges per run; tests keep running once hit, only capture stops |
| `aiqa.github.token` | `${GITHUB_TOKEN}` | PAT for cloning/fetching private repos |
| `aiqa.github.webhook-secret` | `${GITHUB_WEBHOOK_SECRET}` | Verifies GitHub webhook signatures |
| `aiqa.github.workspace-dir` | `repo-workspace` | Where webhook-triggered clones are kept |
| `aiqa.github.watched-base-branches` | *(all)* | Optional allowlist, e.g. `[Release]` |

## Design notes / known limitations

- **Dependency resolution is name-based, not type-bound.** `DependencyService`
  matches on simple class names found in the AST rather than fully-qualified
  binding, which is fast and has zero extra setup but can over-link two
  unrelated classes that happen to share a name in different packages. For
  large monorepos, wire up `javaparser-symbol-solver-core` with a
  `CombinedTypeSolver` pointed at your classpath for exact resolution.
- **Method-level impact tracing is Java-only, and heuristic rather than a
  real call graph.** Beyond "which classes are impacted," `ImpactResult` also
  names the specific impacted method(s) (`ClassName.methodName`) — see
  `DependencyService.buildMethodLevelEdges`. Since there's no
  classpath-based symbol solver wired up (see the bullet above), a call site
  is only attributed to a changed method when its receiver's type can be
  determined locally: `this.foo()`, a field/parameter/local variable whose
  declared type is known from the same file, or a static-style
  `ClassName.foo()` call. A call reached through a chain, a returned value,
  or a superclass-declared field won't be attributed. Overloads also
  collapse onto one node (matched by name only). Treat `impactedMethods` as
  a high-confidence subset, not an exhaustive list — the class-level
  `impactedClasses` remains the reliable, always-populated signal.
- **LLM contract is JSON-only.** `PromptBuilder` instructs the model to
  return a raw JSON array; `TestCaseGenerator` strips markdown fences
  defensively in case a model wraps it anyway, but if the model returns
  free-form prose the pipeline will fail fast with a clear error rather
  than silently writing garbage test files.
- **This build could not be compiled in the sandboxed environment** used to
  generate it, because that environment's network egress allowlist doesn't
  include Maven Central — only npm/PyPI/crates/GitHub domains. Run
  `mvn -q -DskipTests package` locally (or in CI with normal internet
  access) to fetch dependencies and verify the build.
