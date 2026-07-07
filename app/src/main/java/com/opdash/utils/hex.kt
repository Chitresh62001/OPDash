package com.opdash.utils

object Hex {

    fun dump(bytes: ByteArray): String {

        return bytes.joinToString(" ") {
            "%02X".format(it)
        }

    }

}