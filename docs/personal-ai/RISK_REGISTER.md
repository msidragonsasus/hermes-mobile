# Personal AI Shell Risk Register

作成日: 2026-09-06  
対象: `msidragonsasus/hermes-mobile@fd485a69990b358f81d8dd4c12c761e7d2555d24`。旧仕様基準 `5c8ab7e` の所見とは区別する。Task 00では問題を修正していない。

確率は発生条件を踏まえた定性的評価（高/中/低）、影響は重大/高/中。ownerは提案する担当componentで、実担当者の割当てではない。実機上で未再現のものはコード上の条件として扱う。

| ID | リスク / 現在の証拠 | 確率 | 影響 | 検出・受入試験 | 緩和策 / 停止ゲート | Owner / 状態 |
|---|---|---|---|---|---|---|
| R01 | **押した承認カードと送信requestが不一致**。forkはrequestIdありだがChatBubbleはactionだけを通知、ChatViewModel.respondToApprovalは最後のカードを選ぶ。元sessionをApprovalInfoへ保存しない | 中（複数保留/切替時） | 重大：意図しない操作を承認 | A/B要求でAを押す、逆順event、session/profile切替、stale ACK、missing ID | 固定RequestRefをcallbackからtransportへ渡す。missing/staleはwrite不可。ACKまでRespondingを保持。PR 06b前に新Attention応答を有効化しない | Attention / Chat / Transport、未対処 |
| R02 | forkのAllow session / Always allowと新仕様の毎回承認が衝突。EventParserはchoices欠落時にsession/alwaysを補う | 高 | 重大：後続外部writeの承認省略 | full/partial/legacy choices、UI外から不許可choiceを送るtest | 新製品policyはonce/denyをUI・transport双方でallowlist。過去にserverへ保存された許可はclientのボタン削除だけでは取り消せないため別確認 | Approval policy / Hermes host、未対処 |
| R03 | WS送受信/queue全文ログにsecret/sudo/本文、接続URLログにticketが出得る。forkにも残る | 高（debugで該当操作時） | 重大：秘密値漏洩 | 合成secret/cookie/token/ticket・壊れたJSONを送受信しlog sinkを検査 | payloadログを廃止、診断は安全なmetadataだけ。実credentialを使う疎通前にPR 02を完了 | Networking / Diagnostics、未対処 |
| R04 | offline/send拒否時の汎用WS queueが再接続直後に自動flush。Composerの接続gateだけではraceや通知返信等を防げない | 中 | 重大：古いwrite/承認の遅延実行 | 切断直前submit、send(false)、profile変更、secret/approval/offline通知返信 | writeはoffline draftまたは再入力。read retryと分離。期限付き要求は再確認し、無条件再送禁止。PR 06a | Transport / Drafts、未対処 |
| R05 | send結果不明時のREST retryやprotocol fallbackが二重実行を作る。safeApiCallはmethod非依存retry | 中 | 重大：重複write | server受理後レスポンス喪失、429/5xx、fallback切替、期限外idempotency | UnknownExecutionState、同一scope/keyの照合、送信後に別protocolへ切替えない。未対応なら手動確認 | Transport / Submission、未対処 |
| R06 | DB headerがplaintext/読取不能ならdelete。Room destructive fallbackもある | 中（旧DB/障害/未知移行） | 重大：既存データ消失 | v2～v6、plaintext、鍵不一致、読取失敗、未知versionをfixtureで検査 | 元DB保持、非破壊移行または安全なエラー停止。アンインストール・データ消去で解消しない。PR 03は端末更新前必須 | Database / Release、未対処 |
| R07 | #84406・関連PRがOpen。API capabilitiesのcontinuity headerをrunsへ誤適用 | 高（header依存時） | 高：継続欠落/承認と履歴の分断 | 固定版でRun A→approval→同session Run B→履歴・restart照合 | body session_idは候補で未保証。API fallbackのwrite/継続は契約試験合格までdisabled。issue Closeだけで解禁しない | ApiServerTransport / Hermes host、未対処 |
| R08 | gateway.ready/gateway.capabilitiesを包括的機能一覧と誤解 | 高（仕様どおりBoolean化時） | 高：不対応操作の有効化 | partial/malformed/method-not-found/401/404、profile切替 | Unknown/Unsupported/Supported/Blockedと情報源を保持。実在read probeのみ。writeで機能探索しない | CapabilityRepository、未実装 |
| R09 | forkのseq/replayがtruncatedをsnapshot補完せず、epoch/watermarkはin-memory。重要eventのDROP_OLDESTもある | 中 | 高：承認/完了の欠落、誤状態 | reorder/duplicate/gap/truncated/server restart/process death | 既存replayを活用し、gap/epochでhistory/status/pending再照合。欠落中は状態不明、返答を保留 | HermesWsClient / Run・Attention repositories、部分実装 |
| R10 | Chatをrootとするcontrollerとrememberのみのstackに4-tabを追加してBack/復元を壊す | 高（単純置換時） | 高：画面消失/通知誤遷移 | 全27画面＋固有entry、Back/dedup/連打、認証、cold notification、再生成 | registry wrapper→role→復元→shellの別PR。controller経由と画面所有gestureを維持 | Navigation / Shell、未実装 |
| R11 | session runtime IDと保存ID、server/profile間のcache混同 | 中 | 重大：別文脈への返答/データ混在 | 同名/同ID profile、runtime再発行、server切替、resume race | ConnectionScope/SessionRefを明示。新cache keyにserver/profile。既存rowの所属を推測で書換えない | SessionRepository / Database、部分実装 |
| R12 | 原仕様がHilt/Room2を前提にして既存DI/Room3を全面移行 | 高（未整合時） | 高：不要rewrite/ビルド破壊 | Gradle catalog/plugin/import、依存graphの差分確認 | Phase 1はsingle-module/manual injection、既存objectをadapter化。HiltはPhase 2に独立判断 | Architecture、PR 00で文書前提を整合・コード変更なし |
| R13 | mobile独自discovery endpointをHermes標準として呼ぶ | 中 | 高：機能不全/誤serverへの送信 | source route mapとlocal fixture照合 | mobile/配置・apps/manifest/actionは提案契約と表示。Phase 1にremote fetchなし | Mini App platform / Backend contract、未実装 |
| R14 | Schema validをpermission/origin安全性と取り違える。unknown fields方針も本文とSchemaで異なる | 高（Schemaだけの場合） | 重大：権限越境/credential転送 | duplicate IDs、unknown permission、URL scheme/origin/path escape、過大JSON、missing native feature | strict専用decoder、意味検証、default deny、runtime broker、disableで寄与除去。署名metadataだけを信用しない | MiniApp validator / Policy broker、サンプルSchema検証のみ成功 |
| R15 | Androidの承認UIだけではHermesの全tool/external writeを強制承認にできない | 中～高（host policy依存） | 重大：未承認の外部操作 | 隔離tool stubでsend/publish/delete/merge等が必ず待つことを確認 | server policy/tool pathを検証。未確認の任意Runをwrite可能状態にしない。Android側に第二AI routerを作らない | Hermes host / Security policy、運用環境未確認 |
| R16 | cleartextのprivate network制限が注意文止まり。DashboardとAPI Serverのauth/DTOを混用 | 中 | 重大：token漏洩/誤接続 | public host/redirect/base prefix/違うauth mode、profile切替 | Tailscale/信頼LANのみ。host/origin制約、認証provider分離、gated cookieとone-time ticketの既存処理保持。公開portを開けない | Connection / Auth、部分実装 |
| R17 | Foreground Service依存でAttention通知が欠落。clarification通知はsession IDなし、Approval通知なし | 高（背景/終了時） | 高：人間待ち放置 | background/process death、通知から元要求、複数通知、権限拒否、Doze | 同期時Needs Youを再構成。正確なAttention routeを追加。高危険度は詳細画面で確認。v0.1でpushの確実性を保証しない | Notifications / Attention、部分実装 |
| R18 | Windowsでunit 2件とcolor guard失敗。HOME/path不整合は確認、seq重複排除testの原因は未確定。環境のせいとして無視したり期待値を弱めたりする恐れ | 高（失敗を観測済み） | 中～高：品質ゲート喪失 | HOME未設定、path区切り、collector購読開始とevent送信の順序を分離検証 | baseline failureを固定して独立修正。seq testは製品不具合かtest同期問題か先に切り分ける。theme除外をportableにし、違反検出自体は保持。公開CI successとlocal結果を分ける | Build / Tests、監査で失敗・一部原因未確定 |
| R19 | forkの進行により監査SHA/行番号/契約が古くなる | 高 | 高：修正済み箇所の再実装/別bugの見落とし | 次回HEADと監査SHAをcompare、関連CIをSHAで照合 | fd485a6を基準、更新時は関連差分を再監査。旧5c8ab7eへresetしない | Repository owner / Architecture、継続管理 |
| R20 | APK上書き時の署名/applicationId/version/schema不一致をアンインストールで回避 | 中（配布準備時） | 重大：ユーザーデータ消失 | インストール済み情報と署名指紋、DB migrationを照合 | 同一ID/署名・単調version、データ保持更新のみ。不可能なら原因と回復案を示して停止 | Release / Android integration、実機未確認 |
| R21 | 動的コード/Web Mini App追加の先行で信頼境界が崩れる | 低（計画維持時） | 重大：host権限実行 | 依存/DEX/JAR/SO loader/JS bridge検索、manifest起動path確認 | Phase 1にruntime code/renderer/WebViewなし。将来もremote native loading禁止、Webは別設計gate | Mini App platform、禁止事項を維持 |

## PR 00での文書上の対応

2026-09-06: R12のHilt/Room前提、R13の提案endpoint表示、R14の未知manifest field方針、
R19の作業baselineを本文・ADRで整合した。R12の文書誤認は対処済みだが、
コードへの対応・live検証・実機安全性が完了したという意味ではない。
その他のコード上のリスクとR18の失敗結果は変更していない。
[PR00_REPORT.md](PR00_REPORT.md) に作業場所と検証を記録する。

## 着手順とリスク受容

PR 00の文書整合後、ログ抑止を最初の小さなコードPRにする。DBの非破壊化は実機更新前、exact approval/once-only/no-auto-sendは新Today/Activityの操作有効化前に必須。これらを後回しにして見た目だけを先に本番へ出さない。

この監査はlive Hermes/実機での安全性認定ではない。未確認のlive機能はdisabled/Unknownとして扱えば、pure model・Fake・registry・manifest parserの準備を進められる。高リスク項目を未確認のまま自動承認したものとして扱わない。

検出根拠と実行結果は [AUDIT_REPORT.md](AUDIT_REPORT.md)、PR別の変更範囲・受入条件・停止点は [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) を参照。
