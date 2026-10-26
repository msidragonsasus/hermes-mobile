package com.m57.hermescontrol.ui.config

import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The human-facing layer over the Hermes `config.yaml`. The server schema is a mechanical dump of
 * DEFAULT_CONFIG (labels such as "Compression → Proactive Prune Tokens"), so this catalog picks the
 * handful of settings a person actually changes and describes each one by what it does. Every
 * choice is translated back into plain config keys; nothing about the stored format changes.
 */
data class AgentSettingOption(
    val label: String,
    val description: String? = null,
    /** Config writes this choice stands for (dot-path → value). Several keys form one choice. */
    val values: Map<String, JsonElement>,
    /** Shown in a confirmation dialog before this choice is saved. */
    val confirmMessage: String? = null,
)

enum class AgentSettingControl { SWITCH, CHOICE }

data class AgentSetting(
    val id: String,
    val title: String,
    val description: String,
    val control: AgentSettingControl,
    /** For [AgentSettingControl.SWITCH]: `[on, off]`. */
    val options: List<AgentSettingOption>,
    /** Extra words that should find this setting in search. */
    val keywords: List<String> = emptyList(),
    /**
     * Value the server assumes when a key is absent from both config and DEFAULT_CONFIG. Only
     * keys the server reads outside DEFAULT_CONFIG (e.g. `session_reset`) need this.
     */
    val serverFallbacks: Map<String, JsonElement> = emptyMap(),
    /** Schema key whose select options are appended as choices (e.g. the timezone list). */
    val schemaOptionsKey: String? = null,
) {
    val keys: List<String> get() = options.flatMap { it.values.keys }.distinct() + listOfNotNull(schemaOptionsKey)
}

data class AgentSettingGroup(
    val title: String,
    val settings: List<AgentSetting>,
)

private fun str(value: String) = JsonPrimitive(value)

private fun num(value: Int) = JsonPrimitive(value)

private val on = JsonPrimitive(true)
private val off = JsonPrimitive(false)

private fun switch(
    id: String,
    key: String,
    title: String,
    description: String,
    keywords: List<String> = emptyList(),
) = AgentSetting(
    id = id,
    title = title,
    description = description,
    control = AgentSettingControl.SWITCH,
    options =
        listOf(
            AgentSettingOption("オン", values = mapOf(key to on)),
            AgentSettingOption("オフ", values = mapOf(key to off)),
        ),
    keywords = keywords,
)

object AgentSettingsCatalog {
    val groups: List<AgentSettingGroup> =
        listOf(
            AgentSettingGroup(
                "返答の見せ方",
                listOf(
                    switch(
                        "show_reasoning",
                        "display.show_reasoning",
                        "考えている内容を表示",
                        "返答の前に、AIが何を考えたかを表示します。",
                        listOf("思考", "推論", "reasoning"),
                    ),
                    switch(
                        "interim_messages",
                        "display.interim_assistant_messages",
                        "作業の途中で一言報告する",
                        "長い作業の途中で「いま何をしているか」を短く送ってきます。",
                        listOf("途中経過", "進捗"),
                    ),
                    AgentSetting(
                        id = "busy_input",
                        title = "作業中に送ったメッセージ",
                        description = "AIが作業している最中に新しいメッセージを送ったときの扱いです。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption(
                                    "作業を止めて新しい指示へ",
                                    "今の作業を中断し、送ったメッセージにすぐ取りかかります。",
                                    mapOf("display.busy_input_mode" to str("interrupt")),
                                ),
                                AgentSettingOption(
                                    "終わってから順番に",
                                    "今の作業を最後まで終えてから、次のメッセージを処理します。",
                                    mapOf("display.busy_input_mode" to str("queue")),
                                ),
                                AgentSettingOption(
                                    "作業を続けたまま方向修正",
                                    "作業は止めず、送ったメッセージを追加の指示として取り込みます。",
                                    mapOf("display.busy_input_mode" to str("steer")),
                                ),
                            ),
                        keywords = listOf("割り込み", "キュー", "busy"),
                    ),
                ),
            ),
            AgentSettingGroup(
                "速さ",
                listOf(
                    AgentSetting(
                        id = "service_tier",
                        title = "高速モード",
                        description = "対応しているモデルで、料金を多めに払って返答を速くします。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption(
                                    "サーバーにおまかせ",
                                    "特に指定しません。",
                                    mapOf("agent.service_tier" to str("")),
                                ),
                                AgentSettingOption(
                                    "使わない",
                                    "常に通常の速さで動きます。",
                                    mapOf("agent.service_tier" to str("normal")),
                                ),
                                AgentSettingOption(
                                    "いつも使う",
                                    "すべての返答で高速モードを使います。料金が上がります。",
                                    mapOf("agent.service_tier" to str("fast")),
                                ),
                                AgentSettingOption(
                                    "返答の出だしだけ",
                                    "各返答の最初の数十秒だけ高速モードを使います。",
                                    mapOf("agent.service_tier" to str("auto")),
                                ),
                                AgentSettingOption(
                                    "会話の最初だけ",
                                    "会話の最初の返答だけ高速モードを使います。",
                                    mapOf("agent.service_tier" to str("cold")),
                                ),
                            ),
                        keywords = listOf("fast", "速度", "料金"),
                    ),
                ),
            ),
            AgentSettingGroup(
                "安全",
                listOf(
                    AgentSetting(
                        id = "approvals",
                        title = "危険な操作の前に確認",
                        description = "ファイル削除などのコマンドを実行する前に、あなたに確認するかどうかです。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption(
                                    "毎回確認する",
                                    "危険かもしれないコマンドは、すべて確認してから実行します。",
                                    mapOf("approvals.mode" to str("manual")),
                                ),
                                AgentSettingOption(
                                    "AIが判断して必要なときだけ",
                                    "明らかに安全なものは確認せず、危ないものだけ確認します。",
                                    mapOf("approvals.mode" to str("smart")),
                                ),
                                AgentSettingOption(
                                    "確認しない",
                                    "危険なコマンドも確認なしで実行します。",
                                    mapOf("approvals.mode" to str("off")),
                                    confirmMessage =
                                        "ファイルの削除などを含め、AIが確認なしでコマンドを実行するようになります。本当に確認をなくしますか？",
                                ),
                            ),
                        keywords = listOf("承認", "許可", "approval", "yolo"),
                    ),
                    AgentSetting(
                        id = "terminal_backend",
                        title = "コマンドを実行する場所",
                        description = "AIがコマンドを動かす環境です。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption(
                                    "このPCで直接",
                                    "Hermesが動いているPC上でそのまま実行します。",
                                    mapOf("terminal.backend" to str("local")),
                                ),
                                AgentSettingOption(
                                    "Dockerの中で隔離して",
                                    "コンテナの中で実行し、PC本体への影響を抑えます。PCでDockerが動いている必要があります。",
                                    mapOf("terminal.backend" to str("docker")),
                                    confirmMessage =
                                        "PCでDockerが動いていないと、AIはコマンドを実行できなくなります。切り替えますか？",
                                ),
                            ),
                        keywords = listOf("ターミナル", "docker", "サンドボックス"),
                    ),
                ),
            ),
            AgentSettingGroup(
                "作業の長さ",
                listOf(
                    AgentSetting(
                        id = "max_turns",
                        title = "1回の依頼で動ける回数",
                        description = "ひとつの依頼の中で、AIがツールを使って作業を繰り返せる上限です。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption(
                                    "制限なし",
                                    "終わるまで作業を続けます。",
                                    mapOf("agent.max_turns" to JsonNull),
                                ),
                                AgentSettingOption("50回まで", "短めの作業向け。", mapOf("agent.max_turns" to num(50))),
                                AgentSettingOption("150回まで", null, mapOf("agent.max_turns" to num(150))),
                                AgentSettingOption("500回まで", "長い作業も最後まで。", mapOf("agent.max_turns" to num(500))),
                            ),
                        keywords = listOf("ターン", "上限", "ステップ"),
                    ),
                    AgentSetting(
                        id = "terminal_timeout",
                        title = "コマンド1回の制限時間",
                        description = "ひとつのコマンドがこの時間を超えると打ち切ります。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption("1分", null, mapOf("terminal.timeout" to num(60))),
                                AgentSettingOption("3分", null, mapOf("terminal.timeout" to num(180))),
                                AgentSettingOption("10分", null, mapOf("terminal.timeout" to num(600))),
                                AgentSettingOption("30分", "ビルドなど時間のかかる作業向け。", mapOf("terminal.timeout" to num(1800))),
                            ),
                        keywords = listOf("タイムアウト", "timeout"),
                    ),
                ),
            ),
            AgentSettingGroup(
                "記憶と会話",
                listOf(
                    switch(
                        "memory",
                        "memory.memory_enabled",
                        "大事なことを覚える",
                        "会話の中で覚えておくべきことを、次の会話でも使えるよう保存します。",
                        listOf("メモリ", "memory"),
                    ),
                    switch(
                        "user_profile",
                        "memory.user_profile_enabled",
                        "あなたについて覚える",
                        "好みや呼び方など、あなた自身についての情報を覚えます。",
                        listOf("プロフィール", "memory"),
                    ),
                    switch(
                        "memory_write_approval",
                        "memory.write_approval",
                        "覚える前に確認する",
                        "記憶を保存する前に、保存してよいかあなたに確認します。",
                        listOf("メモリ", "承認"),
                    ),
                    switch(
                        "compression",
                        "compression.enabled",
                        "長い会話を自動で要約",
                        "会話が長くなりすぎたら古い部分を要約して、そのまま続けられるようにします。",
                        listOf("圧縮", "compression", "コンテキスト"),
                    ),
                    AgentSetting(
                        id = "session_reset",
                        title = "会話を自動でリセット",
                        description = "会話の文脈をまっさらにするタイミングです。覚えた記憶は残ります。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption(
                                    "しない",
                                    "自分でリセットするまで会話を続けます。",
                                    mapOf("session_reset.mode" to str("none")),
                                ),
                                AgentSettingOption(
                                    "3時間放置したら",
                                    null,
                                    mapOf(
                                        "session_reset.mode" to str("idle"),
                                        "session_reset.idle_minutes" to num(180),
                                    ),
                                ),
                                AgentSettingOption(
                                    "1日放置したら",
                                    null,
                                    mapOf(
                                        "session_reset.mode" to str("idle"),
                                        "session_reset.idle_minutes" to num(1440),
                                    ),
                                ),
                                AgentSettingOption(
                                    "毎朝4時",
                                    null,
                                    mapOf("session_reset.mode" to str("daily"), "session_reset.at_hour" to num(4)),
                                ),
                            ),
                        keywords = listOf("セッション", "リセット", "reset"),
                        serverFallbacks =
                            mapOf(
                                "session_reset.mode" to str("none"),
                                "session_reset.idle_minutes" to num(1440),
                                "session_reset.at_hour" to num(4),
                            ),
                    ),
                ),
            ),
            AgentSettingGroup(
                "地域と言語",
                listOf(
                    AgentSetting(
                        id = "timezone",
                        title = "タイムゾーン",
                        description = "AIが日時を扱うときの基準です。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption(
                                    "PCの設定に合わせる",
                                    "Hermesが動いているPCのタイムゾーンを使います。",
                                    mapOf("timezone" to str("")),
                                ),
                                AgentSettingOption("日本（Asia/Tokyo）", null, mapOf("timezone" to str("Asia/Tokyo"))),
                            ),
                        keywords = listOf("時間", "時刻", "timezone"),
                        schemaOptionsKey = "timezone",
                    ),
                    AgentSetting(
                        id = "stt_language",
                        title = "音声入力の言語",
                        description = "音声メッセージを文字にするときの言語です。",
                        control = AgentSettingControl.CHOICE,
                        options =
                            listOf(
                                AgentSettingOption("日本語", null, mapOf("stt.language" to str("ja"))),
                                AgentSettingOption("英語", null, mapOf("stt.language" to str("en"))),
                                AgentSettingOption("自動で判定", null, mapOf("stt.language" to str(""))),
                            ),
                        keywords = listOf("音声", "文字起こし", "stt"),
                    ),
                ),
            ),
        )

    val all: List<AgentSetting> = groups.flatMap { it.settings }

    fun byId(id: String): AgentSetting? = all.firstOrNull { it.id == id }
}

/**
 * A setting is shown only when the connected server knows every key it writes, so an older or
 * newer Hermes never receives keys it does not understand. Keys with [AgentSetting.serverFallbacks]
 * are read by the server outside DEFAULT_CONFIG and therefore count as known.
 */
fun AgentSetting.isAvailable(
    schema: ConfigSchemaResponse?,
    values: Map<String, JsonElement>,
): Boolean {
    val schemaKeys = schema?.fields?.keys.orEmpty()
    return keys.all { it in schemaKeys || it in values || it in serverFallbacks }
}

/** Catalog choices plus any extra values the schema offers (e.g. every IANA timezone). */
fun AgentSetting.resolvedOptions(schema: ConfigSchemaResponse?): List<AgentSettingOption> {
    val key = schemaOptionsKey ?: return options
    val known = options.mapNotNull { (it.values[key] as? JsonPrimitive)?.content }.toSet()
    val extra =
        schema
            ?.fields
            ?.get(key)
            ?.options
            .orEmpty()
            .filter { it.isNotEmpty() && it !in known }
            .map { AgentSettingOption(it, values = mapOf(key to JsonPrimitive(it))) }
    return options + extra
}

/** The value the server actually uses for [key]: saved config, then defaults, then fallbacks. */
fun AgentSetting.effectiveValue(
    key: String,
    values: Map<String, JsonElement>,
    defaults: Map<String, JsonElement>,
): JsonElement? =
    when {
        key in values -> values[key]
        key in defaults -> defaults[key]
        else -> serverFallbacks[key]
    }

/** The choice matching the current config, or null when the config holds something custom. */
fun AgentSetting.currentOption(
    schema: ConfigSchemaResponse?,
    values: Map<String, JsonElement>,
    defaults: Map<String, JsonElement>,
): AgentSettingOption? =
    resolvedOptions(schema).firstOrNull { option ->
        option.values.all { (key, value) -> sameConfigValue(effectiveValue(key, values, defaults), value) }
    }

/** Config writes that restore the server defaults, or null when there is nothing to restore. */
fun AgentSetting.defaultWrites(
    values: Map<String, JsonElement>,
    defaults: Map<String, JsonElement>,
): Map<String, JsonElement>? {
    val writes =
        options
            .flatMap { it.values.keys }
            .distinct()
            .mapNotNull { key ->
                val default = defaults[key] ?: serverFallbacks[key] ?: return@mapNotNull null
                if (sameConfigValue(effectiveValue(key, values, defaults), default)) null else key to default
            }.toMap()
    return writes.ifEmpty { null }
}

/** Human summary of the current state for the list row. */
fun AgentSetting.summary(
    schema: ConfigSchemaResponse?,
    values: Map<String, JsonElement>,
    defaults: Map<String, JsonElement>,
): String = currentOption(schema, values, defaults)?.label ?: "カスタム（詳細設定で変更されています）"

fun AgentSetting.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return title.lowercase().contains(q) ||
        description.lowercase().contains(q) ||
        keywords.any { it.lowercase().contains(q) } ||
        keys.any { it.lowercase().contains(q) }
}
