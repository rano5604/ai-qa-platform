# AI-QA Platform — handoff

Written 2026-08-18, updated 2026-08-19. Everything here is **uncommitted work on branch
`feat/automation-generation-and-execution`** (last commit `69fc609`).

Delete this file once the work is merged — it exists to carry context into a new
chat, not to live in the repo.

```bash
mvn -Dtest='!AiQaPlatformApplicationTests' test    # 101 unit tests, all passing
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

## 5. Providers and credentials

Chain: `[gemini, cerebras, groq, mistral, github, ollama]`.

| Provider | State (verified live 2026-08-18) |
| --- | --- |
| gemini | **works**; `gemini-3.6-flash` available. Free tier limits per minute |
| cerebras | key authenticates; catalog default corrected to `llama3.3-70b` (no hyphen after "llama") — **confirm against your own account** |
| groq | key valid, but `openai/gpt-oss-120b` on the on-demand tier **413s** on the automation prompt even with context trimmed |
| mistral | **402** — needs a paid subscription |

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
- **`.gitignore` added.** `target/` (106 files) and the `repo-workspace/` clones
  are out of the index — the clones were tracked as **gitlinks with no
  `.gitmodules`**, so their SHA changed on every fetch. `repo-workspace/merge-history/`
  stays tracked; it is the platform's own state. The removals are staged;
  `git reset` undoes them.
- **API surface**: `POST/GET/DELETE /api/v1/llm-keys` (in-memory only, never
  logged or returned), `GET /api/v1/test-cases/download`, `POST /api/v1/replay`,
  and `repoPath` is optional on `/generate-tests` — pass `repoUrl` and it clones
  on demand.

---

## 7. Test coverage

101 unit tests, 15 classes — the first real tests in this repo beyond the
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
| `JavaSourcesTest` | 3 | modern syntax parses on another thread |
| `ProviderLogsTest` | 3 | provider reasons survive flattening |
| `CompileSalvagerPruneTest` | 10 | §3.4 — unreachable fixtures dropped, shadowed locals kept |
| `SwaggerLocatorTest` | 9 | §3.5 — overridden docs paths, one component per deployable |
| `OpenApiContractExtractorTest` | 8 | $ref/allOf/cycles, flattening to `data.tiers[].rate` |
| `OpenApiSpecLoaderTest` | 7 | never throws; a swagger-ui page is not a spec |
| `ApiContractRendererTest` | 7 | relevance, budget, shape de-duplication |

**Not covered by tests, and worth knowing:** every prompt change. Those are only
verifiable by a live run, and several have never been through one — see §8.

---

## 8. Open items, in priority order

1. **Still no end-to-end run on the current build.** The 2026-08-18 17:20 mms
   run was made by a **stale server** and proves nothing about this code. The
   merged script contains no `@BeforeClass` at all, which
   `ensureBaseUriIsHonoured` cannot leave behind, and the orphan-field prune -
   which drops 21 methods when run against that same file today - plainly never
   ran. Read nothing in that report as evidence. Restart on this build,
   regenerate mms, run it, and expect roughly 13 tests rather than 34.
2. **The uniqueness rule needs a live check.** It was in the committed prompt,
   so the model had it and ignored it in 5 of 8 merchant creates: two used
   `System.currentTimeMillis()` and got 201, five hardcoded `REG123456` and got
   409. The rule in `PromptBuilder` is now much more specific — it names the
   field kinds that bite and carves out the deliberate-duplicate case. If the
   next run still hardcodes, it has proven unreliable twice and belongs in code,
   per the working rule in §1 — but that rewrite is AST surgery on payloads and
   should not be attempted before the evidence exists.
3. **The contract has never been through a generation.** It is unit-tested and
   verified end-to-end against the live mms document — 71 operations, correct
   `data.id` paths, `maxLength 50` on `capabilityCode` — but no script has yet
   been generated with it in the prompt. Watch three things in the next run:
   whether ids are extracted at `data.id` rather than `id`, whether boundary
   cases use the contract's own limits, and whether any 4xx body assertion
   appears despite the note saying no error shape is documented.
4. **Groq cannot serve the automation prompt** on the on-demand tier even at
   minimum context. Either accept it as a fallback that runs with less context,
   move to a paid tier, or shrink the system prompt itself (it is ~900 lines).
5. **The Cerebras model id is unverified** against the account — `llama3.3-70b`
   is the documented form, but list the models to be sure.
6. **Groq's default model is a judgement call.** `openai/gpt-oss-120b` answered,
   but has never been compared for test-generation quality.
7. **The request editor has not been driven in a real browser.** Its logic is
   verified by running the report's own emitted script against a DOM stub under
   Node — fill, read, toggle, reset, edited-detection and the empty-URL guard
   all pass, and the emitted JS parses clean — but nobody has clicked it in
   Chrome. The layout in particular is unverified.
8. **Nothing is committed.** Now including the whole `com.company.aiqa.openapi`
   package (6 source files, 4 test files) and a `jackson-dataformat-yaml`
   dependency, on top of the earlier 24 modified files, 10 new source files and
   9 new test files, plus staged removals of `target/` and the gitlinks.

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

**order-service** — no validation at all despite
`spring-boot-starter-validation` on the classpath. `19.99` is silently stored as
`19` (money loss); negative prices accepted; `GET /orders/999` returns **200**
for a non-existent order.

---

## 11. Traps that would cost a day to rediscover

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
