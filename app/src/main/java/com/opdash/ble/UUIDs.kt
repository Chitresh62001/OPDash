package com.opdash.ble

import java.util.UUID

object UUIDs {

    val SERVICE: UUID =
        UUID.fromString("74686562-6c75-6172-6d6f-722e636f6d00")


    val TX: UUID =
        UUID.fromString("74686562-6c75-6172-6d6f-722e636f6d01")

    val RX: UUID =
        UUID.fromString("74686562-6c75-6172-6d6f-722e636f6d02")
}