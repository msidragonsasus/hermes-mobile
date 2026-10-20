# ADR-0003: Mini Appは三層拡張モデルとする

Status: Accepted  
Date: 2026-08-31

## Context

機能をアプリ更新なしで追加したいが、
Androidのremote native code loadingは危険。

## Decision

1. Native Feature
2. SDUI Mini App
3. Isolated Web Mini App

遠隔DEX/JAR/SOは読み込まない。

## Default

SDUIを将来のruntime追加の標準とする。Phase 1はoffline manifest
model/parser/validator・permission型と3 fixturesに限定する。
remote discovery/SDUIはPhase 3、Web hostはPhase 4。Nativeは組込IDへ手動登録し、
ホストのテーマ・共通UI・承認ルールを継承する。

## Consequences

- 安全で一貫したUX
- Native品質とruntime柔軟性を両立
- SDUI rendererとpermission brokerの実装が必要
- WebViewは制限付き
