package dev.rain.laundry.core

import java.security.MessageDigest

object AgreementPolicy {
    const val CONFIRMATION = "我已阅读免责声明，保证遵守用户协议"

    fun validConfirmation(text: String): Boolean = text == CONFIRMATION

    fun revision(disclaimer: String, rules: String): String = MessageDigest.getInstance("SHA-256")
        .digest("jianxi-policy-v1\u0000$disclaimer\u0000$rules\u0000$CONFIRMATION".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

interface AgreementStorage {
    fun acceptedRevision(): String?
    fun record(revision: String, acceptedAt: Long): Boolean
}

/** A grant applies only to these exact documents and only after durable acknowledgement. */
class AgreementAcceptance(
    private val storage: AgreementStorage,
    private val revision: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun isAccepted(): Boolean = storage.acceptedRevision() == revision

    fun accept(confirmation: String): Boolean =
        AgreementPolicy.validConfirmation(confirmation) && storage.record(revision, now())
}
