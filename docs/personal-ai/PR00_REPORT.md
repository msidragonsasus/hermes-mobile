# PR 00 — 文書・仕様の整合

確認日: 2026-09-06  
状態: ローカルの文書変更のみ。commit・push・PR公開は未実施。PR 01以降は未着手。

## 作業checkoutとHEAD

```text
pwd / git rev-parse --show-toplevel:
E:/OSHIIRE/software/Hermes/hermes-mobile

git remote -v:
origin https://github.com/msidragonsasus/hermes-mobile.git (fetch)
origin https://github.com/msidragonsasus/hermes-mobile.git (push)

git branch --show-current:
docs/personal-ai-audit

git rev-parse HEAD:
fd485a69990b358f81d8dd4c12c761e7d2555d24

開始時 git status --short:
（空、clean）
```

親 `E:/OSHIIRE/software/Hermes` はGit管理外。既存の製品checkoutが見つからなかったため、
ユーザーforkを上記の新しい作業ディレクトリへcloneし、devから文書用branchを作成した。
`git ls-remote` で確認したforkのHEAD/devと、採用監査のSHAは一致する。
旧5c8ab7eへのreset、監査checkoutの製品化、親フォルダでのGit初期化は行っていない。
upstream参照先は `Hy4ri/hermes-mobile` だが、今回のoriginはユーザーforkだけ。

親の `docs/personal-ai-spec/` と `docs/personal-ai/` は採用監査時の原本として保持し、
コピーした製品checkout側だけを更新した。`.audit`には監査・検証素材があり、
製品の変更・成果物をそこへ作成していない。

## 変更した文書

既存root文書4件を部分更新:

- [AGENTS.md](../../AGENTS.md): 必須作業ルールと資料参照、実構成、Scaffold/ログ/公開手順の矛盾解消。
- [DESIGN.md](../../DESIGN.md): 既存Compose / Material 3を継承し、共通状態/承認/質問/成果物UIとアクセシビリティを定義。
- [README.md](../../README.md): 計画への入口と、古い技術・テーマ一覧を現catalogへ訂正。
- [CONTRIBUTING.md](../../CONTRIBUTING.md): UI仕様参照、文書PRの検証、公開承認・FAB例外をrootルールと整合。

`docs/personal-ai-spec/` を取り込み、次の原本文書を更新:

| 文書 | 整合した内容 |
|---|---|
| [README.md](../personal-ai-spec/README.md)、[QUICKSTART.md](../personal-ai-spec/QUICKSTART.md)、`package-index.json` | fork/実SHA、開始手順、PR 00での停止、資料一覧 |
| [00_DECISION.md](../personal-ai-spec/00_DECISION.md)、[ARCHITECTURE.md](../personal-ai-spec/ARCHITECTURE.md) | 単一`:app`・手動DI・Room 3、限定capability、既存replay、検証済みfallback、非破壊移行 |
| [UX_SPEC.md](../personal-ai-spec/UX_SPEC.md)、[MINIAPP_PLATFORM.md](../personal-ai-spec/MINIAPP_PLATFORM.md)、[SECURITY.md](../personal-ai-spec/SECURITY.md) | ホストUI継承、once/deny・固定要求・ACK、offline draft、Schemaに従う未知field拒否、秘密ログ禁止 |
| [ROADMAP.md](../personal-ai-spec/ROADMAP.md)、[DESIGN_REVIEW.md](../personal-ai-spec/DESIGN_REVIEW.md)、[SOURCES.md](../personal-ai-spec/SOURCES.md) | Phase 1/2/3境界、実態と将来候補の区別、未確認事項の扱い |
| `adr/0001-use-hermes-mobile-fork.md`、`0002-transport-abstraction.md`、`0003-three-tier-miniapps.md` | 上記の既存決定を現実態へ訂正 |
| `codex/CODEX_START_HERE.md`、`CODEX_00_AUDIT_AND_PLAN.md`、`CODEX_01_FOUNDATION_VERTICAL_SLICE.md` | 監査の不要な再実行を避け、依頼されたPRだけを進める。Hilt必須や未検証fallbackを除去 |
| `codex/AGENTS_ADDENDUM.md` | 重複ルールの追記方式を廃止し、root/製品仕様への参照に整理 |

追加ADR: [0004-audit-aligned-foundation.md](../personal-ai-spec/adr/0004-audit-aligned-foundation.md)。
各矛盾の旧記述・採用方針・変更理由・根拠をまとめた。

採用監査文書3件も取り込んだ。[AUDIT_REPORT.md](AUDIT_REPORT.md) は歴史的観測を保持して
現在の作業記録への注記を追加。[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) と
[RISK_REGISTER.md](RISK_REGISTER.md) はPR 00の状態・文書上の対処だけを更新した。
この報告書も追加した。

`PRODUCT_SPEC.md`、`codex/CODEX_BACKLOG.md`、Schema、3つのmanifest例、既存
`VALIDATION_REPORT.json` は原本のまま取り込んだ。検証記録の転記を今回のAndroid再試験と扱わない。

## 主要な解消点

- **実態**: Hilt導入済み・Room 2という前提を除去し、Phase 1は単一`:app`・手動DI・Room 3とした。
- **デザイン**: 既存theme tokensと共通Scaffoldを維持。Today / Chat / Activity / Appsは目標と明記。
  人間待ちを優先し、通信状態とRun状態を区別する。共通部品の再利用、日本語・文字拡大・TalkBack・
  片手操作・IME/insetsを統一規則とし、既存Chatの「最新へ」操作は保持する。
- **二重規則**: AGENTSの状態表示だけpaddingを加える例を訂正。全resourceにCRUDを要求する記述、
  debug本文ログ許可、常時許可や自動再送を容認し得る仕様を解消した。コードの対処は未着手。
- **段階性**: Mini Appのoffline parser基盤はPhase 1、discovery/SDUIはPhase 3、WebはPhase 4。
  Hilt/module抽出はPhase 2の独立判断。監査採用からTask 01へ自動的に進まない。

## 検証

文書差分に必要な以下だけを確認した。root文書4件の更新と、新規文書30件（仕様JSONを含む）に限定されている。

- 実root/origin/branch/HEAD/clean開始を確認。fork devと採用監査SHAが一致。
- ローカル参照87件、package indexの全掲載ファイル、UTF-8、code fenceの確認に成功。既存root文書のBOM/改行形式も保持。
- Draft 2020-12 + FormatCheckerでSchemaと3 manifestの検証に成功。Schema/例は原本とbyte一致。
- rootのDESIGN token定義はdescription以外のfront matterを保持。
- `git diff --check` 成功。Git差分と未追跡ファイルはMarkdownと仕様JSONのみ。アプリコード、Gradle、DB/schema、署名、依存に差分なし。staged filesも0件。

Androidビルド・単体テスト・Lint・ktlintは再実行していない。採用監査では同じSHAで
build/lint/ktlint/release compile成功、unit 1,275件中2件失敗、Windows color guard失敗。
これらを今回修正・再成功したとは扱わない。CI起動・実機インストール・実Hermes接続も未実施。

## 残る未確認事項と停止点

- 実稼働Hermes版・接続認証・実機の署名/version/DB/鍵の連続性。
- #84406と関連PRは採用監査（2026-09-06）のOpen観測を保持。今回は再照会していない。
  API継続Run/approval有効化前に最新状態と稼働版での互換性を確認する。
- 承認binding、秘密ログ、DB削除経路、offline queue、再同期不足は未修正。
- Windowsのunit 2件・color guard失敗、background通知、process recreationなどの実機保証。

文書PRを妨げる未回答事項はない。上記は後続の対応工程のゲートであり、
今回の文書変更で解消済みとしない。変更文書・解消した矛盾・残る未確認事項を報告し、
commit・push・PR公開・Task 01以降に進まず停止する。
