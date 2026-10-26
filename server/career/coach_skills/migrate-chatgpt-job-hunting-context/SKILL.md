---
name: migrate-chatgpt-job-hunting-context
description: ChatGPTが現在参照できる保存済みメモリ、過去Chat由来コンテクスト、ユーザー提示のMemory Summary、Project資料、ChatGPTデータエクスポートから、就活関連情報だけをprivate GitHub v4状態への移行候補にする。「記憶を移して」「過去チャットから状態」「データエクスポートを取り込む」で明示的に使う。完全取得やraw保存は行わない。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# ChatGPTコンテクストをv4状態候補へ移行する

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/chatgpt-context-migration.md`、`references/github-chat-storage.md`、`references/evidence-policy.md`、`references/ingestion-policy.md`、`references/state-management.md`、`references/state-patch.md`、`references/privacy-policy.md`

## 手順

1. GitHub connector、対象private repository、v4 state、`import_chatgpt_context`同意を確認する。未接続でも候補previewは作れるが未保存と明示する。
2. 取得範囲を`SURFACED_CONTEXT_ONLY / MEMORY_SUMMARY_INCLUDED / PROJECT_CONTEXT_INCLUDED / DATA_EXPORT_INCLUDED`で明示する。全保存メモリ・全過去Chatを取得したと表現しない。
3. 現在実際に提供されたコンテクスト、貼付Memory Summary、読めるProject資料、添付exportだけを使う。Project外を取得済みと扱わない。
4. export ZIPは安全検査し、user発言とmodel回答を分離する。raw ZIP、生Chat、展開原本はGitHub payloadにしない。ローカル/Codexでは`scripts/import_chatgpt_export.py`をpreview-onlyで使える。
5. `ingest-jp-job-hunting-materials`の分解規則を再利用し、fact、story、application、document metadata、selection event、hypothesis候補へ分ける。
6. `CONFIRMED / INFERRED / UNKNOWN / CONFLICTED / SUPERSEDED / SENSITIVE_REQUIRES_OPT_IN / SKIPPED_NOT_JOB_HUNTING`を付け、モデル清書内だけの数字・因果を本人事実へ昇格しない。
7. provenanceを付け、即時候補、要確認、矛盾、sensitive除外、非就活除外のpreviewを返す。
8. ユーザーが承認した項目だけをSHA-safe write-throughする。一括承認でもsensitiveと第三者PIIは分離し、第三者連絡先は保存しない。

## 出力

1. **取得範囲と限界**
2. **候補件数**
3. **そのまま取り込める候補**
4. **確認が必要／矛盾**
5. **sensitive／非就活除外**
6. **承認後の保存結果またはUNSAVED_JOBHUNT_PATCH_V4**

「完全移行した」「すべてのChatGPTメモリを取得した」と表現しない。
