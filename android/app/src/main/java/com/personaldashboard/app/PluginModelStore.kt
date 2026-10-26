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
        PluginModelPolicy.validateStoredBudget(budgetTokens)
        return transaction(pluginId, today) { row ->
            val current = row ?: BudgetRecord(budgetTokens, 0, 0)
            current.copy(budgetTokens = budgetTokens)
        }
    }

    /**
     * Atomically reserve a conservative call allowance. Unknown outcomes keep the reservation until
     * the local day rolls over; they are never reported as used.
     */
    fun reserve(
        pluginId: String,
        tokens: Long,
        today: String,
        dailyHardLimitTokens: Long,
    ): BudgetRecord {
        require(tokens > 0) { "模型预留用量必须大于零" }
        PluginModelPolicy.validateDailyHardLimit(dailyHardLimitTokens)
        return transaction(pluginId, today) { row ->
            val current = row ?: BudgetRecord(PluginModelPolicy.DEFAULT_DAILY_BUDGET_TOKENS, 0, 0)
            val effectiveBudget = current.effectiveBudgetTokens(dailyHardLimitTokens)
            val refusal =
                PluginModelPolicy.reservationRefusal(
                    current.usedTokens,
                    current.reservedTokens,
                    effectiveBudget,
                    tokens,
                )
            if (refusal != null) {
                throw BudgetReservationRejected(
                    refusal.reason,
                    current,
                    tokens,
                    dailyHardLimitTokens,
                    refusal.message,
                )
            }
            current.copy(reservedTokens = Math.addExact(current.reservedTokens, tokens))
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
                usedTokens = Math.addExact(current.usedTokens, actualTokens),
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
        validateDay(today)
        synchronized(lock) {
            val root = loadRequired()
            val records = root.getJSONObject("records")
            val old = records.optJSONObject(id)
            val previous = old?.let(::rowToRecord)
            if (previous != null) {
                val currentDay = old?.optString("day") ?: error("模型预算日期无效")
                if (currentDay != today) {
                    val history = historyForPlugin(root, id)
                    if (today < currentDay) {
                        // A late response belongs to its original day. Never backdate or reset the
                        // current row; legacy files without history still retain known usage.
                        val historical =
                            history.optJSONObject(today)?.let(::rowToRecord)
                                ?: BudgetRecord(previous.budgetTokens, 0, 0)
                        val result = change(historical)
                        validate(result)
                        history.put(today, recordToRow(result, today))
                        save(root)
                        return result
                    }

                    // Forward rollover archives only this plugin's immediately previous day. The
                    // old reservation no longer consumes the new day but remains settleable.
                    check(!history.has(currentDay)) { "模型预算历史日期冲突，已停止本次模型操作" }
                    history.put(currentDay, recordToRow(previous, currentDay))
                    val result = change(previous.copy(usedTokens = 0, reservedTokens = 0))
                    validate(result)
                    records.put(id, recordToRow(result, today))
                    save(root)
                    return result
                }
            }

            val result = change(previous)
            validate(result)
            records.put(id, recordToRow(result, today))
            save(root)
            return result
        }
    }

    private fun historyForPlugin(root: JSONObject, pluginId: String): JSONObject {
        val history =
            if (root.has("history")) root.getJSONObject("history")
            else JSONObject().also { root.put("history", it) }
        if (history.has(pluginId)) return history.getJSONObject(pluginId)
        return JSONObject().also { history.put(pluginId, it) }
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
        validateHistory(parsed)
        return parsed
    }

    private fun validateHistory(root: JSONObject) {
        val raw = root.opt("history") ?: return
        if (raw !is JSONObject) error("宿主模型预算历史结构损坏，已停止本次模型操作")
        raw.keys().asSequence().forEach { pluginId ->
            PluginContract.id(pluginId)
            val days = raw.getJSONObject(pluginId)
            days.keys().asSequence().forEach { day ->
                validateDay(day)
                val row = days.getJSONObject(day)
                if (row.optString("day") != day) {
                    error("宿主模型预算历史日期损坏，已停止本次模型操作")
                }
                rowToRecord(row)
            }
        }
    }

    private fun validateDay(day: String) {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) { "模型预算日期无效" }
    }

    private fun rowToRecord(row: JSONObject): BudgetRecord {
        if (!row.has("day")) error("宿主模型预算记录损坏，已停止本次模型操作")
        validateDay(row.optString("day"))
        return BudgetRecord(
                budgetTokens = row.getLong("budgetTokens"),
                usedTokens = row.getLong("usedTokens"),
                reservedTokens = row.getLong("reservedTokens"),
            )
            .also { validate(it) }
    }

    private fun recordToRow(record: BudgetRecord, today: String): JSONObject =
        JSONObject()
            .put("day", today)
            .put("budgetTokens", record.budgetTokens)
            .put("usedTokens", record.usedTokens)
            .put("reservedTokens", record.reservedTokens)

    private fun validate(record: BudgetRecord) {
        PluginModelPolicy.validateStoredBudget(record.budgetTokens)
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
        fun effectiveBudgetTokens(dailyHardLimitTokens: Long): Long =
            minOf(budgetTokens, dailyHardLimitTokens)

        fun availableAt(effectiveBudgetTokens: Long): Long {
            if (usedTokens >= effectiveBudgetTokens) return 0
            val afterRealUsage = effectiveBudgetTokens - usedTokens
            return if (reservedTokens >= afterRealUsage) 0 else afterRealUsage - reservedTokens
        }

        fun overBudgetAt(effectiveBudgetTokens: Long): Boolean = usedTokens >= effectiveBudgetTokens

        fun exhaustedAt(effectiveBudgetTokens: Long): Boolean = usedTokens >= effectiveBudgetTokens

        val availableTokens: Long
            get() = availableAt(budgetTokens)

        /** Raw-record helpers; host responses must use the current effective budget instead. */
        val overBudget: Boolean
            get() = overBudgetAt(budgetTokens)

        val exhausted: Boolean
            get() = exhaustedAt(budgetTokens)
    }

    class BudgetReservationRejected
        internal constructor(
            val reason: String,
            val record: BudgetRecord,
            val requestedTokens: Long,
            val dailyHardLimitTokens: Long,
            message: String,
        ) : IllegalStateException(message)

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
    const val BUDGET_WARNING_THRESHOLD_PERCENT = 80

    const val REASON_DAILY_BUDGET_EXHAUSTED = "daily_budget_exhausted"
    const val REASON_DAILY_BUDGET_RESERVED = "daily_budget_reserved"
    const val REASON_RESERVATION_EXCEEDS_REMAINING = "daily_budget_reservation_exceeds_remaining"

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

    fun validateDailyHardLimit(value: Long): Long {
        require(value > 0) { "插件模型每日硬上限必须是正整数" }
        return value
    }

    /** The configurable host ceiling applies to new settings, never to persisted accounting. */
    fun validateBudget(value: Long, dailyHardLimitTokens: Long) {
        validateDailyHardLimit(dailyHardLimitTokens)
        validateStoredBudget(value)
        require(value <= dailyHardLimitTokens) {
            "每插件日预算不能超过当前宿主上限 $dailyHardLimitTokens tokens"
        }
    }

    fun validateStoredBudget(value: Long) {
        require(value >= MIN_DAILY_BUDGET_TOKENS) {
            "每插件日预算不能低于 $MIN_DAILY_BUDGET_TOKENS tokens"
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

    data class BudgetRefusal(val reason: String, val message: String)

    /** Classify an atomic reservation refusal without adding counters (which could overflow). */
    fun reservationRefusal(
        usedTokens: Long,
        reservedTokens: Long,
        budgetTokens: Long,
        requestedTokens: Long,
    ): BudgetRefusal? {
        require(budgetTokens > 0) { "插件模型有效预算无效" }
        require(usedTokens >= 0 && reservedTokens >= 0 && requestedTokens > 0) {
            "插件模型用量或预留无效"
        }
        if (usedTokens >= budgetTokens) {
            return BudgetRefusal(
                REASON_DAILY_BUDGET_EXHAUSTED,
                "插件本地每日预算已由真实用量耗尽（真实已用 $usedTokens/$budgetTokens tokens）",
            )
        }
        val afterRealUsage = budgetTokens - usedTokens
        val remaining =
            if (reservedTokens >= afterRealUsage) 0 else afterRealUsage - reservedTokens
        if (requestedTokens <= remaining) return null
        if (reservedTokens > 0 && remaining == 0L) {
            return BudgetRefusal(
                REASON_DAILY_BUDGET_RESERVED,
                "插件本地每日预算的剩余部分均被未知结果预留（真实已用 $usedTokens，预留 $reservedTokens/$budgetTokens tokens）",
            )
        }
        return BudgetRefusal(
            REASON_RESERVATION_EXCEEDS_REMAINING,
            "本次需要预留 $requestedTokens tokens，但仅剩 $remaining（真实已用 $usedTokens，已有预留 $reservedTokens，预算 $budgetTokens）",
        )
    }

    /** Return the accounting basis when real usage plus conservative reservations reaches 80%. */
    fun budgetWarningBasis(usedTokens: Long, reservedTokens: Long, budgetTokens: Long): String? {
        require(budgetTokens > 0) { "插件模型有效预算无效" }
        require(usedTokens >= 0 && reservedTokens >= 0) { "插件模型用量无效" }
        val threshold = budgetTokens - budgetTokens / 5
        if (usedTokens >= threshold) return "used"
        val untilWarning = threshold - usedTokens
        return if (reservedTokens >= untilWarning) "used_plus_reserved" else null
    }

    fun parseUsage(value: JSONObject): Long {
        val usage = value.optJSONObject("usage") ?: error("MODEL_USAGE_MISSING")
        if (usage.has("total_tokens")) {
            // An explicitly supplied total is authoritative; never hide an invalid value behind
            // fallback fields or truncate a JSON number/string into a fabricated real usage.
            val total = strictTokenCount(usage.opt("total_tokens"))
            if (total <= 0) error("MODEL_USAGE_MISSING")
            return total
        }
        val prompt = strictTokenCount(usage.opt("prompt_tokens"))
        val completion = strictTokenCount(usage.opt("completion_tokens"))
        val sum =
            try {
                Math.addExact(prompt, completion)
            } catch (e: ArithmeticException) {
                throw IllegalStateException("MODEL_USAGE_MISSING", e)
            }
        if (sum <= 0) error("MODEL_USAGE_MISSING")
        return sum
    }

    private fun strictTokenCount(value: Any?): Long {
        val result =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> error("MODEL_USAGE_MISSING")
            }
        if (result < 0) error("MODEL_USAGE_MISSING")
        return result
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
