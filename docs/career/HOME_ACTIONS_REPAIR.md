# Career Home action feed repair

## Verified source-level cause

The shell Home and Career Home already use `CareerHomeProvider` to read the same
existing `/api/plugins/career/home` response. The shell renders every server
`category == action` item. Career Home previously retained only actions with
`target.kind == conversation`; its other section displayed results only.
Deadlines, company actions and mail actions from that same response therefore had
no render path in Career Home.

Career Home's agenda was built independently from `CareerSnapshot.entities`.
An action present only in the Home response cannot appear in that agenda.
The established calendar filter intentionally hides dates before today in Japan;
it is not evidence that an unresolved action has been completed.

There was also a refresh difference: shell Home reads on resume and every minute,
whereas Career Home depended on an explicit refresh key or a changed snapshot
value. A lifecycle refresh returning an equal snapshot could leave the Home
response stale.

These are findings from the authorized source snapshot and synthetic fixtures.
The user's live response and device have not been inspected, so they establish
code defects, not a confirmed diagnosis of every item missing on that device.

## Changes

- Preserve all server-selected career actions using the same `homeItems` function
- Keep conversation actions in the existing answer/decision section
- Render the previously dropped actions through the shared `HomeEntry` and
  existing scoped `HomeDetailKey` route, including deadline and overdue labels
- Keep future/past/completed/replaced-offer rules for the separate agenda intact
- Read the Home feed independently on resume, explicit refresh, snapshot change
  and every 60 seconds while resumed; no background worker or new API is added
- Preserve the last successful page after read failure, expose the stale/error
  condition, and never infer an empty confirmed agenda from an unavailable feed

The server remains the authority for whether an action is still pending. The
client does not mark an action completed, invent a deadline time, or suppress a
server action using an unrelated local entity snapshot. An action and a scheduled
calendar entry may refer to the same underlying record but remain different views.

## Synthetic verification

`CareerHomeFeedTest` compares action IDs from the same response in both Home
pipelines, including company, mail, settings and conversation targets. It covers
date-only, UTC-to-Japan boundary, unknown and overdue dates, other-provider/result
filtering, response replacement after completion, repeated refresh without any
entity snapshot input, error retention and cancellation. Existing
`CareerAgendaTest` preserves completed/replaced/past calendar exclusions.

`CareerHomeActionsTest` uses a single synthetic HomePage in both actual Compose
Home presentations, with an empty entity snapshot. It checks that the deadline
is visible, opens its exact HomeItem, and that unavailable data is not presented
as no upcoming work. Android execution results belong in the integration report;
no live server calls or external writes were made by this repair.
