package org.mindenit.glidephone

/**
 * Tracks an outgoing v8 pairing request. A remote acceptance alone never
 * authorizes access: the user must also confirm the displayed code locally.
 * Times for expiry use a monotonic clock supplied by the caller.
 */
internal class PairingState(
    val timestampSeconds: Long,
    startedAtMillis: Long,
    localKey: ByteArray,
    remoteKey: ByteArray
) {
    enum class Status { WAITING, CONFIRMED, REJECTED, EXPIRED }

    private val localKeyBytes = localKey.copyOf()
    private val remoteKeyBytes = remoteKey.copyOf()
    val verificationCode = PairingVerification.code(localKeyBytes, remoteKeyBytes, timestampSeconds)
    private val deadline = startedAtMillis + TIMEOUT_MILLIS
    private var localConfirmed = false
    private var remoteConfirmed = false
    var status: Status = Status.WAITING
        private set

    fun matchesKeys(localKey: ByteArray, remoteKey: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(localKeyBytes, localKey) &&
            java.security.MessageDigest.isEqual(remoteKeyBytes, remoteKey)

    fun confirmLocally(nowMillis: Long): Status {
        if (expire(nowMillis) != Status.WAITING) return status
        localConfirmed = true
        return finishIfConfirmed()
    }

    fun onPeerResponse(accepted: Boolean, nowMillis: Long): Status {
        if (expire(nowMillis) != Status.WAITING) return status
        if (!accepted) {
            status = Status.REJECTED
        } else {
            remoteConfirmed = true
            finishIfConfirmed()
        }
        return status
    }

    fun reject(): Status {
        if (status == Status.WAITING) status = Status.REJECTED
        return status
    }

    fun expire(nowMillis: Long): Status {
        if (status == Status.WAITING && nowMillis >= deadline) status = Status.EXPIRED
        return status
    }

    private fun finishIfConfirmed(): Status {
        if (localConfirmed && remoteConfirmed) status = Status.CONFIRMED
        return status
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
