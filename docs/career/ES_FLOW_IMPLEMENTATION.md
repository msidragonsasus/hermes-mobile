# 企業詳細からES回答への改修記録

2026-09-13。`ES_FLOW_MINIMAL_PLAN.md` と添付指示書に対する、ユーザーの「実装」指示を受けたローカル改修。

## 実装した導線

企業詳細 → 対象募集のES作業 → 任意の設問 → 回答の編集／相談 → 差分確認・保存 → 戻る → 前回の続き。

- 企業詳細の主操作をESに変更。全設問の原文表示をES側へ移し、募集・締切・提出条件と状況を表示。企業相談と面接準備は補助操作として維持。
- ES作業では短い見出し、主要制約、採用版・候補・本人確認の状態を表示。全設問と原順への経路を維持。同じ見出しの設問は原文確認を促し、原文中の設問番号を補助表示する。
- 本文と相談を切り替えても、本文・未送信の相談・スクロール位置を保持。募集、設問ID・版、基準文書版、未保存本文を既存の会話APIに渡す。
- 既存の本人情報を参照するCoachの処理を再利用。生成案は既存の文書版として保存し、現在採用している版とは分離。採用時は本文を置き換える確認を出す。
- 手動保存も既存の文書保存・版履歴・操作IDによる重複防止を利用。保存、採用、本人確認、企業への提出は別の状態。
- 編集内容と保留操作を既存の暗号化 `CareerLocalStore` に保持。通信失敗、設問変更、サーバー版の競合、端末保存失敗で本文を捨てない。長い貼り付けも切り捨てず制約違反を表示。
- サーバーの既存metadataに採用版と本人確認した版を記録。新規テーブル・DB移行・別のLLM接続は追加していない。

## 主な変更箇所

| 対象 | ファイル |
| --- | --- |
| 企業詳細と開始・再開 | `CareerCompanyScreen.kt`、`CareerCompanyViewModel.kt`、`CareerCompanyModels.kt` |
| ES作業・本文・復元 | 新規 `CareerEsScreen.kt`、`CareerEsModels.kt`、`CareerEsViewModel.kt`、既存 `CareerLocalStore.kt` |
| 既存画面・会話の再利用 | `CareerDialogs.kt` の本文入力、`CareerDocumentReadDialog.kt` の版閲覧、`CareerCoachPanel.kt`、`CareerCoachViewModel.kt` |
| 局所的な遷移登録 | `CareerNavigation.kt` の `CareerEsKey` と `Navigation.kt` のentry追加 |
| 保存と相談の契約 | 新規 `server/career/career_core/es_work.py`、既存 `api.py`、`shared.py`、`company_views.py`、`coach_store.py`、`coach_runtime.py`、`coach_tools.py` |
| 制約と取得元 | `portal_reader.py`、`collector.py`、`collection_store.py`。必須条件が既知か、改行禁止かを保持し、条件変更も設問版へ反映 |
| 配布への収録 | `server/career/tools/release_files.json` に新規モジュールとテストを追加 |

作業開始時に存在した未コミット差分は保持。開始時の対象コードを保存した `career-validation/es-flow-20260913/baseline.zip` と比較して今回の変更を確認した。

## 検証

| 検証 | 結果 |
| --- | --- |
| Android全単体テスト | 1,445件成功、失敗・スキップ0。その後の表示調整と長文保持は下記の関連テストで再確認 |
| 最終差分のCareer・遷移登録テスト | 41件成功、失敗・スキップ0。長文貼り付けの保持テストを含む |
| サーバー全単体テスト | 128件成功。追加9件は保存・再試行・再開・版競合・設問制約・相談本文固定・募集／設問分離・4.1文書・HTTP契約を検証 |
| Compose操作テスト | 14件成功。ESの8件は幅360dp／412dp × 文字倍率1.0／1.5の一覧・編集を検証。企業画面の既存6件も成功 |
| `ktlintCheck`、`checkColorLiterals`、`lintDebug` | すべて成功 |
| `assembleDebug`、`assembleDebugAndroidTest`、`compileReleaseKotlin` | すべて成功 |
| 既存差分の空白検査 | `git diff --check` 成功 |

Composeでは合成データのRepositoryと、エミュレーター内の実際の暗号化ローカル保存を使用。入力→相談切替→保存失敗→画面モデル再作成→同一操作で再試行→保存成功→再作成後の本文・文書版を確認した。端末の既存実入力は使用していない。

サーバーでは一時SQLiteを使用し、HTTP保存→企業詳細の採用版取得→保存本文の再読込も確認。GitHub同期と外部モデルはスタブ化しており、外部書き込みやAPI課金は行っていない。

## 画面確認

合成データによるエミュレーター画像。文字拡大時の企業名の折り返しを修正し、既存Scaffoldの余白を重複適用していないこととキーボード上の相談入力を確認。

- [企業詳細・360dp／拡大文字](E:/OSHIIRE/software/Hermes/career-validation/es-flow-20260913/career-company-detail-360-font150.png)
- [ES作業・360dp／標準文字](E:/OSHIIRE/software/Hermes/career-validation/es-flow-20260913/es-list-360-font100.png)
- [回答保存・412dp／標準文字](E:/OSHIIRE/software/Hermes/career-validation/es-flow-20260913/es-saved-412-font100.png)
- [相談・360dp／拡大文字](E:/OSHIIRE/software/Hermes/career-validation/es-flow-20260913/es-consultation-360-font150.png)
- [保存失敗・412dp／標準文字](E:/OSHIIRE/software/Hermes/career-validation/es-flow-20260913/es-save-failure-412-font100.png)

## 未検証と適用範囲

- 日鉄エンジニアリングの現在のログイン後DOM・本番DBと、設問27／28の重複の原因は未確認。原文を「第二志望」等へ推測で書き換えていない。
- 本番のCodex認証・モデル応答、GitHub同期、実データによる一連の操作は未検証。Composeの画面モデル再作成は実プロセス強制終了の試験とは異なる。TalkBackと再認証・部分取得の全画面組合せも未検証。
- サーバー保存の正本は既存Careerサーバー。新しい保存・設問指定相談は対応サーバーが必要で、未対応時は画面で説明する。
- 本番サーバーへの反映、接続中の実機へのAPK適用、push・mergeは実施していない。最終APKはデータを消去しない `install -r` で `emulator-5554` に適用し、`com.m57.hermescontrol` の起動を確認した。共通ナビゲーションの再設計、認証・課金基盤の変更、外部提出は対象外。

APK: `app/build/outputs/apk/debug/app-debug.apk`。SHA-256: `69afa288053346c7c811fc09a0f4efa11de7320813ccd0a7d25ef75d8ee54ba0`。

詳細ログは `E:/OSHIIRE/software/Hermes/career-validation/es-flow-20260913/`。全単体テストは `android-final-gates-2.log`、最終差分の検証は `android-delivery-gates.log`、納品APKの操作テストは `ui-delivery.log`。
