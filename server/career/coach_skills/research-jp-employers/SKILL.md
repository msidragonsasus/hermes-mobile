---
name: research-jp-employers
description: 日本の新卒採用・インターン向けに、企業、事業、職種、募集要項、設問、締切、プログラム、処遇、選考上の確認事項を最新の公式情報から調査する。「企業研究」「採用ページ」「締切を確認」「この会社ならでは」「募集内容」で使う。複数社の選定はplan、公開通過ESはbenchmarkを使う。
---

> Hermes版: 接続・保存・実行はHERMES.mdの契約とcareer_*ツールを使う。本文の業務手順・証拠規約・質問規約は維持する。


# 企業・募集を公式情報から調べる

## 読むもの

現在の依頼に必要な参照だけ読む。本人事実を扱うときは証拠規約、対話では質問規約を確認する。状態に依存する最初の処理でstate-management、実際の接続・読書込でgithub-chat-storageを読む。state-patchは変更・復旧、移行資料とv3資料は該当作業時だけ読む。Pythonが使える場合の素材選択・再開確認には `scripts/coaching_state.py`、4.0読込には `scripts/migrate_state_v40.py` を使える。通常Chatでの実行を仮定しない。

`references/evidence-policy.md`、`references/question-policy.md`、`references/state-management.md`、`references/github-chat-storage.md`、`references/web-research-policy.md`、`references/research-output-schema.md`

## 手順

1. 企業名、卒年、選考区分、職種・コース、地域、ユーザーが判断したいことを特定する。最新の本人訂正を優先する。
2. 現在変わり得る情報はWebで確認し、別年度・別コースを混ぜない。
3. 企業事実を `CF-###`、募集事実を `RF-###`、締切を `DL-###`、出典を `SRC-###` として整理する。
4. 公式情報、外部補助情報、推論を分ける。
5. 締切に `deadline_precision` を付ける。時刻不明を補わない。
6. 志望動機に使える接続は、確認済み本人事実と企業事実の組合せだけを候補にする。
7. 「この会社ならでは」は、比較対象と差分根拠がある範囲だけで述べる。
8. 出典の確認日時と鮮度をv4 source候補へ記録し、同意済みならSHA-safeに保存する。更新できない場合は未保存差分を返す。

## 出力

1. **調査対象**（年度、区分、コース、地域）
2. **公式事実**（ID、要点、出典）
3. **募集・締切**（絶対日時、精度、確認日時）
4. **本人との接続候補**（CONFIRMED / INFERRED）
5. **比較して初めて言える差分**
6. **志望理由に使わない一般論**
7. **未確認・アクセス不能・鮮度リスク**
8. **状態差分**

公式情報がないことを隠さない。社風、採用基準、通過理由を公式記述の範囲を超えて断定しない。
