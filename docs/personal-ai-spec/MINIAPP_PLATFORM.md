# Mini App Platform Specification

## 1. 目的

就活・大学・開発等を、ホストアプリ本体の巨大な画面群として直書きせず、
独立した機能単位として追加・無効化・更新できるようにする。

ホストの [DESIGN.md](../../DESIGN.md) と [UX_SPEC.md](UX_SPEC.md) に従い、
テーマ、semantic colors、余白、共通Run/承認/質問/成果物UIを再利用する。
Mini App独自の承認ルールや画面ごとの色・余白体系を増やさない。
Phase 1はoffline model/parser/validator/permission型とfixturesだけ。
以下のdiscovery/SDUI/Web契約はPhase 3以降の目標で、Hermes実装済みendpointではない。

## 2. 三層モデル

### Tier A: Native Feature

用途:

- 高頻度
- Android統合が必要
- 複雑なgesture/animation
- 大量データ
- オフライン重視
- 厳しいアクセシビリティ

実装:

- Kotlin/Compose
- Phase 1は単一`:app`内のpackageと手動contribution registry
- APKビルド時に組み込む。組込feature IDを解決し、class名のreflectionで起動しない
- Gradle feature module/HiltはPhase 2以降の独立判断

利点:

- 最高のUX
- 型安全
- 最も安全
- テストしやすい

欠点:

- 追加時にAPK更新が必要

### Tier B: SDUI Mini App — default

用途:

- ダッシュボード
- フォーム
- 一覧
- 締切
- ワークフロー
- CRUD
- AI実行ボタン
- 比較表

実装:

- Hermes backend pluginがmanifestとtyped JSONを返す
- Android hostが許可済みcomponentだけ描画
- actionは許可済みcommandだけ
- 任意JavaScriptを実行しない

利点:

- APK更新なしで機能追加
- 統一UX
- 権限管理が容易
- Android policy上のリスクが低い

欠点:

- UI表現はcomponent catalogの範囲内

### Tier C: Isolated Web Mini App

用途:

- 高度なchart/editor
- 既存Webアプリ再利用
- SDUIで表現しにくい機能

実装:

- 専用WebView host
- origin allowlist
- narrow message protocol
- scoped short-lived token
- file/content access disabled
- external linksはsystem browser

利点:

- 表現力
- Web資産再利用

欠点:

- セキュリティとUX一貫性の負担
- オフライン/アクセシビリティが弱い

Webを標準にしない。

## 3. Hermes pluginとの対応

Hermes公式dashboard pluginは次を持てる。

```text
~/.hermes/plugins/<plugin>/
├── plugin.yaml
├── __init__.py
└── dashboard/
    ├── manifest.json
    ├── dist/index.js
    └── plugin_api.py
```

本プロジェクトでは、同じplugin directoryに次の**独自規約**を追加する。

```text
~/.hermes/plugins/<plugin>/
├── plugin.yaml
├── __init__.py
├── dashboard/
└── mobile/
    ├── manifest.json
    ├── sdui/
    │   ├── index.json
    │   └── routes/
    └── web/
        └── ...
```

注意:

- `mobile/` は現時点のHermes公式規約ではなく、本アプリの提案
- Hermes backend pluginのofficial route namespace
  `/api/plugins/<name>/` を利用する
- dashboardのJavaScript bundleをnative appへ直接流用しない

例:

```text
GET /api/plugins/personal-ai-mobile/apps
GET /api/plugins/<app-id>/mobile/manifest
GET /api/plugins/<app-id>/mobile/sdui/<route>
POST /api/plugins/<app-id>/mobile/actions/<action-id>
```

これらの具体endpointは本プロジェクト側のcontractであり、
Hermes core endpointと混同しない。

## 4. Manifest

JSON Schema:

`schemas/miniapp-manifest.schema.json`

必須概念:

- schemaVersion
- id
- localized name/description
- version
- publisher
- entry type
- contributions
- permissions
- network allowlist
- offline policy
- integrity/signature metadata

## 5. Contribution slots

### `home.cards`

Todayへカードを追加。

制約:

- hostが最大高さと更新頻度を制御
- 1 appが画面を占有しない
- hidden/disabled時は即時除去

### `global.commands`

Command paletteとrouting hintへ追加。

例:

- `ESを準備`
- `課題を登録`
- `CIを確認`

### `chat.composer.actions`

添付メニューへaction追加。

例:

- 企業ページとして取り込む
- 課題資料として保存
- Repositoryへ送る

### `activity.renderers`

特定Run/tool eventの表示を拡張。
任意の表示拡張だけが未知なら、hostの安全な要約表示へfallbackできる。
未知の必須component/action/permissionは実行せず明示エラーにする。

### `settings.sections`

Mini App固有設定。

### `notification.routes`

通知deep linkとactionの解決。

## 6. Permission model

### Hermes permissions

- `hermes.sessions.read`
- `hermes.sessions.write`
- `hermes.runs.create`
- `hermes.runs.read`
- `hermes.runs.steer`
- `hermes.runs.interrupt`
- `hermes.approvals.respond`
- `hermes.jobs.read`
- `hermes.jobs.manage`
- `hermes.memory.read`
- `hermes.memory.write`
- `hermes.files.read`
- `hermes.files.write`

### Plugin API

- `plugin.api:<plugin-id>`
- `plugin.api:<plugin-id>:<scope>`

### Device permissions

- `device.camera`
- `device.photos`
- `device.files`
- `device.microphone`
- `device.notifications`
- `device.share.receive`
- `external.openUrl`

### 原則

- default deny
- manifest宣言
- install/enable時に表示
- 実行時にもhost policyで検査
- secretsをMini Appへ生で渡さない
- app自身がAndroid permissionを直接取得しない
- hostがbrokerになる

## 7. Risk classes

| Class | 例 | 初期ポリシー |
|---|---|---|
| R0 | local表示、cache read | 自動 |
| R1 | Hermes read、Run作成 | manifest権限内で自動 |
| R2 | 外部write、GitHub変更、メール下書き | 毎回承認 |
| R3 | send/publish/delete/merge/secret change | 詳細確認、必要なら生体認証 |
| R4 | financial order | 非対応 |

## 8. SDUI component catalog

v1 allowlist:

### Layout

- `Column`
- `Row`
- `Grid`
- `Section`
- `Spacer`
- `Divider`
- `Tabs`

### Content

- `Text`
- `Markdown`
- `Image`
- `Icon`
- `Status`
- `Badge`
- `Metric`
- `KeyValue`
- `FilePreview`

### Collections

- `List`
- `Table`
- `Timeline`
- `Kanban`
- `GroupedList`

### Input

- `Form`
- `TextInput`
- `TextArea`
- `Select`
- `MultiSelect`
- `DateTime`
- `Toggle`
- `Button`
- `Confirmation`

### Hermes-aware

- `RunCard`
- `SessionLink`
- `ApprovalCard`
- `ArtifactCard`
- `ToolEvent`
- `PromptComposer`

### Visualization

- `Progress`
- `Sparkline`
- `BarChart`
- `LineChart`

chartはv1では単純なdatasetのみ。

## 9. SDUI action catalog

- `navigate`
- `goBack`
- `submitForm`
- `callPluginApi`
- `startHermesRun`
- `openSession`
- `openRun`
- `respondApproval`
- `copy`
- `download`
- `share`
- `openExternalUrl`
- `refresh`

任意shell command、任意HTTP request、任意JavaScriptは許可しない。

## 10. SDUI document例

```json
{
  "schemaVersion": "1.0",
  "route": "career.overview",
  "title": "就活",
  "body": [
    {
      "type": "Section",
      "title": "締切",
      "children": [
        {
          "type": "List",
          "dataSource": {
            "type": "pluginApi",
            "path": "/api/plugins/career/mobile/deadlines"
          },
          "itemTemplate": {
            "type": "KeyValue",
            "title": "{{company}}",
            "value": "{{deadline}}",
            "status": "{{urgency}}"
          }
        }
      ]
    }
  ]
}
```

v1実装では、templateとdata bindingを小さく限定する。
汎用プログラミング言語に発展させない。

## 11. Native contribution contract

```kotlin
interface NativeMiniAppContribution {
    val manifest: MiniAppManifest
    val entryInstaller: EntryProviderInstaller
    val homeCardProviders: Set<HomeCardProvider>
    val commandProviders: Set<GlobalCommandProvider>
    val activityRenderers: Set<ActivityRenderer>
}
```

Phase 1は手動registryで登録する。Hilt `@IntoSet` はPhase 2の候補に留める。

## 12. WebView security profile

必須:

- `javaScriptEnabled` は対象appだけ
- `allowFileAccess = false`
- `allowContentAccess = false`
- mixed content禁止
- origin allowlist
- navigation intercept
- system browserへの外部リンク移譲
- generic `addJavascriptInterface` 禁止
- message schema validation
- short-lived capability token
- tokenはURL queryへ長期間残さない
- cache/cookie削除操作
- download/uploadをhost broker経由
- CSPとframe policy
- crashをhost全体から隔離

可能なら別processのActivityを使う。

## 13. Lifecycle

```text
Discovered
→ Validated
→ Permission review
→ Enabled
→ Active
→ Disabled
→ Removed
```

validation failure時:

- hostは起動しない
-理由を表示
-他Mini Appに影響させない

## 14. Versioning

- manifest `schemaVersion`
- host `minHostVersion`
- Mini App semver
- SDUI component version
- action protocol version

manifestは専用camelCase decoderとSchemaの `additionalProperties:false` に従い、
Schemaが許可しない未知fieldを拒否する。既存通信用SnakeCase/ignoreUnknownKeys設定を流用しない。
これは監査で見つかった本文とSchemaの不一致の解消であり、Schema自体は変更しない。
未知の必須component/action/permissionも明示エラー。ID重複、サイズ、scheme/origin/path、
組込feature存在確認などの意味検証はSchema検証だけで代替しない。

## 15. ストレージ

Mini Appごとにnamespaceを分ける。

```text
miniapp/<id>/preferences
miniapp/<id>/cache
miniapp/<id>/drafts
```

Mini Appは他appのnamespaceを読めない。

## 16. サンプル

- `examples/career/manifest.json`
- `examples/university/manifest.json`
- `examples/development/manifest.json`
