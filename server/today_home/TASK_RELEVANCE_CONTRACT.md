# Today Home task relevance contract (candidate)

This is a candidate-source change, not a deployment or data cleanup. It changes
which stored records are presented as actionable; moving a record into review is
**not** completion, resolution, deletion, or evidence of submission.

## Model output and evidence

The synthesis response remains `{ "tasks": [...], "resolved": [...] }`.
`tasks` here means proposed records to store, not an instruction to put every
record in the main actionable list. Existing task fields and stable `task_key`
are unchanged. New synthesis must include this exact nested object:

```json
{
  "eligibility": {
    "kind": "user_application",
    "user_intent": "confirmed",
    "source_id": "gmail:cached-evidence-id",
    "quote": "An exact excerpt from the cached evidence text",
    "action_status": "pending",
    "deadline_policy": "closes"
  }
}
```

This is a schema illustration, not real user evidence.

| Field | Allowed values / meaning |
| --- | --- |
| `kind` | `confirmed_obligation`, `user_application`, `user_goal`, `coursework`, `optional`, `unknown` |
| `user_intent` | `confirmed`, `inferred`, `unknown`; confirmed means direct evidence of the person's obligation or expressed choice, not a guess |
| `source_id` | An actual cached source record, from the same source as the task; may differ from the task's deadline-evidence record |
| `quote` | Nonblank, at most 2000 characters including whitespace, exact substring of the cited record's text |
| `action_status` | `pending` = the proposed action is needed and available; `unknown` = need/completion/availability is unverified; `closed` = unavailable or not needed |
| `deadline_policy` | `remains_actionable` = evidence supports necessary available action after the deadline; `closes` = opportunity closes; `unknown` = late actionability unverified |

The validator rejects unknown fields/enums, bad types, absent evidence IDs,
fabricated quotes and cross-source citations atomically. It verifies structural
provenance, **not the semantic truth of an LLM's interpretation**. The synthesis
prompt therefore explicitly requires personal obligation/choice evidence:

- Receiving an advertisement or deadline email is not intent to apply.
- Portal/service registration is not an in-flight application or participation.
- A manaba unsubmitted-list row alone is not a personal requirement: optional
  surveys, representative-only submissions and practice can be listed too.
- Unknown submission/completion must not be called submitted or unsubmitted.
- Confirmed personal coursework and other obligations remain actionable when
  necessary work is still possible after the deadline.

Missing `eligibility` remains accepted for old receipts/rows, but fails closed
into review for **both Gmail and manaba**. Old priorities/titles are not evidence
of a commitment. No keyword blacklist or arbitrary task-count hiding is used.

## Plan response

`GET /plan`, completion, refresh and interest responses return the same plan
snapshot, with these lists:

- `tasks`: confirmed personal actionable work, plus explicit user-chosen actions.
- `review_tasks`: optional, unclassified, inferred, unknown-status, expired or
  closed records that have not been dismissed/resolved.
- `dismissed_tasks`: unresolved records the user explicitly marked 対象外.
- `resolved_tasks`: the existing independently evidenced resolution history view.

Every unresolved record appears in exactly one of the first three lists. IDs,
stored evidence, stable keys, completion and resolution events are retained.
Lists are not silently truncated. Each unresolved response item adds:

```json
{
  "actionability": "review",
  "review_reason": "unclassified",
  "user_interest": null
}
```

`actionability` is `actionable | review | dismissed`. `user_interest` is null
(no user decision), true (user selected), or false (user dismissed).
`review_reason` is null for actionable work, otherwise one of `unclassified`,
`optional`, `unknown_relevance`, `interest_unconfirmed`, `status_unknown`,
`expired`, `closed`, `dismissed`.

Review/dismissed priorities are projected as 3, never "today mandatory", without
rewriting stored priority. `eligibility` is absent on old unclassified rows.
`eligibility_checked_at`, when present, is server-supplied citation freshness
metadata, not an accepted model-write field. A stale/old-run citation makes the
task stale even if the deadline's primary record was fetched today. `fresh`
continues to describe source coverage and main-list freshness; it does not claim
that every review candidate is confirmed or that absent coursework is absent in
reality. Clients must retain source-gap/review notices even with no main tasks.

Without a user choice, only an actionable kind with `user_intent=confirmed` and
`action_status=pending` qualifies. An elapsed deadline also needs
`deadline_policy=remains_actionable`. Date-only deadlines last through the JST
calendar day; naive date-times use JST; explicit offsets are honored. Review is
computed on each read, so closed opportunities do not roll into today's main
list after midnight. No fabricated resolution event is created.

## Explicit user interest / dismissal

Authenticated route, under the same `/api/plugins/today_home` mount and optional
`?profile=...` query as the existing routes:

```http
PUT /tasks/{identity}/interest
Content-Type: application/json

{"interested": false}
```

- false: persist 対象外 separately from `done`, hide from both actionable/review
  lists, expose in `dismissed_tasks` for recovery.
- true: persist an explicit user choice and override model relevance/inferred
  interest. It does **not** overwrite the model citation or source status.
- A still-open optional task with known pending action can now be actionable.
- For unclassified legacy work with a future/null deadline, or selected
  unknown-status work, the actionable projection is a **verification action**:
  `verification_required=true`, priority 3, title prefixed `確認する：`, and reason
  explicitly saying submission/completion is unverified. The original payload
  remains unchanged. The optional flag is absent on ordinary records.
- Expired legacy opportunities and classified expired work without an explicit
  `remains_actionable` policy stay in review. Known closed work is not reopened.
- A second false/true call is idempotent. There is no implicit reset-to-null API.
- The route does not collect sources, start a model, submit/apply, or resolve work.

Both fields are strict booleans: strings/numbers/unknown body fields return 422.
Missing task returns 404 `task_missing`. The existing standard sensitive-route
admission, origin checks and profile resolution apply without modification.

User decisions are stored in the additive `task_preferences(task_id, interested)`
table, keyed by the existing stable task ID. Omission, regenerated title/reason,
model reclassification, restart and completion changes cannot reset them. The
model cannot write preferences. Synthesis is instructed to reuse the original
`task_key`, including for dismissed work; semantic key changes cannot be matched
reliably by title and are not guessed. As with existing tasks/cache, changing a
source account clears only that source's task preferences, preventing leakage to
another account. Disabling collection without an account change preserves them.

## Completion compatibility and deployment

`PATCH /tasks/{identity}` with `{ "done": true }` is unchanged. It changes only
local list completion, commits, then returns a snapshot. Isolated API tests also
GET the exact item and open a new PlanStore to verify persisted completion.
Completion is not dismissal, source submission, or evidence of application intent.
No backend route/method failure has been established for the reported UI symptom.

Older clients can ignore additive fields and receive the safer main list, but
need the matching UI to inspect review/dismissed records and choose interest.
The new interest endpoint requires this backend candidate; an old backend returns
404. Do not simulate success, submit external actions, or modify the old task to
fake a successful choice when that route is unavailable.

Deployment is not performed by these tests. Back up the target DB, place only
reviewed backend changes, reload the actual authenticated HTTP owner and Gateway
plugin/tool owner using the authorized mechanism, and verify authenticated GET,
completion readback, interest persistence and source-gap presentation on Mobile.
The additive table requires no purge or manual reclassification migration. With
no new evidence, existing unclassified records simply project into review.

Read-only source comparison found live `store.py`, `runtime.py`, and `api.py`
identical to the pre-change candidate. Live `sources.py` additionally recognizes
`未提出の課題` using `未提出(?:の)?課題`; that live-only parsing fix is outside this
change and must not be overwritten by blanket plugin-folder replacement.

## Local regression loop

Run from the candidate repository, using the managed interpreter and scratch
location (no credentials, network, browser, gateway/model, production DB or service
changes are needed):

```sh
TMPDIR=/var/lib/hermes/.hermes/cache/scratch \
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=server/today_home \
/var/lib/hermes/venv/bin/python -m unittest discover -s server/today_home/tests -v
```

`test_eligibility.py` covers legacy review without record mutation, same-title
classification, explicit choice/verification, persistent dismissal, strict
citations, stale evidence, optional expiry versus actionable overdue personal
work, source-account isolation, auth/origin checks, and completion readback.
Existing suites retain current-run resolution, receipt identity/immutability,
stable completion, selected-account and run authorization checks.
