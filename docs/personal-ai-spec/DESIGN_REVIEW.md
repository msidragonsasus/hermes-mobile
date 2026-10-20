# Design Review

## 1. 要求充足

### 専用アプリ

満たす。
既存クライアントをベースに、日常運用向けShellを追加する。

### UX重視

満たす。
Chat中心ではなくToday/Attention/Activity中心。
承認と長時間Runを第一級オブジェクトにする。

### 機能追加が容易

満たす。
Native、SDUI、Webの三層とContribution slotを設ける。

### Codex-ready

満たす。
段階的プロンプト、受入条件、Schema、サンプル、ADRを含む。

## 2. 主なトレードオフ

### Forkによる速度 vs upstream追従

forkは最速だが、navigation shell変更はupstream conflictを増やす。
対策は`:app`内のpackage境界と段階PR、upstream参照先の記録。module/HiltはPhase 2で独立判断する。

### Native UX vs runtime追加

Nativeは最良だがAPK更新が必要。
日常的な機能追加はSDUI、重要機能だけNativeへ昇格させる。

### Web表現力 vs 安全性

Webは便利だが権限とtoken漏洩が難しい。
Tier Cに限定し、SDUIをdefaultとした。

### Full Hermes UI vs 日常の単純さ

既存appは管理画面が非常に多い。
削除せずAdvancedへ隔離し、普段は4-tab shellだけ見せる。

## 3. 修正済みの危険な初期案

### 危険: 動的Native plugin

不採用。
Androidのremote dynamic code riskが大きい。

### 危険: API Serverだけへ固定

不採用。
Hermes protocolごとに機能差があり、API issueも存在する。
transport abstraction + capability negotiationへ修正。

### 危険: 最初から全面multi-module化

不採用。
最初は`:app`内でregistry/transport境界を作り、後で抽出する。

### 危険: 全機能を1回のCodex runで実装

不採用。
採用監査 → PR 00文書整合 → 安全性ゲートとFoundationの小PR → 分割の独立判断 → SDUIの順。

### 危険: offline draft自動送信

不採用。
再接続後に人間が確認する。

## 4. 残る不確実性

- Hermesの実稼働versionとgateway exposure
- API bugの修正状況
- base repoのdev branch変化
- notificationのbackground reliability
- Tailscale環境
- app名称/applicationId
- first domain appの優先順位

監査でfork/HEAD・公開sourceは確認済み。実稼働Hermes/端末/通知の保証は未確認で、
[リスク登録簿](../personal-ai/RISK_REGISTER.md) の該当ゲートで確認する。
名称は最初の文書PRを止めず、applicationIdは既存値を保持する。

## 5. 最終評価

実装可能性: 高い  
UX改善余地: 高い  
初期工数: 中〜高  
維持コスト: 中  
技術的最大リスク: upstream追従とHermes protocol変化  
推奨: Phase 0/1を実施し、実利用で価値を確認してからSDUIへ進む
