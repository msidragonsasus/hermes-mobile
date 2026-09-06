# Roadmap and Acceptance Criteria

## 原則

- 大規模全面改修を避ける
- 各Phaseで動くAPKを維持
- protocolとUXの縦切りを先に作る
- Mini App Store等は後回し
- 各Phaseを実装計画の小さなPRへ分割。Phase全体を一つのPRにしない
- 文書整合とコード実装を分け、後続PR/Phaseへは依頼された範囲だけ進む

## Phase 0 — Baseline and Audit

### 作業

- fork/clone
- 実forkのHEAD確認・監査SHAとの差分確認。旧5c8ab7eへresetしない
- upstream参照先を記録。remote追加は必要になった工程で行う
- build/test/ktlint/lint確認
- Hermes接続経路確認
- current REST/WS code map
- current navigation map
- ADR追加
- secrets scan

### 完了条件

- baseline debug APKがbuild可能、または阻害要因が明記
- current tests結果
- 変更対象ファイル一覧
- risk register
- implementation plan

## Phase 1 — Foundation Vertical Slice

### 作業

1. `HermesTransport` interface
2. existing transport adapter
3. `FakeHermesTransport`
4. capability negotiation
5. domain event normalization
6. `AppContribution` registry
7. Today / Chat / Activity / Apps shell
8. existing admin screensをAdvancedへ保持
9. Attention model
10. approval/clarification UI
11. local test fixtures
12. offline Mini App manifest model/parser/validator・permission型と3 fixtures（remote取得・実行なし）

単一`:app`・手動DIを維持。既存replay/approval機構を再利用し、ログ・DB・送信/承認の
安全性ゲートは実装計画の順で解消する。Hilt/大規模module分割を必須にしない。

### 完了条件

- 4-tab shellが動く
- Chatの既存streamingが壊れない
- Activityで確認済みlive eventが表示（Fakeはtest/previewのみ）
- Attentionからapproval/clarificationへ遷移
- capability missing時にdisabled state
- existing management screensへ到達可能
- tests/ktlint/lint/build pass。監査で確認した既存失敗も解消・確認し、未実施を成功扱いしない
- 3 manifestのoffline検証成功。SDUI/Webをparseしても未対応の起動はdisabled

## Phase 2 — Modularization / DIの独立判断

### 作業

- 効果・依存・移行コストをADRで確認してからcore/feature `api/impl` 抽出
- Navigation3 contribution継続。Hilt multibindingは候補であり導入済みでも必須でもない
- 互換性を確認してhardcoded ScreenRegistry wrapperを段階移行
- test fixtures共有

### 完了条件

- `:app` がfeature implementationへ直接依存しすぎない
- cross-feature navigationはapi moduleだけ
- circular dependencyなし
- build time regression記録

## Phase 3 — SDUI Mini App MVP

### 作業

- Phase 1のmanifest parser/Schema fixturesを再利用し、SDUI契約へ拡張
- permission broker
- discovery endpoint
- SDUI renderer
- action dispatcher
- Career sample
- Apps catalog/enable flow

### 完了条件

- sample manifest 3件validation
- Career appをAPK更新なしで有効化
- Today card contribution
- global command contribution
- unauthorized action blocked
- malformed documentがhostをcrashさせない

## Phase 4 — Web Mini App Host

### 作業

- isolated WebView
- allowlist
- scoped token
- message protocol
- navigation interception
- file broker
- crash/timeout handling

### 完了条件

- untrusted origin blocked
- token leakage test
- external link system browser
- permission revocation反映
- host crashなし

## Phase 5 — Android Integration

- share intent
- voice
- camera
- notification actions
- widget
- app shortcuts
- reliable push abstraction

## Phase 6 — Domain Mini Apps

- Career
- University
- Development
- Personal Ops
- Investment research-only

## 品質ゲート

コードPRは影響範囲に応じて以下を実行。文書だけのPRはリンク・Schema・文書間整合・差分範囲を確認し、同一HEADの監査結果を日付付きで参照する。

```text
./gradlew ktlintCheck
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

CIに既存の追加jobがある場合はそれもpass。

## Core test matrix

### Transport

- reconnect
- duplicate event
- out-of-order event
- capability missing
- auth expiry
- unknown execution state
- backend restart

### Attention

- request
- expire
- respond
- duplicate notification
- stale response
- run already completed

### Mini App

- valid manifest
- invalid ID
- unsupported schema
- permission mismatch
- blocked origin
- malformed SDUI
- unknown component
- disabled app contribution removal

### UX

- process recreation
- rotation
- large font
- TalkBack labels
- offline/reconnect
- notification deep link
- scroll follow/unfollow

## Release criteria for personal v0.1

- one real Hermes gateway
- one Android phone
- Tailscale outside-home connection
- 7日間連続で重大なstate lossなし
- approval flowが誤Runへ返答しない
- secretがdiagnosticsへ出ない
- crash-free manual daily use
