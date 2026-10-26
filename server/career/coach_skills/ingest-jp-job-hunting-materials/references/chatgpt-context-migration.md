# ChatGPT Context Migration Policy

移行対象は現在実際に参照できる範囲だけであり、全保存メモリ・全過去Chatの完全取得を保証しない。

## Scope levels

1. `SURFACED_CONTEXT_ONLY`: 現在のChatへ提供された保存済みメモリ、過去Chat由来コンテクスト、カスタム指示、現在Chat。
2. `MEMORY_SUMMARY_INCLUDED`: ユーザーが貼り付けたMemory Summary。「私について何を覚えている？」への回答も完全一覧とは扱わない。
3. `PROJECT_CONTEXT_INCLUDED`: このProjectで実際に読める添付・過去Chat・資料。Project外を取得済みと扱わない。
4. `DATA_EXPORT_INCLUDED`: ユーザー提供のChatGPTデータエクスポートZIP。安全検査後、就活関連だけを候補化する。

Project-only環境で情報が少ない場合は、通常Chat／デフォルトメモリのProject、Memory Summary貼付、またはデータエクスポートZIPを案内する。取得できない情報を存在しないと判断しない。

## Classification and provenance

候補を`CONFIRMED / INFERRED / UNKNOWN / CONFLICTED / SUPERSEDED / SENSITIVE_REQUIRES_OPT_IN / SKIPPED_NOT_JOB_HUNTING`へ分類する。現在の本人明言、明確な過去の本人発言で時間変化しにくく矛盾がないものだけCONFIRMED候補にできる。現在の応募状態、締切、志望、学年、勤務地、古い自己評価、モデル清書内だけの数字・因果は原則再確認する。

根拠はrecordのprovenanceに`source_kind`、`source_role`、`source_ref`、`source_date`、`imported_at_jst`、`confidence`を持たせる。source kindは`CHATGPT_MEMORY_SURFACED / CHAT_HISTORY_SURFACED / PROJECT_CONTEXT / USER_PASTED_MEMORY_SUMMARY / CHATGPT_DATA_EXPORT / CURRENT_USER_STATEMENT`を区別する。source_roleは本人発言・本人一次資料・モデル要約・生成文書・外部・不明を分ける。exportやMemoryという取得経路だけから、生成主体を本人と認定しない。

## Preview and write boundary

取込前に取得範囲、候補件数、即時候補、要確認、矛盾、sensitive除外、非就活除外をpreviewする。ユーザーが承認した項目だけをv4へ追加する。一括承認でもsensitive情報と第三者PIIは別同意とし、第三者連絡先はGitHubへ保存しない。

```text
取得範囲:
  SURFACED_CONTEXT_ONLY
  MEMORY_SUMMARY_INCLUDED
  PROJECT_CONTEXT_INCLUDED
  DATA_EXPORT_INCLUDED

今回確認できたもの:
  本人事実候補: XX
  経験素材候補: XX
  応募案件候補: XX
  文書候補: XX
  選考観測候補: XX
  仮説候補: XX

そのまま取り込める:
  ...
確認が必要:
  ...
矛盾:
  ...
sensitiveのため既定除外:
  ...
就活と無関係のため除外:
  ...
```

生チャット、raw ZIP、展開原本、添付原本、モデル内部推論はGitHubへ保存しない。必要最小限の構造化情報とprovenanceだけを保存する。データエクスポート原本は削除しない。

## Export safety

ZIPはpath traversal、絶対path、backslash path、symlink、正規化重複、file数、個別size、総展開size、異常圧縮率を検査する。`conversations.json`とHTML等を検出し、user発言とmodel回答を分離する。Pythonは安全な抽出・正規化・候補化だけを行い、LLM判断が必要な本人事実を確定しない。既定はpreview-only。
