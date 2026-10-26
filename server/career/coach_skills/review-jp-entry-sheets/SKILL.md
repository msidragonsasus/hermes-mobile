---
name: review-jp-entry-sheets
description: 日本の新卒採用向けESの既存本文を、設問回答性、本人事実、論理、役割、判断、企業固有性、他設問との整合、字数まで厳しく添削・再構成する。「ESを添削」「厳しく評価」「内容を直して」「提出レベルへ」で使う。誤字だけはproofread、新規作成はdraftを使う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# 日本語ESを内容添削する

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/evidence-policy.md`、`references/question-policy.md`、`references/state-management.md`、`references/github-chat-storage.md`、`references/es-quality-gates.md`、必要に応じて `references/industry-adjustments.md`

## 手順

1. 設問、字数、企業・職種・コース、元文、修正禁止範囲、応募案件IDを確認する。
2. 関連fact、story、application、document metadataに加え、今回の整合性確認に必要な関連設問の本文または主要主張と根拠の要約を取得し、別企業・別コース・別年度の混入を検査する。Hermes版はcareer_contextのsaved_documentsを読み、必要ならdocument_offsetで本文の続きを取得する。参照できない設問間の整合性は未確認と明示する。未接続でも添削は止めない。
3. 元文を、結論、状況、課題、役割、判断、行動、修正、結果、学び、企業接続へ分解する。
4. `提出停止 / 要修正 / 提出可` の品質ゲートで判定する。点数を通過確率へ変換しない。
5. 最優先の問題を最大3点に絞り、なぜ弱いか、何を削るか、どの本人事実が必要かを示す。
6. 方向を大きく変える不足だけ最大3問する。既知事実だけで改善できる場合は先に修正版を出す。
7. 元文の本人らしい表現と有効な具体性を残し、確認済み事実だけで再構成する。
8. 文字数、禁止文字、他設問との候補者像の重複を確認する。
9. Hermes版は依頼された修正版をcareer_saveでstatus:draftingの新しい版として保存し、提出・採用の本人操作とは区別する。直接GitHubを使う互換経路では、本人が候補を採用した後だけimmutableな新しい版をcreateし、成功後にSHA-safeにstate pointerを更新する。失敗時は本文を失わず旧版を有効維持し、orphanを報告して自動削除しない。Local v3互換時だけ従来scriptを使う。

## 出力

本文だけを求められた場合は、修正版・文字数と計測方式・提出を止める重大な未確認点だけを示す。以下は通常レビューの確認項目。

1. **判定**
2. **提出を止める問題**
3. **最優先の改善点**（最大3点）
4. **提出候補**（原則1案）
5. **文字数と計測方式**
6. **修正版にも残る弱点**
7. **他設問・応募案件との整合性**
8. **版更新候補**

情報不足を能力不足と扱わず、通過を保証しない。
