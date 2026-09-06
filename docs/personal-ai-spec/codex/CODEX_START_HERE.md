# Codex Start Here — そのまま渡す開始指示

あなたは `Hy4ri/hermes-mobile` を基盤に、
Hermes Agent専用のPersonal AI Shellを設計・実装するシニアAndroidエンジニアです。

## 最初に確認すること

対象リポジトリのルートで、まず以下を報告してください。

```text
pwd
git remote -v
git branch --show-current
git rev-parse HEAD
git status --short
```

採用済みfork baselineは次です。

```text
repository: msidragonsasus/hermes-mobile
upstream: Hy4ri/hermes-mobile
branch: dev
reference commit: fd485a69990b358f81d8dd4c12c761e7d2555d24
license: Apache-2.0
```

実際のworking treeが異なる場合、想定へ無理に戻さず差分を報告してください。

## 必ず読むもの

1. 既存root `AGENTS.md`
2. 既存root `DESIGN.md`
3. このパッケージの `README.md`
4. `00_DECISION.md`
5. `PRODUCT_SPEC.md`
6. `UX_SPEC.md`
7. `ARCHITECTURE.md`
8. `MINIAPP_PLATFORM.md`
9. `SECURITY.md`
10. `ROADMAP.md`
11. `DESIGN_REVIEW.md`
12. `adr/*.md`
13. `codex/AGENTS_ADDENDUM.md`

既存 `AGENTS.md` と本仕様が矛盾する場合、勝手にどちらかを無視せず報告してください。

## 製品の核

これはChatGPTクローンではありません。

```text
Today / Chat / Activity / Apps
```

を中心に、次を最短で扱うAndroidアプリです。

- 雑な依頼
- 長時間Run
- 実行状況
- 人間待ち
- 承認
- Clarification
- 通知
- 就活・大学・開発等のMini App

Hermes Agentがオーケストレーターとsource of truthです。
Android側に第2のAIルーターを作らないでください。

## 拡張方針

Mini Appは三層です。

1. Native Feature
2. SDUI Mini App
3. Isolated Web Mini App

remote DEX/JAR/SO loadingは禁止です。

## 通信方針

新規UI/ViewModelからHermes endpointを直接呼ばないでください。既存直接呼出は実装計画のadapterから段階移行し、全面置換しないでください。

```text
HermesTransport
  ├ DashboardRpcTransport
  ├ ApiServerTransport
  └ FakeHermesTransport
```

起動時はprotocol別のread-only capability negotiationを行い、
Hermes versionや利用可能機能を推測しないでください。

`NousResearch/hermes-agent#84406` の最新状態を確認し、
Runs、session continuity、approvalの互換性を検証してください。

## 作業の選択と停止点

未監査のリポジトリでだけ `codex/CODEX_00_AUDIT_AND_PLAN.md` を使用します。
採用済み監査があれば実HEADとの差分から再確認範囲を決め、監査全体を繰り返しません。
Git外の親や`.audit`と製品checkoutを混同しないでください。

実行範囲はユーザーが指定したPRだけです。PR 00は文書整合のみで、
アプリコード・Gradle・DB・署名・依存を変更しません。
[実装計画](../../personal-ai/IMPLEMENTATION_PLAN.md) に従い、push/PR公開前で停止します。

作成物:

```text
docs/personal-ai/AUDIT_REPORT.md
docs/personal-ai/IMPLEMENTATION_PLAN.md
docs/personal-ai/RISK_REGISTER.md
```

`codex/CODEX_01_FOUNDATION_VERTICAL_SLICE.md` は次の明示依頼時の参照です。
Phase 1は単一`:app`・手動DI・Room 3を維持し、実装計画の小PRへ分けます。
この開始文書自体は後続実装や公開の承認ではありません。

## 禁止

- blank projectへの置換
- big-bang modularization
- GPL code copy
- remote native code loading
- endpointの捏造
- public exposure of Hermes
- secretのsource/log保存
- autonomous external writes
- 後続Phaseの無断実装

## 最終報告

1. actual baseline
2. build/test結果
3. current architecture map
4. highest-risk finding
5. recommended first PR
6. 作成文書
7. 本当に必要なblockerだけ
