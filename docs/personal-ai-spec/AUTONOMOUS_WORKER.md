# Autonomous Worker 実装仕様（M1a 契約）

2026-11-15 · 企画書「Hermes Agent 自律ワーカー化」の M1 実装契約。
本書が Hermes Agent フォーク・`server/mobile_worker` プラグイン・Android アプリの間の唯一の契約。
変更は本書を先に直してから実装する。

## 0. 決定事項

- Hermes Agent 本体はフォーク専用で改造してよい（上流 PR は想定しない）。ただし本体変更は
  **プラグインで実現できないものだけ**に限る。調査の結果、本体変更は §3 の cron エスクロー 1 点。
- 通知は FCM の「中身なしの合図」＋ Tailscale 経由の本文取得（§4）。
- 最初の業務ジョブは `today_home` の朝実行。
- 承認の正本は Hermes 側。アプリは表示と応答だけを行い、判定をしない。

## 1. 既存機能（作り直さない）

| 必要なもの | 既存実装（フォーク `feat/plugin-client-surfaces` 基準） |
| --- | --- |
| 内容に束縛された承認 | `hermes_cli/approval_transport.py`（`ApprovalRequest.digest`、`request_id`）、`tools/approval_gateway_wait.py` |
| 会話中の承認カード | アプリ `approval` server request / `approval.respond` / `approval.pending` 再送（`EventParser.kt`） |
| 任意ツールを要承認に格上げ | `pre_tool_call` フックが `{"action": "approve", "message", "rule_key"}` を返す（`hermes_cli/plugins.py`） |
| サブエージェントの承認 | `delegation.subagent_auto_approve: false`（既定） |
| メモリ書き込み承認 | `memory.write_approval`（`tools/write_approval.py`） |
| cron 一覧・停止・再開・削除 | WS `cron.manage`（`action: list/add/remove/pause/resume`）、イベント `cron.changed` |
| サブエージェント一覧・中断 | WS `subagent.list` / `subagent.interrupt` / `subagent.roster` |
| 無変化の結果を送らない | cron 出力 `[SILENT]` 規約 |
| 配送先プラグイン | `ctx.register_platform(name, ...)` ＋ `BasePlatformAdapter.send()` |
| アプリ向け REST | ダッシュボードプラグイン `dashboard/plugin_api.py` の `router`（`server/today_home` と同じ形） |

**欠けているもの**: (a) cron 中に要承認操作が来ると `approvals.cron_mode: deny` で黙って止まる、
(b) アプリ宛ての配送先と受信箱、(c) アプリが閉じているときの通知。

## 2. 全体構成

```
cron job ─┬─ 結果 ──────────▶ deliver: mobile ─▶ [mobile_worker platform] ─▶ inbox(SQLite) ─┐
          └─ 要承認操作 ─▶ [core: cron escrow] ─▶ hook cron_escrow_created ─▶ inbox(approval) ┤
                                                                                              ▼
                              FCM data-only {t, v, seq}  ◀── push sender ◀──────────── inbox insert
Android ──(Tailscale)── GET /api/plugins/mobile_worker/inbox?since=seq ──▶ ローカル通知を組み立て
        ──(Tailscale)── POST .../escrow/{id}/decision {decision, digest} ──▶ core resolve_escrow
```

プロセス境界: cron とプラットフォームはゲートウェイプロセス、REST はダッシュボードプロセス。
両者は同じプロファイルの `HERMES_HOME` 配下のファイル（SQLite / JSON, 0600）だけで連携する。

## 3. 本体変更: cron エスクロー（担当 Sol, リポジトリ `msidragonsasus/hermes-agent`）

### 3.1 設定

`approvals.cron_mode` に第 3 の値 `escrow` を追加する（既定は `deny` のまま）。
`_binary_approval_mode("cron_mode")` は `escrow` を受理するように拡張し、不正値は従来どおり `deny`。

### 3.2 動作

cron 文脈（`HERMES_CRON_SESSION`）かつ `cron_mode: escrow` で、次のいずれかが起きたとき:

- 危険コマンド判定（`_unattended_deny` の対象）
- `pre_tool_call` の `approve` 格上げ

実行せずに **EscrowRecord** を作り、エージェントへは「承認待ちとして保留した（id）。代替手段を探さず、
保留したことを結果に書いて終了せよ」という block 結果を返す。

```json
{
  "schema_version": 1,
  "id": "esc_<uuid hex>",
  "status": "pending | approved | denied | expired | executed | failed",
  "job_id": "...", "job_name": "...", "execution_id": "...", "profile": "...",
  "kind": "command | tool",
  "tool_name": "terminal | <tool>",
  "display": {"title": "...", "command": "<redacted>", "description": "...", "impact": "...", "alternative": "..."},
  "digest": "sha256 hex of canonical {kind, tool_name, full_args, job_id, execution_id}",
  "created_at": "ISO8601", "expires_at": "ISO8601 (既定 +24h, approvals.escrow_ttl_hours)",
  "deliver": "<job deliver value>",
  "result": {"ok": true, "summary": "...", "at": "ISO8601"}
}
```

- 保存先: `$HERMES_HOME/cron/escrow/<id>.json`（0600、ディレクトリ 0700、atomic write）。
  `full_args` は同ディレクトリの `<id>.args.json`（0600）に分離し、REST・ログ・通知には出さない。
- `display` は `_redact_approval_command` を通した値だけ。表示しきれない場合は既存の
  `card_deny_only` と同じく **却下のみ可**（`display.deny_only: true`）。
- 作成後に新しいプラグインフック `cron_escrow_created(record: dict)` を呼ぶ（戻り値無視、例外は握りつぶしてログ）。
- 同一 `digest` の pending が既にあれば新規作成せず既存 id を返す（重複承認依頼を作らない）。

### 3.3 解決 API

`cron.escrow.resolve_escrow(id: str, decision: "approve"|"deny", digest: str, actor: str) -> dict`

- `digest` 不一致・`status != pending`・期限切れは拒否（`{"ok": false, "reason": ...}`）。冪等: 同じ決定の再送は現状を返す。
- `deny` → `denied`。何も実行しない。
- `approve` → `approved` にしてから、**保存済みの操作そのもの**を 1 回だけ実行する
  （LLM に再計画させない）。command は digest に束縛した一回限りの許可で terminal 実行、tool は保存引数で dispatch。
  実行結果を `executed` / `failed` と `result` に記録し、ジョブの `deliver` 先へ結果を配送する。
- 失敗時は自動再試行しない（取り消せない操作の二重実行防止）。`failed` を配送して人間に戻す。
- 期限切れは次の scheduler tick で `expired` にし、`cron_escrow_created` と同じ経路で通知はしない（Today に表示のみ）。

`cron.escrow.list_escrows(status: str | None) -> list[dict]`（`full_args` は含めない）も提供する。

### 3.4 テスト（必須）

- `cron_mode: deny` の既存挙動が変わらない（既存テストを弱めない）。
- escrow で危険コマンド・`approve` 格上げツールが実行されず record が作られる。
- digest 不一致・二重承認・期限切れ・却下で実行されない。承認で 1 回だけ実行される。
- `full_args` が REST 用 list・フック引数・ログに出ない。

## 4. プラグイン `server/mobile_worker`（担当 Sol, リポジトリ hermes-mobile）

`server/today_home` と同じ構成（`plugin.yaml`、`__init__.py: register(ctx)`、`dashboard/`、`tests/`）。

### 4.1 ゲートウェイ側 `register(ctx)`

1. `ctx.register_platform("mobile", "Hermes Mobile", ...)`。アダプタの `send(chat_id, content)` は
   受信箱へ `kind=result`（失敗通知は `kind=diagnostic`）で 1 件追加し、合図を送る。
   `[SILENT]` のみの出力は追加しない。
2. `cron_escrow_created` フックで `kind=approval` を追加し、合図を送る。
3. `pre_tool_call` フックで承認ポリシーを設定から強制する（ルールファイルを外しても止まる）。
   ```yaml
   mobile_worker:
     require_approval_tools: [send_message, gmail_send, slack_post, github_create_pr, github_merge_pr, skill_manage, cronjob]
   ```
   既定リストはインストール済みツール名を実機で確認して確定する（未確認事項）。
   `cronjob` は `action in (create, update)` のときだけ格上げ（一覧は自動）。

### 4.2 受信箱

`$HERMES_HOME/mobile_worker/inbox.sqlite3`（0600、WAL）。

| 列 | 型 | 内容 |
| --- | --- | --- |
| seq | INTEGER PK AUTOINCREMENT | 単調増加。アプリの既読位置 |
| id | TEXT UNIQUE | `inb_<uuid>` |
| kind | TEXT | `result` / `diagnostic` / `approval` / `memory_diff` |
| title | TEXT | 内部用語を含まない 1 行（例「今日やること 3件」） |
| body | TEXT | Markdown。生エラー・ツール名・cron 等の内部用語を出さない |
| ref | TEXT JSON | `{"job_id", "execution_id", "escrow_id", "digest", "deny_only"}` の該当分 |
| created_at | TEXT | ISO8601 |
| acked_at | TEXT NULL | アプリが表示済みにした時刻 |

保持 30 日。`memory_diff` は M2 の memory-gardener が使う（M1 では型だけ予約）。

### 4.3 REST（ダッシュボード、既存のダッシュボード認証の内側）

| メソッド | パス | 本文 / 応答 |
| --- | --- | --- |
| GET | `/api/plugins/mobile_worker/inbox?since=<seq>&limit=50` | `{"items": [InboxItem], "latest_seq": n}` |
| POST | `/api/plugins/mobile_worker/inbox/{id}/ack` | `{"ok": true}` |
| GET | `/api/plugins/mobile_worker/escrows?status=pending` | `{"items": [EscrowRecord without full_args]}` |
| POST | `/api/plugins/mobile_worker/escrow/{id}/decision` | 要求 `{"decision": "approve"|"deny", "digest": "..."}` → `resolve_escrow` の結果 |
| POST | `/api/plugins/mobile_worker/push/register` | `{"token": "<FCM token>", "device_label": "..."}` |
| DELETE | `/api/plugins/mobile_worker/push/register` | `{"token": "..."}` |

- 承認は `digest` 必須。アプリは受信箱 `ref.digest` をそのまま返す。`deny_only` のものに approve は 409。
- 応答・ログにトークン・本文以外の秘密を出さない。FCM トークンはログに出さない。

### 4.4 通知の合図（FCM HTTP v1）

- サービスアカウント鍵: `$HERMES_HOME/mobile_worker/fcm-service-account.json`（0600、リポジトリに入れない）。
  無ければ合図は無効（受信箱は動く）。
- data-only メッセージ。**本文・タイトル・id を入れない**:
  `{"data": {"t": "hermes.tickle", "v": "1", "seq": "<latest seq>"}}`
- `android.priority`: `approval` を含むとき `high`、それ以外 `normal`。`collapse_key: "hermes"`。
- 送信失敗は受信箱に影響させない。`UNREGISTERED` のトークンは削除。

## 5. Android（担当 Hydrogen, リポジトリ hermes-mobile `app/`）

DESIGN.md・UX_SPEC・AGENTS.md の UI/ライフサイクル規則に従う。既存部品を再利用する:
`HermesScaffold`、共有の loading/error/empty、既存の承認カード、`AttentionNotificationRoute`、
`ChatNotificationService` のチャンネル定義、`NavigationController.navigateTo()`。

1. **FCM 受信**: `FirebaseMessagingService`。`t == hermes.tickle` のとき WorkManager の
   expedited work で `inbox?since=<保存済みseq>` を取得し、項目ごとにローカル通知を組み立てる。
   取得失敗時は汎用文「新しい結果があります」1 件のみ（本文は出さない）。
2. **ビルドの任意化**: `google-services.json` が無い構成（CI 等）でもビルド・テストが通ること。
   無い場合は通知を無効にし、設定画面に「通知は未設定」と表示する。
3. **トークン登録**: ログイン後（`ApiClient.rebuild()` → 接続の後）と `onNewToken` で register。
   ログアウトでは `HermesWsClient` 切断・トークン破棄の**前に** unregister を試みる（失敗しても続行）。
4. **通知の種類**:
   - `result` / `diagnostic`: タップで該当画面。チャンネルは既存の結果用。
   - `approval`: 本文（実行内容・影響・代替案。サーバーで秘匿化済み）を BigText で全文表示する。
     **通知上の承認ボタンは、本文全体が 500 字以内で通知に収まるときだけ**出し、`setAuthenticationRequired(true)`
     （端末ロック解除必須）にする。収まらない・`deny_only` のときは「確認する」（アプリのカードを開く）と却下だけ。
     見せていない内容に承認が効かないようにするため。応答は `escrow/{id}/decision` に `digest` を付けて送る。
5. **Work 画面**: `cron.manage` で一覧・一時停止・再開・削除、`subagent.list` / `subagent.interrupt`、
   承認待ちエスクロー一覧（`escrows?status=pending`）。削除は確認ダイアログ必須。
6. **Today**: 受信箱の当日 `result` と（M2 以降）`memory_diff` を表示。
7. 文言は内部用語を出さない（cron→「定期」、escrow→「承認待ち」、subagent→「手伝い中の作業」等は UX_SPEC で確定）。

## 6. 受け入れ確認（M1 完了条件）

- [ ] `cron_mode: escrow` で要承認操作が承認なしに実行されない（本体テスト）
- [ ] プラグインの `pre_tool_call` 格上げがルールファイル無しで効く
- [ ] 承認は表示した digest にだけ効き、二重承認で 2 回実行されない
- [ ] アプリを閉じた状態で cron 結果と承認依頼が通知される（エミュレーター＋実サーバー）
- [ ] 通知・FCM ペイロード・ログに本文やトークンが出ない
- [ ] `google-services.json` 無しで `assembleDebug` / `testDebugUnitTest` が通る

## 7. 未確認事項（実機で確認、sudo が必要）

- サーバーの Hermes が本フォーク由来か、バージョンと差分（本体変更のデプロイ方法を決める）
- cron を実行しているプロセス・プロファイル（`hermes-gateway-career-mypage` か）と、
  ダッシュボードと `HERMES_HOME` を共有しているか
- 外部送信系ツールの実名（§4.1 の既定リスト）
- FCM 送信のための外向き通信が許可されているか

## 8. 常設のメイン会話（M3.5、2026-11-16 決定）

ユーザーにセッションの作成・選択・切り替えをさせない。見える会話は1本、中の Hermes セッションは自動で切り替える。

### 8.1 原則

| 概念 | 扱い |
|---|---|
| 話しかける相手 | Hermes 1体（メイン Profile） |
| アプリのメイン会話 | default プロファイルの「Bot Chat」（Hermes 本体の常設会話。タイトルで一意、要約の系列は `canonical_session.resolved_id` で追う） |
| Hermes の中のセッション | 本体の自動要約（compression）が子セッションを作って切り替える。アプリは既存の系列表示でつなげて見せる |

### 8.2 切り替え（2026-11-16 改訂）

- アプリは起動時、明示の指定（通知・一覧からの遷移）がなければ default の Bot Chat を開く。無ければ「Bot Chat」の題で作る。
- 中のセッションの切り替えは本体の要約に任せる。日付などでアプリが独自に新しいセッションを作ることはしない（Bot Chat の系列が切れるため）。
- Bot Chat 内の `/new` は既存どおり要約（`/compact`）に置き換わる。
- セッション一覧（過去の会話履歴画面）は UI から撤去する（§9.5 参照。サーバー側の会話データは要約・Bot Chat 継続・`session_search` のため保持）。
- 本体の変更は不要。

### 8.3 長い仕事

- `delegate_task` は親セッションの `/new` や終了で止まる（`tools/delegate_tool.py` の説明）。切り替えをまたぐ仕事には使わない。
- 長い仕事・切り替えをまたぐ仕事は Hermes 標準の Kanban に載せる。メインは仕事を渡したらすぐ返事を返す（SOUL に明記）。
- 結果は受信箱ではなくメイン会話に返す（2026-11-16 改訂）。会話から作った Kanban は本体の自動購読（`kanban.auto_subscribe_on_create`）で元の会話が起こされ、定期作業は届け先 `bot-chat` で Bot Chat への発言として届く。承認待ちは当面ホームとプッシュ通知に残す。

### 8.4 記憶

- 既存の MEMORY.md（2200字）・USER.md（1375字）・`session_search`・作業ファイルで始める。
- プロジェクト別の記憶層は作らない。M4 で足りないと分かったら追加する。

### 8.5 M4 の合格条件（暫定目標）

| 項目 | 条件 |
|---|---|
| 会話の継続 | アプリ再起動後も同じメイン会話に戻る |
| 自動切り替え | 切り替え後も過去の重要な決定を正しく参照できる |
| 応答性 | 重い仕事の実行中も新しい質問に返事できる |
| 作業の永続性 | アプリ終了・ゲートウェイ再起動後も仕事の状態が戻る |
| 記憶の精度 | 既知の事実・決定についての確認質問20問中18問以上正解 |
| 通知 | 完了・失敗・承認待ちが重複なく表示される |
| 混線 | 別のプロジェクトの情報を取り違えない |

## 9. 専門 bot 構成（Muse × Grok Bot 型、M5、2026-11-16 決定）

単一ユーザー運用のため、VM や OS ユーザーの分離、および API キー等の秘密情報の共有範囲を選択する UI は設けない（認証情報・`.env` はメインとそのまま共有）。
bot 分割の目的は **「コンテクスト（SOUL・長期記憶・会話文脈）の分離」「スキル・ツールの絞り込みによる精度と速度の維持」「既定モデルと承認ルール（自律度）のプリセット分離」** に特化する。

### 9.1 全体構成と役割

- **メイン窓口（Muse 型）**: `default` プロファイルの「Bot Chat」を唯一のメイン会話とする。下部ナビゲーションの `Agent` タブを `Hermes` に改名し、常にメインの Hermes 会話を表示する。
- **専門 bot（Grok Bot 型）**: 1 bot ＝ 1 プロファイル（例: 調査 bot、開発 bot、キャリア bot 等）。それぞれ独立した SOUL・MEMORY・有効スキル・定例作業（cron）・既定モデル・承認設定を持つ。
- **仕事の委譲と結果の返却**:
  - 非同期・長時間の仕事は Kanban のプロファイル割り当てで専門 bot に渡す。
  - 専門 bot の定例作業・完了報告は `bot-chat:default` を配送先に指定し、メイン会話へ専門 bot 名・アイコン付きで返す。
  - 短時間の相談・ブレストは既存の `message_agent` で並列に呼び出す。

### 9.2 統合メニュー（ドロワー）とナビゲーション整理

既存の「約28画面のドロワー」「チャット履歴ドロワー」「右上歯車のアプリ管理」の3入口を廃止・統合し、左上の単一メニュー（ドロワー）に集約する。

```text
Hermes            ● 接続中
──────────────
Hermes（メイン）
──────────────
専門bot
  調査bot   作業中
  開発bot
  キャリア
  + 新しいbotを作成
──────────────
（ピン留め1枠: 将来 Muse の Spaces 相当の自作画面用）
──────────────
⚙ 設定・管理   → 残りの約25画面はすべてここへ集約（横断検索付き）
```

- 会話ヘッダー上部の「会話／グループ」切り替え、およびプロファイル（bot）切り替えプルダウンは撤去する。
- ドロワーから専門 bot をタップすると、メイン会話を切り替えるのではなく、その bot の専用ページ（詳細設定＋直接の個別相談チャット）を開く。

### 9.3 bot の作成と設定フロー（Grok Bot 方式）

1. **最小入力での作成**: ドロワーの「+ 新しいbotを作成」からボトムシートを開き、テンプレート（白紙 / 調査 / キャリア / 開発 / 生活）、名前、アイコンを選んで作成する。
   - 単一ユーザー運用のため、API キーや外部接続の共有選択 UI は出さず、メインの認証環境をそのまま引き継ぐ（ただし配布・再利用用テンプレート定義自体には秘密情報や稼働中 cron、会話履歴を含めない）。
2. **初回会話によるセットアップ**: 作成直後にその bot との個別会話へ遷移し、bot 側から「担当する役割」「参照する情報源・使うスキル」「確認なしで進めてよい範囲」を質問して、SOUL・スキル・定例作業の下書きを作成しユーザーが確定する。
   - 未設定（初期セットアップ未完了）の bot にはメインからの自動振り分けを行わない。
   - アプリの bot 説明欄と、メインが振り分け判断に読むプロファイル説明の保存先を1つに統一する。
3. **bot 詳細画面**: カードを多用せず、「役割・ルール（SOUL）」「定例作業」「スキル」「承認の範囲」「既定モデル」「外観」「削除」を1行ずつのリストで並べる。

### 9.4 ブレスト（グループチャットの代替と廃止）

- メイン会話で「みんなで考えて」「ブレストして」と依頼すると、Hermes が適した専門 bot 群を選び、既存の `message_agent`（最大8体並列）で一斉に意見を集める。
- メイン会話上に各 bot の回答を名前・アイコン付きで表示し、最後に Hermes が統合まとめと次のアクション（特定 bot への深掘り委譲など）を提示する。
- このブレスト機能が稼働した段階で、アプリ側のグループチャット UI とサーバー側の `group_chat` 実行処理をともに廃止する。

### 9.5 会話履歴 UI の撤去と導線修正

専門 bot がトピック別の部屋として機能するため、過去のチャット履歴一覧画面と「新規チャット」ボタンは UI から撤去する（裏側のセッションデータは要約・`session_search` のため保持）。

- **通知の遷移先**: 古いセッションへ直接飛んでメイン会話に戻れなくなるのを防ぐため、承認通知は承認画面へ、完了通知はメイン会話（または該当 bot 会話）の最新位置へ統一する。
- **`/new` の扱い**: 新規セッション作成による分断ではなく、要約を引き継いで話題を区切る（実質 `/compact`）動作に統一する。
- **会話内の遡り**: メイン会話および各 bot 会話内の上スクロールによる過去ログ閲覧と会話内検索は維持する。

### 9.6 本体変更とプラグインの分担・作業順序

| 段階 | アプリ（Android） | サーバー（Hermes 本体 / プラグイン） |
|---|---|---|
| **Step 0** | `UX_SPEC.md`・`AUTONOMOUS_WORKER.md` の更新（自動振り分け・並行作業の解禁、履歴・グループの整理） | 同左 |
| **Step 1** | ドロワーの統合、`Agent` → `Hermes` タブ改名、上部「会話/グループ」・bot 切替プルダウンの撤去 | bot ごとの承認設定、Kanban 実行時のエスクロー（承認待ち）対応、承認後の即時実行（60秒待ちの解消） |
| **Step 2** | 会話履歴画面・新規チャットボタンの撤去、通知の遷移先修正 | プロファイル説明の保存先統一、未設定 bot の振り分け除外、メイン SOUL への振り分けルール記載 |
| **Step 3** | bot 作成シート、bot 詳細画面＋個別相談画面 | 初回セットアップ会話の下書き確定フロー、テンプレート機構（稼働 cron・履歴を含めない） |
| **Step 4** | ブレスト結果の表示（メイン会話内の bot アイコン・名前付き発言行） | `message_agent` を使った並列ブレスト呼び出しと集計 |
| **Step 5** | グループチャット画面・導線の撤去 | `server/group_chat` 実行処理の撤去 |

### 9.7 M5 の合格条件

- アプリを閉じていても、メインから専門 bot への委譲と定例作業が進み、結果がメイン会話に届く。
- 複数 bot への相談・作業時に、どの bot が成功・失敗したかが判別できる。
- 専門 bot の要承認操作がエスクローに入り、承認すると対象の bot で1回だけ即座に実行される。
- 再起動や自動要約（compression）のあとも、同じメイン会話・各専門 bot 会話が維持される。

