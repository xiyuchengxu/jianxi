package dev.rain.laundry.i18n

import android.content.Context
import android.annotation.SuppressLint
import java.util.Locale

internal val messagePlaceholder = Regex("%([1-9][0-9]*)\\\$[sdf]")

internal class MessageTemplate(val source: String, val id: Int) {
    val argumentCount = messagePlaceholder.findAll(source).count()
    val marker = source.split(messagePlaceholder).maxByOrNull { it.length }.orEmpty()
    val matcher: Regex? by lazy {
        if (argumentCount == 0) null else {
            val out = StringBuilder("^")
            var end = 0
            messagePlaceholder.findAll(source).forEach {
                out.append(Regex.escape(source.substring(end, it.range.first))).append("(.*?)")
                end = it.range.last + 1
            }
            Regex(out.append(Regex.escape(source.substring(end))).append('$').toString(), RegexOption.DOT_MATCHES_ALL)
        }
    }
}

/** Resource-backed UI text. Protocol messages and identifiers stay canonical in the domain layer. */
@SuppressLint("StaticFieldLeak") // Only the Application and a context derived from it are retained.
object AppStrings {
    @Volatile private var application: Context? = null
    @Volatile private var cached: Context? = null
    private val exact by lazy { messageCatalog.filter { it.argumentCount == 0 }.associateBy { it.source } }
    private val byId by lazy { messageCatalog.associateBy { it.id } }
    private val templates by lazy { messageCatalog.filter { it.argumentCount > 0 }.sortedByDescending { it.marker.length } }

    fun initialize(context: Context) {
        application = context.applicationContext
        LanguagePreferences.initialize(context.applicationContext)
        invalidate()
    }

    fun invalidate() { cached = null }

    private fun resourceContext(): Context? {
        val context = application ?: return null
        return cached ?: LanguagePreferences.wrap(context).also { cached = it }
    }

    fun locale(): Locale = resourceContext()?.resources?.configuration?.locales?.get(0) ?: Locale.SIMPLIFIED_CHINESE

    fun text(id: Int, vararg arguments: Any?): String {
        val context = resourceContext()
        val source = byId[id]?.source.orEmpty()
        return if (context == null) canonicalFormat(source, arguments) else {
            try { if (arguments.isEmpty()) context.getString(id) else context.getString(id, *arguments) }
            catch (_: Exception) { canonicalFormat(source, arguments) }
        }
    }

    fun translate(source: String): String {
        if (source.length > 12_000 || source.none { it in '\u3400'..'\u9fff' } || locale().toLanguageTag().startsWith("zh-Hans")) return source
        exact[source]?.let { return text(it.id) }
        for (entry in templates) {
            if (entry.marker.isNotEmpty() && !source.contains(entry.marker)) continue
            val match = entry.matcher?.matchEntire(source) ?: continue
            // Captures may be user-entered names or platform content. Keep them verbatim.
            val arguments = match.groupValues.drop(1).toTypedArray()
            return text(entry.id, *arguments)
        }
        return source // Unknown platform content is retained, never sent to a translation service.
    }

    private fun canonicalFormat(source: String, arguments: Array<out Any?>): String =
        if (arguments.isEmpty()) source else messagePlaceholder.replace(source) { match ->
            arguments.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: ""
        }
}

fun appText(id: Int, vararg arguments: Any?): String = AppStrings.text(id, *arguments)
fun localizedMessage(source: String): String = AppStrings.translate(source)
