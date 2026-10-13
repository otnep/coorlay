package com.coorlay.transport

/** CDC NCM controls are issued after the data alternate setting has been selected. */
internal object LegacyNcmControlSetup {
    fun configure(controlId: Int, capabilities: Int,
        transfer: (Int, Int, Int, Int, ByteArray, Int) -> Int, log: (String) -> Unit) {
        val parameters = ByteArray(28)
        val count = transfer(0xa1, 0x80, 0, controlId, parameters, parameters.size)
        fun unsigned(offset: Int, length: Int): Long = (0 until length).fold(0L) { result, index ->
            result or ((parameters[offset + index].toLong() and 255) shl (8 * index))
        }
        if (count == parameters.size && unsigned(0, 2) >= 28) {
            log("NCM parameters interface=$controlId capabilities=0x${capabilities.toString(16)} " +
                "formats=${unsigned(2, 2)} inputMax=${unsigned(4, 4)} outputMax=${unsigned(16, 4)}")
        } else log("NCM GET_NTB_PARAMETERS interface=$controlId result=$count")
        val inputSize = ByteArray(4)
        val inputCount = transfer(0xa1, 0x85, 0, controlId, inputSize, inputSize.size)
        val inputValue = if (inputCount == 4) (0..3).fold(0L) { result, index ->
            result or ((inputSize[index].toLong() and 255) shl (8 * index))
        } else null
        log("NCM GET_NTB_INPUT_SIZE interface=$controlId result=$inputCount bytes=${inputValue ?: "unavailable"}")
        if (capabilities and 1 != 0) {
            // Directed + broadcast + all multicast, including IPv6 neighbour discovery.
            val result = transfer(0x21, 0x43, 0x0e, controlId, ByteArray(0), 0)
            log("NCM SET_ETHERNET_PACKET_FILTER interface=$controlId value=0x000e result=$result")
        } else log("NCM Ethernet filter not advertised by interface=$controlId; optional request skipped")
    }
}
