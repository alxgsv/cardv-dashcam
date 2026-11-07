package com.cardv.shared.protocol

/**
 * Result code from the first line of a `Config.cgi` response.
 *
 * The camera always answers with HTTP 200; the real outcome is this number. Keep unknown codes
 * intact instead of mapping them to a sentinel: newer firmware may add codes and the error text
 * still helps when reporting a problem.
 */
data class ResultCode(val code: Int) {
    val isSuccess: Boolean get() = code == OK.code

    /** The camera refused the command because of its current state (typically "while recording"). */
    val isStateRefusal: Boolean get() = code == REFUSED.code

    override fun toString(): String = "${nameOf(code)} ($code)"

    companion object {
        val OK = ResultCode(0)
        val ILLEGAL_PARAM = ResultCode(701)
        val UNKNOWN_ACTION = ResultCode(702)
        val UNKNOWN_PROPERTY = ResultCode(703)
        val UNKNOWN_PARAM = ResultCode(704)
        val MISSING_PARAM = ResultCode(705)
        val REFUSED = ResultCode(709)
        val AUTH_REQUIRED = ResultCode(710)
        val AUTH_FAILED = ResultCode(711)
        val AUTH_LOCKED = ResultCode(712)
        val DEVICE_ERROR = ResultCode(721)

        fun from(code: Int): ResultCode = when (code) {
            OK.code -> OK
            ILLEGAL_PARAM.code -> ILLEGAL_PARAM
            UNKNOWN_ACTION.code -> UNKNOWN_ACTION
            UNKNOWN_PROPERTY.code -> UNKNOWN_PROPERTY
            UNKNOWN_PARAM.code -> UNKNOWN_PARAM
            MISSING_PARAM.code -> MISSING_PARAM
            REFUSED.code -> REFUSED
            AUTH_REQUIRED.code -> AUTH_REQUIRED
            AUTH_FAILED.code -> AUTH_FAILED
            AUTH_LOCKED.code -> AUTH_LOCKED
            DEVICE_ERROR.code -> DEVICE_ERROR
            else -> ResultCode(code)
        }

        fun nameOf(code: Int): String = when (code) {
            OK.code -> "OK"
            ILLEGAL_PARAM.code -> "ILLEGAL_PARAM"
            UNKNOWN_ACTION.code -> "UNKNOWN_ACTION"
            UNKNOWN_PROPERTY.code -> "UNKNOWN_PROPERTY"
            UNKNOWN_PARAM.code -> "UNKNOWN_PARAM"
            MISSING_PARAM.code -> "MISSING_PARAM"
            REFUSED.code -> "REFUSED"
            AUTH_REQUIRED.code -> "AUTH_REQUIRED"
            AUTH_FAILED.code -> "AUTH_FAILED"
            AUTH_LOCKED.code -> "AUTH_LOCKED"
            DEVICE_ERROR.code -> "DEVICE_ERROR"
            else -> "UNKNOWN"
        }
    }
}
