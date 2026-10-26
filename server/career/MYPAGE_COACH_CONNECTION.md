# コーチからのマイページ接続設定（career_app `mypage_connection`）

2026-10-23 設計・ローカル実装。本番未反映。

## 目的

就活コーチから、企業ごとのマイページ自動巡回の「接続URL」と「自動確認のオン/オフ」を設定できるようにする。
ログインID・パスワードはコーチの会話・ツール引数・受領記録に一切流さない。

## 既存の保存経路（変更なし）

- App: `POST /collection/mypage-settings` → `MyPageBotStore.configure()`（mypage_bot.py）
  - URL検証 `portal_url()`、DNS確認 `public_host()`、版照合 `expected_version`
  - ID・パスワードは `SecretStore`（Fernet暗号化、`/var/lib/hermes/career-private/*.sealed`）。DBには `secret_ref` だけ
  - 新規接続とURL変更では ID・パスワードの再入力が必須（`credentials_required_for_new_url`）
- App画面: `CareerMyPageSettings.kt`。パスワードは保存状態・Room・下書きに入れない

この「本人がAppで入力 → 暗号化保存」を、秘密情報の唯一の入口として維持する。

## 追加した流れ

`career_app` に `action: "mypage_connection"` を追加（coach_app.py → mypage_request.py）。
mail_account / mail_sender と同じく、本人発言の照合 → 版照合 → 冪等receipt（`tool_once`）で処理する。

引数: `company_id`, `url`（省略可）, `enabled`, `expected_version`, `user_statement{message_id, quote}`

1. 引数に `login_id` / `password` 等の資格情報キーがあれば、receiptや記録より前に `credentials_app_only` で拒否
2. daily / research 実行、企業限定相談で別企業 → 拒否（既存と同じ）
3. URLの決定
   - `url` 指定: `portal_url()` で正規化し、本人の発言（quote）にそのURLが含まれることを確認
   - `url` 省略: その企業の登録済み MyPage リンク（company_links, type=MyPage）が1つに決まる場合だけ採用。0件・複数は `mypage_url_required`
4. `public_host()` で公開HTTPSホストか確認（トランザクション外）
5. receipt内で: 発言照合 → 企業の有効性（削除・終了企業は不可）→ `expected_version` と接続版の一致
6. 分岐
   - **既存接続・同じURL**: オン/オフをその場で保存。版を+1（実行中の確認と古いApp画面を無効化）、`secret_ref` は変えない。保存後に `mypage_cron.reconcile` で定期確認を同期
   - **新規接続・URL変更**: 認証情報が要るので保存しない。資格情報を含まない「入力待ち（pending）」を metadata `mypage:pending:<connection_id>` に記録し、`credentials_required: true` と App での次の操作を返す
   - 未接続で `enabled:false`: 入力待ちを取り消すだけ

## 入力待ち（pending）の扱い

- 中身: `portal_url`, `enabled`, `url_source`(user_statement / company_link), `requested_at`, `base_version`, `run_id`。秘密情報なし
- `MyPageStore.view()` が `pending_request` として返す（App・company_overview・connections 共通）。`base_version` が現在の接続版と違えば古いので表示しない
- 本人が App で保存（`configure()`）すると同じトランザクションで削除
- `mypage_readiness.next_action` は入力待ちがあると「Appのマイページ設定でログインID・パスワードを入力」

## App（Android）側

- `CareerMyPage.pending_request` を追加
- マイページ設定フォームは URL・自動確認の初期値に入力待ちを使い、「コーチに依頼された接続先です…」と表示
- ID・パスワード欄・保存処理は既存のまま（新しい秘密情報の経路は作らない）
- 旧Appのままでも動く（pendingは無視され、フォームは従来どおり登録済み MyPage リンクを初期値にする）

## コーチ側の案内

- `connections` に `mypage_connections`（企業ごとの版・URL・オン/オフ・確認済みか・登録済みリンク・入力待ち）を追加。秘密情報は「保存済みか」だけ
- HERMES.md とツール説明に、ID・パスワードを会話で求めない／本人が書いても使わない、入力待ちは巡回成功ではない、を追記

## 意図的にやらないこと

- コーチがID・パスワードを受け取って保存する経路（本人が会話に書いても使わない）
- 入力待ちの段階での巡回開始・定期実行の登録
- 会話に貼られたパスワードの自動検出・削除（Hermes本体の会話ログは対象外。必要なら別作業）

## テスト

`tests/test_coach_mypage_connection.py`（合成データ。実在ポータル・実アカウントなし）

- 登録済みリンク → 入力待ち → App保存で接続作成・入力待ち消去・パスワードがDBにない
- 明示URLは本人発言との照合必須、userinfo付きURLは拒否
- 資格情報キーは receipt 記録前に拒否、ツールschemaは追加プロパティ不可
- 同じURLのオン/オフは即保存・版+1・secret_ref維持・reconcile呼出し・古い版は競合
- URL変更は既存接続を変えずに入力待ち。版が変わると入力待ちは失効
- 企業スコープ違反、登録リンク0件/複数、終了企業、daily実行
