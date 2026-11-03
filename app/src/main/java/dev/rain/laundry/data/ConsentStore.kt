package dev.rain.laundry.data

import android.content.Context
import dev.rain.laundry.R
import dev.rain.laundry.core.AgreementAcceptance
import dev.rain.laundry.core.AgreementPolicy
import dev.rain.laundry.core.AgreementStorage

data class UsageDocuments(val disclaimer: String, val rules: String) {
    val revision: String = AgreementPolicy.revision(disclaimer, rules)

    companion object {
        const val FEEDBACK_GROUP = "1124696181"
        fun load(context: Context): UsageDocuments = UsageDocuments(
            context.resources.openRawResource(R.raw.disclaimer).bufferedReader(Charsets.UTF_8).use { it.readText() },
            context.resources.openRawResource(R.raw.user_rules).bufferedReader(Charsets.UTF_8).use { it.readText() }
        )
    }
}

class ConsentStore(context: Context) {
    val documents = UsageDocuments.load(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences("usage_agreement", Context.MODE_PRIVATE)
    private val acceptance = AgreementAcceptance(object : AgreementStorage {
        override fun acceptedRevision(): String? = preferences.getString("accepted_revision", null)

        override fun record(revision: String, acceptedAt: Long): Boolean {
            val oldRevision = preferences.getString("accepted_revision", null)
            val oldTime = preferences.getLong("accepted_at", 0L)
            val saved = preferences.edit().putString("accepted_revision", revision)
                .putLong("accepted_at", acceptedAt).commit()
            if (!saved) {
                // SharedPreferences can update its in-memory map even if the disk commit fails.
                preferences.edit().putString("accepted_revision", oldRevision).putLong("accepted_at", oldTime).commit()
            }
            return saved
        }
    }, documents.revision)

    fun isAccepted(): Boolean = acceptance.isAccepted()
    fun accept(confirmation: String): Boolean = acceptance.accept(confirmation)
}
