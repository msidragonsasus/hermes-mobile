# ADR-0002: HermesTransport抽象化を導入する

Status: Accepted  
Date: 2026-08-31

## Context

HermesはTUI Gateway JSON-RPCとAPI Server REST/SSEを提供する。
機能面とversion差がある。API Serverのruns/session/approvalには
既知issueが報告されている。

## Decision

`HermesTransport` interfaceを導入し、少なくとも以下を持つ。

- `DashboardRpcTransport`
- `ApiServerTransport`
- `FakeHermesTransport`

起動時はprotocol別のread-only capability negotiationを行う。限定handshakeを
包括一覧と扱わず、Unknown/Unsupported/Supported/Blockedと根拠を保持する。
既存RPC clientをadapter化し、replay/approvalを作り直さない。API Serverのlive writeは
検証済み機能だけ、送信前の選択に限定し、結果不明時の切替/再submitは禁止する。
根拠とPhase 1の境界は [ADR-0004](0004-audit-aligned-foundation.md)。

## Consequences

- backend差分がUIへ漏れない
- contract test可能
- 契約検証済みの機能に限りfallback可能（自動的な安全性保証ではない）
- 初期refactor工数は増える
