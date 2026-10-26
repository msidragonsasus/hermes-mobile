# 就活通知の絞り込み・内容表示（2026-09-26）

## 実装した動作

- OS通知は選考結果、面接・面談の予約、提出・回答依頼、期限変更、本人による再認証が必要な状態に限定する。
- ホームと通知の分類は `career_core/notice_policy.py` で共通化。保存済みの件名・本文を判定し、追加のAI実行はない。「結果は後日通知」は結果として扱わない。
- 通知は接続先・プロフィール・会社ごとに1件。会社名、用件、件名、短い原文引用、明記された期限を表示する。本文を展開でき、公開版には会社名や案内の内容を含めない。
- 同じ元資料の新しい版は表示中の古い版を置き換える。元資料自体は保持する。再認証は未解決中に1回だけ通知し、復旧時に取り消し、再発時に新たな通知を出す。
- 単独の通知は保存済みの該当資料、複数件は該当会社の通知一覧、再認証は該当設定へ遷移する。通知Intentには既存の不透明なUUIDトークンだけを渡し、ルートと案内一覧は暗号化した端末内保存を利用する。
- メールは会社・アカウントごとの初回取得開始時刻以前の受信分を通知しない。Androidのメール取得は、空のメールボックスでも取得開始をサーバーへ記録する。過去メールの続きのページでも同じ基準を使う。
- マイページBotは接続先URL・プロフィール・会社ごとの最初の取得が完了するまで通知しない。初回のpartial、checkpoint、再実行をまたいでも履歴を通知しない。旧マイページ・pilot取得も初回を通知しない。
- 初回のv2通知チェックで旧通知を取り消し、フィードの末尾へカーソルを移す。未配信の過去通知は再通知しない。通常チェックでは通知対象外・別プロフィールのページも走査カーソルを進める。
- 会社ごとの配信済みカーソルも保存する。一部の会社で配信済み、別の会社で失敗した場合も、次の取得で配信済み分を繰り返さない。Androidへの引き渡し前に走査カーソルは進めない。
- 新着判定、通知の取り消しは既存の15分間隔のWorkManagerチェックで反映される。実際の実行時刻はAndroidのバックグラウンド制約による。

## API・保存の互換性

- `GET /api/plugins/career/collection/notices` の既存フィールドを維持し、`eligible`, `company_name`, `category`, `label`, `quote`, `deadline_text`, `source_ref` を追加する。`source_ref.key` は元資料単位の不透明な安定識別子。
- `baseline=true` は通知を返さずフィード末尾のカーソルを返す。旧サーバーがこの引数を無視する場合も、Androidは履歴を表示せず走査する。
- `GET /api/plugins/career/collection/notices/{notice_id}` で該当資料の保存済み本文を返す。Dashboard認証、会社・プロフィールの照合を維持する。
- Android側の追加フィールドは省略可能。旧サーバーの詳細不足の通知は表示しない。
- SQLiteには `career_notice_details` を追加する。既存のフィード・資料・アカウント・メール・企業情報は削除しない。古いフィード行はOS通知対象に昇格させない。
- 新しい共通分類ファイルとテストをサーバーのリリース許可リストへ追加済み。適用時はAndroid更新を先に行えば、旧サーバーの汎用通知は表示されなくなる。

## 今回の検証

| 対象 | 結果 |
| --- | --- |
| Python: notice_policy / home / mypage_bot / collection / mail_collection / release_package | 105件成功（13 / 14 / 35 / 30 / 10 / 3） |
| Android: CareerNoticePresentation / CareerNoticeDelivery / CareerGmailSync / ContributionRegistry | 24件成功（8 / 4 / 6 / 6） |
| Pixel_9_Proエミュレーター: CareerNoticeNotificationTest | 2件成功。通知本文、公開版、通知フラグ、不変Intent、暗号化保存の再オープン、プロフィール分離を確認 |
| assembleDebug / compileReleaseKotlin / ktlintCheck / lintDebug | 成功 |
| git diff --check | 成功 |

追加で実行した `test_mypage_native.py` は34件中31件成功、3件エラー。`execute_fixture` が指定するOpenRouterの無料モデルを、現在の `selected_mypage_execution` の専用モデル条件が拒否する `mypage_private_model_required`。今回の通知変更でその実行ポリシーやテストモデル条件は変更していない。

最初のCodex_Personal_AI_Phase1エミュレーターはAPK更新時の容量不足でテストを開始できなかった。アプリのデータ消去・アンインストールはせず、空き容量のあるPixel_9_Proで検証した。

## 未適用・未検証

- 実機・運用サーバーへの適用、Gitのコミット・push・PR公開は実施していない。
- 実際にユーザーへ出ていた通知の内訳、実データを使った通知の発生数、実機の通知欄・ロック画面の見え方は今回の検証に含まない。
- 分類は明示的な日本語パターンに基づく。未認識の表現はOS通知されず、元資料はアプリ内に残る。合否・期限・必要な対応を生成AIで補完しない。
- 適用は別途承認後に行う。サーバーは通常のバックアップ・リリース確認、実機は同じ署名・applicationIdによる `adb install -r` を使い、データを保持する。

## 再利用の確認

実装前にAndroid公式の公開通知サンプルと標準通知APIを確認した。既存のNotificationCompatを利用し、BigTextStyle、publicVersion、onlyAlertOnceによる表示と繰り返し抑制を採用した。新しい通知SDKや外部サービスは追加していない。

- https://github.com/android/user-interface-samples
- https://developer.android.com/reference/kotlin/android/app/Notification.BigTextStyle
