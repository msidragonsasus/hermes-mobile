# プラグイン登録型クライアントsurface 設計(2026-11-14)

## 現在の状態

設計のみ。本体・プラグイン・アプリのどのコードも変更していない。フォークも未作成。
この文書は実装の許可ではない。サーバー反映、push、PR公開には本人の許可が別途必要。

行番号と挙動は、上流 `NousResearch/hermes-agent` のコミット
`5b147ab036d6d19131bff8d20701b5001eab844a`(2026-10-21、v0.22.2 系)を読んだ結果。
本番サーバーの本体は `/var/lib/hermes/app` にあり、origin は上流のまま(フォークではない)。
本番の HEAD と手パッチの有無は sudo が必要で未確認。

## 目的

Hermes Mobile で表示しているターンだとサーバーが構造化データとして知る。
アプリがユーザー発言の本文へ表示用の文面を埋め込む方式をやめる。

## 現状の問題

- アプリは送信のたびに発言末尾へ `<hermes-mobile-display-context>` とネイティブカタログの記述を付ける
  (`app/.../ui/chat/RichAnswerSubmissionTransport.kt` 23行、`RichAnswerPrompt.append`)。
- 付けた文面はユーザー発言として履歴に保存される。そのため表示・プレビュー・検索・タイトルの各所で
  剥がす処理が必要になり、`RichAnswerPrompt.kt` は約300行ある。過去の版の記述も、履歴から隠すために
  `NativeUiInstructions.previousDescriptors` へ残し続けている。
- サーバー側の `server/mobile_display` は `pre_llm_call` でユーザー発言の本文からその印を探している。
  サーバーはモバイルを `platform=tui` としか認識できないため。
- 埋め込みはクライアントが決めた任意の文字列で、そのままモデル入力になる。

## 本体にすでにある仕組み

`gateway/session_surface.py` が同じ問題を別クライアント向けに解いている。

- surface はセッションではなく1ターンの事実として扱う。
- 受付時に `surface_v1` として確定し、モデルにはユーザー発言に付く注記(api_content サイドカー)として届く。
  保存されるユーザー行は打った文字のまま、固定済みのシステムプロンプトも変わらない。
- 許可値は `SURFACES = frozenset({'hud', 'voice-live'})` に固定。注記の生成も `_SURFACE_NOTES` に直書き。
- 未登録の値の扱いは経路で違う。
  - 旧経路(`tui_gateway/methods_prompt.py` 860行): 黙って空にする。
  - canonical 経路(`admit_surface`): `invalid_params` で拒否する。

足りないのは、許可値と注記をプラグインから登録する口だけ。

## 設計

### 1. 本体パッチ(フォークの1コミット目、上流PR候補)

| ファイル | 変更 |
|---|---|
| `gateway/session_surface.py` | 登録表と `register_surface(name, note_fn)` を追加。`admit_surface` と `surface_note` が組み込み2種に加えて登録表も見る |
| `hermes_cli/plugins.py` | `ctx.register_client_surface(name, note)` を追加。戻り値(`PluginRegistration`)と重複拒否は `register_system_prompt_section`(953行)と同じ流儀 |
| `tui_gateway/methods_prompt.py` 813行、860行 | import 時に固定した集合 `_CLIENT_SURFACES` ではなく、リクエスト時に登録表を照会する |
| `tui_gateway/methods_voice.py` 513行、`tui_gateway/contracts/liveness.py` 21行 | `gateway.capabilities` の結果に `client_surfaces: list[str]` を追加(組み込み + 登録済み、ソート済み) |

制約:

- 名前は `[a-z][a-z0-9-]{0,31}`。組み込み名(`hud`、`voice-live`)は上書き不可。同名の二重登録は拒否。
- 注記は上限6000字で切る(既存の `_VOICE_CONTEXT_LIMIT` と同じ値)。
- `note_fn(committed, valid_tool_names)` が例外を出したら、注記なしでターンを続行しログだけ残す。
  プラグインの不具合でターンを失敗させない。
- プラグインの無効化・再読み込みで登録が外れること。
- 本体に `mobile` という語を入れない。上流の方針は「プラグイン固有のロジックを本体に書かず、汎用の口を広げる」
  (`plugins/AGENTS.md`)。消費者(このプラグイン)が実在することがPRの前提になる。
- `voice_context` の検査(`voice-live` 以外では拒否)は変えない。

`tui_gateway/session_notifications.py` 858行は `surface_note(committed, direct)` を呼ぶだけなので、
登録表を見るようになれば変更不要のはず(要確認)。
`restore_surface` は `admit_surface` で再検証するため、管理ワーカー側でも登録されている必要がある。

### 2. プラグイン(`server/mobile_display` 0.6.0)

- `ctx.register_client_surface` があるホストでは、surface を版ごとに登録する: `mobile-v1`、`mobile-v2`、`mobile-v3`。
- カタログ記述をサーバー側に持つ。版ごとのファイルをプラグインに同梱し、アプリの `NativeUiInstructions` の
  各版と一致することを、既存の「Android/agent guide equality」テストと同じ形で検査する。
- 注記の中身は、今 `turn_context` がネイティブ版ありのときに返している文面 + その版のカタログ記述。
  「表示専用のクライアント情報であり、ツールの許可ではない」の一文は維持する。
- 旧アプリ向けの本文検出(`has_mobile_context`)は残す。
- `platform in {"tui", "cli"}` の「表示能力は未確認」分岐は、surface 対応ホストでは外す。
  残すと、新アプリのターン(本文に印が無い)で surface の注記と矛盾する注記が入る。
- `register_client_surface` が無いホストでは現在の動作のまま。
- `preflight.py` に、この口の有無の検査を足す。

### 3. アプリ

- 交渉: 接続時にすでに呼んでいる `gateway.capabilities` の結果から `client_surfaces` を読む。
  保持は `terminal_acceptance` と同じキャッシュ(`HermesWsClient.kt` の `TerminalCapability`、
  scope + epoch + socket)に載せる。別のキャッシュを作らない。
- 送信: 自分の版(現在 `mobile-v3`)が広告されていれば、本文はそのままにして `surface` パラメータを付ける。
  広告されていなければ従来どおり `RichAnswerPrompt.append` を使う。
  サーバーが古い版(`mobile-v2` まで)しか広告しない場合も従来方式に落とす。
- 広告なしでは `surface` を送らない。canonical 経路では未登録値が拒否されるため、これは必須。
- `HermesSubmissionTransport.submitPrompt` に surface を渡す引数が必要。
  `RichAnswerSubmissionTransport` が「付加するか surface を付けるか」を決める唯一の場所になる。
- 空文字と `/` で始まる入力には、従来の `append` と同じく何も付けない。
- 剥がす処理(`displayText`、`previewText`)は残す。過去の履歴に埋め込みが残っている。

## 判断: 版はパラメータではなく名前に入れる

自由形式の `surface_params` を足す案は採らない。

- 差分が倍になる。契約2つ(`contracts/canonical_sessions.py`、`contracts/prompt_voice.py`)と
  許可リスト2か所(`gateway/session_controls.py` 752行、`gateway/session_authority.py` 656行)に手が入り、
  上流へ追従するときの衝突面が増える。
- クライアントが任意の文字列をモデル入力へ入れる経路が残る。名前だけなら、モデルに届く文面はすべて
  サーバー側のプラグインが決める。現在の本文埋め込みより厳格になる。

表示能力の軸が版番号ひとつで足りなくなったら、そのとき見直す。

## 互換性

| アプリ | サーバー | 動作 |
|---|---|---|
| 新 | stock、または旧プラグイン | 従来の本文付加 |
| 旧 | 新 | プラグインが本文から検出(従来どおり) |
| 新 | 新 | surface。保存される発言は打った文字のまま |

導入順は 本体 → プラグイン → アプリ。どの段階で止めても従来動作が保たれる。

## 受け入れ条件

本体:

- 未登録名の挙動が変わらない(旧経路は空にする、canonical は `invalid_params`)。
- 登録名は `surface_v1` に確定し、注記がモデル入力に載り、保存行は入力文字列と一致する。
- `note_fn` の例外でターンが失敗しない。
- 組み込み名を上書きできない。
- `gateway.capabilities` が登録名を返す。
- 管理ワーカー越しの `restore_surface` が通る。

プラグイン:

- 版ごとの記述がアプリと一致する。
- 1ターンに注記が二重に入らない(surface あり、本文の印あり、どちらも無し、の3ケース)。
- 口の無いホストで登録が失敗せず、現在の動作になる。

アプリ:

- 広告あり: 本文に付加せず `surface` を送る。
- 広告なし: 従来どおり付加し、`surface` を送らない。
- 接続先・プロファイル・認証が変わったら、前の接続の広告を使わない。

実機:

- 送信後のセッションをデスクトップかTUIで開き、ユーザー発言に埋め込みが無い。
- 回答がネイティブ部品で描画される。

## 未検証(実装前に確認が必要)

1. アプリの送信が旧経路か canonical か。`HermesWsClient` は `submission_id` を送っていないので旧経路と
   見ているが、`DashboardRpcTransport` 側は未確認。未登録値の挙動がここで変わる。
2. プラグインの `register(ctx)` がどのプロセスで走るか。受付側とエージェント実行側の両方で登録されて
   いないと、注記が黙って落ちる。広告は受付側のプロセスから出るので、アプリの交渉は受付側の状態を正しく
   反映するが、実行側の欠落は検出できない。
3. 本番サーバーの HEAD と、上流の 2026-10-21 以降の変更。同等の口が上流に入っていないかを先に見る。

## やらないこと

- トークン量の削減。注記は現在の埋め込みと同じく毎ターン載る。
- `pre_llm_call` への引数追加。プラグインの分岐削除で不要になる。
- 過去の履歴からの埋め込み除去。
- `terminal_acceptance`、ホスト型プラグインへの `profile` 転送、接続系(`service_connections`、
  `google_workspace_connect`)の本体パッチ化、`group_chat` の本体 `groups.*` への移行。別件。

## フォークの運用(このパッチの前提)

- GitHub で上流をフォークし、`main` は上流のミラーのまま触らない。パッチは別ブランチに積む。
- 1コミット = 1機能。新規コードはできるだけ新しい関数にまとめ、既存行の書き換えを最小にする。
- `hermes update` はフォークを検知して `upstream` の追加と `main` の fast-forward を行う
  (`hermes_cli/update_cmd_git.py`)。`main` 以外のブランチで動かしたときの挙動は読んでいない。
  最初は手動(fetch → rebase → 再起動)で更新する。
