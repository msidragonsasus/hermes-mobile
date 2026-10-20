# UX Specification

既存Compose / Material 3と [DESIGN.md](../../DESIGN.md) を継承する。
以下は目標UXであり、4-tab/共通カード/通知の完成を示さない。PR分割と未対応の扱いは
[実装計画](../personal-ai/IMPLEMENTATION_PLAN.md) に従う。

## 1. UX原則

### 1. Conversation is not the product

チャット履歴を主役にしない。
主役は「今やるべきこと」「AIが何をしているか」「人間が決めること」。

### 2. One input, many destinations

ユーザーは最初から就活・大学・開発を選ばなくてよい。
通常は自動ルーティングし、誤分類時だけ修正する。

### 3. Progressive disclosure

最初は要点だけ見せる。
ツール引数、ログ、JSON、思考過程に相当する内部情報は必要時に展開する。
隠しすぎず、常時ノイズにもさせない。

### 4. Attention over activity

実行中の量より、人間の判断が必要な項目を優先表示する。

### 5. Safe by interaction design

危険操作は、単純な「OK」ではなく、対象・差分・影響を理解して承認できる形にする。

### 6. One-handed first

スマホ下部に主要操作を置き、片手で送信・承認・停止できる。

## 2. ナビゲーション

下部ナビゲーションは4項目。

```text
Today      Chat      Activity      Apps
```

設定、接続、プロファイル、Advancedは右上のアバター/メニューから開く。

### Today

「開いた瞬間に何を見ればいいか」を示す。

優先順:

1. Needs You
2. 今日の重要事項
3. 実行中
4. 近日予定
5. Mini App cards
6. 最近の完了

### Chat

新規依頼とセッション継続。

- 起動時は最後のセッションまたは空のComposer
- 上部のSession switcherで履歴へ移動
- モデル/プロファイルは通常隠し、詳細シートで変更
- Tool eventsは本文の間にコンパクト表示
- 長いRunはActivityへのリンクを表示

### Activity

AIのMission Control。

区分:

- Live now
- Needs input
- Queued
- Scheduled
- Recent

Run cardの必須要素:

- タイトル
- 発生元（Chat / Career / Development等）
- 状態
- 現在のステップ
- 経過時間
- セッション
- 停止/追加入力
- 詳細への導線

### Apps

Mini Appのランチャーと管理。

- Pinned
- Installed
- Available from server
- Disabled
- Search
- Permission review
- Update state

## 3. グローバルComposer

TodayとChatから常に開ける。

### 基本構成

```text
[＋] [何をする？                         ] [mic] [send]
```

`＋`:

- ファイル
- 写真
- カメラ
- URL
- クリップボード
- Mini App action

長押しや隠しジェスチャーを必須にしない。

### 送信前

通常は自動。

詳細シートでのみ変更可能:

- Destination / Mini App
- Session
- Model
- Background run
- Tool permission profile

### 送信後

- 即座にローカル表示
- server accepted / rejectedを後から反映
- 二重送信防止
- 送信状態が不明な場合は「確認中」とする
- 再送はserverのidempotency保証・scope・保持期間を検証できた経路だけで同一keyを使う。未対応では状態不明を保持し、WS request IDをidempotency keyとみなさない

## 4. Today画面詳細

### 4.1 Needs You

最上段。0件ならセクション自体を縮小。

カード例:

```text
GitHubへの変更を承認
personal-ai-shell / feat/foundation
3 files changed, tests passed
Risk: external write

[差分] [拒否] [修正] [今回だけ許可]
```

### 4.2 Focus

最大3件。
AIが推薦しただけの項目と、確定締切を視覚的に分ける。

### 4.3 Running

最大3件を表示し、残りはActivityへ。

### 4.4 Mini App cards

Mini Appが貢献するカード。
ホストがサイズ、更新頻度、表示順を制御する。

## 5. Chat画面詳細

### メッセージ表現

- User: 右寄せのコンパクトBubble
- Assistant: 全幅寄りのドキュメント型
- Tool: 折りたたみ式の構造化カード
- Approval/Clarification: 強調された操作カード
- Error: 再試行可能性と原因を分離

### ストリーミング

- ユーザーが下端にいる時だけ自動追従
- 上へスクロールしたら追従を停止
- 「最新へ」ボタンを表示
- token単位で毎回再描画せず、短い時間窓でまとめる
- Markdownの未完成構文で表示崩壊しない

### Tool card

折りたたみ時:

- tool名
- 実行状態
- 一行要約
- 経過時間

展開時:

- 入力要約
- 出力要約
- ファイル/差分
- ログ
- 再試行または関連Run

秘密情報はマスクする。

## 6. Approval UX

### 必須表示

- 何をするか
- 対象
- 外部への影響
- 可逆性
- 変更差分または要約
- 依頼元Run
- 有効期限
- 要求権限

### 操作

- Reject
- Modify
- Approve once

v1ではsession/always許可をUIとtransportの両方で禁止し、once/denyだけを許可する。
Modifyは安全に対応できる編集・再確認導線であり、専用endpointの存在を仮定しない。
未対応なら理由付きdisabled。編集だけで元要求を許可しない。
カードは元server/profile/session/request IDへ固定し、解決ACKまでRespondingを保持する。
要求失効・不明・接続先切替では返答を保留。期限が未提供なら不明と表示する。

### 高危険度

以下は追加確認または生体認証を検討。

- 公開投稿
- メール送信
- 予定の削除
- Git push / merge
- ファイルの広範削除
- 認証・秘密情報の変更
- 金融操作（初期版ではそもそも非対応）

## 7. Clarification UX

自由記述だけでなく、Hermes側が候補を返した場合は選択肢を表示する。

```text
どのリポジトリですか？

( ) personal-ai-shell
( ) batting-viewer
( ) investment-learning-memory
[別の名前を入力]
```

回答後、元のRunへ戻る。
新しいチャットを作らない。

## 8. Activity UX

Runの状態遷移:

```text
Queued
  → Running
  → NeedsInput / WaitingApproval
  → Running
  → Completed / Failed / Canceled
```

表示上、`NeedsInput` と `WaitingApproval` は最優先。
Connection（Offline/Reconnecting等）はRun stateと別軸にする。切断時は最終確認時点を示し、
Unknown/再同期中をFailedへ変換しない。durable Run IDがないRPC経路は観測範囲を明示する。

### 詳細画面

- Run summary
- Timeline
- Active subagents
- Tool steps
- Artifacts
- Pending inputs
- Logs（折りたたみ）
- Stop / Steer / Follow-up / Resume

## 9. Apps UX

### カタログカード

- アイコン
- 名前
- 目的
- 種別: Native / SDUI / Web
- Publisher
- Required permissions
- Version
- Enabled state

### 初回有効化

1. 機能説明
2. 要求権限
3. データ送信先
4. オフライン保存
5. 有効化

### 権限変更

設定からいつでも無効化。
無効化してもMini App固有キャッシュを削除するかは別確認。

## 10. オフラインUX

### 原則

オフライン時に作った依頼は、勝手に後から実行しない。

理由:
時間依存・締切依存・外部状態依存の命令が古くなる可能性がある。

### 表示

- 明確なOffline banner
- キャッシュ済み履歴は閲覧可
- ComposerはDraftとして保存
- 再接続後に「送信する / 編集 / 破棄」を確認

### 実行中に切断

- 「実行失敗」と断定しない
- `状態不明、再同期中`
- server Run IDがあれば照合。RPCでは元sessionのhistory/status/pendingと既存replayで再同期し、架空のRun IDを送らない

## 11. 通知

通知カテゴリ:

- Approval required
- Clarification required
- Run completed
- Run failed
- Scheduled event
- Connection unhealthy（繰り返し抑制）

通知action:

- Open
- Reject
- Approve once
- Stop

高危険度承認は通知上だけで完結させず、詳細画面へ送る。

## 12. Android統合

P1以降:

- ACTION_SEND / ACTION_SEND_MULTIPLE
- 共有URLの自動要約
- カメラ/写真
- 音声入力
- 通知inline reply
- App Shortcut
- Widget
- Quick Settings Tile

## 13. 視覚設計

### 基調

- 既存Jetpack Compose / Material 3、6 theme presetsとdynamic colorsを継承
- OLED friendly dark themeと既存light themeを維持
- 階層は色面と余白で表現し、影を多用しない
- statusはアイコン＋文言＋色
- コード/ログはmonospace
- UIに装飾目的の絵文字を使わない

### 情報密度

既存Hermes Mobileの高密度設計を維持しつつ、
日常画面では管理情報を減らす。
管理機能はAdvancedへ退避し、既存画面・詳細導線を保持する。

### 共通部品とアクセシビリティ

Run/status、承認、質問、成果物はToday/Chat/Activity/Mini Appで共通部品を設計する。
HermesScaffold、StateViews、DetailDialog/Rows、既存Chat部品を再利用し、
semantic colors・LocalSpacing・既存Typography/Shapesを使う。画面単位の独自色/余白を増やさない。
Scaffold内はloading/error/emptyも同じinset ownerに従い、paddingValuesを二重適用しない。
日本語の折返し、文字拡大、TalkBackのラベル/状態/順序、48dp touch target、片手操作、
IME上の入力/承認/質問操作を確認する。承認の対象・影響を省略して密度を優先しない。

## 14. 主要ユーザーフロー

### Flow A: 新規依頼

```text
App open
→ Today composer
→ 入力
→ Send
→ Chatにローカル表示
→ Run開始
→ 離脱可能
→ Activityで進捗
→ 完了通知
```

### Flow B: 承認

```text
Notification
→ Approval detail
→ Diff/impact確認
→ Approve once
→ 元Runが再開
→ Activityへ戻る
```

### Flow C: Mini App追加

```text
Apps
→ Available
→ Career
→ 権限確認
→ Enable
→ Today cardとglobal commandが追加
```

### Flow D: 共有

```text
Browser/Files
→ Android Share
→ Personal AI Shell
→ command sheet
→ destination自動推定
→ Send
```
