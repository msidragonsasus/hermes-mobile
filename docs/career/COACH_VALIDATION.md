# 就活コーチ 0.2 実装・検証記録

検証日: 2026-09-08（JST）。ブランチ: `feat/career-coach`。ベース: `719a809810f69503e2aafcc5ba5a0e295b63e27f`。

## 変更

- ホームを「今日の提案＋会話」へ変更。企業・応募・本人情報の補助画面を保持し、通常Chatへの下書き引き渡しを廃止。
- 独立したコーチ会話の暗号化キャッシュ、入力途中の文、相談対象、未完了操作。接続先・profileを固定したHTTP通信と差分イベント取得。
- 会話内から成果物を開き、同じ会話へ戻って修正を依頼。通常Chatのセッション・入力欄を変更しない。
- Hermes本体の `AIAgent` を使用する実行アダプター。就活Skill 23本・参照を含むMarkdown 184本を移植し、Hermesへの登録と段階的な読取を実装。
- Codex OAuthのみを許可し、fallback・有料検索・任意シェル・外部送信ツールを提供しない。調査の直接HTTPモデル呼出をHermes実行に置き換え。
- SQLiteへ会話・作業・イベント・操作結果を追加。キュー処理、停止、明示再開、再起動後の中断、同じ操作の再送、作業単位の保存結果を保持。
- 依頼された下書き・計画の保存。本人事実は発言IDと原文を照合し、訂正履歴・提出版を保持。応募判断は本人操作。
- 日次実行は09:00 JSTに調査と次の行動の整理をキューへ追加。手動メッセージを企業単位で優先し、停止後の調査結果は保存しない。
- 生の会話はサーバーだけに保持。完了した構造化checkpointを `last_session.hermes_coach[run_id]` に追記し、ほかのChatの情報と4.0を保持。4.1適用は追加していない。

## 検証

サーバーと画面のテストは合成データで実行。実Hermesによるモデル応答の品質検証とは区別する。

| 確認 | 結果 |
| --- | --- |
| Python unittest | 45件成功。既存25件＋会話・保存・中断等20件 |
| コーチViewModel単体テスト | 6件成功。未送信保持、同じ操作の再送、保存失敗時の通信拒否、対象変更、画面終了時の継続、再接続 |
| 就活画面テスト | エミュレーターで6件成功。ホームから送信、成果物を開いて修正、本人の応募操作、保存前の差分、文字サイズ2倍 |
| 画面キャプチャ | ホームと成果物の2ケースを再実行して成功。画像を目視確認 |
| Android Debug単体テスト | 128スイート、1,418件成功。失敗・エラー・スキップなし |
| Android全体ゲート | ktlintCheck、checkColorLiterals、lintDebug、assembleDebug、compileReleaseKotlin成功 |
| Hermes本体の契約調査 | 基準commitのAIAgent、run_conversation、register_skill/register_tool、Dashboard mount、停止APIをソース確認 |
| 実Hermesの通し動作 | 未実施。SSH管理者認証で停止 |

配布APKは前回Driveへ置いたAPKとapplicationId（`com.m57.hermescontrol`）、versionCode（1）、署名証明書が一致することを確認した。署名SHA-256は `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`。実機での更新可否・データ保持を検証したという意味ではない。

初回の新規単体テスト2件は初回読込完了前に長い差分取得間隔を選ぶ点を検出した。読込完了後に監視を始めるよう実装を修正し、対象6件が成功した。初回全体ゲートの失敗を成功として扱わない。

検証中にAndroidビルドが重なり、Windowsのclasses.jarファイルロックで1回失敗した。以後はGradleを順次実行。画面キャプチャはAGPのadditionalTestOutputDirから回収し、テスト環境の終了後も確認できるようにした。

ログ・画像・配布物はリポジトリ外の `../career-validation/` に保存する。`coach-server-tests-final.log`、`coach-unit-targeted.log`、`coach-ui-tests.log`、`coach-ui-capture.log`、`coach-android-gates-final-pass.log` が各検証の記録。最後のAndroidゲートは既に成功した1,418件の単体テスト結果をUP-TO-DATEとして再利用した。直前のゲートは画面キャプチャ補助関数の書式だけで失敗しており、その書式を修正した。

## 実環境で残る確認

- `keiya@100.103.190.68` への既存SSH接続は `Permission denied (publickey,password)`。実HEAD・設定・権限の確認、バックアップ、配置、再起動、timer有効化を行っていない。
- `python smoke_coach.py --live` を実Hermes環境で実行する必要がある。合成データの一時DBでSkill読込・事実と下書き保存・再開・訂正・版保持・探索の回答待ちを確認する。未実行のため、Hermes連携が稼働したとは扱わない。
- 既存Codex OAuthでの実行、実GitHub同期、日次無人実行、実転送ファイルの本人による復号と取り込み、実機のデータ保持更新は未確認。
- スマートフォン実機には更新・アンインストール・データ消去を行っていない。今回の画面テストはAGP管理のエミュレーターで実施。
- push・PR公開・本番導入は行っていない。個人state、会話、認証情報、実転送ファイルはコード配布物に含めない。

導入手順は [サーバーREADME](../../server/career/README.md)。初版の既存機能の検証記録は [VALIDATION.md](VALIDATION.md)。
