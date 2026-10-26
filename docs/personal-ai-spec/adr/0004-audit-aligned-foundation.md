# ADR-0004: 採用監査に合わせたPhase 1基盤とホストUIの継承

Status: Accepted（文書方針。製品への実装は後続PR）  
Date: 2026-09-06  
Scope: PR 00 — 文書・仕様の整合のみ

## Context

ユーザーがTask 00の監査結果を採用し、PR 00だけを依頼した。
実forkは `msidragonsasus/hermes-mobile`、devの監査SHAは
`fd485a69990b358f81d8dd4c12c761e7d2555d24`。PR 00開始時のremote devも同じSHA。
旧仕様の5c8ab7eに合わせて実装を戻す必要はない。

監査は [AUDIT_REPORT.md](../../personal-ai/AUDIT_REPORT.md)、作業場所・変更一覧・検証は
[PR00_REPORT.md](../../personal-ai/PR00_REPORT.md) に記録する。

## Decision and Reasons

| 旧記述・衝突 | 採用する方針 | 変更理由・根拠 |
|---|---|---|
| 5c8ab7eが作業baseline、upstreamをoriginとする開始手順 | ユーザーforkの実HEADを確認し、feature branchで作業。旧SHAは比較履歴 | 採用監査とPR 00のremote確認。Git外の親・`.audit`は製品checkoutではない |
| Hilt採用済み、contributionに `@IntoSet` を要求 | Phase 1は単一`:app`、手動登録・constructor injection。Hilt/module抽出はPhase 2の独立判断 | `settings.gradle.kts`、version catalog、既存factoryに合わせる。不要な依存変更を避ける |
| root AGENTS/READMEのRoom 2・古いKSP等 | Room 3.0.2、KSP 2.3.11、schema v6という現状を記述 | `gradle/libs.versions.toml`、`app/build.gradle.kts`、`HermesDatabase.kt`。依存自体は変更しない |
| keyboard-firstの開発者管理UIが日常画面にも優先 | Compose / Material 3のテーマ・共通部品を継承し、Today / Chat / Activity / Appsを目標とする | ユーザー指定。管理機能はAdvancedへ保持し、人間待ちを優先する。既存UIの全面置換は不要 |
| 画面別のstatus/承認/質問/成果物表示が増える余地 | ホスト共通部品と状態意味をToday/Chat/Activity/Mini Appで共有する | 一貫した承認と可読性を保ち、独自色・余白を増やさない。通信状態と実行状態を別軸にする |
| 密度・固定サイズ・chip横一列が全内容を拘束 | 既存tokensを維持し、日本語、font scaling、TalkBack、48dp、片手操作、IME/insetsを優先 | 対象・影響の欠落を防ぐ。filter chipと長い質問選択肢は同じlayout制約ではない |
| AGENTSだけLoading/Error/EmptyへpaddingValuesを再適用 | HermesScaffold内は全状態で再適用しない | `HermesScaffold.kt` はcontent呼出前にside/bottomとdynamic topを処理。DESIGNの原則と統一 |
| FAB全面禁止と既存ChatScrollFab | 新規FABを作らず既存「最新へ」操作を維持 | 無関係なChat回帰を避けるため例外を明記。新操作は共通top-bar/fixed dock |
| 全resourceにCRUDを要求 | 検証済みcapabilityと権限がある操作だけ表示/有効化 | 未確認endpoint・writeの捏造を防ぐ。read-only resourceはread-onlyでよい |
| debugならBODY logging可能 | debug/releaseとも秘密値・本文の診断出力は禁止 | 現RESTはBASIC/NONE、WS全文・ticket経路は未修正のリスク。文書訂正は修正完了を意味しない |
| root AGENTSへ製品仕様を追記するaddendum | rootは必須作業ルールと参照、DESIGNは共通設計、個別仕様は製品契約 | 重複によるHilt・承認・UI規則の矛盾再発を防ぐ。addendumは互換リンク集へ整理 |
| 起動時に全health/capabilityを共通照会、Booleanのみ | protocol別read-only probe、限定handshake、Unknown/Unsupported/Supported/Blocked | Dashboard `/api/health` とAPI Server `/health` 等は別surface。gateway.ready/capabilitiesは包括一覧ではない |
| REST/SSE fallbackを無条件に利用 | 送信前に検証済み機能を選択。送信結果不明時に切替/再submitしない | #84406とruns継続/承認の契約が未確認。広告と実証を分ける |
| approval/replayを新設する前提 | forkのrequest ID/pending/received、batch質問、seq/epoch/replayを再利用 | 再実装は不要。固定RequestRef、truncated同期、Responding/ACKの不足を別PRで補う |
| 既存session/always承認、汎用offline queueと新仕様の衝突 | 新policyはUI/transportでonce/deny、offline writeはdraft/再入力。自動flush禁止 | ユーザーの判断対象と時点を固定する。Modify専用endpointは仮定しない。server側policy確認も必要 |
| 現DBの削除/fallbackとデータ保持 | 自動delete・destructive再生成で回復しない。元DB/鍵を保持して停止 | 既存データを保持する依頼が優先。実機更新前のDB保護PRは引き続き必要 |
| manifest parserはPhase 3、Task 01には基盤必須 | Phase 1はoffline model/parser/validator/permission型と3 fixtures。SDUI/discoveryはPhase 3、WebはPhase 4 | Task 01の最小基盤とROADMAPを一致させる。組込Native IDへ解決し動的codeを読まない |
| 未知manifest fieldを無視 vs SchemaのadditionalProperties:false | Schemaが許可しない未知fieldを専用camelCase decoderで拒否。意味検証も行う | 既存通信用decoderの流用を避け、Schema自体は変更せず本文を合わせる |
| 監査後にTask 01へ直行、全PRで全ビルド、pushを作業手順として自動実行 | ユーザー指定の小PRだけ進める。文書PRは差分・参照・Schemaを検証し、既存試験結果を日付付きで再利用。公開は別途承認 | 今回の文書限定・検証再実行禁止・push/PR公開禁止に一致させる。PR経由でdevへ統合する原則は維持 |

## Consequences

- 既存AGENTS/DESIGNを白紙化せず、navigation guard、drawer所有、共通Scaffold、
  semantic colors、テーマ、コードスタイル、署名・データ保護、PR経由の統合を維持する。
- READMEの技術一覧も現catalogと整合する。これは依存の更新や性能改善ではない。
- 仕様は目標、監査は観測、PR 00は文書変更として区別する。4-tabや安全性対策が
  実装済みになったとは記載しない。
- 実Hermes版・認証・端末・#84406の将来変化、承認binding/ログ/DB/queueの修正、
  Windowsテスト失敗は後続の該当ゲートへ残す。PR 00を理由に安全性制約を解除しない。
- 今回は文書差分の報告で停止し、コード実装・commit・push・PR公開へ進まない。
