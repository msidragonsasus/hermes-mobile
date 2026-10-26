# 内部ブラウザの一回承認と停止

今回の接続コードは、実装済みのWebView入力と60秒・一回grantへ、既存Dashboard SessionとAndroidの明示承認を接続する。既定OFFを維持し、実grant・実秘密・本番設定は操作していない。企業順序は変更しない。

## ユーザー操作

有効化が別途承認された環境では、企業→マイページを開く→登録ログイン画面を確認→初回に「この企業で自動入力」を押す。確認ダイアログに企業名/ID、就活profile、接続version、HTTPS gateway、登録URL全体、端末の内部ブラウザへの一時転送、サイトが入力値を読めること、選択期限60秒、会社/profile/URL/version限定で最大30分有効なことを表示する。サーバーのprepareは接続metadataだけを読む。初回クリックで限定許可を作り、その範囲が有効な間は開くと自動入力する。毎回の確認は要求しない。許可はメモリ内だけで最大30分、token期限/更新・登録対象変更・取消・再起動で再確認する。各資格情報取得は別の60秒single-use参照と同一request grantを必要とする。ログインボタンは本人が押す。

「手動で入力」、ダイアログを閉じる、期限切れは入力許可を送らない。離脱・バックグラウンド・サーバー/profile切替はrequestと入力を止める。確認IDとURLはnavigation/saveable stateへ保存しない。未対応フォーム・redirect・iframe・別origin・SPA書換えは既存の入力ガードを維持し、手動入力へ戻る。

## 既存認証への接続

親Dashboardのmiddlewareが検証した公式 `hermes_cli.dashboard_auth.base.Session` を利用する。providerとopaque access tokenをランダムsaltでHMAC化し、元のtoken/refresh値は保持・記録しない。Cookie/Header/JSON/User IDを認証状態の根拠にしない。per-login ID追加やprovider変更は不要。承認の単位はaccess tokenの有効期間であり、refresh/rotation・再ログインでは再承認する。同じtokenを共有するcallerの権限は同じで、端末固有の識別や証明は主張しない。expiryも取得直前に再確認する。

plugin_apiの `create_router(browser_consent=True)` がadapterを構築し、browser requestに同じinstanceをapp.stateへ結び付ける。設置だけでは既存flagを変更しない。初期状態ではgrantを発行せず、flag=1の運用承認後でも、厳密なtargetに対する初回確認とクリックが必要。有効な限定許可では再クリックせず入力する。JSONの `approval_id` は認証権限ではなく、検証済みsession/DB targetに結び付いた一回の確認参照である。

## HTTPとgrant

`POST /mypage/browser-access/prepare` は会社/profile/version/full URLとDB有効接続を一致確認し、暗号学的random IDをメモリだけに60秒保存する。上限256、各token scope8件。`requires_confirmation` は同じtargetの限定許可が現在有効な時だけfalseになる。`allow_target` は初回確認を、`use_target` は既存許可の利用を表し、後者から新規許可を作れない。`POST /mypage/browser-credentials` は同じtargetと確認IDを一度消費し、同じHTTP requestのsession objectへ60秒single-use grantを発行する。失敗・不一致でも同じ確認を再利用しない。DB targetは既存SecretStore読取直前にも再確認する。

HTTPS、JSON、サイズ上限、`X-Hermes-Browser-Consent: once` を必須とする。Originとsec-fetch-*を持つブラウザ要求は同じDashboard originでも拒否する。CORSは追加しない。これはambient Cookieを使うWeb scriptを防ぐチェックであり、侵害されたnative clientから人間のクリックを証明する仕組みではない。レスポンスはno-store/private、エラーは秘密を含まないcodeのみ。URL/token/資格情報/確認IDをHTTP payloadログへ残さない運用を確認する。

## 取消と全体停止

`GET /mypage/browser-access` は当該verified token scopeのgrant状態のみを返す（URL/秘密/生tokenは返さない）。`POST /mypage/browser-access/revoke` の `{}` はそのscopeのpending確認・対象限定許可・未consume grantを、`{company_id,profile}` は該当接続を取り消す。新しい初回確認とクリックは新たに許可できる。許可取消はAndroidの「許可を取り消す」から行い、成功ACK前に成功扱いしない。永久拒否の設定ではない。既に取得/入力済みの値は取消で回収できない。

運用上の全体停止はflag OFFとtrusted `disable()` を使う。`disable()`/`close()`はstickyで、そのinstanceをHTTPの確認で再有効化できない。容量超過の安全停止も同様。再構築/再起動後にも新しい確認とクリックが必要で、永続許可やboot時のgrant発行はない。

同じstoreの `browser-consent.lock` に既存job_lock（Windows msvcrt/Linux flock）をprocess寿命中保持し、ledger/取消はそのprocessだけで行う。別workerのprepare/revokeは503で失敗させ、偽の成功ACKを返さない。forkでPIDが変わっても拒否する。shutdown hookでcloseし、hookが実行されなくてもprocess終了時にOSがlockを解放する。実運用は単一workerを確認する。複数workerの別workerへrequestが届く場合は利用できず、手動入力になる。

## 有効化前に残る作業

対象gateway/端末/会社/profile/登録URL/versionと、既存SecretStoreからHTTPSを経て端末・サイトの入力欄へ渡す範囲を示した別の承認が必要。TLS終端/proxyのforwarded header信頼、単一worker、秘密を含むログがないこと、稼働版の公式Session互換性、ダミーAndroid実機検証を確認する。このPCのインストール済みHermesの公開Session構造ではdummy契約が成立するが、hermes-nodeの稼働版一致や実機動作を主張しない。deploy/install/pushは行わない。

既存generic managerの失効marker互換性は維持するが、新しいconsent adapterは取消対象のticketを直接失効し、pending/限定許可を削除するためmarkerが蓄積しない。低リスク：global generationは並行する別scopeのissueを一度拒否する場合がある。tokenが確認中に更新される場合は再確認が必要。hostのplugin unloadでshutdown hookが走らない場合、process終了までlockが保持される。WebView/JVM/サイトの文字列コピーの完全消去は保証しない。

限定許可の上限は全1024・各token scope32件で、期限切れは操作時に削除する。status/revokeはthreadpoolで実行し、SQLite処理中のmanager lockで親event loopを止めない。初回ボタンは700ms後に有効になり、dialog SecureOn/obscured-touch guardを付ける。Mono Expressiveは既存正本を継承し、共通themeを編集しない。
