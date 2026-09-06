# Technical Architecture

## 1. 方針

- ベースは `Hy4ri/hermes-mobile`
- 最初から全面書き換えしない
- Hermesを唯一のagent/orchestration source of truthとする
- Androidはthin clientではなく、UXとオフラインキャッシュを持つ
- 通信、画面貢献、Mini Appを明確な境界で分離する
- 既存の管理画面を捨てず、Advancedに隔離する

## 2. ベースライン

採用済み監査baseline（2026-09-06確認）:

```text
repo: msidragonsasus/hermes-mobile
upstream: Hy4ri/hermes-mobile
branch: dev
commit: fd485a69990b358f81d8dd4c12c761e7d2555d24
```

現状:

- `settings.gradle.kts` は `:app` のみ
- 画面は `ScreenRegistry.ALL_SCREENS` に集中
- `NavigationController` はglobal object
- REST、WebSocket、Room、通知等は既に存在
- Navigation 3、Compose / Material 3を採用済み。Hilt/Daggerは未導入
- Room 3.0.2（androidx.room3）/ SQLCipher、DB schema v6。手動生成・ViewModel factoryを継承

旧仕様の5c8ab7eは比較履歴でありreset先ではない。理由は [ADR-0004](adr/0004-audit-aligned-foundation.md)。以下は段階導入する目標設計であり、現実装の完成を示さない。

## 3. 目標レイヤー

```text
Presentation
  ├─ Shell: Today / Chat / Activity / Apps
  ├─ Feature UIs
  ├─ Mini App renderers
  └─ Approval / Clarification surfaces

Application
  ├─ Use cases
  ├─ Attention aggregation
  ├─ Run/session coordinators
  └─ Mini App permission broker

Domain
  ├─ Session / Run / Event
  ├─ AttentionItem
  ├─ Capability
  ├─ MiniApp / Contribution
  └─ Policy

Infrastructure
  ├─ DashboardRpcTransport
  ├─ ApiServerTransport
  ├─ Room/SQLCipher cache
  ├─ Android Keystore credentials
  ├─ Notification adapter
  └─ Mini App loaders
```

## 4. Hermes通信

### 4.1 Transport abstraction

新しいViewModel/UIはREST pathやJSON-RPC methodを直接参照しない。既存直接呼出は一括置換せず、read/event adapterから小さなPRで移す。既存singleton socket、auth、request ID/pending/received、batch clarification、seq/epoch replayを再利用する。truncated時のsnapshot再同期など監査の欠落を補う。

```kotlin
interface HermesTransport {
    val connectionState: StateFlow<ConnectionState>
    val capabilities: StateFlow<HermesCapabilities>
    val events: Flow<HermesEvent>

    suspend fun connect(profile: ConnectionProfile)
    suspend fun disconnect()
    suspend fun listSessions(query: SessionQuery): List<Session>
    suspend fun createSession(request: CreateSessionRequest): Session
    suspend fun submitPrompt(request: SubmitPromptRequest): Submission
    suspend fun interrupt(runOrSessionId: String)
    suspend fun steer(request: SteerRequest)
    suspend fun respondToApproval(request: ApprovalResponse)
    suspend fun respondToClarification(request: ClarificationResponse)
}
```

### 4.2 実装

#### DashboardRpcTransport — primary

- JSON-RPC over WebSocket
- sessions
- streaming
- tool events
- approval/clarification
- interrupt/steer
- subagent events
- model options
- full Hermes-aware UX

#### ApiServerTransport — fallback

- REST + SSE
- health/capabilities
- OpenAI-compatible endpoints
- environments where dashboard RPC is unavailable; 検証済み機能だけを送信前に選択
- Phase 1では契約・read-only discoveryまで。live writeは継続session/approvalの結合検証が条件
- 送信後の自動切替・再submitは禁止。DashboardとAPI ServerのDTO/authは分離

#### FakeHermesTransport

- deterministic tests
- screenshot tests
- 明示したpreview/test fixture（live表示にFakeを混ぜない）
- backend bug reproduction

### 4.3 Capability negotiation

接続先のprotocolごとに実在するread-only surfaceを使い、scopeと根拠を保存する。

| Surface | 起動時に利用する情報 | 限界 |
|---|---|---|
| Dashboard RPC | `/api/health`、`gateway.ready`、必要な既存read RPC | readyはskin/change_events/heartbeat/replay_epoch等。包括capability一覧ではない |
| TUI `gateway.capabilities` | 現監査版は `per_session_exclusive_submit` | sessions/runs/approvals全体の対応を推定しない |
| API Server | `/health`、`/health/detailed`、`/v1/capabilities` | Dashboardへ同じpathを送らない。広告だけで継続Run/承認の安全性を保証しない |

401/404/method-not-found/timeout/partial/malformedを区別する。promptやapprovalで
起動時の機能探索をしない。profile/server変更で古い判定を無効化する。
根拠sourceとendpoint表は [監査報告](../personal-ai/AUDIT_REPORT.md) を参照。

UIはHermes versionを推測しない。
利用できない操作は非表示またはdisabledにする。

契約案（Booleanで未確認と非対応を混同しない）:

```text
CapabilityState = Unknown | Unsupported | Supported | Blocked(reason)
CapabilityEvidence = state + source + observedAt + connection/profile scope
HermesCapabilities = optional protocol/version + per-feature CapabilityEvidence
```

advertised/observed/verifiedを区別し、必要な安全性が未確認ならwriteはdisabled。

### 4.4 Event normalization

transport固有eventをdomain eventへ変換する。

```kotlin
sealed interface HermesEvent {
    data class MessageDelta(...) : HermesEvent
    data class MessageCompleted(...) : HermesEvent
    data class ToolStarted(...) : HermesEvent
    data class ToolProgress(...) : HermesEvent
    data class ToolCompleted(...) : HermesEvent
    data class ApprovalRequested(...) : HermesEvent
    data class ClarificationRequested(...) : HermesEvent
    data class RunStateChanged(...) : HermesEvent
    data class SubagentStarted(...) : HermesEvent
    data class SubagentCompleted(...) : HermesEvent
    data class ConnectionChanged(...) : HermesEvent
    data class ProtocolError(...) : HermesEvent
}
```

## 5. 既知のAPIリスクへの対応

HermesのAPI surfaceは発展中であり、特定endpointだけに依存しない。

特に `/v1/runs` とsession continuity/approvalの組合せには、
Hermes 0.20.0時点でopen issueが報告されている。

対策:

1. primaryはTUI/Dashboard RPC
2. REST/SSEは検証済み機能だけのfallback。送信結果不明時の乗換えは禁止
3. 起動時はread-only capability negotiationのみ
4. contract testsを作る
5. server versionごとのworkaroundをtransport内に閉じ込める
6. UI/ViewModelにworkaroundを漏らさない

2026-09-06監査時は#84406と関連PR #84535がOpen。runsでの
`X-Hermes-Session-Id` 継続は未保証、body `session_id` はsource上の候補に留まる。
実稼働版での契約検証前にAPIの継続Run/approvalを有効化しない。
新UIの承認は固定RequestRef（server/profile/session/request ID）とonce/deny、
解決ACKを必須とする。offline writeをqueueから自動flushせずdraft/再入力へ分離する。
これは後続PRで変更する製品policyであり、既存挙動を実装済みとしない。

## 6. Gradle modularization

### Phase 2以降の分割候補（採用は独立ADRで判断）

```text
:app

:core:model
:core:network
:core:database
:core:security
:core:designsystem
:core:navigation
:core:plugins
:core:notifications
:core:testing

:feature:today:api
:feature:today:impl
:feature:chat:api
:feature:chat:impl
:feature:activity:api
:feature:activity:impl
:feature:apps:api
:feature:apps:impl
:feature:advanced:api
:feature:advanced:impl

:miniapp:contract
:miniapp:sdui
:miniapp:web
```

### 段階移行

Phase 1は単一`:app`と手動DIを維持する。以下のStep 2以降やHilt導入をPhase 1の受入条件にしない。

#### Step 1

`:app` 内でinterfaceとregistryを導入し、挙動を維持する。

#### Step 2

`core`を抽出。

#### Step 3

新規4 featureを `api/impl` に分ける。

#### Step 4

既存機能を必要に応じて移す。

### Navigation contribution

Android公式のNavigation 3 modular patternに合わせる。

```kotlin
typealias EntryProviderInstaller =
    EntryProviderScope<NavKey>.() -> Unit
```

Phase 1は手動登録・constructor injectionでentryと画面metadataを渡す。
Phase 2で分割の効果を確認した場合だけ、Hilt `@IntoSet` を候補として独立判断する。

```kotlin
interface AppContribution {
    val id: String
    val destinations: Set<DestinationMetadata>
    val commands: Set<GlobalCommand>
    val homeCards: Set<HomeCardProvider>
}
```

現在の `ScreenRegistry.ALL_SCREENS` は互換wrapperから段階移行する。
27定義だけでなくNavigation.ktのauth/settings/detail entryも維持し、
root/Advanced/drill-downのrole、Back、通知待機、保存可能なstackを別PRで扱う。

## 7. Shell architecture

### AppShellState

```kotlin
data class AppShellState(
    val selectedTab: ShellTab,
    val connection: ConnectionState,
    val attentionCount: Int,
    val activeRunCount: Int,
    val activeProfile: ProfileSummary?,
)
```

### Shell tabs

```kotlin
enum class ShellTab {
    TODAY,
    CHAT,
    ACTIVITY,
    APPS,
}
```

既存のHermes管理画面は `AdvancedDestination` 配下へまとめる。

## 8. データと正本

### Server source of truth

- sessions
- runs
- tools
- approvals
- jobs
- memory
- plugin data

### Local encrypted cache

- session summaries
- recent messages
- run snapshots
- attention read state
- drafts
- pinned apps
- app order
- Mini App manifests
- UI preferences

ローカルキャッシュからserver stateを上書きしない。現DBはChatMessageのみで、
上記cache全体は目標。既存schema v6から明示migrationし、server/profile scopeを保持する。
plaintext/読取不能/鍵不一致/未知versionで自動delete・destructive再生成しない。
既存の削除経路は後続DB保護PRで修正し、端末更新はそれまで行わない。

## 9. Repository pattern

```text
HermesTransport
      ↓
HermesRepository
      ├─ SessionRepository
      ├─ RunRepository
      ├─ AttentionRepository
      └─ CapabilityRepository
             ↓
Room cache + in-memory state
             ↓
ViewModels
```

## 10. Connection

初期:

- Tailscale or trusted LAN
- no public port
- Hermes dashboard/gateway authentication
- credentials in Android Keystore-backed storage

将来:

- Cloudflare Access等
- HTTPS
- notification relay

transportとauth providerを分離する。

## 11. Notification abstraction

```kotlin
interface NotificationGateway {
    suspend fun showAttention(item: AttentionItem)
    suspend fun showRunCompleted(run: RunSummary)
    suspend fun cancelForRun(runId: String)
}
```

v0.1:

- existing foreground service / WebSocket
- local notifications

将来:

- FCM
- self-hosted WebPush relay
- UnifiedPush

## 12. Error model

```kotlin
sealed interface AppError {
    data class Network(...)
    data class Authentication(...)
    data class Protocol(...)
    data class CapabilityMissing(...)
    data class PermissionDenied(...)
    data class MiniAppFailure(...)
    data class UnknownExecutionState(...)
}
```

「通信失敗」と「実行失敗」を分ける。

## 13. Upstream merge strategy

- `upstream` remoteに `Hy4ri/hermes-mobile`
- 自分のforkを `origin`
- ベース変更とPersonal AI機能を同一commitに混ぜない
- Phase 1は既存`:app`内のpackage境界を使う。module抽出は独立した後続判断
- 既存ScreenRegistryの置換は小さい段階に分ける
- upstream merge前後でcontract/UI testsを実行
- GPL clientからコードをコピーしない
