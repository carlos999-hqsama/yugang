package com.personaldashboard.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Host-private model accounting. All instances targeting the same canonical path share one lock and
 * reload the file for every transaction; a damaged file is an error rather than an opportunity to
 * silently reset a budget.
 */
class PluginModelStore(directory: File) {
    private val file = File(directory, "model-budgets.json")
    private val lock = pathLock(file.absoluteFile.canonicalPath)

    constructor(context: Context) : this(File(context.filesDir, "plugin-model"))

    fun record(pluginId: String, today: String): BudgetRecord =
        transaction(pluginId, today) { row ->
            row ?: BudgetRecord(PluginModelPolicy.DEFAULT_DAILY_BUDGET_TOKENS, 0, 0)
        }

    fun setBudget(pluginId: String, budgetTokens: Long, today: String): BudgetRecord {
        PluginModelPolicy.validateBudget(budgetTokens)
        return transaction(pluginId, today) { row ->
            val current = row ?: BudgetRecord(budgetTokens, 0, 0)
            current.copy(budgetTokens = budgetTokens)
        }
    }

    /**
     * Atomically reserve a conservative call allowance. Unknown outcomes keep the reservation until
     * the local day rolls over; they are never reported as used.
     */
    fun reserve(pluginId: String, tokens: Long, today: String): BudgetRecord {
        require(tokens > 0) { "模型预留用量必须大于零" }
        return transaction(pluginId, today) { row ->
            val current = row ?: BudgetRecord(PluginModelPolicy.DEFAULT_DAILY_BUDGET_TOKENS, 0, 0)
            check(current.availableTokens >= tokens) {
                "剩余模型预算不足（真实已用 ${current.usedTokens}，结果未知预留 ${current.reservedTokens}）"
            }
            current.copy(reservedTokens = current.reservedTokens + tokens)
        }
    }

    /** Settle only provider-reported usage. A malformed output must settle first. */
    fun settleUsage(
        pluginId: String,
        actualTokens: Long,
        reservedTokens: Long,
        today: String,
    ): BudgetRecord {
        require(actualTokens > 0) { "模型真实用量必须大于零" }
        require(reservedTokens > 0) { "模型预留用量必须大于零" }
        return transaction(pluginId, today) { row ->
            val current = row ?: error("模型预留记录不存在，不能结算未知状态")
            val released = reservedTokens.coerceAtMost(current.reservedTokens)
            current.copy(
                usedTokens = current.usedTokens + actualTokens,
                reservedTokens = current.reservedTokens - released,
            )
        }
    }

    /** Only a cancellation before the HTTP call starts is known to have no remote side effect. */
    fun releaseReservation(pluginId: String, reservedTokens: Long, today: String): BudgetRecord {
        require(reservedTokens > 0) { "模型预留用量必须大于零" }
        return transaction(pluginId, today) { row ->
            val current = row ?: error("模型预留记录不存在，不能释放")
            current.copy(
                reservedTokens = (current.reservedTokens - reservedTokens).coerceAtLeast(0),
            )
        }
    }

    private inline fun transaction(
        pluginId: String,
        today: String,
        change: (BudgetRecord?) -> BudgetRecord,
    ): BudgetRecord {
        val id = PluginContract.id(pluginId)
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(today)) { "模型预算日期无效" }
        synchronized(lock) {
            val root = loadRequired()
            val records = root.getJSONObject("records")
            val old = records.optJSONObject(id)
            val previous = old?.let(::rowToRecord)
            val effectivePrevious =
                if (old?.optString("day") == today) previous
                else {
                    // Carry the user's configured limit; a new day releases old reservations.
                    previous?.copy(usedTokens = 0, reservedTokens = 0)
                }
            val result = change(effectivePrevious)
            validate(result)
            records.put(id, recordToRow(result, today))
            save(root)
            return result
        }
    }

    private fun loadRequired(): JSONObject {
        if (!file.isFile) return JSONObject().put("records", JSONObject())
        val parsed = runCatching {
            JSONObject(file.readText())
        }
            .getOrElse {
                throw IllegalStateException("宿主模型预算存储损坏，已停止本次模型操作", it)
            }
        if (parsed.opt("records") !is JSONObject) {
            error("宿主模型预算存储结构损坏，已停止本次模型操作")
        }
        val records = parsed.getJSONObject("records")
        records.keys().asSequence().forEach { key ->
            PluginContract.id(key)
            rowToRecord(records.getJSONObject(key))
        }
        return parsed
    }

    private fun rowToRecord(row: JSONObject): BudgetRecord {
        if (!row.has("day")) error("宿主模型预算记录损坏，已停止本次模型操作")
        return BudgetRecord(
            budgetTokens = row.getLong("budgetTokens"),
            usedTokens = row.getLong("usedTokens"),
            reservedTokens = row.getLong("reservedTokens"),
        )
    }

    private fun recordToRow(record: BudgetRecord, today: String): JSONObject =
        JSONObject()
            .put("day", today)
            .put("budgetTokens", record.budgetTokens)
            .put("usedTokens", record.usedTokens)
            .put("reservedTokens", record.reservedTokens)

    private fun validate(record: BudgetRecord) {
        PluginModelPolicy.validateBudget(record.budgetTokens)
        require(record.usedTokens >= 0 && record.reservedTokens >= 0) { "模型预算记录无效" }
    }

    private fun save(next: JSONObject) {
        file.parentFile?.mkdirs()
        check(file.parentFile?.isDirectory == true) { "无法创建宿主模型预算目录" }
        val temp = File(file.parentFile, "model-budgets.new")
        FileOutputStream(temp).use { output ->
            output.write(next.toString().toByteArray())
            output.fd.sync()
        }
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    data class BudgetRecord(
        val budgetTokens: Long,
        val usedTokens: Long,
        val reservedTokens: Long,
    ) {
        val availableTokens: Long
            get() = (budgetTokens - usedTokens - reservedTokens).coerceAtLeast(0)

        val overBudget: Boolean
            get() = usedTokens + reservedTokens > budgetTokens
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        internal fun pathLock(path: String): Any = locks.getOrPut(path) { Any() }
    }
}

/** Pure limits and request/response validation for the host model route. */
object PluginModelPolicy {
    const val CAPABILITY = "plugin.model"
    const val PROVIDER = "deepseek"
    const val DEFAULT_MODEL = "deepseek-flash"
    val ALLOWED_MODELS = listOf("deepseek-flash", "deepseek-v4-pro")

    const val DEFAULT_DAILY_BUDGET_TOKENS = 2_000L
    const val MIN_DAILY_BUDGET_TOKENS = 100L
    const val MAX_DAILY_BUDGET_TOKENS = 20_000L

    const val MAX_MESSAGES = 16
    const val MAX_MESSAGE_CHARS = 8_000
    const val MAX_INPUT_CHARS = 24_000
    const val MAX_INPUT_BYTES = 32 * 1024
    const val MAX_INPUT_TOKEN_ESTIMATE = 16_000
    const val MAX_OUTPUT_CHARS = 24_000
    const val MIN_OUTPUT_TOKENS = 1
    const val MAX_OUTPUT_TOKENS = 4_096
    const val DEFAULT_OUTPUT_TOKENS = 1_024
    const val MAX_TRANSLATION_CHARS = 12_000
    const val DEFAULT_TRANSLATION_TARGET = "zh-CN"

    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MIN_TIMEOUT_MS = 5_000L
    const val MAX_TIMEOUT_MS = 60_000L
    const val MAX_CONCURRENT_CALLS = 2
    const val MAX_RESPONSE_BYTES = 128 * 1024

    private val COMMON_FIELDS = setOf("op", "model", "maxOutputTokens", "timeoutMs")
    private val CHAT_FIELDS = COMMON_FIELDS + "messages"
    private val TRANSLATE_FIELDS = COMMON_FIELDS + setOf("text", "targetLanguage")

    fun validateRequest(op: String, request: JSONObject) {
        val allowed =
            when (op) {
                "status" -> setOf("op")
                "budget" -> setOf("op", "dailyTokenLimit")
                "chat" -> CHAT_FIELDS
                "translate" -> TRANSLATE_FIELDS
                else -> throw IllegalArgumentException("不支持的模型操作")
            }
        request.keys().asSequence().forEach { field ->
            require(field in allowed) { "模型接口不支持额外参数：$field" }
        }
    }

    fun validateBudget(value: Long) {
        require(value in MIN_DAILY_BUDGET_TOKENS..MAX_DAILY_BUDGET_TOKENS) {
            "每插件日预算必须在 $MIN_DAILY_BUDGET_TOKENS–$MAX_DAILY_BUDGET_TOKENS tokens 内"
        }
    }

    fun validateModel(value: String): String {
        require(value in ALLOWED_MODELS) { "宿主只支持固定 DeepSeek 模型" }
        return value
    }

    fun timeout(value: Long): Long {
        require(value == 0L || value >= MIN_TIMEOUT_MS) { "模型超时时间过短" }
        return (if (value == 0L) DEFAULT_TIMEOUT_MS else value).coerceAtMost(MAX_TIMEOUT_MS)
    }

    fun outputTokens(value: Int): Int {
        require(value == 0 || value in MIN_OUTPUT_TOKENS..MAX_OUTPUT_TOKENS) { "输出 token 数无效" }
        return if (value == 0) DEFAULT_OUTPUT_TOKENS else value
    }

    /** UTF-8 bytes are a conservative upper bound for one token; add protocol overhead. */
    fun estimateTokens(messages: List<Pair<String, String>>): Int = messages.sumOf { (_, text) ->
        text.toByteArray(Charsets.UTF_8).size + 16
    }

    fun messages(value: org.json.JSONArray): List<Pair<String, String>> {
        require(value.length() in 1..MAX_MESSAGES) { "模型消息数量无效" }
        val result =
            (0 until value.length()).map { index ->
                val row = requireNotNull(value.optJSONObject(index)) { "模型消息必须是对象" }
                val role = row.optString("role")
                require(role in setOf("system", "user", "assistant")) { "模型消息角色无效" }
                require(row.length() == 2 && row.has("content")) { "模型消息包含额外字段" }
                val content = row.opt("content")
                require(content is String) { "模型消息内容必须是字符串" }
                role to content
            }
        validateMessageText(result)
        val estimate = estimateTokens(result)
        require(estimate <= MAX_INPUT_TOKEN_ESTIMATE) { "模型输入超出保守 token 上限" }
        return result
    }

    fun translation(text: String, targetLanguage: String): List<Pair<String, String>> {
        require(text.isNotEmpty() && text.length <= MAX_TRANSLATION_CHARS) {
            "翻译文本长度必须在 1–$MAX_TRANSLATION_CHARS 字内"
        }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "翻译文本超过 32 KiB" }
        require(Regex("[A-Za-z][A-Za-z0-9-]{1,19}").matches(targetLanguage)) { "目标语言无效" }
        val result =
            listOf(
                "system" to
                    "Translate the user text to $targetLanguage. Return only the translation.",
                "user" to text,
            )
        validateMessageText(result)
        require(estimateTokens(result) <= MAX_INPUT_TOKEN_ESTIMATE) { "翻译文本超出保守 token 上限" }
        return result
    }

    fun preflight(
        usedTokens: Long,
        budgetTokens: Long,
        estimatedInputTokens: Int,
        outputTokens: Int,
    ) {
        require(budgetTokens in MIN_DAILY_BUDGET_TOKENS..MAX_DAILY_BUDGET_TOKENS) { "插件模型预算无效" }
        require(usedTokens >= 0) { "插件模型用量无效" }
        require(usedTokens + estimatedInputTokens + outputTokens <= budgetTokens) {
            "剩余模型预算不足（真实已用 ${usedTokens}，估算输入 ${estimatedInputTokens} + 输出 $outputTokens tokens）"
        }
    }

    fun parseUsage(value: JSONObject): Long {
        val usage = value.optJSONObject("usage") ?: error("MODEL_USAGE_MISSING")
        val prompt = usage.optLong("prompt_tokens", -1)
        val completion = usage.optLong("completion_tokens", -1)
        val total = usage.optLong("total_tokens", -1)
        val sum = if (prompt > 0 && completion > 0) prompt + completion else -1
        return total.takeIf { it > 0 } ?: sum.takeIf { it > 0 } ?: error("MODEL_USAGE_MISSING")
    }

    fun parseOutput(value: JSONObject): Pair<String, String> {
        val choices = value.optJSONArray("choices") ?: error("MODEL_OUTPUT_INVALID")
        if (choices.length() != 1) error("MODEL_OUTPUT_INVALID")
        val choice = choices.optJSONObject(0) ?: error("MODEL_OUTPUT_INVALID")
        val text = choice.optJSONObject("message")?.optString("content").orEmpty()
        if (text.isEmpty() || text.length > MAX_OUTPUT_CHARS) error("MODEL_OUTPUT_INVALID")
        return text to choice.optString("finish_reason").take(32)
    }

    private fun validateMessageText(messages: List<Pair<String, String>>) {
        require(messages.all { it.second.length <= MAX_MESSAGE_CHARS }) {
            "单条模型消息超过 $MAX_MESSAGE_CHARS 字"
        }
        val all = messages.joinToString("\n") { it.second }
        require(all.length <= MAX_INPUT_CHARS) { "模型输入超过 $MAX_INPUT_CHARS 字" }
        require(all.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) { "模型输入超过 32 KiB" }
    }
}
