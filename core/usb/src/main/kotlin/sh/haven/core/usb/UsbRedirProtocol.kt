package sh.haven.core.usb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * Wire protocol for SPICE `usbredir` (`usbredirproto.h` v0.8+,
 * https://www.spice-space.org/usbredir.html).
 *
 * Unlike USB/IP ([UsbIpProtocol]), which requires the `vhci_hcd` kernel module
 * and `sudo usbip attach` on the Linux host, `usbredir` is spoken directly in
 * userspace by QEMU (`-chardev socket,id=usbredir0,host=127.0.0.1,port=4000
 * -device usb-redir,chardev=usbredir0`), Virt-Manager/libvirt TCP `<redirdev>`,
 * and SPICE `SPICE_CHANNEL_USBREDIR` channels (#613). That makes phone-attached
 * USB devices (flash drives, FIDO/HID keys, CDC serial adapters) usable both in:
 *  - remote QEMU/KVM machines over an unprivileged SSH `-R` forward, and
 *  - local QEMU inside Haven's proot Linux container (where the Android kernel
 *    cannot load `vhci_hcd`).
 *
 * Wire framing:
 *  - All multi-byte fields are **little-endian** and packed.
 *  - The initial [TYPE_HELLO] packet on each side always uses a 12-byte header
 *    (`type: u32, length: u32, id: u32`) because capability flags have not yet
 *    been exchanged when `hello` is queued.
 *  - Once both sides have exchanged [TYPE_HELLO] and both advertise
 *    [CAP_64BITS_IDS] (bit 5), every subsequent packet uses a 16-byte header
 *    (`type: u32, length: u32, id: u64`); otherwise 12-byte headers continue.
 */
object UsbRedirProtocol {
    const val DEFAULT_PORT = 4000
    const val VERSION_STRING = "haven-usbredir 0.8.0"
    private const val VERSION_FIELD_LEN = 64
    private const val MAX_PACKET_LENGTH = 64 * 1024 * 1024

    // ---- Packet types (enum usb_redir_type) ------------------------------

    const val TYPE_HELLO = 0
    const val TYPE_DEVICE_CONNECT = 1
    const val TYPE_DEVICE_DISCONNECT = 2
    const val TYPE_RESET = 3
    const val TYPE_INTERFACE_INFO = 4
    const val TYPE_EP_INFO = 5
    const val TYPE_SET_CONFIGURATION = 6
    const val TYPE_GET_CONFIGURATION = 7
    const val TYPE_CONFIGURATION_STATUS = 8
    const val TYPE_SET_ALT_SETTING = 9
    const val TYPE_GET_ALT_SETTING = 10
    const val TYPE_ALT_SETTING_STATUS = 11
    const val TYPE_START_ISO_STREAM = 12
    const val TYPE_STOP_ISO_STREAM = 13
    const val TYPE_ISO_STREAM_STATUS = 14
    const val TYPE_START_INTERRUPT_RECEIVING = 15
    const val TYPE_STOP_INTERRUPT_RECEIVING = 16
    const val TYPE_INTERRUPT_RECEIVING_STATUS = 17
    const val TYPE_ALLOC_BULK_STREAMS = 18
    const val TYPE_FREE_BULK_STREAMS = 19
    const val TYPE_BULK_STREAMS_STATUS = 20
    const val TYPE_CANCEL_DATA_PACKET = 21
    const val TYPE_FILTER_REJECT = 22
    const val TYPE_FILTER_FILTER = 23
    const val TYPE_DEVICE_DISCONNECT_ACK = 24
    const val TYPE_START_BULK_RECEIVING = 25
    const val TYPE_STOP_BULK_RECEIVING = 26
    const val TYPE_BULK_RECEIVING_STATUS = 27

    // Data packets
    const val TYPE_CONTROL_PACKET = 100
    const val TYPE_BULK_PACKET = 101
    const val TYPE_ISO_PACKET = 102
    const val TYPE_INTERRUPT_PACKET = 103
    const val TYPE_BUFFERED_BULK_PACKET = 104

    // ---- Capabilities (bit indices in uint32_t caps[0], from usbredirproto.h) --

    /** usb_redir_cap_bulk_streams: usb_redir_ep_info_header includes uint32_t max_streams[32] (288 bytes vs 160; requires [CAP_EP_INFO_MAX_PACKET_SIZE]). */
    const val CAP_BULK_STREAMS = 0
    const val CAP_EP_INFO_MAX_STREAMS = CAP_BULK_STREAMS
    /** usb_redir_cap_connect_device_version: usb_redir_device_connect_header includes 16-bit device_version_bcd (10 bytes vs 8). */
    const val CAP_CONNECT_DEVICE_VERSION = 1
    /** usb_redir_cap_filter: filter rules exchange. */
    const val CAP_FILTER = 2
    /** usb_redir_cap_device_disconnect_ack: client acknowledges device_disconnect. */
    const val CAP_DEVICE_DISCONNECT_ACK = 3
    /** usb_redir_cap_ep_info_max_packet_size: usb_redir_ep_info_header includes uint16_t max_packet_size[32] (160 bytes vs 96). */
    const val CAP_EP_INFO_MAX_PACKET_SIZE = 4
    /** usb_redir_cap_64bits_ids: packet framing header carries a 64-bit `id` (16 bytes vs 12) after HELLO. */
    const val CAP_64BITS_IDS = 5
    /** usb_redir_cap_32bits_bulk_length: usb_redir_bulk_packet_header appends uint16_t length_high (10 bytes vs 8). */
    const val CAP_BULK_LENGTH_32BITS = 6
    /** usb_redir_cap_bulk_receiving: buffered bulk receiving. */
    const val CAP_BULK_RECEIVING = 7

    /** Capabilities advertised by Haven's usbredir server. */
    const val OUR_CAPS: Int =
        (1 shl CAP_BULK_STREAMS) or
            (1 shl CAP_CONNECT_DEVICE_VERSION) or
            (1 shl CAP_DEVICE_DISCONNECT_ACK) or
            (1 shl CAP_EP_INFO_MAX_PACKET_SIZE) or
            (1 shl CAP_64BITS_IDS) or
            (1 shl CAP_BULK_LENGTH_32BITS)

    fun hasCap(caps: Int, bit: Int): Boolean = (caps and (1 shl bit)) != 0

    // ---- Status codes (enum usb_redir_status) ----------------------------

    const val STATUS_SUCCESS = 0
    const val STATUS_CANCELLED = 1
    const val STATUS_INVAL = 2
    const val STATUS_IOERROR = 3
    const val STATUS_STALLED = 4
    const val STATUS_TIMEOUT = 5
    const val STATUS_BABBLE = 6

    // ---- Endpoint transfer types (enum usb_redir_transfer_type) ----------

    const val EP_TYPE_CONTROL = 0
    const val EP_TYPE_ISO = 1
    const val EP_TYPE_BULK = 2
    const val EP_TYPE_INTERRUPT = 3
    const val EP_TYPE_INVALID = 255

    // ---- Speeds (enum usb_redir_speed) -----------------------------------

    const val SPEED_LOW = 0
    const val SPEED_FULL = 1
    const val SPEED_HIGH = 2
    const val SPEED_SUPER = 3
    const val SPEED_UNKNOWN = 255

    // ---- Raw packet framing ----------------------------------------------

    data class RawPacket(
        val type: Int,
        val id: Long,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is RawPacket && type == other.type && id == other.id && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * (31 * type + id.hashCode()) + payload.contentHashCode()
    }

    /**
     * Read one framed usbredir packet from [input], or null at clean EOF on the
     * packet boundary. [use64BitIds] must be false for the initial [TYPE_HELLO]
     * packet and true thereafter iff both peers advertised [CAP_64BITS_IDS].
     */
    fun readPacket(input: DataInputStream, use64BitIds: Boolean): RawPacket? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("truncated usbredir packet type")
        val type = (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
        val length = readIntLE(input)
        if (length < 0 || length > MAX_PACKET_LENGTH) {
            throw IOException("invalid usbredir packet length $length for type $type")
        }
        val id = if (use64BitIds) {
            readLongLE(input)
        } else {
            readIntLE(input).toLong() and 0xFFFF_FFFFL
        }
        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return RawPacket(type, id, payload)
    }

    /** Encode one framed usbredir packet with a 12-byte or 16-byte header. */
    fun encodePacket(type: Int, id: Long, payload: ByteArray, use64BitIds: Boolean): ByteArray {
        val headerLen = if (use64BitIds) 16 else 12
        val out = ByteArray(headerLen + payload.size)
        putIntLE(out, 0, type)
        putIntLE(out, 4, payload.size)
        if (use64BitIds) {
            putLongLE(out, 8, id)
        } else {
            putIntLE(out, 8, id.toInt())
        }
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, headerLen, payload.size)
        }
        return out
    }

    // ---- Handshake (usb_redir_hello) -------------------------------------

    data class Hello(val version: String, val caps: Int)

    /**
     * Encode `usb_redir_hello` (always framed with a 12-byte header, `id = 0`,
     * because peer capabilities are not yet known when `hello` is queued).
     */
    fun encodeHello(version: String = VERSION_STRING, caps: Int = OUR_CAPS): ByteArray {
        val payload = ByteArray(VERSION_FIELD_LEN + 4)
        val verBytes = version.toByteArray(Charsets.US_ASCII)
        val copyLen = minOf(verBytes.size, VERSION_FIELD_LEN - 1)
        System.arraycopy(verBytes, 0, payload, 0, copyLen)
        putIntLE(payload, VERSION_FIELD_LEN, caps)
        return encodePacket(TYPE_HELLO, id = 0L, payload = payload, use64BitIds = false)
    }

    fun decodeHello(payload: ByteArray): Hello {
        require(payload.size >= VERSION_FIELD_LEN) {
            "short usb_redir_hello payload (${payload.size} bytes)"
        }
        val nul = (0 until VERSION_FIELD_LEN).firstOrNull { payload[it] == 0.toByte() } ?: VERSION_FIELD_LEN
        val version = String(payload, 0, nul, Charsets.US_ASCII)
        val caps = if (payload.size >= VERSION_FIELD_LEN + 4) getIntLE(payload, VERSION_FIELD_LEN) else 0
        return Hello(version, caps)
    }

    // ---- Device / interface / endpoint announcement ----------------------

    data class InterfaceEntry(
        val interfaceNumber: Int,
        val interfaceClass: Int,
        val interfaceSubClass: Int,
        val interfaceProtocol: Int,
    )

    data class EndpointEntry(
        /** 0..15 for OUT (ep & 0x0f), 16..31 for IN (16 | (ep & 0x0f)). */
        val index: Int,
        /** [EP_TYPE_CONTROL], [EP_TYPE_ISO], [EP_TYPE_BULK], [EP_TYPE_INTERRUPT], or [EP_TYPE_INVALID]. */
        val type: Int,
        val interval: Int,
        val interfaceNumber: Int,
        val maxPacketSize: Int,
        val maxStreams: Int = 0,
    )

    data class ParsedDeviceDescriptors(
        val speed: Int,
        val deviceClass: Int,
        val deviceSubClass: Int,
        val deviceProtocol: Int,
        val vendorId: Int,
        val productId: Int,
        val deviceVersionBcd: Int,
        val configurationValue: Int,
        val interfaces: List<InterfaceEntry>,
        /** 32 entries indexed by usbredir endpoint index (0..15 OUT, 16..31 IN). */
        val endpoints: List<EndpointEntry>,
    ) {
        /** True when [endpointAddress] belongs to a USB Mass Storage (0x08) interface. */
        fun isMassStorageEndpoint(endpointAddress: Int): Boolean {
            val idx = endpointIndex(endpointAddress)
            val ep = endpoints.getOrNull(idx) ?: return false
            if (ep.type == EP_TYPE_INVALID) return false
            val iface = interfaces.firstOrNull { it.interfaceNumber == ep.interfaceNumber } ?: return false
            return iface.interfaceClass == 0x08
        }
    }

    /** Map a USB endpoint address (`0x00..0x0f` OUT, `0x80..0x8f` IN) to usbredir's 0..31 table index. */
    fun endpointIndex(endpointAddress: Int): Int =
        (endpointAddress and 0x0F) or (if ((endpointAddress and 0x80) != 0) 0x10 else 0x00)

    /**
     * Parse raw USB descriptors (18-byte device descriptor followed by active
     * configuration descriptor, as returned by [UsbBroker.rawDescriptors]) into
     * the structures `usbredir` needs to announce the device.
     */
    fun parseDescriptors(raw: ByteArray): ParsedDeviceDescriptors {
        require(raw.size >= 18) { "short USB device descriptor (${raw.size} bytes)" }
        fun u8(buf: ByteArray, i: Int) = buf[i].toInt() and 0xFF
        fun u16le(buf: ByteArray, i: Int) = u8(buf, i) or (u8(buf, i + 1) shl 8)

        val ep0MaxPacket = u8(raw, 7)
        val rawConfig = if (raw.size > 18) raw.copyOfRange(18, raw.size) else ByteArray(0)
        // Strip CCID from composite FIDO+CCID security keys just like UsbIpServer so
        // host pcscd cannot starve FIDO, while leaving mass-storage / CDC / etc. intact.
        val config = UsbIpServer.filterToFidoInterface(rawConfig)
        val configValue = if (config.size >= 6 && u8(config, 1) == 0x02) u8(config, 5) else 1

        val usbIpSpeed = UsbIpServer.detectSpeed(config, ep0MaxPacket)
        val speed = when (usbIpSpeed) {
            1 -> SPEED_LOW
            2 -> SPEED_FULL
            3 -> SPEED_HIGH
            4 -> SPEED_SUPER
            else -> SPEED_HIGH
        }

        val epTypes = IntArray(32) { idx -> if ((idx and 0x0F) == 0) EP_TYPE_CONTROL else EP_TYPE_INVALID }
        val epIntervals = IntArray(32)
        val epInterfaces = IntArray(32)
        val epMaxPacketSizes = IntArray(32) { idx ->
            if ((idx and 0x0F) == 0) {
                if (ep0MaxPacket == 9) 512 else ep0MaxPacket
            } else {
                0
            }
        }

        val interfaces = ArrayList<InterfaceEntry>()
        var currentInterfaceNumber = 0
        var currentAltSetting = 0
        var i = 0
        while (i + 2 <= config.size) {
            val len = u8(config, i)
            if (len < 2 || i + len > config.size) break
            val descType = u8(config, i + 1)
            when (descType) {
                0x04 -> if (len >= 9) { // INTERFACE descriptor
                    currentInterfaceNumber = u8(config, i + 2)
                    currentAltSetting = u8(config, i + 3)
                    if (currentAltSetting == 0 && interfaces.size < 32 &&
                        interfaces.none { it.interfaceNumber == currentInterfaceNumber }
                    ) {
                        interfaces.add(
                            InterfaceEntry(
                                interfaceNumber = currentInterfaceNumber,
                                interfaceClass = u8(config, i + 5),
                                interfaceSubClass = u8(config, i + 6),
                                interfaceProtocol = u8(config, i + 7),
                            ),
                        )
                    }
                }
                0x05 -> if (len >= 7 && currentAltSetting == 0) { // ENDPOINT descriptor
                    val addr = u8(config, i + 2)
                    val attributes = u8(config, i + 3)
                    val rawMps = u16le(config, i + 4)
                    val interval = u8(config, i + 6)
                    val idx = endpointIndex(addr)
                    val xferType = attributes and 0x03
                    var mps = rawMps and 0x07FF
                    if (speed == SPEED_HIGH && xferType == EP_TYPE_ISO) {
                        val mult = (rawMps shr 11) and 0x03
                        if (mult == 1) mps *= 2 else if (mult == 2) mps *= 3
                    }
                    epTypes[idx] = xferType
                    epIntervals[idx] = interval
                    epInterfaces[idx] = currentInterfaceNumber
                    epMaxPacketSizes[idx] = mps
                }
            }
            i += len
        }

        val endpoints = (0 until 32).map { idx ->
            EndpointEntry(
                index = idx,
                type = epTypes[idx],
                interval = epIntervals[idx],
                interfaceNumber = epInterfaces[idx],
                maxPacketSize = epMaxPacketSizes[idx],
            )
        }

        return ParsedDeviceDescriptors(
            speed = speed,
            deviceClass = u8(raw, 4),
            deviceSubClass = u8(raw, 5),
            deviceProtocol = u8(raw, 6),
            vendorId = u16le(raw, 8),
            productId = u16le(raw, 10),
            deviceVersionBcd = u16le(raw, 12),
            configurationValue = configValue,
            interfaces = interfaces,
            endpoints = endpoints,
        )
    }

    /** Encode `usb_redir_interface_info` (type = 4, 132-byte payload). */
    fun encodeInterfaceInfo(desc: ParsedDeviceDescriptors, use64BitIds: Boolean): ByteArray {
        val payload = ByteArray(132)
        val count = minOf(desc.interfaces.size, 32)
        putIntLE(payload, 0, count)
        for (idx in 0 until count) {
            val iface = desc.interfaces[idx]
            payload[4 + idx] = iface.interfaceNumber.toByte()
            payload[36 + idx] = iface.interfaceClass.toByte()
            payload[68 + idx] = iface.interfaceSubClass.toByte()
            payload[100 + idx] = iface.interfaceProtocol.toByte()
        }
        return encodePacket(TYPE_INTERFACE_INFO, id = 0L, payload = payload, use64BitIds = use64BitIds)
    }

    /**
     * Encode `usb_redir_ep_info` (type = 5).
     * Payload size depends on negotiated capabilities:
     *  - base: 96 bytes (`type[32]`, `interval[32]`, `interface[32]`)
     *  - + [CAP_EP_INFO_MAX_PACKET_SIZE]: +64 bytes (`max_packet_size[32]`, 160 bytes)
     *  - + [CAP_EP_INFO_MAX_STREAMS]: +128 bytes (`max_streams[32]`, 288 bytes)
     */
    fun encodeEpInfo(desc: ParsedDeviceDescriptors, negotiatedCaps: Int, use64BitIds: Boolean): ByteArray {
        val includeMps = hasCap(negotiatedCaps, CAP_EP_INFO_MAX_PACKET_SIZE)
        val includeStreams = includeMps && hasCap(negotiatedCaps, CAP_EP_INFO_MAX_STREAMS)
        val size = when {
            includeStreams -> 288
            includeMps -> 160
            else -> 96
        }
        val payload = ByteArray(size)
        for (idx in 0 until 32) {
            val ep = desc.endpoints[idx]
            payload[idx] = ep.type.toByte()
            payload[32 + idx] = ep.interval.toByte()
            payload[64 + idx] = ep.interfaceNumber.toByte()
            if (includeMps) {
                putShortLE(payload, 96 + idx * 2, ep.maxPacketSize)
            }
            if (includeStreams) {
                putIntLE(payload, 160 + idx * 4, ep.maxStreams)
            }
        }
        return encodePacket(TYPE_EP_INFO, id = 0L, payload = payload, use64BitIds = use64BitIds)
    }

    /**
     * Encode `usb_redir_device_connect` (type = 1).
     * Payload is 10 bytes when [CAP_CONNECT_DEVICE_VERSION] is negotiated, else 8 bytes.
     */
    fun encodeDeviceConnect(desc: ParsedDeviceDescriptors, negotiatedCaps: Int, use64BitIds: Boolean): ByteArray {
        val includeVersion = hasCap(negotiatedCaps, CAP_CONNECT_DEVICE_VERSION)
        val payload = ByteArray(if (includeVersion) 10 else 8)
        payload[0] = desc.speed.toByte()
        payload[1] = desc.deviceClass.toByte()
        payload[2] = desc.deviceSubClass.toByte()
        payload[3] = desc.deviceProtocol.toByte()
        putShortLE(payload, 4, desc.vendorId)
        putShortLE(payload, 6, desc.productId)
        if (includeVersion) {
            putShortLE(payload, 8, desc.deviceVersionBcd)
        }
        return encodePacket(TYPE_DEVICE_CONNECT, id = 0L, payload = payload, use64BitIds = use64BitIds)
    }

    /** Encode `usb_redir_configuration_status` (type = 8, 2-byte payload). */
    fun encodeConfigurationStatus(id: Long, status: Int, configuration: Int, use64BitIds: Boolean): ByteArray =
        encodePacket(
            TYPE_CONFIGURATION_STATUS,
            id,
            byteArrayOf(status.toByte(), configuration.toByte()),
            use64BitIds,
        )

    /** Encode `usb_redir_alt_setting_status` (type = 11, 3-byte payload). */
    fun encodeAltSettingStatus(id: Long, status: Int, interfaceNumber: Int, alt: Int, use64BitIds: Boolean): ByteArray =
        encodePacket(
            TYPE_ALT_SETTING_STATUS,
            id,
            byteArrayOf(status.toByte(), interfaceNumber.toByte(), alt.toByte()),
            use64BitIds,
        )

    /** Encode `usb_redir_interrupt_receiving_status` (type = 17, 2-byte payload). */
    fun encodeInterruptReceivingStatus(id: Long, status: Int, endpoint: Int, use64BitIds: Boolean): ByteArray =
        encodePacket(
            TYPE_INTERRUPT_RECEIVING_STATUS,
            id,
            byteArrayOf(status.toByte(), endpoint.toByte()),
            use64BitIds,
        )

    /** Encode `usb_redir_iso_stream_status` (type = 14, 2-byte payload). */
    fun encodeIsoStreamStatus(id: Long, status: Int, endpoint: Int, use64BitIds: Boolean): ByteArray =
        encodePacket(
            TYPE_ISO_STREAM_STATUS,
            id,
            byteArrayOf(status.toByte(), endpoint.toByte()),
            use64BitIds,
        )

    /** Encode `usb_redir_bulk_streams_status` (type = 20, 9-byte payload). */
    fun encodeBulkStreamsStatus(id: Long, endpoints: Int, noStreams: Int, status: Int, use64BitIds: Boolean): ByteArray {
        val payload = ByteArray(9)
        putIntLE(payload, 0, endpoints)
        putIntLE(payload, 4, noStreams)
        payload[8] = status.toByte()
        return encodePacket(TYPE_BULK_STREAMS_STATUS, id, payload, use64BitIds)
    }

    /** Encode `usb_redir_bulk_receiving_status` (type = 27, 6-byte payload). */
    fun encodeBulkReceivingStatus(id: Long, streamId: Int, endpoint: Int, status: Int, use64BitIds: Boolean): ByteArray {
        val payload = ByteArray(6)
        putIntLE(payload, 0, streamId)
        payload[4] = endpoint.toByte()
        payload[5] = status.toByte()
        return encodePacket(TYPE_BULK_RECEIVING_STATUS, id, payload, use64BitIds)
    }

    // ---- Data packet codecs (control, bulk, interrupt) -------------------

    /**
     * `usb_redir_control_packet_header` (10 bytes):
     *  - offset 0: `endpoint` (uint8)
     *  - offset 1: `request` / bRequest (uint8)
     *  - offset 2: `requesttype` / bmRequestType (uint8)
     *  - offset 3: `status` (uint8)
     *  - offset 4: `value` / wValue (uint16 LE)
     *  - offset 6: `index` / wIndex (uint16 LE)
     *  - offset 8: `length` / wLength (uint16 LE)
     * Followed by `data` (present on Guest->Host OUT or Host->Guest IN).
     */
    data class ControlPacket(
        val id: Long,
        val endpoint: Int,
        val request: Int,
        val requestType: Int,
        val status: Int,
        val value: Int,
        val index: Int,
        val length: Int,
        val data: ByteArray,
    ) {
        val isIn: Boolean get() = (endpoint and 0x80) != 0
    }

    fun decodeControlPacket(id: Long, payload: ByteArray): ControlPacket {
        require(payload.size >= 10) { "short usb_redir_control_packet (${payload.size} bytes)" }
        val endpoint = payload[0].toInt() and 0xFF
        val request = payload[1].toInt() and 0xFF
        val requestType = payload[2].toInt() and 0xFF
        val status = payload[3].toInt() and 0xFF
        val value = getUShortLE(payload, 4)
        val index = getUShortLE(payload, 6)
        val length = getUShortLE(payload, 8)
        val data = if (payload.size > 10) payload.copyOfRange(10, payload.size) else ByteArray(0)
        return ControlPacket(id, endpoint, request, requestType, status, value, index, length, data)
    }

    /**
     * Encode a server->client `usb_redir_control_packet` reply.
     * Per `usbredirparser_verify_type_header`:
     *  - On IN (`endpoint & 0x80 != 0`): `header.length` must equal `inData.size`,
     *    and `inData` is appended after the 10-byte type header.
     *  - On OUT (`endpoint & 0x80 == 0`): no trailing data bytes are appended, and
     *    `header.length` records [actualLength] (0 on error).
     */
    fun encodeControlReply(
        req: ControlPacket,
        status: Int,
        actualLength: Int,
        inData: ByteArray,
        use64BitIds: Boolean,
    ): ByteArray {
        val trailing = if (req.isIn && status == STATUS_SUCCESS) inData else ByteArray(0)
        val reportedLen = if (req.isIn) trailing.size else if (status == STATUS_SUCCESS) actualLength else 0
        val payload = ByteArray(10 + trailing.size)
        payload[0] = req.endpoint.toByte()
        payload[1] = req.request.toByte()
        payload[2] = req.requestType.toByte()
        payload[3] = status.toByte()
        putShortLE(payload, 4, req.value)
        putShortLE(payload, 6, req.index)
        putShortLE(payload, 8, reportedLen)
        if (trailing.isNotEmpty()) {
            System.arraycopy(trailing, 0, payload, 10, trailing.size)
        }
        return encodePacket(TYPE_CONTROL_PACKET, req.id, payload, use64BitIds)
    }

    /**
     * `usb_redir_bulk_packet_header` (10 bytes with [CAP_BULK_LENGTH_32BITS], else 8 bytes):
     *  - offset 0: `endpoint` (uint8)
     *  - offset 1: `status` (uint8)
     *  - offset 2: `length` low 16 bits (uint16 LE)
     *  - offset 4: `stream_id` (uint32 LE)
     *  - offset 8: `length_high` high 16 bits (uint16 LE, when cap 6 is active)
     */
    data class BulkPacket(
        val id: Long,
        val endpoint: Int,
        val status: Int,
        val length: Int,
        val streamId: Int,
        val data: ByteArray,
    ) {
        val isIn: Boolean get() = (endpoint and 0x80) != 0
    }

    fun decodeBulkPacket(id: Long, payload: ByteArray, negotiatedCaps: Int): BulkPacket {
        val has32BitLen = hasCap(negotiatedCaps, CAP_BULK_LENGTH_32BITS)
        val hdrLen = if (has32BitLen) 10 else 8
        require(payload.size >= hdrLen) { "short usb_redir_bulk_packet (${payload.size} < $hdrLen bytes)" }
        val endpoint = payload[0].toInt() and 0xFF
        val status = payload[1].toInt() and 0xFF
        val lenLow = getUShortLE(payload, 2)
        val streamId = getIntLE(payload, 4)
        val lenHigh = if (has32BitLen) getUShortLE(payload, 8) else 0
        val length = (lenHigh shl 16) or lenLow
        val data = if (payload.size > hdrLen) payload.copyOfRange(hdrLen, payload.size) else ByteArray(0)
        return BulkPacket(id, endpoint, status, length, streamId, data)
    }

    fun encodeBulkReply(
        req: BulkPacket,
        status: Int,
        actualLength: Int,
        inData: ByteArray,
        negotiatedCaps: Int,
        use64BitIds: Boolean,
    ): ByteArray {
        val has32BitLen = hasCap(negotiatedCaps, CAP_BULK_LENGTH_32BITS)
        val hdrLen = if (has32BitLen) 10 else 8
        val trailing = if (req.isIn && status == STATUS_SUCCESS) inData else ByteArray(0)
        val reportedLen = if (req.isIn) trailing.size else if (status == STATUS_SUCCESS) actualLength else 0
        val payload = ByteArray(hdrLen + trailing.size)
        payload[0] = req.endpoint.toByte()
        payload[1] = status.toByte()
        putShortLE(payload, 2, reportedLen and 0xFFFF)
        putIntLE(payload, 4, req.streamId)
        if (has32BitLen) {
            putShortLE(payload, 8, (reportedLen ushr 16) and 0xFFFF)
        }
        if (trailing.isNotEmpty()) {
            System.arraycopy(trailing, 0, payload, hdrLen, trailing.size)
        }
        return encodePacket(TYPE_BULK_PACKET, req.id, payload, use64BitIds)
    }

    /**
     * `usb_redir_interrupt_packet_header` (4 bytes):
     *  - offset 0: `endpoint` (uint8)
     *  - offset 1: `status` (uint8)
     *  - offset 2: `length` (uint16 LE)
     */
    data class InterruptPacket(
        val id: Long,
        val endpoint: Int,
        val status: Int,
        val length: Int,
        val data: ByteArray,
    ) {
        val isIn: Boolean get() = (endpoint and 0x80) != 0
    }

    fun decodeInterruptPacket(id: Long, payload: ByteArray): InterruptPacket {
        require(payload.size >= 4) { "short usb_redir_interrupt_packet (${payload.size} bytes)" }
        val endpoint = payload[0].toInt() and 0xFF
        val status = payload[1].toInt() and 0xFF
        val length = getUShortLE(payload, 2)
        val data = if (payload.size > 4) payload.copyOfRange(4, payload.size) else ByteArray(0)
        return InterruptPacket(id, endpoint, status, length, data)
    }

    fun encodeInterruptPacket(
        id: Long,
        endpoint: Int,
        status: Int,
        actualLength: Int,
        inData: ByteArray,
        use64BitIds: Boolean,
    ): ByteArray {
        val isIn = (endpoint and 0x80) != 0
        val trailing = if (isIn && status == STATUS_SUCCESS) inData else ByteArray(0)
        val reportedLen = if (isIn) trailing.size else if (status == STATUS_SUCCESS) actualLength else 0
        val payload = ByteArray(4 + trailing.size)
        payload[0] = endpoint.toByte()
        payload[1] = status.toByte()
        putShortLE(payload, 2, reportedLen and 0xFFFF)
        if (trailing.isNotEmpty()) {
            System.arraycopy(trailing, 0, payload, 4, trailing.size)
        }
        return encodePacket(TYPE_INTERRUPT_PACKET, id, payload, use64BitIds)
    }

    // ---- Little-endian primitive helpers ---------------------------------

    private fun readIntLE(input: DataInputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if ((b0 or b1 or b2 or b3) < 0) throw EOFException("unexpected EOF reading uint32_le")
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun readLongLE(input: DataInputStream): Long {
        val lo = readIntLE(input).toLong() and 0xFFFF_FFFFL
        val hi = readIntLE(input).toLong() and 0xFFFF_FFFFL
        return lo or (hi shl 32)
    }

    internal fun getUShortLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    internal fun getIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    internal fun putShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    internal fun putIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    internal fun putLongLE(buf: ByteArray, offset: Int, value: Long) {
        putIntLE(buf, offset, value.toInt())
        putIntLE(buf, offset + 4, (value ushr 32).toInt())
    }
}
