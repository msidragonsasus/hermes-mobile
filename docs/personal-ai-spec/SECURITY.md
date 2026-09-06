# Security, Privacy, and Human-Control Specification

## 1. 脅威モデル

対象:

- スマホ紛失
- Hermes token漏洩
- 家庭内ネットワーク侵入
- 不正Mini App
- WebView origin乗っ取り
- 送信の重複
- 接続断による状態誤認
- AIによる過剰な外部操作
- ログへの秘密情報流出
- 古いoffline commandの遅延実行

## 2. 信頼境界

```text
User
  ↓
Android Host App         trusted
  ↓ permission broker
Mini App                 limited trust
  ↓ scoped request
Hermes Gateway           trusted if self-hosted
  ↓
External tools/services  separate trust boundary
```

## 3. 認証情報

- Android Keystoreを利用
- token/passwordをplain Roomへ保存しない
- debug/releaseとも秘密値・本文全体のHTTP/WS loggingを禁止。URL token/ticket、例外、queue、diagnosticsも対象
- diagnostics export時にtoken、cookie、secretをredact
- screenshotに秘密情報を出さない
- clipboardへsecretを自動コピーしない

## 4. ネットワーク

v0.1:

- Tailscaleまたは信頼できるLAN
- 公開InternetへHermes portを直接露出しない
- host/origin allowlist
- cleartext利用時はprivate networkに限定し、UIで警告

将来:

- HTTPS
- device-bound token
- token rotation
- Cloudflare Access等

## 5. Mini App enforcement

manifest宣言だけを信用しない。
実行時にhost側でpermissionを検査する。

```kotlin
interface MiniAppPolicyEnforcer {
    suspend fun authorize(
        appId: String,
        capability: Capability,
        context: ActionContext,
    ): AuthorizationDecision
}
```

## 6. Human approval

### 必ず承認

- メール送信
- 予定作成/変更/削除
- GitHub push/merge/publication
- 外部投稿
- destructive file operations
- credential changes
- payment/financial actions

### Approval card要件

- human-readable summary
- machine target
- diff/preview
- risk
- reversibility
- expiry
- source Mini App/Run
- exact permission

固定server/profile/session/request IDにだけ返答し、解決ACK前に完了扱いしない。
v1はonce/denyのみでsession/always許可は禁止。既存serverの恒久許可も別途確認する。
Android承認UIだけで全Hermes toolの外部writeを制御できるとは限らないため、
server側policy/実行経路が確認できるまで未検証の任意writeを有効化しない。

## 7. 生体認証

R3操作で任意に利用。
Android BiometricPromptをhostが呼ぶ。
Mini Appは生体情報へアクセスしない。

## 8. Web Mini App

- arbitrary originを許可しない
- host auth tokenをそのまま渡さない
-短命scoped token
- generic JS bridge禁止
- URI scheme allowlist
- file access禁止
- popup禁止
- external navigationを捕捉
- crash/timeout隔離
- permission revocation時にsessionを破棄

## 9. Dynamic code

遠隔DEX/JAR/SOを読み込まない。

理由:

- host権限で任意コードが動く
- tampering
- Play policy
- 署名/rollback
- ABI互換性
- crash isolation不足

runtime拡張はSDUIまたはisolated Webとする。

## 10. Audit log

記録:

- appId
- runId/sessionId
- requested capability
- decision
- user action
- timestamp
- target summary
- result

秘密値・本文全体は不要。
audit logは改ざん防止を将来検討。

## 11. Offline safety

- offline commandはdraft
- reconnect時に再承認
- stale thresholdを設定
- deadline-sensitive commandはwarning
- 検証済みserver契約に限り同一idempotency keyで照合。送信後の自動protocol切替/再送は禁止
- unknown execution stateを明示

## 12. Local data deletion

以下はユーザーが明示的に選ぶ削除機能の目標であり、障害回復時の自動削除を許可しない。
既存DB/鍵/設定を保持し、plaintext/読取不能/未知version/復号不能でdelete・
destructive再生成しない。現在の削除経路の解消は後続DB保護PR。

設定から以下を分離して削除できるようにする。

- chat cache
- run cache
- drafts
- Mini App cache
- credentials
- diagnostics
- all local data

server data削除とは別操作にする。

## 13. Telemetry

初期:

- third-party analyticsなし
- crash logはlocal exportのみ
- diagnosticsはopt-in
- usage dataを外部送信しない

将来導入時も明示同意。
