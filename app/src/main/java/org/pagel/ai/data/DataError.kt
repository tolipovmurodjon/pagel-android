package org.pagel.ai.data

/**
 * Every network or database failure becomes one of these at the repository (RULES.md, data layer). Nothing past
 * the repository sees an IOException or an HTTP code.
 */
sealed interface DataError {
    /** No connection, a timeout, a dropped stream: try again later. */
    data object Network : DataError

    /** The phone's token was revoked or never valid: pair again. */
    data object Unauthorized : DataError

    /** The server said why, in words Boss can read (a wrong or expired pairing code, a bad request). */
    data class Refused(val status: Int, val message: String) : DataError

    /** The server broke (5xx) or answered something unreadable. */
    data class Server(val status: Int) : DataError
}

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val error: DataError) : Outcome<Nothing>
}
