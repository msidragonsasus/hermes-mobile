# Hermes PC電源App

Hermes Mobileの「PC電源」画面から、固定した自宅PCへ次の処理だけを実行する。

- 起動: 自宅LAN上の `hermes-node` が固定MACへMagic Packetを3回送る。
- スリープ: Dashboardが固定URLのWindows受信処理へ、秘密値付きの固定操作を1回送る。
- 状態: `hermes-node` から固定IPv4へpingし、起動中／到達不能／確認不能を区別する。

LLM・Agent・任意コマンド実行は経路に含めない。Mobileは既存Dashboardのcookie認証を利用し、MAC、LANアドレス、受信秘密値を保持しない。スリープの通信結果が不明なときは自動再送しない。

## 現在確認済みの構成

2026-09-15時点で、`hermes-node` は `192.168.0.38/24`、対象 `desktop-ryzen7` はLAN `192.168.0.11` / Tailscale `100.92.109.109`。対象の有線MACは以前の実機確認で `9C-6B-00-88-78-CB`。導入時に再確認すること。

## Windows受信処理

導入は管理者権限・Firewall・起動時タスクを変更するため、コード配置とは別に本人の承認後に行う。

```powershell
Set-Location server\pc_power\windows
.\Install-HermesPcPower.ps1 -ListenAddress 100.92.109.109 -AllowedClient 100.103.190.68
```

生成された `%ProgramData%\HermesPcPower\sleep-token` は表示・チャット送信せず、安全な管理経路で `hermes-node` の `/var/lib/hermes/pc-power/sleep-token` へ0600・hermes所有で配置する。
導入作業用の `/tmp/.../sleep-token` へ直接渡す場合だけ、`-SshKey` とallowlist検証される `-TokenDestination` を同時に指定できる。秘密値自体はコマンドラインへ載らない。

インストーラーはSYSTEM・AdministratorsのSIDだけに許可したディレクトリを作成してからコード・秘密値を配置する。
既存配置は所有者・全ファイルのACL・reparse pointを検査し、安全な再導入では秘密値と操作履歴を保持する。
検査失敗時は配置・タスク変更前に停止するため、既存データを削除せず管理者が由来と権限を確認すること。
過去に第三者が読めた秘密値を権限変更だけで安全とは扱わず、必要な再発行は接続先との更新を含めて別途承認して行う。

## Dashboardプラグイン

コードを `/var/lib/hermes/.hermes/plugins/pc-power` に配置し、既存サービスへ次の環境ファイルを追加する。秘密値はリポジトリへ保存しない。

```dotenv
PC_POWER_TARGET_NAME=desktop-ryzen7
PC_POWER_TARGET_IPV4=192.168.0.11
PC_POWER_TARGET_MAC=9C-6B-00-88-78-CB
PC_POWER_BROADCAST_IPV4=192.168.0.255
PC_POWER_WAKE_PORT=9
PC_POWER_SLEEP_URL=http://100.92.109.109:9120/sleep
PC_POWER_SLEEP_TOKEN_FILE=/var/lib/hermes/pc-power/sleep-token
```

Dashboard plugin APIのmountと認証middlewareが現在のHermes版で維持されていることを確認後、サービスを再起動する。公開ポートやルーターのポート転送は追加しない。

`tools/install_server.sh` は本配置前に既存プラグイン、状態ディレクトリ、systemd drop-inを `/var/backups/hermes/` へ保存し、固定ファイルだけを配置する。引数にはコード、`runtime.env`、Windowsから安全に転送した `sleep-token` を含む管理者専用の一時ディレクトリを指定する。

## テスト

```sh
cd server/pc_power
PYTHONPATH=. python3 -m unittest discover -s tests -v
```

単体テストはネットワーク送信を偽装する。実機確認では、在宅時に状態取得、スリープ、データ保持を伴う復帰、起動の順で一度ずつ確認する。

## スリープ受信の再発防止（2026-10-18）

- `SetSuspendState`のBOOLEAN引数・戻り値を1 byteで扱い、`SeShutdownPrivilege`を明示的に有効化する。
  [MicrosoftのAPI仕様](https://learn.microsoft.com/en-us/windows/win32/api/powrprof/nf-powrprof-setsuspendstate)に従う。
- 権限不足の場合は`/health`の`ready=false`、POSTは503。受理202は実際のスリープ完了を表さない。
- 実行結果を`completed-operations.txt.status.json`へ保存し、失敗した最新操作の重複要求には409を返す。
  再起動後も受理済みIDを実行し直さない。診断にはID、状態、Win32エラー番号だけを含め、秘密値は含めない。
- `/status`は実行失敗・権限不足をMobileへ返す。認証済み受信応答があればICMP不応答でもスリープを操作できる。
- Tailscaleのアドレス確定を最大約1分待ち、起動時・ログオン時・5分間隔のタスクで復旧する。
  `IgnoreNew`により稼働中の二重起動を防ぐ。インストーラーは旧スクリプトを退避し、待受開始まで検査する。
- WindowsのHTTP統合テストは一時コピーのネイティブ処理だけを失敗する偽物へ置換し、認証、権限不足、
  実行失敗、重複、再起動後の記録保持を確認する。PCのスリープ、タスク登録、ProgramDataの変更は行わない。

更新反映にはWindows受信処理とDashboardプラグインの両方の更新が必要。
通常権限でタスクが表示されなくても未登録とは断定せず、管理者権限で稼働状態を確認する。
