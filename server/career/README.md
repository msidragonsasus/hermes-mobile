# Hermes 就活App サーバープラグイン

Hermes Mobileの組込「就活」App用。Dashboardと同じポート・cookie認証を使い、SQLiteの保存処理をAPI・Agentツール・定期ジョブで共有する。

## 企業別画面と募集・締切の取得

企業詳細の募集・締切からログイン後の案内を読み、管理から取得用の接続を設定する。取得状態・検索・スクロール・編集中の内容を保持し、タブ切替から取得やLLMを起動しない。
マイページの新接続はHermes Bot・Agent・ローカルブラウザ・Cronを利用する。登録企業ごとにURL・ID・パスワードを暗号化保存し、手動確認と日本時間9時・18時の定期確認を同じ非同期経路で実行する。初回確認成功前や追加認証時は定期確認を開始しない。`mypage_reader.py` は未移行の日鉄接続だけに残し、新接続がある企業・プロファイルでは使用しない。4社の実ログイン互換性は未検証。詳細・制限・反映手順は [マイページBotの実装記録](../../docs/career/MYPAGE_BOT_IMPLEMENTATION.md) を参照。
既存の募集・下書き・選考記録は残し、対策・管理から利用する。公開募集の保存データをログイン後の情報として表示しない。実装・検証範囲は [企業詳細改修の記録](../../docs/company-redesign/IMPLEMENTATION.md) を参照。

- Gmailは既存Hermesの `google-workspace/scripts/google_api.py` を利用する。`hermes_constants.get_hermes_home()` で稼働中のホームを解決し、既存の `google_token.json` をラッパーが参照する。認証のコピー、新規OAuthクライアント、`gws auth login` は不要。
- Hermes本体のPythonにGoogleクライアントがない場合は `tools/prepare_workspace.py --install` で別環境へ公式Skillと同じ依存関係を導入する。`CAREER_WORKSPACE_PYTHON` で読取子プロセスだけを切り替え、Hermes本体のPython依存関係は変更しない。
- 保存済み権限に `gmail.readonly` が含まれることを確認し、profile・対象送信元の検索・messageAdded差分・メッセージの読み取りだけを実行する。メール同期は検索・履歴・バッチの認証経路を揃え、同じ公式ラッパーのPythonクライアントを使う。メール送信・削除・既読化・ラベル変更は公開しない。
- マイページ案内は手動または接続単位で有効にした定期ジョブから取得する。同一出典の重複を防ぎ、変更前の原文と取得履歴を保持する。年・日付・時刻・時間帯を推測せず、締切の原文と精度を表示する。
- systemdの日次調査は既定profile、会話を利用したprofile、旧取得設定に残るprofileの保存済みマイページ案内・企業メールを整理する。公開サイトの自動巡回は行わず、旧ES取得ジョブは再実行せず取り消す。稼働中の定期設定そのものはこの改修で変更しない。
- Gmailは登録送信元を全企業まとめて5分ごとに差分同期する。初回は直近30日を優先し、過去分は自動で継続取得する。App・AIは同じ保存データを参照し、確認時刻・直近分の完了・過去分の進捗を分けて表示する。詳細と測定手順は [メール同期](../../docs/career/MAIL_SYNC_20260928.md) を参照。
- 端末通知はWorkManagerによる15分以上の間隔。Tailscale・ネットワーク・省電力・通知許可に左右され、強制停止中の配信は保証しない。通知本文にはメール本文を載せず、接続先とプロフィールを照合して企業画面を開く。

導入・復旧の具体的な手順、検証済みと未確認の境界は [0.4の検証記録](../../docs/career/PRODUCT_RESET_VALIDATION.md) を参照。

## 保存先と契約

- データ: `/var/lib/hermes/career/career.sqlite3`。企業・募集・応募・予定はこのDBが正本。
- 本人事実・経験・ES・プロフィール・訂正・出典: private `msidragonsasus/jp-es-memory` の `.jobhunt/state.json` と `.jobhunt/documents/` が共有正本。`.jobhunt/context/` の検証済みsidecarは、訂正履歴・再利用制限・回答済み／未回答論点の読取専用補助情報として同じcommitから取得する。DBは同期コピーと未同期変更を保持する。
- `POST /api/plugins/career/{action}` の更新は `operation_id` を保持して再試行する。同じIDで違う内容を送ると409。同時編集は `expected_version` で拒否する。結果不明でも新しいIDで自動再送しない。
- `GET /api/plugins/career/snapshot` は `api_version: 1`、entities・notices・conflicts・sync・researchを返す。書き込み操作は `save / decide / import / correct / document / approve / resolve / sync / research`。
- 通常Chatは従来の `career_read` / `career_propose` を維持する。就活Appは専用の `AIAgent` 実行へ入り、`career_context / career_skill / career_fetch / career_save / career_checkpoint / career_count` を使う。依頼された下書き・計画は保存まで進める。本人事実は本人の発言IDと原文をツール側で照合し、曖昧な解釈は確認する。応募判断と提出状態の変更は本人の補助画面で行う。
- 提出済み文書の本文とmetadataは不変。編集は新しい文書IDへ保存し、未提出文書の本文変更も新しい版のpathへ保存する。過去の本文はDBとGitHubに残す。
- GitHubの同じfieldの同時変更は競合。異なるfieldはmergeできるが、配列・入れ子はatomic。無関係なstate項目・並び順を保持し、変更がなければGitHubへcommitしない。
- GitHub認証失敗・CAS失敗・ACK不明は未同期。成功したように見せず、次回のfresh SHA取得で照合する。
- state 4.0の旧 `id` 別名は検証用viewで読む。元stateを書き換えない。`GET /migration-preview` は4.1移行候補と差分だけを返し、移行を実行するAPIはない。
- 4.0から4.1への変換が成立しない既存stateも4.0のまま保守的に読める。sidecarの制限が未取得・不整合なら、相談は続けてもES・面接回答・企業別推薦の生成を止める。旧日付形式の文書pathは読取だけを許可し、Hermesからの新規文書は `DOC-数字-v数字.md` に限定する。
- シングルユーザー用のサーバー共通就活ストア。Mobileの接続先・profileごとにキャッシュと再試行を分離するが、同一サーバー内のprofileは同じ就活DBを参照する。

## コーチ会話と作業（0.2）

ホームは「今日の提案＋会話」。企業・応募・本人情報の一覧は補助画面として残す。企業・ESの相談対象は同じコーチ会話に追加され、通常Chatの選択中セッションや入力欄を変更しない。成果物を会話内から開き、同じ会話で修正を依頼できる。

- `GET /coach/conversation?profile=...&before=...`: `api_version: 2`、会話80件、最新作業40件、引き継ぎ要旨、イベントcursor。`older_before`で以前の会話を読む。
- `POST /coach/message?profile=...`: `text / operation_id / target:{kind,id}`。依頼とユーザー発言を保存してから受け付ける。
- `POST /coach/control?profile=...`: `id / control:stop|resume / operation_id`。停止・明示再開。同じ操作IDでの応答喪失に対応する。
- `GET /coach/events?profile=...&after=...`: 最大200件、次cursorとmore。イベントは状態・Skill読取・出典・保存・最終メッセージを参照する。モデル内部推論は配信しない。
- `GET /coach/capabilities?profile=...&refresh=true` は機能フラグ、Hermesの接続済みプロバイダーのモデル候補、推論設定、確認日時、作業の準備状態を返す。Codexの候補はアカウント一覧で照合する。プロバイダーごとの候補取得失敗は `provider_errors` で返し、他の接続済み候補を消さない。候補掲載は実推論の成功を意味しない。古いサーバーでは新機能の送信を停止し、履歴を保持する。
- 手動のmessage/researchは `execution:{provider,model,reasoning_effort}` を指定する。受付時にRunへ固定し、同じ操作IDの再送・再開で変更しない。送信直前の実設定と照合し、不一致なら中断する。実行設定のない旧Runは明示再開時に設定を確認し、過去の設定を補作しない。
- `GET/POST /coach/daily-settings?profile=...` は自動調査専用の設定。保存は `operation_id / expected_version / execution` を使用し、次に受け付ける日次Runから適用する。手動募集確認は `POST /coach/research` で会話の実行設定を使用する。
- 会話内の `artifacts` は保存操作の台帳から作る。文書ID・版・本文プレビュー・文字数・サーバー保存・GitHub同期を区別する。`GET /document/{id}/{version}` で全文を読み、修正依頼は `target:{kind:"documents",id,version}` を指定する。他の更新で版が進んでいれば衝突として停止する。
- HTTP経路はすべて既存の `/api/plugins/career` 以下でcookie認証と同一origin検査を使う。接続先・profileを端末で固定し、切替中の応答を別の会話へ反映しない。

Dashboard内のworkerがOSロックを保持して処理する。画面の終了・通信断はworkerを停止しない。再起動時のrunningはpausedへ戻し、保存済み操作の結果を照合して本人の「続行」を待つ。停止後の保存はツールでも拒否する。状態はqueued/running/waiting_user/paused/completed/failed/cancelledを区別する。

会話・実行・操作履歴はSQLiteの追加テーブルに保存する。最終的な構造化checkpointだけを `last_session.hermes_coach[run_id]` に追記し、ほかのChatのlast_session項目を保持する。生の会話・tool会話履歴はGitHubへ送らない。

端末には会話キャッシュ・入力途中の文・相談対象・未完了操作を暗号化して保持する。再接続は読取だけで、送信失敗した依頼を自動再送しない。ユーザーが再送すると同じ操作IDを使う。

`coach_skills/` は元のMIT就活プラグイン23 Skillと184 Markdownを保持した移植。接続・保存・実行環境の読み替えは `coach_skills/HERMES.md`、元ファイルのhashは `upstream-sha256.json` に記録する。再生成は `python tools/port_skills.py SOURCE_PLUGIN coach_skills`。Hermesの `register_skill` に登録し、専用のread-only `career_skill` で本文・参照を段階的に読む。

Agentはユーザーが選択したHermesのプロバイダーへ明示的に接続する。2026-09-14の追加依頼でCodex専用制約を撤廃した。候補はHermesの `hermes_cli.inventory` を再利用し、未接続の候補と自動ルーティング/MoAは含めない。Codexは既存OAuthと実際に送れる推論強度を維持する。それ以外は `reasoning_effort: "default"`（画面表示は「Hermes標準」）で本体の既定の推論処理を使い、未確認の強度を候補として作らない。

実行時は `hermes_cli.runtime_provider.resolve_runtime_provider` にproviderとmodelを明示し、解決結果が別provider/modelなら中断する。対応する実行方式は `chat_completions` / `anthropic_messages` / `codex_responses`。未対応方式はエラーとして返す。資格情報は本体の既存保存先から解決し、端末・進捗イベントへ返さない。一般のterminal・ファイル書込・メール・課金検索ツールは公開しない。fallbackは空で、ホスト設定変更による別providerへの切替を拒否する。別providerを使い得る自動圧縮を無効にし、直近の完全な会話ターンとcheckpointを渡す。長い履歴の全文はサーバーに保持する。

## 取り込み

Mobileのファイル選択画面で `.shukatsu` を開き、合言葉を入力する。復号は端末内のArgon2id / AES-256-GCM。ファイルサイズ、KDFパラメーター、形式、参照版を確認する。

端末から送るのはallowlistのCompany / CompanyStatus / CompanyAlias / CompanyMailSender / CompanyLink / Deadline / Task / CompanyEventだけ。パスワード・OAuth・メール本文・ログインアカウント・学習済みログイン操作は送らない。CompanyLoginAccountはマイページ取得に使わないため取り込まない。既存端末に保存済みの値は自動削除しない。リンクのquery・fragment・userinfoを除外する。型・元ID・元版・bundle ID・元ファイルSHA-256を保持する。

同じ元版は再取り込みしても増えない。元版が変わった項目、削除履歴、参照不整合は対象・理由・受信内容を確認用に保持する。元ファイルは変更しない。旧Shukatsuへの書き戻しはない。

Companyの存在だけでは応募を作らない。旧アプリの明示的な応募・選考状態だけを引き継ぎ、元の状態ラベルを保存する。Recruit型リンクを公式採用URLとして調査に使用する。URLがない企業はAppで公式採用URLを登録するまで「公式URL未設定」となる。

## 定期調査

systemd timerは毎日09:00 Asia/Tokyoにコーチの依頼キューへ追加する。会話を利用したprofileが対象。Persistentで停止中の予定を復帰時に1回追加し、未完了の日次依頼は重ねない。Dashboardのworkerが実行し、企業単位で手動メッセージを優先する。日次実行の保存権限は次の行動の整理までで、ES本文や面接練習を自動開始しない。同期は5分ごと。

調査は保存された企業の公式HTTPS URLから始める。1社4起点、同じ公式originの採用関連リンクを含め最大8ページ、1ページ2 MiB。ログイン必須、PDF、JavaScriptだけで表示されるページ、redirectは失敗として見える。以前の募集は消さない。年度・日時が分からない場合は推定しない。出典の本文に根拠が存在することを検証するが、募集要項の意味上の正しさは実データで別途確認する。

モデル接続はHermes既存の `resolve_codex_runtime_credentials()` を使用する。providerが `openai-codex` かつ接続先が `https://chatgpt.com/backend-api/codex` の場合だけ実行する。モデルと推論強度はAppで選択し、旧クライアントの既定値は管理者設定から受付時に固定する。有料検索API・APIキー課金モデルへのfallbackはない。利用上限・認証切れ・通信不明で中断し、再開位置をDBに保持する。変更がない募集は通知を増やさない。

## ローカル検証

Hermes本体を起動せず、合成データと偽GitHub/Codexを使う。

```sh
python -m pip install -r requirements-test.txt
python -m unittest discover -s tests -v
```

Androidの通常ゲートに加え、`ShukatsuImportTest`、`CareerViewModelTest`、`CareerScreenTest`がある。暗号化fixtureは個人情報を含まず、Python argon2-cffi / cryptographyで独立して生成したもの。テスト用合言葉はfixtureの説明であり、実データ用ではない。

実Hermesの動作検証は、導入先で `python smoke_coach.py --live` を実行する。既存Codexサブスクリプションを使い、一時DBの合成データだけでSkill読取、下書き保存、会話再開、事実訂正、版保持、探索の回答待ちを確認する。GitHub同期は行わない。これは実行して成功した時だけ合格と扱う。

2モデルの確認には、実際の候補ID・対応推論強度を `--model ID --reasoning EFFORT --second-model ID --second-reasoning EFFORT` で指定する。各ターンの受付設定と送信直前の設定が一致した場合だけ `DISPATCH_OK` を出す。このサーバー検証とは別に、Mobileで画面を閉じて再接続し、全文を見ながら修正して旧版が残ることを確認する。

## 導入前の確認

基準はHermes Agent commit `7874ef9f62b9b533f8d3da6107eb2a52f4be252f` の `hermes_cli/web_server_dashboard.py`。`dashboard/manifest.json` の `api` がrouterとして `/api/plugins/<name>` へmountされる契約に合わせている。実HEADの確認に代わるものではない。

1. 通常の既存SSHで管理者が接続する。SSH設定・authorized_keys・認証方式は変更しない。
2. 稼働中のHermesインストールパス・実HEAD、`home-hermes-dashboard.service` のExecStart/環境/書込可能範囲、configのplugins設定、既存timerを確認する。秘密を含むconfig全体はチャットやjournalへ貼らない。
3. 実版に `register(ctx)`、Dashboard API mount、認証middlewareの `request.state.session` があることを確認。`python preflight.py` はモジュールの場所・契約の有無だけを表示する。
4. プラグイン導入前に、既存config・サービス設定・既存careerディレクトリを管理者専用のbackupへコピーする。稼働中DBは `python backup.py /安全な新規保存先.sqlite3` でSQLite backup APIを使う。backupは公開リポジトリに置かない。
5. 既存データがない初回でも `/var/lib/hermes/career` をhermes所有・0700で作る。コードを `/var/lib/hermes/.hermes/plugins/career` へ配置する。DB・暗号化入力ファイル・認証情報をコードと一緒に配布しない。
6. 専用のGitHub認証ファイルを管理者の端末操作で用意し、hermesだけが読める0600にする。対象private repositoryのContents read/writeだけを許可する。Mobileやチャットへtokenを渡さない。
7. `/var/lib/hermes/career/runtime.env` をhermes所有・0600で作る。内容は次の2設定（実token本文は入れない）。モデル名は稼働中Codex OAuthで利用している値に置き換える。

```ini
CAREER_GITHUB_TOKEN_FILE=/var/lib/hermes/career/github-token
CAREER_CODEX_MODEL=既存のCodexモデル名
```

8. 既存configの他の項目を保持し、`plugins.enabled` に `career` を追加する。既存disabledにcareerがあれば意図を確認する。`systemd/dashboard-career.conf` を既存Dashboardサービスのdrop-inとして配置する。
9. `systemd/hermes-career-*.service` と `*.timer` を確認して配置する。`systemd-analyze verify` で確認し、daemon-reload後にDashboardを再起動する。未認証のsnapshotが401、ログイン後が200になることを確認する。
10. まず手動で同期・調査を1回実行する。GitHubの非公開性、4.0維持、未同期表示、選択したプロバイダーへの接続（Codex選択時は既存OAuth）、App内の会話・Skill実行・成果物保存・再接続を確認してからtimerをenable/startする。無人の09:00実行もjournalの状態コードとAppで確認する。既存の停止設定は、モデル変更やUI更新だけでは有効化しない。
11. Mobileは同じapplicationId・署名で `adb install -r app-debug.apk` の上書き更新だけを行う。署名等で更新できなければ停止し、アンインストールやデータ消去をしない。
12. 初回の実ファイル取り込みはMobile上で本人が合言葉を入力する。GitHub同期・実データ取り込み・定期調査の完了は、それぞれの成功結果を確認して初めて記録する。

## 復旧

`tools/update_server.py` は停止前に、installed Dashboardのmodule scopeでrouterがmountされる契約と、実FastAPIの合成lifespanを確認する。更新後は、実際にmountされたendpointと起動完了を、新しいDashboardのMainPID・process開始時刻・配布内容hash・一回限りのchallengeに結び付けたprivate Unix socket receiptで確認する。`/run/hermes-career-update` が既にある場合や未対応loaderでは停止前に拒否する。401は匿名の認証gateだけの確認で、認証済みHTTP・モデル実行の証明にはしない。認証情報や公開health endpointは追加しない。

更新失敗時はcandidateの停止を確認し、現在DBを別backupに保持する。schema・全テーブル値・rowid・採番状態・DB versionが更新前と等しく、旧codeの同一性も確認できた場合だけ旧codeへ自動復帰する。新DB作成、schemaだけの変更、同件数の未同期更新、検査不能、code drift、停止失敗では自動復帰せず、現在DBと新旧codeを保持して管理者の確認を待つ。DB変化がある場合はDashboardを停止したままにするため、同じプロセスの他機能も確認まで利用できない。古いDBの自動復元や未同期変更の破棄は行わない。将来の移行互換性をこの同一性検査で保証するものではない。

DBの対象は固定の既定pathから推定せず、稼働中MainPIDの開始時刻を照合し、そのprocessの `CAREER_DATA_DIR` とcwdから既存の実保存先を停止前に確認する。環境の他の値は表示・記録しない。絶対pathと現在cwd基準の相対pathを扱い、保存先やprocess metadataが確認できない場合は停止前に拒否する。candidateはAPI/router生成前に同じ実保存先であることを検査し、process内の既定保存先を確認済み絶対pathへ固定する。candidateの保存先変更、復旧時に確認できないprocess metadata、runtime.envの変更でも自動復帰を止める。

不具合時はcareerのtimerを停止・disableし、Dashboardのcareer enable設定とdrop-inをバックアップ時点へ戻して再起動する。career DBと元ファイルは保持する。DBを過去backupへ戻す前に現在DBを別名backupし、未同期変更を確認する。GitHubの成功済み書き込みは自動revertしない。移行・公開・データ消去を復旧の近道に使わない。

## 参照実装

`career_core/vendor/v40` と `v41` はMITのjp-job-hunting-coachの純粋なschema/validation/migration helperを同梱している。個人stateや会話は含まない。変更はpackage-relative importのみ。元ファイルのSHA-256は `vendor/upstream-sha256.json`、ライセンスは各ディレクトリのLICENSEを参照。

検証結果と実環境で未完了の項目は [コーチ0.2の検証記録](../../docs/career/COACH_VALIDATION.md)、既存機能は [初版の検証記録](../../docs/career/VALIDATION.md) を参照。
