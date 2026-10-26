---
name: ingest-jp-job-hunting-materials
description: 過去ES、履歴書、自己分析、面接メモ、応募一覧、チャット書き出し、PDF、表計算等の就活資料を読み、本人事実、経験、応募、文書、選考観測、外部情報へ重複なく構造化する。「資料を取り込む」「過去ESを整理」「このZIPから続けられる状態に」「既存台帳を移す」で使う。単なるES添削はreview、初回相談だけならstartを使う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# 就活資料を安全に取り込む

散在した資料を、清書表現を事実と誤認せず、GitHub v4状態への候補へ変える。Local/Codexではv3互換経路も使える。

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

1. `references/ingestion-policy.md`
2. `references/evidence-policy.md`
3. `references/question-policy.md`
4. `references/state-management.md`
5. `references/state-patch.md`
6. `references/privacy-policy.md`
7. `references/github-chat-storage.md`
8. ChatGPTコンテクスト移行時は`references/chatgpt-context-migration.md`

## 手順

1. 利用できる添付・ファイルだけを列挙し、読めない資料を取得済みと扱わない。
2. 各資料に、資料種別、本人作成／外部、対象企業・年度・コース、更新日、信頼度を付ける。
3. fact、story、application、task、document metadataとimmutable版、selection event、hypothesis、sourceのv4 collectionへ分解する。
4. 清書案だけにある数字、役職、感情、因果は `DRAFT_TEXT` の候補に留める。本人確認なしに `CONFIRMED` にしない。
5. 既存IDとの重複、矛盾、別年度・別コース混同を検査する。
6. 結論が変わる矛盾だけ最大3問で確認する。確認前でも、安全な追加・保留候補は作る。
7. ChatGPT exportから委譲された場合はraw ZIP、生Chat、展開原本を保存せず、user発言とmodel回答を分離した候補だけを扱う。
8. GitHub stateへ書く前に候補をpreviewし、承認された項目だけをSHA-safe write-throughする。未接続・失敗時は`UNSAVED_JOBHUNT_PATCH_V4`を出し、「保存した」と表現しない。
9. Local v3互換時だけ従来の`JOBHUNT_PATCH_V3`を使い、v4とdual-writeしない。

## 出力

- 読み取れた資料と読めなかった資料
- 新規／更新／重複／矛盾／保留の件数
- 確認が必要な事実候補
- 状態パッチのプレビューまたは未保存パッチ
- 次に使えるSkill
