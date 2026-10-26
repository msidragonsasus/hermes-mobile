package com.m57.hermescontrol.util

import java.util.Locale

/**
 * Translates the English status/toast/error strings built in ViewModels at display time.
 *
 * ViewModels (and their tests) keep producing English; screens pass the text through
 * [localize] before showing it. Only Japanese is covered; other locales get the input back.
 * Server-supplied reasons after ": " are kept verbatim.
 */
object MessageLocalizer {
    fun localize(
        message: String,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (locale.language != "ja" || message.isBlank()) return message
        exact[message]?.let { return it }
        for ((regex, render) in patterns) {
            val match = regex.matchEntire(message) ?: continue
            return render(match.groupValues)
        }
        failedTo(message)?.let { return it }
        nounFailed(message)?.let { return it }
        return message
    }

    private fun reasonSuffix(reason: String?): String = if (reason.isNullOrBlank()) "" else ": $reason"

    private val exact =
        mapOf(
            "Rescan complete" to "再集計が完了しました",
            "Achievement state reset" to "実績のデータを消去しました",
            "Name is required" to "名前を入力してください",
            "All auxiliary tasks reset to auto" to "補助タスクをすべて自動に戻しました",
            "MOA config saved" to "Mixture of Agentsの設定を保存しました",
            "Key/token is required" to "キーまたはトークンを入力してください",
            "Credential added" to "キーを追加しました",
            "Credential removed" to "キーを削除しました",
            "Backup triggered" to "バックアップを開始しました",
            "No backup archive available" to "バックアップはまだありません",
            "Backup is still being created — try again in a moment" to "バックアップを作成中です。少し待ってからもう一度お試しください",
            "Import started" to "復元を開始しました",
            "Debug share created" to "共有リンクを作成しました",
            "Command is required" to "コマンドを入力してください",
            "Hook created" to "フックを作成しました",
            "Hook deleted" to "フックを削除しました",
            "Could not read selected file" to "選択したファイルを読み込めませんでした",
            "Backup saved to Downloads" to "バックアップをダウンロードフォルダーに保存しました",
            "Could not save backup to Downloads" to "バックアップを保存できませんでした",
            "No billing data returned" to "請求情報を取得できませんでした",
            "Billing unavailable" to "請求情報を利用できません",
            "Usage data unavailable" to "使用量を取得できません",
            "Failed to obtain WebSocket ticket" to "接続用のチケットを取得できませんでした",
            "Failed to load group members" to "グループのメンバーを読み込めませんでした",
            "Task created successfully" to "タスクを作成しました",
            "Skill saved successfully" to "スキルを保存しました",
            "Updating skills from hub…" to "ハブからスキルを更新しています…",
            "Skills updated" to "スキルを更新しました",
            "Folder name is required" to "フォルダー名を入力してください",
            "Folder name cannot contain /" to "フォルダー名に / は使えません",
            "Folder created" to "フォルダーを作成しました",
            "File uploaded" to "ファイルをアップロードしました",
            "Could not decode file" to "ファイルを読み取れませんでした",
            "Key and value are required" to "キーの名前と値を入力してください",
            "Key added successfully" to "キーを追加しました",
            "Key deleted successfully" to "キーを削除しました",
            "Key updated successfully" to "キーを更新しました",
            "Gateway restart initiated" to "Gatewayを再起動しています",
            "Job triggered successfully" to "ジョブを実行しました",
            "Schedule is required" to "スケジュールを入力してください",
            "No enabled servers to test" to "テストできる有効なサーバーがありません",
            "Invalid JSON or no MCP servers found in input" to "JSONが正しくないか、MCPサーバーの定義が見つかりません",
            "Server name is required" to "サーバー名を入力してください",
            "Key is required" to "名前を入力してください",
            "Starting OAuth authorization…" to "OAuth認証を開始しています…",
            "OAuth authorization successful!" to "OAuth認証が完了しました",
            "Configuration saved successfully" to "設定を保存しました",
            "YAML configuration saved" to "YAMLの設定を保存しました",
            "Platform removed" to "連携を削除しました",
            "Still waiting for Telegram. Retrying…" to "Telegramの応答を待っています。再試行中…",
            "Allowed Telegram user IDs must be numeric." to "TelegramのユーザーIDは数字で入力してください。",
            "Add at least one allowed Telegram user ID." to "許可するTelegramのユーザーIDを1つ以上追加してください。",
            "Telegram saved. Restarting gateway…" to "Telegramの設定を保存しました。Gatewayを再起動しています…",
            "Start a new QR setup to try again." to "もう一度QRコードでの設定をやり直してください。",
            "Soul updated successfully" to "人格を更新しました",
            "Model settings updated" to "モデルの設定を更新しました",
            "Pairing request approved" to "連携の申請を承認しました",
            "Pending requests cleared" to "承認待ちの申請を消去しました",
            "Open a chat session first — process list is session-scoped." to
                "プロセスは会話ごとに表示されます。先にチャットで会話を開いてください。",
            "No active session — cannot kill." to "開いている会話がないため終了できません。",
            "Process killed" to "プロセスを終了しました",
            "Disconnected" to "接続を解除しました",
            "Paste the authorization code first." to "先に認可コードを貼り付けてください。",
            "OAuth connected" to "OAuthで接続しました",
            "Title cannot be empty" to "タイトルを入力してください",
            "Session renamed" to "会話の名前を変更しました",
            "Session pinned" to "会話を固定しました",
            "Session unpinned" to "会話の固定を解除しました",
            "Session hidden" to "会話を非表示にしました",
            "Session unhidden" to "会話を再表示しました",
            "Session deleted" to "会話を削除しました",
            "No sessions were deleted" to "削除された会話はありません",
            "Old sessions pruned" to "古い会話を整理しました",
            "No empty sessions to delete" to "削除する空の会話はありません",
            "Please enter a plugin identifier" to "プラグインの指定を入力してください",
            "Plugin installed successfully" to "プラグインをインストールしました",
            "Plugin providers saved" to "提供元の設定を保存しました",
            "Plugins rescanned" to "プラグインを再検出しました",
            "Plugin enabled successfully" to "プラグインを有効にしました",
            "Plugin uninstalled successfully" to "プラグインをアンインストールしました",
            "Plugin updated successfully" to "プラグインを更新しました",
            "Plugin visibility updated" to "プラグインの表示設定を更新しました",
            "Memory provider settings saved" to "記憶の提供元の設定を保存しました",
            "Setup completed" to "セットアップが完了しました",
            "Setup finished with failures" to "セットアップの一部が失敗しました",
            "Upgrade successful" to "アップグレードしました",
            "Upgrade requested" to "アップグレードを申し込みました",
            "Cancellation scheduled" to "解約を予約しました",
            "Plan changed" to "プランを変更しました",
            "Subscription resumed" to "サブスクリプションを再開しました",
            "Unknown error" to "不明なエラー",
            "Failed to start setup: spawn rejected" to "セットアップを開始できませんでした",
            "Failed to start OAuth: No authorization URL returned" to "OAuthを開始できませんでした: 認証URLが返されませんでした",
        )

    private fun enabledJa(word: String) = if (word == "enabled") "有効にしました" else "無効にしました"

    private val patterns: List<Pair<Regex, (List<String>) -> String>> =
        listOf(
            Regex("Saved (.+)") to { g -> "${g[1]} を保存しました" },
            Regex("Cleared (.+)") to { g -> "${g[1]} を消去しました" },
            Regex("Deleted (\\d+) empty sessions") to { g -> "空の会話を${g[1]}件削除しました" },
            Regex("Deleted (.+)") to { g -> "${g[1]} を削除しました" },
            Regex("Installed: (.+)") to { g -> "${g[1]} をインストールしました" },
            Regex("Uninstalled: (.+)") to { g -> "${g[1]} をアンインストールしました" },
            Regex("Backend set to (.+) — needs Nous Portal sign-in to activate") to
                { g -> "接続先を ${g[1]} にしました（有効にするにはNous Portalへのログインが必要です）" },
            Regex("Backend set to (.+)") to { g -> "接続先を ${g[1]} にしました" },
            Regex("Main model set to (.+)") to { g -> "メインのモデルを ${g[1]} にしました" },
            Regex("Aux task '(.+)' set to (.+)") to { g -> "補助タスク「${g[1]}」を ${g[2]} にしました" },
            Regex("Successfully set profile model to (.+)") to { g -> "プロファイルのモデルを ${g[1]} にしました" },
            Regex("Maximum of (\\d+) pinned models reached") to { g -> "固定できるモデルは${g[1]}件までです" },
            Regex("Gateway (start|stop|restart)ed successfully") to
                { g -> "Gatewayを" + mapOf("start" to "開始", "stop" to "停止", "restart" to "再起動")[g[1]] + "しました" },
            Regex("Curator (paused|resumed)") to
                { g -> if (g[1] == "paused") "スキルの自動整理を一時停止しました" else "スキルの自動整理を再開しました" },
            Regex("Webhooks global status (enabled|disabled)") to { g -> "Webhookの受信を${enabledJa(g[1])}" },
            Regex("Subscription \"(.+)\" created") to { g -> "受信設定「${g[1]}」を作成しました" },
            Regex("Subscription \"(.+)\" (enabled|disabled)") to { g -> "受信設定「${g[1]}」を${enabledJa(g[2])}" },
            Regex("Subscription \"(.+)\" deleted") to { g -> "受信設定「${g[1]}」を削除しました" },
            Regex("Server '(.+)' (enabled|disabled)") to { g -> "サーバー「${g[1]}」を${enabledJa(g[2])}" },
            Regex("Server '(.+)' tested — OK \\((.*)\\)") to { g -> "サーバー「${g[1]}」のテストに成功しました（${g[2]}）" },
            Regex("Server '(.+)' test failed: (.*)", RegexOption.DOT_MATCHES_ALL) to
                { g -> "サーバー「${g[1]}」のテストに失敗しました${reasonSuffix(g[2])}" },
            Regex("Testing server '(.+)'…") to { g -> "サーバー「${g[1]}」をテスト中…" },
            Regex("Testing (\\d+) server\\(s\\)…") to { g -> "${g[1]}件のサーバーをテスト中…" },
            Regex("Tested (\\d+) servers: (\\d+) passed, (\\d+) failed") to
                { g -> "${g[1]}件をテストしました: 成功${g[2]}件・失敗${g[3]}件" },
            Regex("Server '(.+)' added") to { g -> "サーバー「${g[1]}」を追加しました" },
            Regex("Server '(.+)' deleted") to { g -> "サーバー「${g[1]}」を削除しました" },
            Regex("Env var '(.+)' added") to { g -> "環境変数「${g[1]}」を追加しました" },
            Regex("Env var '(.+)' removed") to { g -> "環境変数「${g[1]}」を削除しました" },
            Regex("Catalog entry '(.+)' installed") to { g -> "「${g[1]}」をインストールしました" },
            Regex("OAuth failed: (.*)", RegexOption.DOT_MATCHES_ALL) to { g -> "OAuth認証に失敗しました${reasonSuffix(g[1])}" },
            Regex("Waiting for backup… (\\d+)s") to { g -> "バックアップの作成を待っています… ${g[1]}秒" },
            Regex("Reset (\\d+) field\\(s\\) to defaults \\(tap Save to apply\\)") to
                { g -> "${g[1]}件を初期値に戻しました（保存すると反映されます）" },
            Regex("Memory \\((.+)\\) reset successfully") to { g -> "記憶（${g[1]}）を消去しました" },
            Regex("Profile (.+) hidden") to { g -> "プロファイル ${g[1]} を非表示にしました" },
            Regex("Profile (.+) unhidden") to { g -> "プロファイル ${g[1]} を再表示しました" },
            Regex("Switched to profile (.+)") to { g -> "プロファイル ${g[1]} に切り替えました" },
            Regex("Profile '(.+)' cloned successfully to '(.+)'") to { g -> "プロファイル「${g[1]}」を「${g[2]}」に複製しました" },
            Regex("Profile '(.+)' description updated") to { g -> "プロファイル「${g[1]}」の説明を更新しました" },
            Regex("Profile '(.+)' renamed to '(.+)'") to { g -> "プロファイル「${g[1]}」を「${g[2]}」に名前変更しました" },
            Regex("Profile '(.+)' deleted") to { g -> "プロファイル「${g[1]}」を削除しました" },
            Regex("Auto-described profile '(.+)'") to { g -> "プロファイル「${g[1]}」の説明を作成しました" },
            Regex("Auto-describe failed for '(.+)': (.*)", RegexOption.DOT_MATCHES_ALL) to
                { g -> "プロファイル「${g[1]}」の説明を作成できませんでした${reasonSuffix(g[2])}" },
            Regex("Profile (.+) created successfully") to { g -> "プロファイル ${g[1]} を作成しました" },
            Regex("Access revoked for (.+)") to { g -> "${g[1]} のアクセスを取り消しました" },
            Regex("Could not read selected file: (.*)", RegexOption.DOT_MATCHES_ALL) to
                { g -> "選択したファイルを読み込めませんでした${reasonSuffix(g[1])}" },
            Regex("Could not open file: (.*)", RegexOption.DOT_MATCHES_ALL) to
                { g -> "ファイルを開けませんでした${reasonSuffix(g[1])}" },
            Regex("Could not load processes: (.*)", RegexOption.DOT_MATCHES_ALL) to
                { g -> "プロセスを読み込めませんでした${reasonSuffix(g[1])}" },
        )

    private val verbs =
        mapOf(
            "load" to "読み込め", "list" to "一覧を取得でき", "save" to "保存でき", "delete" to "削除でき",
            "add" to "追加でき", "create" to "作成でき", "install" to "インストールでき",
            "uninstall" to "アンインストールでき", "update" to "更新でき", "remove" to "削除でき",
            "set" to "設定でき", "switch" to "切り替えられ", "reset" to "リセットでき", "toggle" to "切り替えられ",
            "start" to "開始でき", "restart" to "再起動でき", "stop" to "停止でき", "trigger" to "実行でき",
            "rename" to "名前を変更でき", "clone" to "複製でき", "enable" to "有効にでき", "reveal" to "表示でき",
            "clear" to "消去でき", "fetch" to "取得でき", "search" to "検索でき", "configure" to "設定でき",
            "download" to "ダウンロードでき", "open" to "開け", "select" to "選択でき", "rescan" to "再検出でき",
            "auto-describe" to "説明を作成でき", "upload" to "アップロードでき", "obtain" to "取得でき",
            "kill" to "終了でき",
        )

    // "Failed to <verb> <object>[: reason]" → "<verb>ませんでした[: reason]". The object is dropped:
    // it is the thing on the current screen, and translating every noun is not worth the table.
    private val failedToRegex = Regex("Failed to ([a-z-]+)(?: [^:]*)?(?:: (.*))?", RegexOption.DOT_MATCHES_ALL)

    private fun failedTo(message: String): String? {
        val match = failedToRegex.matchEntire(message) ?: return null
        val verb = verbs[match.groupValues[1]] ?: return null
        return "${verb}ませんでした${reasonSuffix(match.groupValues[2])}"
    }

    private val nouns =
        mapOf(
            "Rescan" to "再集計", "Reset" to "リセット", "Update" to "更新", "Update check" to "アップデートの確認",
            "Toggle" to "切り替え", "Test" to "テスト", "Restart" to "再起動", "Import" to "復元", "Kill" to "終了",
            "Rename" to "名前の変更", "Pin" to "固定", "Hide" to "非表示", "Delete" to "削除", "Prune" to "整理",
            "Cleanup" to "整理", "Setup" to "セットアップ", "Disconnect" to "接続の解除",
            "Debug share" to "共有リンクの作成", "Action" to "操作",
        )

    private val nounFailedRegex = Regex("([A-Z][a-z]+(?: [a-z]+)?) failed(?:: (.*))?", RegexOption.DOT_MATCHES_ALL)

    private fun nounFailed(message: String): String? {
        val match = nounFailedRegex.matchEntire(message) ?: return null
        val noun = nouns[match.groupValues[1]] ?: return null
        return "${noun}に失敗しました${reasonSuffix(match.groupValues[2])}"
    }
}
