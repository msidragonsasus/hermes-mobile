# アーキテクチャ決定要約

## 目的

スマホから雑な依頼を投げ、家のHermesが実行し、
途中経過・質問・承認・結果をスマホで最短操作できる専用Androidアプリを作る。

## 採用する構成

```text
Android App
  ├─ Today / Chat / Activity / Apps
  ├─ Approval & Clarification UI
  ├─ Native Features（Phase 1は:app内）
  ├─ SDUI Mini App Renderer
  └─ Isolated Web Mini App Host
          │
          │ Tailscale / trusted LAN
          ▼
Hermes Agent on home laptop
  ├─ TUI Gateway JSON-RPC / WebSocket
  ├─ API Server REST / SSE
  ├─ Sessions / Runs / Approvals
  ├─ Tools / Skills / Memory / Cron
  └─ Hermes backend plugins
```

## 主要決定

| 論点 | 決定 |
|---|---|
| 新規開発か既存利用か | `msidragonsasus/hermes-mobile`を使用（upstream: `Hy4ri/hermes-mobile`） |
| UI技術 | Kotlin + Jetpack Compose |
| 主プロトコル | Hermes Dashboard/TUI GatewayのJSON-RPC over WebSocket |
| 予備プロトコル | Hermes API ServerのREST/SSE |
| 画面構成 | Today / Chat / Activity / Apps |
| 拡張性 | Native / SDUI / isolated Web の三層 |
| 動的Nativeコード | 採用しない |
| AIオーケストレーション | Hermesに残す |
| 初期接続 | Tailscaleまたは信頼できるLAN |
| 自動実行の安全性 | 高影響操作は毎回明示承認 |
| 初回実装 | 基盤の縦切りのみ。全面再設計しない |

## この決定の理由

### 1. ゼロから作る必要がない

既存の `Hy4ri/hermes-mobile` は、チャット、セッション、Cron、Kanban、
スキル、モデル、ログ、通知、Room、暗号化、CIまで既に持つ。
不足しているのは「個人AIとしての情報設計」と「拡張基盤」である。

### 2. アプリ内アプリは、任意コード読込で実現しない

Androidで遠隔DEX/JAR等を読み込む方式は、攻撃面・署名・配布ポリシー・保守性が悪い。
そこで、日常追加する機能は宣言型UIを標準とする。
高品質な固有UXが必要な機能だけNativeに昇格させる。

### 3. Hermesの通信方式を1本に固定しない

HermesにはTUI GatewayとAPI Serverがあり、露出機能が異なる。
またAPIの一部にはバージョン依存や既知の不整合があり得る。
ViewModelがエンドポイントを直接呼ぶ構造にせず、
`HermesTransport` と capability negotiation を必須にする。

## 採用しない案

### Telegram/Slackだけで完結

疎通確認には有効だが、承認、実行状況、ミニアプリ、ローカル統合のUXが不足する。

### 自作オーケストレーター

Hermesが既に担う責務と重複し、状態管理・再試行・メモリが二重化する。

### WebViewだけのアプリ

開発は速いが、通知、共有、カメラ、オフライン、アクセシビリティ、
一貫した承認UI、Android統合で不利。

### Play Dynamic Featureを主軸にする

Play配布に強く依存し、個人用サイドロード環境での追加機能導入には過剰。
Native FeatureはAPKへコンパイル時に組み込む。Phase 1は単一`:app`内のpackageと手動registryを使い、Gradle module/Hilt導入を要件にしない。
