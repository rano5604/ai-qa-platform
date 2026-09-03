# AI-QA Platform — handoff

Written 2026-08-18, updated through 2026-09-01. Everything here is **uncommitted work
on branch `feat/automation-generation-and-execution`** (last real commit `3661153`).

Delete this file once the work is merged — it exists to carry context into a new
chat, not to live in the repo.

```bash
mvn -Dtest='!AiQaPlatformApplicationTests' test    # 187 unit tests, all passing
```

The Spring Boot app runs on `:8080`. **Anything you change needs a restart
before a run exercises it** — several hours were lost this session to reading a
running server's old behaviour as a new bug.

---

## 1. The through-line

The platform turns a git diff into manual test cases and runnable REST Assured
automation. Everything below traces to one recurring failure: **a run that
reports something confidently, about work it never actually did.** A category
counted as failed when the model correctly had nothing to say. A suite of 30 red
tests that never reached the service. An assertion about a response shape nobody
had seen. A provider "cooling down" when the real problem was a typo in a model
id.

Two working rules came out of it, and they are worth keeping:

1. **When a prompt instruction proves unreliable, enforce it in code.** The
   model follows most rules most of the time, and "most of the time" costs a
   whole run. `AutomationScriptMerger` and `CompileSalvager` exist for this.
2. **A report must say what it does not prove.** A red verdict that stems from a
   broken fixture, a wrong host, or an invented assertion is worse than no
   report: someone spends an afternoon proving a defect isn't there.

---

## 2. Generation — manual test cases

Prompt work lives in `PromptBuilder`. Each rule traces to a defect seen in real
output in `generated-tests/`.

| Rule | Defect it fixes |
| --- | --- |
| Grounding: no auth/login/DB unless visible in the code | A calculator commit produced *"Unauthorized access to calculator"* |
| Prove it from your own category only | Rejection cases leaked into POSITIVE, BOUNDARY and SECURITY |
| POSITIVE = valid input, success outcome, never a rejection | 3 of 4 "Positive" cases asserted rejection |
| Ordinary input validation is **not** security; return empty | SECURITY was 100% reworded duplicates of NEGATIVE |
| A narrowed type is **not** a new validation rule | See §2.1 — the most important single insight |
| Take limits from the code; never invent one | Asserted `signature hasLength(64)` when config said 16 |
| State the unit; byte limits need a multi-byte case | A 99-**byte** limit is ~33 Bangla characters — untested |
| Don't re-test one boundary across every operation | Zero tested against +/- across 3 operations = 6 cases, 1 rule |
| A tester must be able to run it | Cases needing a config change or a forced internal failure |

Build files no longer produce cases at all: `ConfigType.isBuildTooling(path)`
covers build scripts, wrapper/toolchain metadata, version catalogs, **lock
files**, package descriptors, Dockerfiles and CI pipelines. Path-aware on
purpose — `gradle.properties` classifies as PROPERTIES and `package-lock.json`
as JSON, so matching on the enum alone missed the common cases. Gated by
`aiqa.pipeline.build-config-test-cases-enabled` (**default false**).

### 2.1 The narrowing insight

`double price` → `int price` adds **no** validation. Jackson silently coerces:
`19.99` is stored as `19`, `"50"` becomes `50`, `-1` is accepted. Only a value
too large to represent gives 400, and that is a *parse* failure.

So the correct test asserts the **conversion**, not a rejection:

```java
.then().statusCode(200).body("price", equalTo(19));   // 19.99 truncates
```

This passes today, documents real data loss, and starts failing the moment
someone adds validation. 11 of order-service's failures were tests asserting a
`400` the service was never written to return.

### 2.2 Preconditions

Code rarely reaches the rule under test on its first check, and **the first
requirement that fails is what the tester observes**. A case that leaves an
earlier one unmet silently tests that earlier check instead — it keeps passing
while the rule in its title goes unexercised.

The rule used to live only inside the conditional `## Related implementation`
section, so a root commit or a self-contained diff got no guidance at all. It is
now unconditional in the system prompt and phrased structurally, not by domain:
something referenced must exist; something must be in a state (approved, active,
enabled, not expired); an earlier step must have run; a collection must be
non-empty; a toggle must be on. Three limits keep it honest — ground each in the
code, drop the case if a tester can't reach the state, and write a missing
precondition as **one** negative case rather than one per operation.

### 2.3 Source context

The generators saw signatures and class names, never method bodies, so they
could not know that (e.g.) fee creation validates the merchant exists first.

- Manual: `QaPipelineService.collectRelatedImplementation` walks the impact
  graph, pulls collaborator source (skipping files already in the diff), capped
  at 50k chars → `## Related implementation (source)`.
- Automation: `AutomationGenerationService.collectImplementationSource` follows
  controllers into `referencedTypes`, capped at 60k → `## Endpoint
  implementation (source)`.

Both are driven by structure, never by a feature name. A **root commit**
correctly yields 0 collaborators — every file is already in the diff.

---

## 3. Generation — automation scripts

Prompt rules: assert the status and keep the body generic; never assert a
constant you were not shown; count, don't estimate; only automate what a request
can reach; ids are local variables, never instance fields; ids are usually
UUIDs, so declare `String`; cleanup must never assert (a failing `@AfterClass`
is a suite-level ERROR).

Three rules earned their place the hard way:

- **The manual case's `Preconditions` field is the floor, not the limit.** An
  empty field is not permission to skip setup. The endpoint list and
  implementation source are the authority: assume nothing exists on the target,
  every id must come from a create in the same test, every required state must
  be reached by calling the endpoint that sets it.
- **A precondition is not always a create.** When a case needs something
  approved/activated/verified, call the endpoint that moves it there and assert
  2xx on that call too, or the run exercises the state check instead of the rule.
- **No JSON path against a 4xx/5xx — including the envelope's own fields.** See
  §3.2.

### 3.1 Code-level enforcement (prompt-independent)

`AutomationScriptMerger` merges the per-batch scripts into one class and repairs
what the model reliably gets wrong:

| Repair | Why it exists |
| --- | --- |
| Seeds the required import block always | A missing `org.testng.Assert` import silently cost a whole run |
| Merges every `@BeforeClass` body into the one kept setup | Dropping the others left every id field null |
| Repairs oversized float literals (`1.79e308f`) | Would not compile |
| **Guarantees the base URI is honoured** | See §3.3 — the single biggest cause of false failures |
| **Drops body assertions from 4xx/5xx chains** | See §3.2 |

`CompileSalvager` also prunes tests whose fixture never happens - see §3.4, which
is where the 2026-08-18 run's remaining damage came from.

`CompileSalvager`:

- compiles, and on failure maps each error line to its enclosing method, drops
  only those, recompiles (max 5 passes). Java compiles all-or-nothing, so one
  bad token cost every test in the file. *A 29-test file that produced 0 tests
  now yields 28.*
- `fieldsReadButNeverAssigned` reports id fields that tests read and nothing
  assigns; `dropMethodsUsingUnassignedFields` removes those tests outright.
  Those tests can never send a request — they die with "path parameter at index
  0 is null" before the first call.

### 3.2 Generic assertions

mms answers success with `{"success":true,"data":{...}}` and errors with
`{"status":400,"error":"Validation Failed","message":"Input validation
failed","path":...}`. The generator had seen the code that *rejects* a request
but never the shape it rejects *with*, so it asserted `.body("success",
is(false))` on a 400 — four tests failed against a service behaving perfectly.

- **Code**: `dropErrorBodyAssertions` removes body assertions from chains
  asserting 4xx/5xx. Narrow on three counts — only error chains (a 2xx body
  assertion is often the point of the test, see §2.1), only calls inside a
  `.then()` chain (so the request's own `.body(payload)` is untouched), and
  nothing else in the chain changes.
- **Prompt**: an explicit ban on any json path against an error response,
  envelope fields included. For text, take the whole body as a string and search
  it case-insensitively — and only when the case quotes the words *and* the
  implementation source shows the service producing them. Id extraction keeps
  its path (a wrong guess fails loudly on the next line) with a null-tolerant
  `data.id` → `id` fallback.

### 3.3 The base URI

A whole 30-test mms run went to `http://localhost:8080` — the platform's own
app — while the run was configured for `http://169.58.37.242:8007/mms`. Cause:
**not one generated batch emitted a `@BeforeClass`**, so `RestAssured.baseURI`
was never assigned and REST Assured used its built-in default. The runner passed
`-DbaseUri` correctly; no code read it.

`ensureBaseUriIsHonoured` now guarantees it: adds the assignment when there is
none, prepends it to an existing setup (a fixture built against the wrong host
fails before the assignment could help), and rewrites a hardcoded literal to
`System.getProperty("baseUri", <that literal>)`. `withBaseUriHonoured` applies
the same repair to scripts **already on disk** at execution time, so an existing
suite can be pointed at a real target without regenerating.

---

### 3.4 A shared id field is only as good as what assigns it

The 2026-08-18 17:20 mms run - 34 tests, 9 passed - failed in exactly two
shapes, both of them fixtures rather than defects.

**Nothing assigns the field.** `merchantId` and `capabilityId` were declared,
read across the suite, and written nowhere. Where they were a path parameter the
test died with *"path parameter at index 0 is null"* before sending anything: 7
tests, no request, no response, nothing to diagnose. Where they were formatted
into a payload it was worse - the request went out carrying the four characters
`"merchantId": "null"` and mms answered 500, correctly, to a merchant that
cannot exist. All 10 `POST /api/v1/fees` calls in that run were this. They were
written up as an mms defect. See §10.

**Something assigns it, but nothing that runs first.** `feeConfigId` was
assigned by a `private createFeeConfiguration()` that nothing ever called, and by
two unrelated `@Test` methods. TestNG orders nothing, so the five fee-tier tests
reading it still saw null. The old check asked "is this field assigned
anywhere", which answers yes here and keeps every one of them.

So the rule is reachability, not existence: a read is sound when a `@Before*`
hook assigns the field, or the reading method assigns it itself - directly or
through a helper it calls. Assignment is followed through same-class calls, so a
`@BeforeClass` that delegates to `createMerchant()` counts. Anything else is
dropped, to a fixpoint, because dropping the test that assigned a field can
strand the next one. Only `@Test` methods are dropped for unreachable
assignment - a null-guarded `@AfterClass` asserts nothing and costs nothing to
keep, unless the field itself goes, in which case its readers go with it.

**The part that matters most is what the prune must NOT do.** Twelve of those
tests built their own merchant into a *local* `String merchantId` and were
entirely correct. Matching the bare name deleted all twelve alongside the broken
ones - a prune that silently halves a good suite is worse than the bug it fixes.
Reads and assignments are now shadow-aware: a method that declares a local of
that name is writing and reading the local, and `this.merchantId` names the field
regardless. On the real file this is the whole difference between removing 23
methods (7 of them good) and removing the right 21.

### 3.5 The API contract: reading the target's own OpenAPI document

Everything above this point describes a REQUEST. Nothing described a
**response**, and that gap produced the two most expensive assertion bugs in
this repo: `.body("success", is(false))` against an error envelope mms does not
use (§3.2), and reading an id from `id` when it lives at `data.id`, which
returns null and kills the next call in the test.

The service already publishes the answer. `com.company.aiqa.openapi` reads it.

**Finding it.** Guessing `/v3/api-docs` does not work - mms overrides the path
to `/api-docs` and serves everything under a `/mms` context path, so the default
404s. Both facts are in `mms-app/src/main/resources/application.yml`, so
`SwaggerLocator` reads them: `springdoc.api-docs.path`, `server.servlet.context-path`,
`server.port`, `springdoc.api-docs.enabled`, plus springfox's `/v2/api-docs` when
the build file pulls springfox instead. It also picks up any `openapi|swagger.{json,yaml}`
committed to the tree.

A **component** is a deployable, not a Maven module: the unit that owns an
`application.yml`. mms is nine modules and one deployable. A repo with four
Spring Boot apps is four, each with its own document, and they are merged - a
test that creates a merchant in one service and a fee in another needs both.
Profile files are merged into their module rather than counted as separate
components; before that fix, `application-dev.yml` invented a second mms-app
carrying the default docs path that the real one overrides.

Candidate URLs are ordered, and the run's `baseUri` supplies the host: the port
in `application.yml` is what the service binds locally, which behind a proxy or
container mapping is not the one you can reach. For mms the first candidate is
`http://169.58.37.242:8007/mms/api-docs`, which is the live one.

**Reading it.** `OpenApiContractExtractor` resolves `$ref`, merges `allOf`,
takes the first branch of `oneOf`/`anyOf`, and flattens everything to dotted
paths - `data.id`, `data.tiers[].rate`, `data[].capabilityCode`. That is not a
summary of the structure, it IS the REST Assured path, so the model copies it
instead of deriving it. Bounded in three directions at once, because any one
alone leaves a way to turn a legal document into a megabyte of prompt: `$ref`
cycles stop, depth stops at 3, field count stops at 45.

**Fitting it in the prompt.** mms documents 71 operations against 76 schemas.
Rendered in full that is larger than the endpoint list and the implementation
source combined, and the automation prompt is already big enough that Groq
rejects it outright. Two things keep it to ~24k chars:

- **Relevance.** An operation whose path the batch's cases name gets its full
  request and response; everything else gets a one-line signature. The cases do
  cite paths (`POST /api/v1/fees` appears 8 times in the mms CSV), and a
  `{param}` in the contract is matched as a wildcard against the real id in the
  case. Falling back to the resource word catches cases written as prose.
- **Shape de-duplication.** A resource's response is identical across its GET,
  POST and PUT; mms has thirteen resources and about five operations each. The
  second one onwards says `response 200: same body as GET /api/v1/fees/{id}`.
  27 of them in the mms render. A shape is only credited to an operation that
  was actually rendered in full - crediting one that got cut to a signature
  points the model at nothing.

**What it does NOT prove, which is the part that matters.** springdoc documents
only what the code declares, and mms declares only `200`. There is no 4xx schema
in that document at all. So the contract carries a `notes` list, rendered above
the endpoints, saying exactly that - and the prompt rule is phrased to match:
assert body fields for a status **the contract documents**, and where it
documents only 2xx, the existing ban on JSON paths against a 4xx applies in
full. Silence is not permission. Handing the model a contract makes inventing an
error shape *more* tempting, not less, which is why this note exists.

**Optional by construction.** An unreachable document falls back to the
source-derived DTOs, which is what the generator used before. Trading a better
prompt for no prompt would be a bad bargain. The response summary says which
happened, because a run generated blind and a run generated from the target's
own document are different artefacts and should not read the same.

Also worth knowing: the contract describes the **deployed** service, the source
DTOs describe **this commit**. They can disagree, and the prompt says which wins
for which purpose - the contract for what the target will accept, the source for
what the commit added.

`aiqa.openapi.*` controls it: `enabled`, `timeout-millis`, `max-attempts`,
`max-prompt-chars`, `max-depth`, `max-fields-per-schema`. `openApiUrls` on the
request skips discovery for a service that does not follow the conventions.

### 3.6 Required fields the contract itself doesn't declare

The prompt asks the model to use the contract's `required` fields, and most of
the time it does — mms's `POST /api/v1/merchants` needs `userId`, the contract
says so, and the model still dropped it from nine generated tests, all nine
failing identically on the same validation error. Same lesson as everywhere
else in this file: enforce it in code.

`AutomationScriptMerger.enforceContractRequiredFields` runs at merge time, not
render time — it still has the real `ApiContract` object (not just its
rendered prompt text) in hand. For every `.post(...)`/`.put(...)`/`.patch(...)`
call whose literal path matches a contract operation (`{param}` segments
wildcard on either side), it checks the JSON literal for each field the
contract lists as `required` and splices in whatever's missing. Prefers a
per-call dynamic value (`System.currentTimeMillis()`-derived) when a
`.formatted(...)`/`String.format(...)` call is already there to extend a field
this platform had to invent is exactly the kind a service enforces uniqueness
on, and a fixed literal would 409 on a second run, same trap as §6.1. Falls
back to the contract's own documented `example` only when there's no argument
list to extend. Scoped deliberately narrow: only top-level fields, never a
field the model already named (even with a different value), never a body
built by concatenation or a helper method.

**The contract alone wasn't enough.** Fixing `userId` still left `accountNo`
400ing with `"Account No is required"` — mms's live OpenAPI document lists
`CreateMerchantRequest.required` as `["businessType", "userId"]` only.
`accountNo` is enforced at runtime but springdoc never traced an annotation to
it, so the contract is silently incomplete for that one field. Grounding on
the contract *alone* structurally cannot produce a field the contract doesn't
mention — that would be inventing it, which the prompt explicitly forbids and
this repair correctly refuses to do.

Second, independent source: `RequestDtoSourceScanner` reads the request DTO's
own Bean Validation annotations straight from the target's source —
`@NotNull`/`@NotBlank`/`@NotEmpty`, matched by simple name so both
`jakarta.validation` and the older `javax.validation` work without resolving
which is on the classpath. Finds the file by exact simple class name (handles
both classes and records), gives up quietly (empty result, never a thrown
exception) on anything it can't find or parse. `ApiContract.EndpointContract`
carries a new `requestSchemaName` field (the request body's top-level `$ref`
name, e.g. `"CreateMerchantRequest"`) so the scanner knows which class to look
for. **Only ever upgrades required-ness of a field the contract already
lists** — a field with a presence annotation that never made it into the
contract's schema *at all* is never injected; the contract's field list, not
the DTO, is still the authority on what a request can even contain.

Both repairs are entirely generic — nothing in either class names mms, a path,
or a field. `merge()` gained two more optional parameters (`ApiContract`,
target repo `Path`) via new overloads; the original 2-arg signature still
works unchanged and is a no-op for both repairs, same as before either existed.

**What this does NOT fix, and it matters.** Even with source-grounding wired
in, mms's `accountNo` still didn't get injected on a live run — see §10's new
finding. `RequestDtoSourceScanner` was reading `main`'s copy of
`CreateMerchantRequest.java`, and `main` genuinely has no `userId`/`accountNo`
fields at all. The deployed target is running `feature/mms-0.1.0`. Both
repairs are verified correct in isolation (18 tests between them, including one
that reproduces the exact mms shape with a temp-file DTO) — the gap is that the
local checkout and the live target describe two different builds, which no
amount of grounding logic can paper over.

## 4. Execution and reporting

`ExecutionReportWriter` writes `aiqa-report.html` next to TestNG's own output:
every verdict with the exact request and response underneath it. Self-contained
by design — inline CSS and inline script, no external assets, because these get
emailed and opened off shares.

### 4.1 RunDiagnosis — what the run does *not* prove

Rendered as a banner **above** the per-test detail, and appended to the API
summary. All findings derive from captured exchanges, never guessed:

1. **Every request 404 across several paths** → the base URI does not serve this
   API; nothing in the report reflects the app under test.
2. **Requests went to a different host than the run was configured for** → the
   script is setting its own base URI, or none at all.
3. **Most failing tests stopped on their first call, and that call was a
   create/update** → they died building fixtures. A GET/DELETE that 404s is
   excluded: that is how an "operate on an absent id" case is written.
4. **A null id reached the wire** (`DELETE /api/v1/fees/null-fee`) → the create
   that should have produced it never did.
5. **A `@BeforeClass` failed, so TestNG skipped the whole class** → reported
   first, because nothing else in the run is evidence of anything. Names the
   call the setup died on and how many earlier ones succeeded. A setup failure
   is explicitly NOT counted as a failing test: doing so produced "1 of 1
   failing test(s) stopped on their very first call" out of a run whose only
   event was the setup.
6. **A payload carried the text `"null"`** (`{"merchantId": "null"}`) → an unset
   id formatted into a text block. Whatever status came back is not evidence of
   anything. Only the *quoted* form fires: a bare JSON `null` is the entire point
   of a "required field missing" case and must never be flagged.

### 4.1.1 Naming a cause the report itself disproves

The 2026-08-19 12:31 mms run is worth keeping as the example. A
`@BeforeClass` created a merchant (201) and then tried to approve it (422), so
TestNG skipped all 34 tests. What the report said was:

> 34 test(s) made no HTTP calls at all. They failed before reaching the network -
> usually a setup step, a wrong base URI, or the target service not running.

Every word true of the 34, and the first thing the reader asked was *how can a
setup step fail when I can see two requests reaching the server?* Fair question:
two of the three causes offered were contradicted by exchanges printed two
screens up in the same document. Offering a list of possible causes when the
evidence to pick one is already in hand is worse than saying nothing - it sends
someone to check a base URI that was never wrong.

Three changes, all of them about not overstating:

- The banner now distinguishes the two cases. With a dead setup it says the
  tests were skipped by it and that **the target is demonstrably reachable,
  because the setup reached it**.
- `RunDiagnosis` gained the finding above, first in the list.
- A skipped test no longer wears the setup's assertion message. TestNG copies
  the configuration exception onto every test it skips, so the report rendered
  34 identical "expected 200 or 201 but was 422" blocks and each test read as
  though it had asserted that itself. One failure printed 34 times is not more
  information.

`TestExecutionResult.setupFailureMessage` / `isSetupFailure` are the single
place the config-vs-test distinction is written and read; it used to be folded
into message text at one end and re-derived by eye at the other.

### 4.2 The Send button, and editing the request

Every captured exchange gets a **Send again** button that re-issues that exact
request and shows status, timing and body inline, plus an **Edit request**
toggle that opens method, URL, headers and body for editing first.

Editing is the point of the panel, not a decoration on it. The question after
reading a failure is never only "does it still fail" - it is "what if this one
field were different": a real merchant id instead of the one the fixture failed
to create, a corrected enum value, one field removed to find which one the 400
is actually about. Every iteration of that used to mean rebuilding the request
in Postman from a payload already on the screen.

Four things about it are deliberate:

- **An edited result says so, loudly.** The heading becomes *"Result of your
  edited request"* and a banner names the method and URL with "this is not the
  request the test sent, so it neither confirms nor clears the verdict above".
  A green 200 from a payload you fixed by hand is not evidence the red test
  passes, and a reader scrolling past a badge will assume it is. Edited is
  decided by comparing against the captured request with header names sorted -
  otherwise merely opening the editor would flag it.
- **The fields are filled by script, not rendered into the HTML.** Putting a
  payload into a `value=""` attribute or between `<textarea>` tags means
  escaping it correctly in a second context, and a captured body is exactly
  where that goes wrong. See below - it already did.
- **Forbidden headers are dropped before the editor sees them.** The browser
  sets `Host`, `Content-Length`, `Origin` and friends itself and rejects any
  attempt to override them, failing the whole request rather than ignoring the
  header. Filtering on the way in means the editor shows exactly what will be
  sent, with nothing in it that silently will not.
- **No new server-side power.** `POST /api/v1/replay` always accepted an
  arbitrary `{method, uri, headers, body}` and never checked it against a
  captured exchange. The editor surfaces what the endpoint already allowed; the
  token remains the only thing standing between it and an open proxy.

A report opened from disk has origin `null`, which no CORS allow-list can name,
so the browser blocks a direct call. The platform therefore makes the call:
`POST /api/v1/replay` (`replay` package) re-issues it server-side and returns
status/headers/body/timing. A refused connection comes back as a *result* with
`error` set, not a thrown 500.

The page tries the proxy, falls back to a direct browser call if the platform is
unreachable, and prints curl only when neither can. A proxy that *ran* the call
and got a failure reports that failure — no fallback, no CORS story.

**A `</script>` in a captured body used to break the page.** The request is
handed back to the script as JSON inside `<script type="application/json">`, and
inside a script element `</script` closes the tag whatever its type - so a
payload containing one truncated the JSON, `JSON.parse` threw, that panel's
button never bound, and the rest of the payload landed in the page as markup.
The escape was there and did nothing:

```java
.replace("<", "\u003c")   // compiles to replace("<", "<")
```

A single backslash is consumed by the **compiler's own unicode preprocessing**,
before the string literal exists, so the argument is a plain `<`. It compiled,
it read exactly like the fix, and it was a no-op. `"\\u003c"` is correct.
`ExecutionReportWriterTest` pins it, and that test does fail against the old
form - checked, not assumed.

**The token is what keeps this from being an open proxy.** The endpoint must
answer any origin, so without a secret any web page in the same browser could
drive a developer's local platform into calling hosts only that machine can
reach. `ReplayService` generates one per process; reports embed it; anything
else gets a bare 403. Not persisted — reports from before a restart fall back to
the direct call. `aiqa.report.replay.enabled=false` removes the endpoint.

---

### 4.3 Downloading the execution report itself

`execute-automation` writes `aiqa-report.html` to the SERVER's disk and, until
now, only ever named that local path in the response summary - no use to a
caller on another machine, the exact problem `GET /api/v1/test-cases/download`
already solved for the manual-case CSV. Same shape, second file type:

- `ExecutionReportDownloadService` mirrors `TestCaseDownloadService` -
  resolution delegated to `GeneratedRunLocator` so it looks in exactly the
  folder execution wrote to (`<runOutputDir>/test-report/aiqa-report.html`),
  the same path-traversal guard on `projectName` (normalize, then check the
  resolved path still starts under the output dir - `resolveProjectName`'s
  fallback regex keeps `.` and `-`, so a bare `..` survives it otherwise).
- `AutomationExecutionService.REPORT_DIR_NAME` went from `private` to `public`
  so both classes read the same constant instead of a second hardcoded
  `"test-report"` that could drift from it - the same reasoning
  `GeneratedRunLocator`'s own javadoc gives for sharing folder arithmetic
  between generation and execution.
- Unlike the CSV endpoint, `commitHash` is **required** - a report is
  intrinsically per-run, there is no project-wide rollup to fall back to.
- `ExecuteAutomationResponse` gained `executionReportDownloadUrl`, built the
  same way `manualTestCasesDownloadUrl` is - so a caller copies a ready-made
  relative URL instead of assembling `/api/v1/execution-report/download` by
  hand.

`GET /api/v1/execution-report/download?projectName=mms&commitHash=<sha>` was
run live against the real mms report already on disk - 200, correct
`Content-Disposition` filename, and the downloaded bytes diffed byte-identical
against the source file. 400 on a missing `commitHash`, 404 naming
`execute-automation` when none has run yet, 400 `"Invalid projectName."` on a
`../../etc` traversal attempt - all four checked against a live instance, not
just the unit tests.

## 5. Providers and credentials

Chain: `[gemini, cerebras, groq, mistral, github, ollama]`.

| Provider | State (verified live 2026-08-18) |
| --- | --- |
| gemini | **works**; `gemini-3.6-flash` available. Free tier limits per minute |
| cerebras | key authenticates; catalog default corrected to `llama3.3-70b` (no hyphen after "llama") — **confirm against your own account** |
| groq | key valid, but `openai/gpt-oss-120b` on the on-demand tier **413s** on the automation prompt even with context trimmed |
| mistral | **402** — needs a paid subscription |

### 5.1 The silent fallback to a provider you have no key for

On 2026-08-20 an mms backfill failed 16 times with *"No OpenAI API key
configured"* while working Gemini, Groq and Mistral keys sat in the
environment. The chain was never consulted, because `AiRouterService` did not
exist. Three separate things had to be true, and each is now closed:

- **`OpenAIService` carried `matchIfMissing = true`.** So an unresolvable
  `aiqa.llm.provider` selected OpenAI rather than failing. The server had been
  started from the IDE with **no `target/classes/application.yml` on the
  classpath at all** - the classpath root was `target/classes`, which held only
  `com/`. The flag is gone; the property now selects a bean or the application
  does not start.
- **Nothing in the logs said which client was wired.** `PipelineProperties`
  hardcodes the same output dir, batch size and category list the yml sets, and
  8080 is Spring's own default, so a run with *zero* configuration loaded
  produced logs identical to a healthy one right up to the first LLM call. The
  fault was found by reading the running process's classpath, not its output.
  `LlmClientStartupReport` now logs the implementation, the property value and
  whether credentials exist, on `ApplicationReadyEvent` - never a key or any
  part of one.
- **`hasServerSideCredentials()` defaults to `true`** on the interface, on the
  reasoning that a single-provider bean built from server config must have
  credentials. It is built from `aiqa.llm.provider` alone; the key is a separate
  property and routinely empty. Answering true defeated
  `QaPipelineService.requireLlmCredentials`, whose entire job is refusing a
  keyless run before a prompt is built - so one clear refusal became one
  identical error per category per batch. `OpenAIService` and `GeminiService`
  now check the key.

**The ordering is the part that nearly went wrong.** The check first lived in
`LlmProperties.@PostConstruct`. Startup did fail - but Spring got there first,
through `LlmClientStartupReport`'s own constructor, reporting *"No qualifying
bean of type 'LlmClient' available"*: true, generic, and silent about whether
the property was mistyped or the whole file was missing. A check that fires
after the generic failure adds nothing. It is now a `BeanFactoryPostProcessor`
(`AppConfig.llmProviderValidator`), which runs before any regular bean, and
both messages were verified live by booting the real context with
`-Dspring.config.name=absent-config` and with `-Daiqa.llm.provider=groq`.

Note what this does **not** fix: `target/classes/application.yml` is build
output. `mvn clean` deletes it and something removed it once already. The
guarantee is only that a process in that state refuses to start and says why.

Three routing fixes:

- **The chain accepts any catalog provider now.** `REGISTRY` held only the five
  with dedicated properties, so naming cerebras/deepseek/openrouter logged a
  warning and dropped it. Server-side keys for the rest go in
  `aiqa.router.keys.<name>` (e.g. `keys.cerebras: ${CEREBRAS_API_KEY:}`).
- **Configuration failures are not benched.** A wrong model id or rejected key
  fails identically forever; benching it replaced *"Model does not exist or you
  do not have access to it"* with "cooling down until…", which reads like
  something that clears on its own. Same treatment `isPromptTooLarge` already
  had.
- **Cooldowns match the window the provider meant.** Every 429 used to get 60
  minutes, but Gemini limits per minute and says so (`"retryDelay": "31s"`) —
  one 31-second wait removed the only working provider for an hour. Order now:
  `Retry-After` header → a delay stated in the body (Gemini's `retryDelay`,
  Groq's `try again in 7.5s`) → 2 minutes for a plain rate limit → 60 minutes
  **only** when the text says the quota is gone for the day.

Splitting a batch does **not** shrink an automation prompt — the system prompt,
endpoint list and payload schemas are fixed cost, so a batch of one is nearly as
large as a batch of ten, and all 34 cases were rejected identically.
`shrinkImplementation` now halves the implementation source on a too-large
rejection (line boundaries, dropped below 4k), sharing the reduced size across
batches via an `AtomicReference`.

Keys live in `GEMINI_API_KEY` / `GROQ_API_KEY` / `MISTRAL_API_KEY` /
`CEREBRAS_API_KEY`, and `application.yml` maps them — **no `/llm-keys` call is
needed**. A wrong value posted to `/llm-keys` overrides the working env key and
produces a confusing `API_KEY_INVALID`; `DELETE /api/v1/llm-keys` clears it.
Note that `POST /llm-keys` **replaces** the whole set rather than merging.

To check a key without spending tokens, list models:

```bash
curl -H "x-goog-api-key: $GEMINI_API_KEY" https://generativelanguage.googleapis.com/v1beta/models
curl -H "Authorization: Bearer $GROQ_API_KEY" https://api.groq.com/openai/v1/models
curl -H "Authorization: Bearer $CEREBRAS_API_KEY" https://api.cerebras.ai/v1/models
```

**Security:** API keys for gemini/groq/mistral and a GitHub `ghp_` token were
visible in screenshots during these sessions. Rotate all four; the GitHub token
matters most — it carries repo write access.

### 5.2 OpenRouter: one gateway instead of many, free-only, with its own quirks

`LlmProviderCatalog` already had an `openrouter` entry, but the generic
`CatalogOpenAiProvider` path builds a fixed `{model, temperature, max_tokens,
messages}` body — no room for OpenRouter's own `models` array (client-side
model-level fallback + priority) or `provider.order`/`allow_fallbacks`
(provider-level failover). `AbstractOpenAiCompatProvider` gained a protected
`extraBodyFields()` hook (empty by default, unused by every other catalog
provider), and a new `OpenRouterProvider` overrides it to add both. Wired into
both of `AiRouterService`'s provider-construction paths (server-configured
chain and per-request `llmKeys`) with a name check ahead of the generic
switch — `openrouter` needs the richer provider, everything else still gets
the plain one.

**Free-only, enforced in code.** This platform is meant to call OpenRouter's
zero-cost catalog only. A model id is trusted as free only by naming
convention — a `:free` suffix, or OpenRouter's own self-maintained
`openrouter/free` alias, which always resolves to whatever is currently free
and is the most churn-resistant choice (verified live against
`GET /api/v1/models` filtered for `pricing.prompt == pricing.completion ==
"0"` — the ids guessed from training data on the first pass didn't even exist
in the current catalog, same staleness trap as Cerebras/Groq elsewhere in this
file). A paid-looking primary model **fails startup outright**; a paid
fallback entry is dropped with a warning, not fatal.

**The `models` array has an undocumented cap of 3.** A 5-entry fallback list
answered `HTTP 400 "'models' array must have 3 items or fewer"` on *every*
call — found live, not documented anywhere reachable in advance.
`OpenRouterProvider` now truncates defensively (`MAX_FALLBACK_MODELS = 3`,
logs a warning) on top of trimming `application.yml`'s list itself, so a
future config edit degrades instead of failing every request again.

**Two ways to supply the key, with materially different chain behaviour:**
- `aiqa.router.keys.openrouter` / `OPENROUTER_API_KEY` env var — openrouter
  becomes one link in the full `aiqa.router.chain`; falls through to
  Gemini/Cerebras/Groq/etc. on failure. Needs a restart.
- `POST /api/v1/llm-keys` with `{"openrouter": "..."}` — per `LlmKeys`'
  activation rule, this makes openrouter the **only** active provider for
  every subsequent call, bypassing the rest of the chain entirely (not just
  deprioritizing it), and **replaces** whatever was configured before. No
  restart needed. This is what's actually configured right now.

`OpenRouterProviderTest` (6 tests) pins the free-only guard, the 3-item cap,
and that `provider.order`/`allow_fallbacks` reach the request body correctly.
Unverified: an actual live call through a real OpenRouter account — free-tier
JSON-output reliability in particular (see §8's new item on this).

---

## 6. Other platform changes

- **404 vs 400.** `IllegalArgumentException` mapped to 404 controller-wide, so
  "supply repoPath or repoUrl" answered Not Found. New `error/NotFoundException`
  → 404 (nothing generated for this commit, no script, no checkout);
  `IllegalArgumentException` → 400.
- **Empty category ≠ failure.** `LlmJsonArray` is the single parser for both
  generators and separates "the model answered, there is nothing to generate"
  from "the call failed". Blank or short prose ("No security test cases apply")
  → 0 cases, category covered. Anything containing `[` or `{` is content: a
  truncated array still throws. `QaPipelineService` tracks `emptyCategories` and
  says so in the summary.
- **JavaParser language level.** `StaticJavaParser` keeps its configuration in a
  **ThreadLocal**, so the static initializer configured exactly one thread;
  every Tomcat worker parsed at Java 11 and skipped files using switch
  expressions. New `parser/JavaSources` owns a per-thread parser per language
  level; all call sites go through it.
- **Failure messages.** `ProviderLogs.compactFailure` flattens
  `AllProvidersFailedException` (whose first line is the constant header "All
  providers failed:" — reporting "the first line" told you nothing 34 times).
  Shared by the pipeline and automation generation.
- **A commit that changes nothing still gets a folder.** `run` returned early
  on an empty diff before it had computed an output path, so the commit
  produced nothing on disk at all. The sequence numbers under
  `generated-tests/<project>/` then had holes - QueueManagement was missing 4,
  5, 19 and 20 - and a hole reads exactly like a commit that failed or was
  never processed, which is a question someone has to go and answer from the
  merge history. The output-path block now sits above that return, and the
  folder is created holding `no-changes.txt`, whose entire content is the same
  sentence the API response carries. `writeNoChangesNote` never throws: this
  path has already succeeded with nothing to record, and turning it into a
  failure would have every later backfill retry a merge that is complete.
- **`.gitignore` added.** `target/` (106 files) and the `repo-workspace/` clones
  are out of the index — the clones were tracked as **gitlinks with no
  `.gitmodules`**, so their SHA changed on every fetch. `repo-workspace/merge-history/`
  stays tracked; it is the platform's own state. The removals are staged;
  `git reset` undoes them.
- **API surface**: `POST/GET/DELETE /api/v1/llm-keys` (in-memory only, never
  logged or returned), `GET /api/v1/test-cases/download`,
  `GET /api/v1/execution-report/download` (§4.3), `POST /api/v1/replay`,
  and `repoPath` is optional on `/generate-tests` — pass `repoUrl` and it clones
  on demand.

### 6.4 Untangling "identify the project" from "reach the remote"

Three endpoints took `repoUrl` for reasons that had stopped being true, and one
question - "has this branch moved?" - had no endpoint at all.

- **`/generate-automation` no longer takes `repoUrl`.** It never fetched
  anything: the manual cases come from the commit's own CSV already on disk,
  and the API surface comes from an existing local clone found by matching
  project name - `resolveLocalRepo` already scanned `aiqa.git.workspace-dir`
  by name, `repoUrl` was read only as a fallback way to compute that same
  name. `projectName` is now `@NotBlank` on `GenerateAutomationRequest`, and
  the field is gone rather than merely unused - checked empirically (a request
  body with an unrecognized field does not 400 in this app, so a caller still
  sending `repoUrl` is unaffected, same as `accessToken`/`provider` already
  were).
- **`/merge-history` and `/merge-history/incomplete` now take `projectName`,
  not `repoUrl`.** Both only ever read local state - no clone, no fetch, no
  credential - but `MergeHistoryService`'s storage key is `(repoUrl, branch)`,
  built from the raw origin URL, not a project name. `GitDiffService`
  gained `findOriginUrlForProject(workspaceDir, projectName)` - the reverse of
  `resolveProjectName` - which scans local clones for the one whose derived
  name matches and reads back its actual `origin` URL, the exact string the
  storage key needs. Needs a clone to already exist, which it will for any
  project merge history exists for at all. `resolveProjectName` was refactored
  to share the same `readOriginUrl` JGit read rather than duplicate it.
- **New: `POST /generate-tests-from-branch/check-new-commits`.** The one
  operation in this family that legitimately still needs `repoUrl` and a
  credential, because "is there anything new" is a question about the
  *remote*, not local state. Runs the same `syncRepo` + merge walk
  `runBackfillAndCatchUp` does, stopped before the first call to `run()` - so
  it costs a clone/fetch but zero LLM calls, meant as a cheap check before
  deciding whether a real backfill is worth running.

All three were run against the real endpoints, not just unit tests, using a
local throwaway repo as the "remote" (no network needed, same `syncRepo` code
path either way): `check-new-commits` correctly reported both commits as new
before any processing, `alreadyProcessed: 2, hasNewCommits: false` after a
zero-cost backfill (both commits touched only a `.txt` file), and picked up a
third commit added afterward without restarting anything. `/merge-history` and
`/merge-history/incomplete` by `projectName` returned the same history a
`repoUrl` lookup would have; an unknown project name gave a 404 naming the
fix. `/generate-automation` 400s with no `projectName`, and a request still
carrying `repoUrl` in its body proceeds unaffected.

---

### 6.2 A category with no input is not a category you covered

A backfill reported *"2 newly completed"* against a `generated-tests/mms/`
holding **one** folder. Both of those merges are config-only, and three things
compounded:

- `partition(changedFiles, 40)` returns an empty list for an empty list, so the
  per-batch loop never ran and `categoryFullyCovered` kept its initial `true`.
  Four source-diff categories were credited **without a single LLM call**.
- `expectedCategories` includes CONFIGURATION when config files changed, so that
  loop credited CONFIGURATION too - while the dedicated config block below it,
  whose call had genuinely failed, added CONFIGURATION to `failedCategories`.
  One summary named the same category as both covered and failed, and
  `missingCategories` came out empty: **SUCCESS at 100% with zero test cases**,
  on a run whose only real call failed. `isProcessed` then skipped it forever.
- The CSV write was gated on `!businessTestCases.isEmpty()`, so a commit that
  generated nothing left no folder at all - indistinguishable from a commit the
  backfill never touched.

Not applicable is now a third outcome alongside generated and missing: a
category with no batches is recorded in `notApplicableCategories`, excluded from
the coverage denominator, and never retried. Both halves matter - crediting it
inflates coverage, and merely refusing to credit it would strand the merge as
permanently incomplete against files that do not exist. `coverageOf` is the one
place this is decided, extracted so `ConfigOnlyMergeCoverageTest` can pin the
real shapes.

And every processed commit now leaves a folder. When no CSV is written the
folder holds `no-test-cases.txt` carrying the run summary verbatim, so the
folder and the API response can never disagree. One filename for all of the
reasons - nothing changed, every category failed, the model correctly had
nothing to say - because the rule a reader needs is "a commit folder with no CSV
has a note saying why", and that only works with one name to look for.

Four merges were already stuck SUCCESS-with-nothing when this was found (mms
`0dfaf7fe`, `4c8ad09e`; FoodFrenzy `9e66910c`, `4242dc7f`). Their history entries
were deleted so a backfill re-attempts them; the fix does not retroactively
unstick anything, because `isProcessed` never looks at whether output exists.

### 6.1 What a resume must not destroy

A backfill of the mms initial commit turned 34 manual cases into 5. The
mechanism is worth stating plainly because two of the three bugs behind it read
as reasonable code.

**The write replaced instead of merging.** A resume regenerates only the
categories that failed last time, and the per-commit CSV was written with
`writeCsv(businessTestCases, ...)` - this run's output, over the whole file. For
a full run that is correct. For a partial one it discards everything the earlier
run produced for categories it never touched, and the automation pass reads
exactly that file. `writeCsvPreservingOtherCategories` now keeps what the resume
did not regenerate.

Merging on test case id is not available: `idCursor` restarts at TC-001 on every
run, so the resume's TC-001..TC-005 are different cases from the existing
TC-001..TC-005 and matching on id overwrites the wrong rows. **The category is
what a resume replaces, so the category is what the merge keys on.** Surviving
cases keep their ids - anyone may have referenced them - and the fresh ones are
renumbered past the highest survivor, because duplicate ids in this file are
worse than renumbered ones: the automation pass names its test methods from them.

**The summary described the damage as something else.** It said *"2 categories
failed and produced no test cases: [NEGATIVE, BOUNDARY]"* in a response whose
`businessTestCases` field held five Negative cases. Both halves came from the
same `categoryFullyCovered` flag, which is all-or-nothing across file batches:
one failed batch marks the group as failed, while `merged` keeps the cases the
other batches returned. Failing and producing nothing are now reported as the
different things they are, and `MergeStatus.FAILED` is reserved for a run that
produced nothing at all - which matters, because `MergeHistoryService` preserves
a FAILED status verbatim and only recomputes a PARTIAL one from accumulated
progress.

**Splitting could not fix the underlying failure.** The root commit is 126 files
and 113 classes, and `generateWithSplitting` halves the file list on a too-large
rejection - but the collaborator source rides along in full on every call, so the
prompt fails identically at 40 files and at 1. Same lesson as §5's automation
note, in the other pipeline. When splitting bottoms out at one file,
`shrinkContext` now halves the shared implementation context on a line boundary,
drops it below 4k rather than leaving a fragment, and shares the reduced size
with every later call in the run.

None of this makes the root commit generate under an 8000 TPM ceiling - see
§8. It stops the run from destroying the previous run's work on the way to
failing.

### 6.3 A direct /generate-tests call didn't know a folder already existed

Backfill and `/generate-tests-from-branch` have always resumed correctly:
`buildInnerRequest` looks the merge up in history and sets `onlyCategories` to
just what's missing, which routes the write through
`writeCsvPreservingOtherCategories` instead of a full replace. A raw
`POST /generate-tests` never went through that lookup - it computed its output
folder as `<seq>.<hash>` from whatever `commitSequence` the caller passed (or
bare `<hash>` if they passed none), with no check for whether a folder for that
commit already existed under a DIFFERENT name. Passing the wrong sequence, or
none, created a sibling folder instead of finding the real one - a resume's
`onlyCategories` then merged into an empty directory rather than the one
holding the earlier attempt's output.

`QaPipelineService.run` now asks `GeneratedRunLocator` first - the same lookup
download and execution already used, which knows both shapes a folder can be
named. An existing folder always wins, whatever shape it is; `commitSequence`
only shapes a **fresh** folder's name, unchanged from before. The decision is
split out as `resolveRunOutputDir` (static, no I/O) precisely so it's testable
without a filesystem - see `RunOutputDirResolutionTest`. Verified live too:
a folder was pre-created as `3.<hash>`, `/generate-tests` was called for that
same commit with NO `commitSequence`, and the response's note landed inside
the existing `3.<hash>` folder - no bare-hash sibling was created.

**Automation generation now does the equivalent for scripts.** Every call used
to re-read the whole `manual_test_cases.csv` and regenerate automation for
every case in it, then overwrite the merged file wholesale - correct in that it
can't produce duplicate methods, but it re-spends an LLM call on every case
every time, including ones a prior run already covered.

The automation prompt already required a trace-back comment above every
`@Test` ("put its Test Case ID in a comment above the method so a human can
trace script back to case") - that convention is now read back, not just
written. Before generating, `AutomationGenerationService` computes the merged
file's deterministic name (`AutomationScriptMerger.mergedFileName`, from the
commit hash alone), reads it if present, and extracts covered ids
(`alreadyAutomatedCaseIds`, tolerant of blank lines and CRLF between the
comment and the annotation). Only the cases NOT already covered are sent to the
model; if none remain, the call returns immediately with 0 LLM calls and the
file untouched. Otherwise the existing file is prepended to the fresh batch
before merging, so `AutomationScriptMerger.merge` - unmodified - does the same
job it already does for same-run batches: one `@BeforeClass` survives, a method
NAME collision is renamed rather than dropped. Existing methods go first in
that list on purpose, so an existing fixture stays the authority and any new
batch's setup is absorbed into it, not the other way around.

**What this does not prove, which matters given the mechanism.** The trace-back
comment is a prompt instruction, not a code-enforced format - a method the
model wrote without one, or with a malformed one, reads as NOT yet automated.
The failure mode is a redundant method next run, renamed by the collision
handling above, never a lost one - `alreadyAutomatedCaseIds` can only ever
under-count coverage, never over-count it.

Both new methods were run against a real commit through the actual
`/generate-automation` endpoint, not just unit tests: a case already covered by
an existing script produced a response reporting `alreadyAutomated: 1`, an
unmodified file (byte-identical SHA-256 before and after), and no LLM call in
the logs; adding one genuinely new case to the same CSV then produced
`alreadyAutomated: 1` alongside `1 new case(s) generated`, and the merged file
on disk held both methods - the original's body untouched, the new one
appended under its own `// TC-002`.

### 6.5 Automating (and running) every case a project has, not just one commit's

`/generate-automation` needs a `commitHash` because it's scoped to one commit's
own folder. Sometimes the ask is simpler than that: automate everything the
project has ever accumulated, from `projectName` alone.

**`POST /generate-automation-for-project`** reads the project-wide rollup CSV
(`generated-tests/<project>/all_manual_test_cases.csv` - the same file
`/test-cases/download` already serves when `commitHash` is omitted) instead of
one commit's folder. The API surface is scanned from the checkout's **HEAD**,
not any specific historical commit - there's no single commit that "every case
combined" corresponds to. Writes to the project's own root
(`AutomationTest_<project>.java`), not a commit subfolder. Everything else is
identical to the per-commit endpoint and shares its exact machinery: context
gathering, batching, the two contract/source repairs from §3.6, compile-salvage,
incremental skip-what's-already-covered.

Two small, mechanical refactors made the sharing possible without duplicating
~250 lines: `generateBatch` and `resolveLocalRepo` now take plain parameters
(`LlmKeys`, `repoPath`) instead of the whole per-commit request object, so both
entry points call the same helpers unchanged.

**`POST /execute-automation-for-project`** is the execution half - without it
the project-wide script had nowhere to run, since `/execute-automation` only
ever looks inside a commit-hash-named subfolder (`GeneratedRunLocator`) and
would never find a file sitting in the project root. Reuses `execute()`'s
script-loading, base-URI repair, and compile+run path unchanged.
`ExecutionReportDownloadService` gained the same fallback `TestCaseDownloadService`
already had: a blank `commitHash` now serves the project-root report instead of
requiring one (`GET /execution-report/download?projectName=mms`, no
commitHash). The report's "Commit" field reads `"all (project rollup)"` rather
than a real hash, so it doesn't misleadingly look like one.

Caught one real bug while writing this, worth remembering as its own trap:
`.formatted(...)` binds tighter than string `+`, so
`"a %s b" + "c".formatted(x)` calls `.formatted` on `"c"` alone - `%s` in the
first literal is silently never substituted, no exception, no warning. Always
wrap the full concatenation in parens before calling `.formatted`. Fixed here;
not caught by a test, since `AutomationGenerationService`/
`AutomationExecutionService` have no dedicated unit tests at all (same gap the
original `generate()` already had) - only caught by rereading the diff.

Neither new endpoint has a dedicated unit test for the same reason. Verified by
compiling, running the full suite (no regressions), and manual code review
against the already-tested helpers both reuse.

## 7. Test coverage

187 unit tests, 27 classes — the first real tests in this repo beyond the
context-load smoke test.

| Class | n | Covers |
| --- | --- | --- |
| `LlmJsonArrayTest` | 9 | empty vs failed responses, salvaged arrays, truncation still failing |
| `ProviderCooldownRegistryTest` | 9 | config failures not benched, cooldown windows |
| `AutomationScriptMergerTest` | 8 | base-URI guarantee, error-body stripping |
| `RunDiagnosisTest` | 10 | the five findings, and the cases that must NOT fire |
| `AiRouterChainTest` | 4 | catalog providers usable in the chain |
| `ReplayServiceTest` | 4 | token guard, never-throws contract |
| `ExecutionReportWriterTest` | 4 | §4.2 — `</script>` in a payload, editor controls present |
| `SetupFailureReportTest` | 6 | §4.1.1 — the dead-setup run, from the real mms fixture |
| `ResumeCsvMergeTest` | 6 | §6.1 — a resume keeps the categories it did not regenerate |
| `PartialCoverageReportingTest` | 8 | §6.1 — partial-vs-empty wording, context shrinking |
| `NoChangesNoteTest` | 4 | §6 — every processed commit gets a folder; the note never throws |
| `LlmClientSelectionTest` | 7 | §5.1 — provider validation, blank keys report no credentials |
| `ConfigOnlyMergeCoverageTest` | 6 | §6.2 — a category with no input is neither covered nor missing |
| `ExecutionReportDownloadServiceTest` | 6 | §4.3 — traversal guard, both run-folder shapes, byte-identical read |
| `RunOutputDirResolutionTest` | 5 | §6.3 — an existing folder always wins over a fresh, differently-shaped one |
| `GitDiffServiceTest` | 6 | §6.4 — findOriginUrlForProject: the reverse of resolveProjectName |
| `AutomationScriptMergerTest` (extended) | +8 | §6.3 — id extraction, incremental merge, collision-rename backstop |
| `JavaSourcesTest` | 3 | modern syntax parses on another thread |
| `ProviderLogsTest` | 3 | provider reasons survive flattening |
| `CompileSalvagerPruneTest` | 10 | §3.4 — unreachable fixtures dropped, shadowed locals kept |
| `SwaggerLocatorTest` | 9 | §3.5 — overridden docs paths, one component per deployable |
| `OpenApiContractExtractorTest` | 8 | $ref/allOf/cycles, flattening to `data.tiers[].rate` |
| `OpenApiSpecLoaderTest` | 7 | never throws; a swagger-ui page is not a spec |
| `ApiContractRendererTest` | 7 | relevance, budget, shape de-duplication |
| `OpenRouterProviderTest` | 6 | §5.2 — free-only guard, the 3-item `models` cap, provider-order/allow-fallbacks reach the body |
| `ContractRequiredFieldsRepairTest` | 11 | §3.6 — contract-required injection, path-param matching, enum/no-formatted-call fallback, nested-field exclusion, source-grounding composed with the contract, 2-arg overload unchanged |
| `RequestDtoSourceScannerTest` | 7 | §3.6 — jakarta/javax both match, records, nested types, build/VCS dirs skipped, missing class/blank input never throws |
| `ExecutionReportDownloadServiceTest` (extended) | 6 | §6.5 — blank commitHash now falls back to the project-root report instead of erroring |

**Not covered by tests, and worth knowing:** every prompt change, and
`AutomationGenerationService`/`AutomationExecutionService` in general (both
`generate()`/`execute()` and their new `...ForProject` siblings) - no dedicated
unit test exists for any of the four; verified live and by full-suite
regression only. Those are only
verifiable by a live run, and several have never been through one — see §8.

---

## 8. Open items, in priority order

1. **The current build has now run end to end, and produced one test.** That
   is progress and a warning in equal measure. On 2026-08-19 the generated
   `AutomationTest_3836dee...` carried every marker of the new code working: a
   `@BeforeClass` setting `RestAssured.baseURI`, extraction at `data.id`, an
   assertion on `data.tierFrom`, and a payload of exactly `capabilityCode` /
   `feeType` / `effectiveFrom` / `currency` with `TIERED` from the declared
   enum - `CreateFeeConfigRequest` verbatim, against yesterday's
   `"merchantId": "null"`.

   What it does NOT show is a working suite. The 11:53 generation lost all 34
   cases to providers (§8.3), so only one test existed to run; the 12:31
   execution then died in `@BeforeClass` on the merchant 422 (§10) and skipped
   it. **No green run has ever happened.** The prune, the base-URI repair and
   the contract are proven to execute; nothing yet proves they produce a suite
   that passes.

   (The older 2026-08-18 17:20 report is a **stale-server artefact** and is not
   evidence of anything - see §11.)
2. **The uniqueness rule needs a live check.** It was in the committed prompt,
   so the model had it and ignored it in 5 of 8 merchant creates: two used
   `System.currentTimeMillis()` and got 201, five hardcoded `REG123456` and got
   409. The rule in `PromptBuilder` is now much more specific — it names the
   field kinds that bite and carves out the deliberate-duplicate case. If the
   next run still hardcodes, it has proven unreliable twice and belongs in code,
   per the working rule in §1 — but that rewrite is AST surgery on payloads and
   should not be attempted before the evidence exists.
3. **The mms initial commit still cannot be generated**, and it is the reason
   `main` reports "NOT fully caught up". 126 files / 113 classes is the largest
   prompt this pipeline builds, against a Groq on-demand ceiling of 8000 TPM and
   a rate-limited Gemini. The context now shrinks instead of failing
   identically, but shrinking cannot close a 3x gap on its own - a working
   provider, a paid tier, or a smaller batch size is what finishes it.
4. **The 29 cases lost from that commit's CSV are recoverable but not
   recovered.** `generated-tests/mms/all_manual_test_cases.csv` kept all 136
   rows for `3836dee` (71 Boundary, 33 Configuration, 23 Negative, 9 Security),
   each carrying a `Source Ref` and a `3836dee-TC-nnn` reference, so the
   per-commit file can be rebuilt from it. Nothing does that automatically yet.
5. **The contract has been through one generation, of one test - too small a
   sample to trust.** Id extraction at `data.id` and contract-shaped payloads
   are confirmed (§8.1). Two things it was meant to fix have still never been
   observed, because no case exercising them survived: whether boundary cases
   take their limits from the contract (`maxLength 50` on `capabilityCode`
   rather than a guess), and whether the model stays off 4xx body assertions
   given the note saying mms documents no error shape. Check both on the first
   run that generates more than a handful of tests.
6. **Groq cannot serve the automation prompt** on the on-demand tier even at
   minimum context. Either accept it as a fallback that runs with less context,
   move to a paid tier, or shrink the system prompt itself (it is ~900 lines).
7. **The Cerebras model id is unverified** against the account — `llama3.3-70b`
   is the documented form, but list the models to be sure.
8. **Groq's default model is a judgement call.** `openai/gpt-oss-120b` answered,
   but has never been compared for test-generation quality.
9. **The request editor has not been driven in a real browser.** Its logic is
   verified by running the report's own emitted script against a DOM stub under
   Node — fill, read, toggle, reset, edited-detection and the empty-URL guard
   all pass, and the emitted JS parses clean — but nobody has clicked it in
   Chrome. The layout in particular is unverified.
10. **mms's local checkout doesn't match what's deployed — see §10.** Should
   probably be priority 1, not 10: it silently caps how correct ANY mms
   automation can be, no matter how good the generation prompt or the §3.6
   repairs get, because the source they're grounded in (`main`) is missing
   fields the deployed target (`feature/mms-0.1.0` or later) actually requires.
   Needs someone who knows which branch is meant to be authoritative for this
   target before touching the shared `repo-workspace` clone.
11. **OpenRouter (§5.2) has never been exercised against a real account.** The
   free-only guard, the 3-item cap, and the request shape are all unit-tested;
   whether a free-tier model reliably returns well-formed JSON for a real
   automation/manual-generation prompt is not. The one live attempt this
   session made was blocked by the undocumented cap (now fixed) before a
   response quality could even be judged — try again once a key is configured.
12. **Nothing is committed.** `git status` is the authority; the counts below
   drift. New in this stretch of work:

   - `src/main/java/com/company/aiqa/openapi/` — 8 source files (§3.5), plus a
     `jackson-dataformat-yaml` dependency in `pom.xml` and an `aiqa.openapi.*`
     block in `application.yml`.
   - 8 new test classes: `SwaggerLocatorTest`, `OpenApiContractExtractorTest`,
     `OpenApiSpecLoaderTest`, `ApiContractRendererTest`,
     `ExecutionReportWriterTest`, `SetupFailureReportTest`, `ResumeCsvMergeTest`,
     `PartialCoverageReportingTest`.
   - Modified: `CompileSalvager` (§3.4), `RunDiagnosis` and
     `ExecutionReportWriter` (§4.1, §4.1.1, §4.2), `TestExecutionResult` and
     `RestAssuredTestExecutionService` (§4.1.1), `QaPipelineService` and
     `ManualTestCaseGenerator` (§6.1), `PromptBuilder`,
     `AutomationGenerationService`, `GenerateAutomationRequest`, `AppConfig`,
     plus `QaPipelineService.writeNoChangesNote` and `NoChangesNoteTest` (§6),
     and `OpenAIService` / `GeminiService` / `LlmProperties` / `AppConfig` /
     new `LlmClientStartupReport` + `LlmClientSelectionTest` (§5.1).

   **Since §5.1, still uncommitted, still not re-enumerated file-by-file below
   because `git status` is the authority and this list would only drift again -
   see the sections themselves for what changed and why:**
   - §4.3 — new `ExecutionReportDownloadService` + `execution-report/download`.
   - §6.2 — `Coverage`/`coverageOf` extracted, `notApplicableCategories`, the
     `no-test-cases.txt` note generalized.
   - §6.3 — `QaPipelineService.resolveRunOutputDir` (folder reuse),
     `AutomationScriptMerger.mergedFileName` / `alreadyAutomatedCaseIds`
     (incremental automation generation).
   - §6.4 — `repoUrl` removed from `GenerateAutomationRequest`;
     `/merge-history` and `/merge-history/incomplete` take `projectName`
     (`GitDiffService.findOriginUrlForProject`); new
     `POST /generate-tests-from-branch/check-new-commits`.
   - `postman/` — an Insomnia export and a Postman v2.1 collection covering
     every endpoint, handed to the user directly rather than tracked from a
     design doc. Neither is source; commit only if the user wants them kept
     in the repo.
   - **This session (2026-09-01), same reasoning — see §3.6, §5.2, §6.5:** new
     `com.company.aiqa.ai.router.OpenRouterProvider`,
     `com.company.aiqa.testcase.RequestDtoSourceScanner`; new
     `GenerateAutomationForProjectRequest`/`Response` and
     `ExecuteAutomationForProjectRequest`/`Response`; modified
     `AbstractOpenAiCompatProvider` (`extraBodyFields` hook), `AiRouterService`,
     `RouterProperties`, `ApiContract`/`OpenApiContractExtractor`
     (`requestSchemaName`), `AutomationScriptMerger` (two new overloads +
     the §3.6 repair), `AutomationGenerationService`/`AutomationExecutionService`
     (`...ForProject` methods, `generateBatch`/`resolveLocalRepo` refactored to
     plain parameters), `ExecutionReportDownloadService`, `QaController`
     (two new endpoints), `application.yml` (`aiqa.router.chain`/`keys`/
     `models`/`openrouter-*`). Four new test classes, one extended — see the
     coverage table in §7.

   On top of the earlier uncommitted work, plus staged removals of `target/`
   and the gitlinks. `repo-workspace/` and `generated-tests/` also carry
   untracked run output that is not source and should not be committed.

---

## 9. How to run things

```bash
# specific commit (parent as base)
curl -X POST http://localhost:8080/api/v1/generate-tests -H "Content-Type: application/json" -d '{"repoUrl":"https://github.com/org/repo.git","baseRef":"<sha>^","headRef":"<sha>","testCaseMode":"MANUAL"}'

# automation for a commit that already has manual cases
# the OpenAPI document is found from the repo's own config - pass openApiUrls
# only for a service that does not follow the conventions
curl -X POST http://localhost:8080/api/v1/generate-automation -H "Content-Type: application/json" -d '{"projectName":"mms","commitHash":"<sha>","baseUri":"http://169.58.37.242:8007/mms"}'

# check what the locator would fetch, without spending a generation
curl -s http://169.58.37.242:8007/mms/api-docs | head -c 200

# run it — ALWAYS pass baseUri; the default is localhost:8080, which is this platform
curl -X POST http://localhost:8080/api/v1/execute-automation -H "Content-Type: application/json" -d '{"projectName":"mms","commitHash":"<sha>","baseUri":"http://169.58.37.242:8007/mms"}'

# §6.5 — automate EVERY case the project has ever accumulated, not one commit's;
# only projectName is required
curl -X POST http://localhost:8080/api/v1/generate-automation-for-project -H "Content-Type: application/json" -d '{"projectName":"mms","baseUri":"http://169.58.37.242:8007/mms"}'
curl -X POST http://localhost:8080/api/v1/execute-automation-for-project -H "Content-Type: application/json" -d '{"projectName":"mms","baseUri":"http://169.58.37.242:8007/mms"}'
curl -o mms_report.html "http://localhost:8080/api/v1/execution-report/download?projectName=mms"   # no commitHash
```

- A repository's **first commit** has no parent: use `"baseRef":"EMPTY_TREE"`.
- `<sha>^` and `<sha>~1` both resolve; so does an explicit parent sha.
- JSON has no comments and no trailing commas — both produce a bare 400 with no
  message. This cost real debugging time twice.

---

## 10. Findings about the *target* applications

Worth passing to those teams — these are defects in the services, not in the
platform.

> **Correction, 2026-08-19.** The fee-endpoint entry below was wrong, and it is
> the exact mistake this document was written to prevent. Every one of the 10
> `POST /api/v1/fees` calls in the 17:20 run sent `"merchantId": "null"` —
> checked against `http-exchanges.json`, all 10 — so mms was answering a request
> for a merchant that cannot exist. Its 500 may still be a defect (404 or 400
> would be better), but this run is not evidence of one, and nobody should be
> sent looking. The `POST /api/v1/terminals` entry has not been re-checked and
> carries the same doubt. Re-run on a restarted server before telling that team
> anything.

**mms** (from the 2026-08-18 16:17 run, 24 requests, base URI correct):

- ~~`POST /api/v1/fees` returns **500** for payloads it should accept *and* for
  ones it should reject with 400 — 8 tests, the largest single cluster.~~
  **Withdrawn** — our own payload, see the correction above.
- `POST /api/v1/terminals` returns **500** on a missing `storeId` where 400 is
  right. *Unverified.*
- Earlier runs: a duplicate registration number returns 201 instead of 409;
  uploading a document for an unknown merchant returns 500 rather than 404.
- Correct behaviour worth noting: 404 for absent ids, 409 for a duplicate
  capability code, 422 for an invalid status transition.

- **A merchant can never leave `PENDING`, so most of its lifecycle is
  unreachable.** `MerchantServiceImpl.validateStatusTransition` allows
  `PENDING -> UNDER_REVIEW -> APPROVED -> ACTIVE -> SUSPENDED|TERMINATED`, but
  **nothing in the codebase ever writes `UNDER_REVIEW`** - the five
  `setStatus` calls are PENDING (on create), APPROVED, ACTIVE, SUSPENDED and
  TERMINATED, and `MerchantController` exposes only approve/activate/suspend/
  terminate. So a merchant created through the API is stuck in PENDING,
  `POST /{id}/approve` can only ever answer 422 *"Cannot transition merchant
  from PENDING to APPROVED"*, and activate/suspend/terminate sit behind it
  unreachable. Verified against the source, not inferred from the 422.
  Consequence for us: any case needing an approved or active merchant is
  **not automatable** against this build, and a generated setup cannot be fixed
  by adding a call because there is no call to add.

- **The local checkout this platform reads source from does not match what's
  deployed.** `repo-workspace/.../mms` is on `main` (`4c8ad09`); the live
  target at `169.58.37.242:8007` is running `feature/mms-0.1.0` or later.
  Confirmed, not inferred: that branch's `CreateMerchantRequest.accountNo`
  carries `@NotBlank(message = "Account No is required")` - a **verbatim**
  match to the runtime 400's validation message - and `main`'s copy of the
  same file has no `userId`/`accountNo` fields at all. Every commit generated
  against `main` will keep missing fields the deployed service actually
  requires until the checkout tracks the right branch; §3.6's two required-field
  repairs are both verified correct and both genuinely powerless against this,
  since neither can inject a field that doesn't exist anywhere in the source
  they're reading. This needs a decision from whoever owns this platform's mms
  target config about which branch is authoritative - not changed here, since
  `repo-workspace`'s clone is shared across other in-progress work and
  switching it could affect that too.

**order-service** — no validation at all despite
`spring-boot-starter-validation` on the classpath. `19.99` is silently stored as
`19` (money loss); negative prices accepted; `GET /orders/999` returns **200**
for a non-existent order.

---

## 11. Traps that would cost a day to rediscover

- **A partial re-run that writes the same file as a full one will delete what
  it did not regenerate.** Any "resume only the missing parts" path needs to
  know whether its writer replaces or merges. Here the writer was shared with
  the full-run path, so nothing looked wrong at either call site.
- **A running server is the build it started with, and a report never says so.**
  Three sessions have now lost hours to this. There are two tells in an
  artefact: a merged script with no `@BeforeClass` (the merger always adds one),
  and a script the prune visibly should have cut. Both are checkable in a minute
  against the file on disk — do that before believing any verdict in a report.
- **springdoc documents only what the code declares.** mms's document has 53
  paths and not one 4xx response schema, because nobody wrote `@ApiResponse`
  annotations. A contract that is silent about errors is not a contract that
  says errors have no body — treating it that way is how the platform would
  reintroduce the exact bug §3.2 was written to kill.
- **A payload can carry the word "null".** `String.valueOf(null)` formatted into
  a text block sends `"merchantId": "null"`, the service answers 500 to a thing
  that cannot exist, and the run reads as a product defect. Grep a suspicious
  body for `"null"` before writing anything up.
- **`StaticJavaParser`'s configuration is a ThreadLocal.** Setting it in a static
  initializer configures one thread and nothing else.
- **Do not rewrite `ProviderLogs.oneLine` as a regex.** It first used
  `replaceAll("\s+", " ")` with one backslash — Java 15+ reads `\s` in a string
  literal as a **space escape**, so it compiled to `" +"`, collapsed spaces and
  left every newline. Compiled clean, did nothing. It uses `.lines()`, which
  needs no escapes.
- **REST Assured's default base URI is `http://localhost:8080`** — the same port
  this platform runs on, so a script that never sets it produces a report full
  of plausible-looking 404s from the wrong service.
- **`AllProvidersFailedException`'s first line is a constant header.** Any code
  that reports "the first line of the message" reports nothing.
- **Surefire needs the network on first run** (it downloads
  `surefire-junit-platform`); `mvn -o test` fails until then.
- **`repo-workspace/merge-history/github_com_acquiring_system_mms_git_main.json`
  is currently in a state `git status` shows two ways at once**: staged as a
  61-line deletion (relative to the committed version, which still holds the
  pre-§6.2 stuck entries) AND untracked, because a fresh file with different
  content - `0dfaf7fe`/`4c8ad09e` now correctly PARTIAL instead of falsely
  SUCCESS - has been written to the same path since. Both halves are real and
  neither is wrong; it just means a bare `git add -A` would stage a delete +
  re-add instead of the clean modification it should be. Read the working-tree
  file before touching this one - it is the CORRECT post-§6.2 state, not
  something to discard.
- **`.formatted(...)` binds tighter than string `+`.** `"a %s b" + "c
  literal".formatted(x)` calls `.formatted` on the second literal alone - the
  `%s` in the first one is never substituted, with no exception and no warning,
  since extra/unused format args are silently accepted. Compiles clean, runs
  clean, just prints the placeholder verbatim. Always wrap the whole
  concatenation in parens before calling `.formatted` (see §6.5).
- **A local git checkout can silently be the wrong branch for what's actually
  deployed.** No error anywhere - the model just never sees fields that exist
  on the live target, generates payloads missing them, and every fix that
  grounds itself in "the source" (implementation context, §3.6's DTO
  scanner) inherits the same blind spot, because the checkout genuinely
  doesn't have that field written down anywhere. Confirmed by matching a
  runtime error message **verbatim** to a `@NotBlank` annotation on a
  *different* branch than the one checked out - see §10's mms finding. Worth
  checking early on any target that's been repeatedly wrong about "required"
  fields despite good contract/source grounding.
