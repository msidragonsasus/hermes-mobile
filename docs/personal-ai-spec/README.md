# Personal AI Shell — Hermes Android専用クライアント設計パッケージ

作成日: 2026-08-31  
状態: 採用監査に基づくPR 00文書整合（2026-09-06）。製品実装は未着手  
想定利用者: 単一ユーザー  
想定ホスト: 自宅の中古ノートPC上で常時稼働するHermes Agent  
想定クライアント: Android（日本語優先）

## 1. このパッケージの結論

新規にチャットアプリをゼロから作るのではなく、Apache-2.0の既存Androidクライアント
`Hy4ri/hermes-mobile` をフォークし、その上に次の2層を追加する。

1. **Personal AI Shell**
   - Today / Chat / Activity / Apps の4つを中心にした、日常利用向けの操作面
   - Hermesの細かい管理画面は「Advanced」に残す
   - チャットよりも「現在何が動いているか」「人間の判断が必要か」を重視する

2. **Mini App Platform**
   - 就活、大学、開発、投資などを独立した「アプリ内アプリ」として追加できる
   - 任意のDEX/JARを後から読み込む方式は採用しない
   - 拡張方式は以下の三層とする
     - APKに組み込むNative Feature
     - サーバー駆動の宣言型UI（SDUI）
     - 隔離したWeb Mini App

Hermesはエージェント実行・ツール・メモリ・スケジュールを担当し、
Androidアプリは表示・入力・承認・通知・ローカルキャッシュを担当する。
アプリ側で独自のAIオーケストレーターを作り直さない。

## 2. ベース実装

推奨ベース:

- Repository: `msidragonsasus/hermes-mobile`（upstream: `Hy4ri/hermes-mobile`）
- Branch: `dev`
- Audited base commit: `fd485a69990b358f81d8dd4c12c761e7d2555d24`
- 旧5c8ab7eは監査の比較点。reset先ではない
- License: Apache License 2.0
- Stack: Kotlin / Jetpack Compose / Material 3 / Navigation 3 / Room 3.0.2 + SQLCipher / Retrofit / OkHttp WebSocket。Hilt未導入、Phase 1は手動DI
- 現状は単一Gradleモジュール `:app`
- 現状の画面登録は大きなハードコード済み `ScreenRegistry.ALL_SCREENS`

比較対象として `adebnar/hermes-android` には優れたMission Control系UXがあるが、
GPL-3.0のためコードを混ぜない。画面の考え方だけを参考にする。

## 3. 先に読む順番

最初にリポジトリの [AGENTS.md](../../AGENTS.md) と [DESIGN.md](../../DESIGN.md) を読む。
UI変更は `UX_SPEC.md` の該当フローも必須。各文書の全文をAGENTSへ転記しない。

1. `00_DECISION.md`
2. `PRODUCT_SPEC.md`
3. `UX_SPEC.md`
4. `ARCHITECTURE.md`
5. `MINIAPP_PLATFORM.md`
6. `SECURITY.md`
7. `ROADMAP.md`
8. `DESIGN_REVIEW.md`
9. `codex/AGENTS_ADDENDUM.md`
10. `codex/CODEX_00_AUDIT_AND_PLAN.md`
11. `codex/CODEX_01_FOUNDATION_VERTICAL_SLICE.md`

## 4. Codexへの渡し方

採用済み監査 → PR 00文書整合 → 承認された小さなコードPRの順。
[実装計画](../personal-ai/IMPLEMENTATION_PLAN.md) が分割と停止点の正本。
同じHEADの監査全体や成功済みテストを理由なく再実行しない。

### 第1回

Codexを対象リポジトリのルートで起動し、
`codex/CODEX_00_AUDIT_AND_PLAN.md` の全文を依頼として渡す。

この回は原則として読取・検証・計画のみ。
現行Hermes API、ビルド可否、変更箇所を実コードで確認する。

### 第2回

PR 00を確認後、実装計画から次のPRを明示して依頼する。
`codex/CODEX_01_FOUNDATION_VERTICAL_SLICE.md` はPhase 1全体の参照で、
監査の採用やこの文書を読むだけでは実装を許可しない。

この回で実装するのは基盤の縦切りだけ。
全ミニアプリ、WebView、FCM、音声、ウィジェットまで一気に作らせない。

## 5. 未確定事項

以下は実装を止めるほどではないため、暫定値で進める。

- 製品名: `Personal AI Shell`（仮称）
- applicationId: 既存値を保持。将来変更も当然視せず、データ・署名互換性と別途明示承認が必要
- 配布: 最初は署名済みAPKのサイドロード
- 接続: 最初はTailscaleまたは信頼できるLAN
- Push通知: v0.1は既存WebSocket/Foreground Service、後でFCM等を抽象化
- 複数ユーザー/マルチテナント: 初期対象外
- iOS: 初期対象外
- Play Store公開: 初期対象外

## 6. 完了の定義

設計段階の完了条件:

- 実装基盤が選定されている
- 主要画面と主要導線が定義されている
- Hermesとの通信境界が抽象化されている
- Mini Appの拡張方式と権限モデルが定義されている
- 安全性と非目標が明示されている
- Codexが作業を開始できる段階的プロンプトがある
- JSON Schemaとサンプルmanifestが検証可能である
