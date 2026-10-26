# Explicit action-classification owner job

Status: the production invocation path is implemented and synthetically tested. It is not deployed or live-runtime verified. No live mail, model, key, denied database or production service was accessed during implementation. v13 UI/APK is unchanged.

The real entry point is career_core.action_classification_job: it obtains the profile's existing ExecutionPolicy.daily execution (or a complete explicitly selected provider/model/reasoning triplet), validates it against the configured authorized catalogue, constructs HermesActionClassifier with the existing Hermes runtime and calls classify_saved_mail_actions once. There is no GET invocation, collector hook, daemon, retry loop or new recurring job.

## Owner command

Run only as the already-authorized Career data owner, using the same Python environment and existing provider configuration as Hermes. From the installed Career plugin directory:

    cd /var/lib/hermes/.hermes/plugins/career
    python3 -m career_core.action_classification_job \
      --data-dir /var/lib/hermes/career \
      --profile default --limit 5 --scan-pages 3 --execute

The command attaches to an existing database without schema initialization or migration. A wrong/missing path stops instead of creating an empty DB. --execute is required before any database opening/model selection. --help is safe and does not start runtime or open the DB. The operator's process uses existing permissions; this is not a permission-changing helper.

The saved profile execution must be configured already. If selecting another existing authorized model explicitly, provide all three --provider, --model and --reasoning-effort values from Hermes' current configuration; the policy rejects unsupported models/efforts. There is no Luna name restriction, new provider, key installation or fallback. Default environment selection is used only if existing policy has no saved profile execution; it must still validate.

--limit is required and restricted to 1..10 model classifications per invocation. --scan-pages is 1..10 (default 3), each Home page at most 100 entries. The existing Home reader still constructs its saved-source projection; the page cap limits selection traversal, not every internal SQL read. Model source text above 24,000 characters abstains. Unknown/ambiguous dates are skipped without model transmission. Only current actionable mail in the Home window is eligible.

## Receipt and failure contract

Stdout is one JSON receipt with status, validated execution and aggregate counts. Source IDs, bodies, quotes, raw model output, URLs, credentials and exceptions are not printed by the job. Python runtime prints are discarded. Existing Hermes runtime must retain its own no-payload logging contract.

Successful counts include classified, matched, abstained, cache_results_committed, source_messages_modified (always zero), scanned_items, scan_pages and scan_limited. scan_limited means the configured page cap prevented inspecting remaining pages; classified=0 with scan_limited=true is not proof that all mail was processed. A later explicitly authorized run can choose a larger bounded page cap. Repeating a successful batch skips already validated matched/abstained results for unchanged source versions, so it makes no new classification requests for them.

Every response is validated against original ID/version, exact quote spans, literal program/cohort/round, permitted action, deadline and confidence. After all model calls, one BEGIN IMMEDIATE transaction rechecks current Home eligibility, ID/version/target/deadline plus current account/company and full subject/body hash before committing cache results. Changed, resolved, closed, expired or invalid sources reject the entire batch. Failed/partial responses commit no classification results, including earlier successful responses. Calls already made cannot be undone; the small explicit limit bounds that work.

Source mail, IDs, body, deadline data, completion receipts and prior version cache history remain intact. Home GET only reads the saved, revalidated action_identity; unchanged v13 client grouping consumes its key and evidence. Different subjects/reminder bodies can group semantically. Different rounds/actions/deadlines remain distinct even when sharing a form URL. No key/URL heuristic grouping is added.

## Deployment and first-run plan (not executed)

1. Review this patch against the existing v13 source snapshot. The release allowlist now includes the job, action classifier, mail deadline correction and synthetic tests/documentation. Use the existing code-only release packaging workflow; do not replace user data or credentials.
2. An authorized owner stages/backups code and applies the reviewed modules in the installed plugin using the existing deployment procedure. Both the Home projection from v13 and its action-classification/mail-correction dependencies must be present. Do not weaken permissions or create a new provider.
3. Verify import/help with the existing Hermes Python environment and confirm the saved profile execution through the normal authorized model-selection UI. This implementation does not establish actual catalogue availability or successful inference.
4. Parent/user separately authorize any required service reload to load the updated Home projection. A CLI process alone does not refresh an already-running dashboard's imported modules.
5. Parent/user separately authorize one small live command, initially --limit 1. Check its aggregate receipt and resulting Home grouping. Only then consider another explicitly authorized bounded batch. Do not wire it into GET or a scheduler automatically.

Synthetic coverage uses the real ExecutionPolicy with a fresh fake configured catalogue and a fake model factory: stored same-subject and different-subject reminders, distinct rounds/actions, refusal to extend, version changes, atomic failure/partial/invalid batches, current company/source changes, scan caps, CLI authorization/errors, cache idempotence and read-only Home projection. Live runtime remains unverified until this deployment/authorized first-run sequence succeeds.
