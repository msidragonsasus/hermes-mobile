---
name: draft-jp-entry-sheets
description: 日本の新卒採用向けESを、本人の箇条書き、会話、経験バンク、企業調査から新規作成する。ガクチカ、自己PR、志望動機、応募理由、強み弱み、挑戦、チーム、学業の本文・骨子・字数版で使う。既存文の内容修正はreview、誤字だけはproofread、経験発掘はbuild-jp-career-profileを使う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# 日本語ESを新規作成する

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/evidence-policy.md`、`references/question-policy.md`、`references/state-management.md`、`references/github-chat-storage.md`、`references/es-quality-gates.md`、必要に応じて `references/industry-adjustments.md`

## 手順

1. 設問全文、最小・最大字数、企業、職種・コース、選考区分、禁止文字、希望文体を抽出する。
2. private GitHub v4 stateから関連fact、story、application、document metadataだけを取得し、応募案件IDと文書IDを照合する。未接続でも本文作成は進める。
3. 使用可能なプロフィール、経験、企業事実を `CONFIRMED / INFERRED / UNKNOWN / EXTERNAL` に分ける。
4. 方向を大きく変える不足だけ最大3問する。確認前でも安全な骨子・暫定案は先に出す。
5. 抽象的な強みを先に決めず、具体的な判断・行動・修正から主張を一つ選ぶ。
6. 設問へ直接答える一行骨子を作り、背景より本人の役割、判断、行動、接続へ字数を使う。
7. 企業固有情報は、本人側の根拠と接続できるものだけ使う。
8. 本人が面接で普段の言葉へ戻せない過剰な美文、定型的な「〜力」、不自然な因果を避ける。
9. 実行環境に補助スクリプトがあれば、このSkillの `scripts/count_chars.py` で文字数を検証する。実行不可なら計測方法と限界を明示する。
10. 品質ゲートと他設問との重複・矛盾を確認する。
11. 保存時は新しい`.jobhunt/documents/DOC-###-v###.md`を先にcreateし、成功後だけSHA-safeにstate pointerを更新する。state更新失敗時は本文を回答にも残し、旧版を有効維持し、orphanを報告して自動削除しない。Local v3互換時だけ従来scriptを使う。

## 出力

1. **設問意図**
2. **使用する確認済み事実ID**
3. **一行骨子**
4. **提出候補**（原則1案）
5. **文字数と計測方式**
6. **残る重大な要確認**（最大3点）
7. **文書・状態の更新候補**

本文だけを求められた場合は、提出候補、文字数、提出を止める要確認だけを示す。上限を機械的に埋めない。
