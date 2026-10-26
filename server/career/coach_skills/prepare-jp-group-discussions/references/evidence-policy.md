# 本人事実・証拠ポリシー

## 区分

- **CONFIRMED**: 本人が今回明示した、本人が訂正した、または本人の一次資料で確認できた事実。
- **INFERRED**: 確認済み事実からの推論。本人事実として確定保存しない。
- **UNKNOWN**: 結論へ影響するが未確認の事項。
- **SUPERSEDED**: 本人の訂正等により置き換えられた旧情報。
- **EXTERNAL**: 企業、募集、統計、公開ES、第三者など本人以外の情報。
- **CONFLICTED**: 信頼できる情報同士が食い違い、解消していない状態。
- **SENSITIVE_REQUIRES_OPT_IN**: 本人の明示的な保存同意が必要な機微情報。未同意の内容を保存しない。
- **SKIPPED_NOT_JOB_HUNTING**: 就活目的の対象外として取り込まない情報。本文を保存する許可ではない。

EXTERNALは出所の区分であり、schema 4.1のfactのclassificationではない。企業・募集等の外部情報はsources等の対応するcollectionへ保存し、本人事実へ変換しない。

## 出所と時点で判断する

GitHubは記録の正本であり、保存場所だけで主張を真実と認定しない。今回の本人による明示的な訂正を優先する。本人一次資料、本人の過去発言、モデル要約、生成文書、外部資料を区別し、出所・時点・本人確認・参照可能性で扱いを決める。

現在と過去が食い違うときは、明示的な訂正か、時点による変化か、未解消の矛盾かを区別する。単に新しい記述だから過去を誤りにしない。過去ESや現在のユーザーメッセージに引用されたモデル文章も、本人がその主張を認めた発言とは別にする。

## 証拠ID

- `FACT-###`: 構造化された本人事実・推論候補
- `ST-###`: 経験・ストーリー
- `CO-###`: 企業候補
- `APP-###`: 応募案件
- `TASK-###`: 作業
- `DOC-###`: 提出文書
- `EV-###`: 選考観測
- `SRC-###`: 出典（本人資料と外部資料の出所を区別）
- `HYP-###`: 強み・適合・選考原因等の仮説

重要な主張は根拠IDへ追跡できるようにする。IDが未採番でも作業を止めず、保存時に安定IDを付ける。

## 確定条件

v4のfactを`CONFIRMED`にできるのは、現在の本人明言、明確に本人発言と識別できる過去情報、または本人一次資料に追跡できる場合だけ。モデル回答、清書案、`INFERRED`、`UNKNOWN`を本人事実へ自動昇格しない。

schema 4.1のfactは`classification`と空でない`claim`を必須とする。CONFIRMEDの`provenance`には取得経路`source_kind`、生成主体`source_role`、空でない位置情報`source_ref`、発言・資料の日付`source_date`（不明はunknown）、取込時刻`imported_at_jst`、`confidence`を持たせる。

`source_kind`の許可値は`CURRENT_USER_STATEMENT / USER_PRIMARY / CHAT_HISTORY_SURFACED / CHATGPT_DATA_EXPORT / PROJECT_CONTEXT / CHATGPT_MEMORY_SURFACED / USER_PASTED_MEMORY_SUMMARY / EXTERNAL / LOCAL_V3_MIGRATION / LEGACY_NOTE`。今回の本人発言・訂正には`source_kind="CURRENT_USER_STATEMENT"`と`source_role="USER_STATEMENT"`を使う。`current_chat`、`USER_DIRECT`などの別表記を新規4.1データへ書かない。本人一次資料には`USER_PRIMARY`と同名role、外部情報には`EXTERNAL`と同名roleを使う。Memoryは`source_kind="CHATGPT_MEMORY_SURFACED"`、貼付Memory Summaryは`source_kind="USER_PASTED_MEMORY_SUMMARY"`とし、どちらも`source_role`だけを`MODEL_SUMMARY`または`UNKNOWN`にする。未分類の旧移行・旧メモもroleは`UNKNOWN`。過去会話・export・Project経由では、取得経路からroleを決めず実際の生成主体を確認する。

`source_role`は`USER_STATEMENT / USER_PRIMARY / MODEL_SUMMARY / GENERATED_DOCUMENT / EXTERNAL / UNKNOWN`。CONFIRMEDの根拠になれるのは前二者である。confidenceがhighでもモデル文章を本人事実へ昇格しない。source IDの形式・参照先・役割を検査したことと、出典が主張を実際に支持することは別に確認する。参照不能なら到達不能を明示し、生成文書を根拠の代用品にしない。

SRCを追加するだけで確定させない。source_refは、実際に読んだ資料の頁・節、または本人発言の会話・発言位置へ追跡できるものにする。存在しないChat URLやlocatorを作らない。本人確認の短い構造化要旨は残せるが、生チャットや資料全文を保存しない。

## 禁止

- 数字、人数、期間、役職、発言、感情、反対、制度、原因、施策、成果、因果の創作
- チーム成果を本人単独の成果へ変換すること
- 不合格・通過から原因を断定すること
- 企業の表現を本人の価値観として貼り付けること
- 情報不足を能力不足と断定すること

## 保存前の確認

INFERREDをCONFIRMEDへ変更する場合は本人の明示確認を得る。訂正で旧情報が不要になった場合も削除せず `SUPERSEDED` とし、変更履歴を残す。

事実誤りは旧factのclaimを上書きせず、`FACT_CORRECTION` decisionと根拠を保存し、必要な場合だけ新factへ関連づける。撤回だけなら代替事実を作らない。設問上の不採用・文体の不採用・一時的な方針は別decisionとして対象と適用範囲を残す。訂正に依存するstory・未提出文書は再確認し、提出済みの本文と版は歴史として維持する。

## 本人の役割と本音

異なる経験を、話題の類似だけで一つの実績へ混ぜない。同一経験でも、本人が教わったこと、本人が支援したこと、他者が行ったことを分ける。

待遇など私的な選択理由、仕事への関心仮説、企業で実現したいこと、応募文で説明できる接点を分ける。本音を企業への架空の情熱・原体験へ置き換えない。接点が弱ければ弱さを示し、本人が確かめられる問いにする。社員一人の証言はその人・部署・時点の観測であり、全社制度とは扱わない。
