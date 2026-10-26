# Intelligent UI live input — v49

ユーザー報告: v48へ更新後、以前の回答に保存されたUIのバーがタップ時にしか反映されず、ドラッグがライブに動かない。

## 原因と修正

通常チャットの履歴を開く横スワイプがWebViewのドラッグを途中で奪っていた。IntelligentPreviewWebViewはネイティブtouch slopを超えた横方向の動きだけ、親への割り込み禁止を要求する。縦方向は外側のスクロールに残す。UP/CANCEL/detachで解除する。

ChatScreenの既存履歴処理をconversationHistorySwipeへ抽出し、同じ本番modifier配下でAndroid操作テストを実施。HTML/JSの書き換えやnative bridge追加、サーバー変更はない。既存回答の表示にもアプリ側の修正が適用される。

## 検証

- 修正前実装を同じ親ジェスチャー配下で再ビルドすると、指を保持した2段階目で停止し、履歴が開く失敗を再現。配布済みv48バイナリそのものの検証とは区別する。
- 修正後、指を離す前に45,000円 / 1,826.1万円 → 60,000円 / 2,434.8万円 → 75,000円 / 3,043.5万円へ更新。各段階でhistoryOpened=false。
- Android操作12件、対象JVM64件（7スイート）、assembleDebug/assembleDebugAndroidTest/checkColorLiterals成功。全JVMスイートを実行したとは主張しない。
- 通常のMarkdown本文から履歴を開く操作、グラフ下の操作欄への縦スクロール、拡大文字、展開時の状態保持を確認。
- 検証用エミュレーターのみ使用。実機への導入・ユーザーの保存済みHTMLそのものは未確認。旧配置を模したfixtureの検証と区別する。
- AndroidX Compose UIは実APK内の1.13.0-alpha01。新規ライブラリは導入していない。

## 配布

- [v49 APK on Drive](https://drive.google.com/file/d/1YP2yF7C9j67xoXSUfdMDeTic-3jdykrQ/view?usp=drivesdk)
- versionCode 49 / versionName 1.0.49-local / applicationId com.m57.hermescontrol
- 47,034,415 bytes。DriveのID・名前・MIME・サイズを再取得して照合済み。Drive側checksumは取得していない。
- SHA256: `25983eee1d830a82960779218ef910d1c222f651a1adfcea2f9e0e3441665607`
- 署名SHA256: `8561de18c9143b61a26d970ec28c30a20f4260ffba6879eab8250badc3f3524b`（従来と同一）
- アンインストール・データ消去は行っていない。v48配布物は残してある。

## 証跡

`output/intelligent-ui-live-20261023/validation.json` に検証範囲・配布情報を保存。native-final.logはOK (12 tests)。android-final/nisa-live-samples.txtは指を保持中の値、android-final/nisa-live-held.pngは保持中の画面。hermes-live-v49.mp4は未編集のエミュレーター動画で起動前後も含む。フレームレートの性能保証には使わない。

検証用emulator-5584は停止済み。サーバーmobile_display 0.3.1の反映状態は既存のintelligent-ui-usability-20261023.mdを参照。
