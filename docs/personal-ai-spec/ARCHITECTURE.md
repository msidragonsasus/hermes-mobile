# Technical Architecture

## 1. 方針

- ベースは `Hy4ri/hermes-mobile`
- 最初から全面書き換えしない
- Hermes内の実行はHermesをsource of truthとする。独立Agentハブではbroの本人識別・会話・権限を分離し、別API agentへ代替しない
- Androidはthin clientではなく、UXとオフラインキャッシュを持つ
- 通信、画面貢献、Mini Appを明確な境界で分離する
- 既存の管理画面を捨てず、Advancedに隔離する

### Implementation consistency

2026-10-19のユーザー方針: デザインとコードの双方で一貫性を最優先の品質基準とする。
同じ目的・状態・操作が、機能や画面ごとに微妙に異なる動作にならない構造を保つ。
この節は実装・レビューの要件であり、既存の重複が解消済みという意味ではない。

- **追加前に経路を確認する**: 同等機能、共有UI、状態管理、入力検証、データ更新処理と
  その呼出元をコードで探す。既存実装の再利用・拡張を先に検討し、新規コードを書く前に
  GitHub等の公開実装を活用できるかも確認する。
- **同じ責務の正本を保つ**: 同一の操作・状態の判断は共通の処理・契約に集め、各画面が
  個別に再実装しない。見た目は共通部品とテーマ、動作は共通ロジックを通して揃える。
  コピー後に別々の条件分岐や不具合修正を積み重ねない。
- **実際の違いを明示する**: backend能力、権限、接続先・profile等のデータ境界が違う場合は
  契約とadapterで表現する。名前が似ているだけの機能を無理に統合せず、差異の理由を残す。
- **共有変更の影響を確認する**: 共通部品・処理の変更前に呼出元と依存先を確認する。
  代表的な利用画面と状態遷移を検証し、失敗・再試行・無効状態も含めて回帰を確認する。
  必要なテストは共通契約と意味のある利用側の動作を検証し、実装の写しにはしない。
- **修正は共通原因へ戻す**: 同じ不具合を画面ごとの補修で隠さない。影響範囲を把握し、
  関連箇所を小さく移行する。無関係な全面改修は行わず、未移行の差異は明記する。

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
  ├─ Shell: Home / Agent (existing Hermes Chat) / Apps (Activity is diagnostic only)
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
解決ACKを必須とする。transportのoffline writeは自動flushしない。
2026-10-20: 通常Chatの明示的な送信待ちは`DraftState.QUEUED`で暗号化DBへ保存し、
元のserver/profile/sessionかつ指定時と同じprocess/接続世代でcreate ACK・idle resume ACK・turn終了を確認後に一件ずつ送信する。
再接続・アプリ再起動で世代が変わった送信待ちは、記録を消さず確認待ちの下書きとして扱い、自動再bind・送信しない。本人の再指定後に現在scopeへ結び直す。送信直前にはUNKNOWNを保存する。
通常DRAFT・UNKNOWNは自動送信対象外。通知返信・approval・slashの一般writeは従来通り。
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
    AGENT,
    APPS,
}
```

これは表示する3タブの契約である。実装の旧CHAT/ACTIVITY値は保存状態の読込互換だけに残し、CHATはAGENT、ACTIVITYはTODAYへ移行する。Agentのルートは既存Hermes Chatであり、独立接続の候補一覧は主タブと混同しない。

既存のHermes管理画面は `AdvancedDestination` 配下へまとめる。

Agentハブ候補は静的なbro/Hermes一覧と接続詳細を追加する。独立したNavigation3スタックを持ち、
既存Chat、token、socket、DBを再利用して接続済みと見なすことはしない。
`AgentConnection.canUse` は検証済みの所有者・接続先・profile・agentと現在scopeの一致、および
対象機能のSupportedを要求する。候補登録は全件Unverified/Unknownで、通信・購読・書込は実装しない。
[候補の境界と検証](../agent/AGENT_HUB_CANDIDATE.md)に従い、実接続の実証後に対応アダプターを追加する。

### Home contributions (native)

`HomeContributionProvider` supplies stable ID/version, category, display text, optional precise deadline,
native target and coverage. The host renders the common card; the Career provider owns classification.
`GET /api/plugins/career/home?profile=&cursor=` reads stored sources only. Snapshot cursors reject
changed pages with 409; clients retry once and commit only complete results. `/home/content` resolves
exact source/version beyond source-list limits. Both require the existing Dashboard session.
`collection/source-resolved` records explicit App-side completion of a source; it never submits externally.

SQLCipher Room v8 adds `home_items`, `home_reads`, `home_providers` through migration 7→8.
Room v9 also retains the installed client's `chat_transcript_state` table with its
target/session key, write epoch, revision and payload. Migration 8→9 only adds the
table; it neither backfills provenance nor rewrites existing messages or transcript
state. The supported-version guard uses the same version constant as `@Database`.
Rows are server/profile scoped. Read receipts and visibility preferences survive feed replacement and
are never added to GitHub sync. Home caches contain metadata only, not complete mail bodies or credentials.
Chat completion metadata is stored from scoped WS events; unreceived completions are not reconstructed.

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
