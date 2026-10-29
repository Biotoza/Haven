package sh.haven.core.usb

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class UsbRedirServerTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /**
     * Realistic 18-byte device descriptor + 32-byte configuration descriptor for a
     * SuperSpeed USB Mass Storage flash drive (VID 0x0781 SanDisk, PID 0x5581,
     * interface 0 class 0x08/0x06/0x50 BOT, EP 0x81 IN bulk 1024, EP 0x02 OUT bulk 1024).
     */
    private fun flashDriveDescriptors(): ByteArray {
        val devDesc = bytes(
            18, 0x01,             // bLength, bDescriptorType (DEVICE)
            0x00, 0x03,           // bcdUSB = 3.00
            0x00, 0x00, 0x00,     // bDeviceClass/SubClass/Protocol (defined at interface level)
            9,                    // bMaxPacketSize0 = 9 (2^9 = 512 @ SuperSpeed)
            0x81, 0x07,           // idVendor = 0x0781
            0x81, 0x55,           // idProduct = 0x5581
            0x00, 0x01,           // bcdDevice = 1.00
            1, 2, 3,              // iManufacturer, iProduct, iSerialNumber
            1,                    // bNumConfigurations
        )
        val iface0 = bytes(
            9, 0x04,              // INTERFACE
            0, 0,                 // bInterfaceNumber = 0, bAlternateSetting = 0
            2,                    // bNumEndpoints = 2
            0x08, 0x06, 0x50,     // Mass Storage / SCSI transparent / Bulk-Only (BOT)
            0,
        )
        val epIn = bytes(7, 0x05, 0x81, 0x02, 0x00, 0x04, 0)  // EP 0x81 IN bulk, mps = 1024
        val epOut = bytes(7, 0x05, 0x02, 0x02, 0x00, 0x04, 0) // EP 0x02 OUT bulk, mps = 1024
        val totalConfigLen = 9 + iface0.size + epIn.size + epOut.size
        val cfgHeader = bytes(
            9, 0x02,
            totalConfigLen and 0xFF, (totalConfigLen ushr 8) and 0xFF,
            1, 1, 0, 0x80, 100,
        )
        return devDesc + cfgHeader + iface0 + epIn + epOut
    }

    @Test
    fun `parseDescriptors extracts SuperSpeed mass-storage interface and bulk endpoints`() {
        val parsed = UsbRedirProtocol.parseDescriptors(flashDriveDescriptors())
        assertEquals(UsbRedirProtocol.SPEED_SUPER, parsed.speed)
        assertEquals(0x0781, parsed.vendorId)
        assertEquals(0x5581, parsed.productId)
        assertEquals(0x0100, parsed.deviceVersionBcd)
        assertEquals(1, parsed.configurationValue)
        assertEquals(1, parsed.interfaces.size)
        assertEquals(0x08, parsed.interfaces[0].interfaceClass)
        assertEquals(0x06, parsed.interfaces[0].interfaceSubClass)
        assertEquals(0x50, parsed.interfaces[0].interfaceProtocol)

        // EP0 OUT (index 0) and EP0 IN (index 16) are control endpoints with 512 mps.
        assertEquals(UsbRedirProtocol.EP_TYPE_CONTROL, parsed.endpoints[0].type)
        assertEquals(512, parsed.endpoints[0].maxPacketSize)
        assertEquals(UsbRedirProtocol.EP_TYPE_CONTROL, parsed.endpoints[16].type)
        assertEquals(512, parsed.endpoints[16].maxPacketSize)

        // EP 0x02 OUT -> index 2; EP 0x81 IN -> index 17 (16 | 1).
        assertEquals(UsbRedirProtocol.EP_TYPE_BULK, parsed.endpoints[2].type)
        assertEquals(1024, parsed.endpoints[2].maxPacketSize)
        assertEquals(UsbRedirProtocol.EP_TYPE_BULK, parsed.endpoints[17].type)
        assertEquals(1024, parsed.endpoints[17].maxPacketSize)
        assertTrue(parsed.isMassStorageEndpoint(0x02))
        assertTrue(parsed.isMassStorageEndpoint(0x81))
        assertFalse(parsed.isMassStorageEndpoint(0x83))
    }

    @Test
    fun `hello framing uses 12-byte header and negotiates 64-bit packet IDs and 32-bit bulk length`() {
        val encoded = UsbRedirProtocol.encodeHello("qemu usb-redir guest 0.8.0", UsbRedirProtocol.OUR_CAPS)
        assertEquals(12 + 68, encoded.size)
        val pkt = UsbRedirProtocol.readPacket(DataInputStream(ByteArrayInputStream(encoded)), use64BitIds = false)
        assertNotNull(pkt)
        assertEquals(UsbRedirProtocol.TYPE_HELLO, pkt!!.type)
        assertEquals(0L, pkt.id)
        val hello = UsbRedirProtocol.decodeHello(pkt.payload)
        assertEquals("qemu usb-redir guest 0.8.0", hello.version)
        assertEquals(UsbRedirProtocol.OUR_CAPS, hello.caps)
    }

    @Test
    fun `32-bit bulk packet encodes and decodes lengths larger than 64 KiB`() {
        val payload = ByteArray(70_000) { (it and 0xFF).toByte() }
        val req = UsbRedirProtocol.BulkPacket(
            id = 0x1_0000_0042L,
            endpoint = 0x81,
            status = UsbRedirProtocol.STATUS_SUCCESS,
            length = payload.size,
            streamId = 0,
            data = ByteArray(0),
        )
        val wire = UsbRedirProtocol.encodeBulkReply(
            req = req,
            status = UsbRedirProtocol.STATUS_SUCCESS,
            actualLength = payload.size,
            inData = payload,
            negotiatedCaps = UsbRedirProtocol.OUR_CAPS,
            use64BitIds = true,
        )
        val raw = UsbRedirProtocol.readPacket(DataInputStream(ByteArrayInputStream(wire)), use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_BULK_PACKET, raw.type)
        assertEquals(0x1_0000_0042L, raw.id)
        val decoded = UsbRedirProtocol.decodeBulkPacket(raw.id, raw.payload, UsbRedirProtocol.OUR_CAPS)
        assertEquals(0x81, decoded.endpoint)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, decoded.status)
        assertEquals(70_000, decoded.length)
        assertArrayEquals(payload, decoded.data)
    }

    @Test
    fun `serveStream completes handshake, announces device, and services BOT flash-drive transfers`() {
        val rawDesc = flashDriveDescriptors()
        val inquiryData = ByteArray(36) { (0x10 + it).toByte() }
        val cswData = byteArrayOf(
            'U'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte(),
            1, 0, 0, 0, // dCSWTag
            0, 0, 0, 0, // dCSWDataResidue
            0,          // bCSWStatus = 0 (Command Passed)
        )
        val bulkInQueue = ArrayBlockingQueue<ByteArray>(4).apply {
            add(inquiryData)
            add(cswData)
        }
        val recordedCbw = ArrayList<ByteArray>()

        val backend = object : UsbIpBackend {
            @Volatile var alive = true
            override fun isAlive(): Boolean = alive
            override fun control(
                requestType: Int,
                request: Int,
                value: Int,
                index: Int,
                out: ByteArray,
                length: Int,
                timeoutMs: Int,
            ): ByteArray = rawDesc.copyOfRange(0, minOf(18, length))

            override fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray {
                return if ((endpointAddress and 0x80) != 0) {
                    bulkInQueue.poll(1, TimeUnit.SECONDS) ?: throw IOException("no IN data")
                } else {
                    recordedCbw.add(out)
                    ByteArray(0)
                }
            }
        }

        val clientToServerOut = PipedOutputStream()
        val clientToServerIn = PipedInputStream(clientToServerOut, 65536)
        val serverToClientOut = PipedOutputStream()
        val serverToClientIn = PipedInputStream(serverToClientOut, 65536)

        val serverThread = thread(name = "test-usbredir-session") {
            UsbRedirServer.serveStream(
                input = clientToServerIn,
                output = serverToClientOut,
                rawDescriptors = rawDesc,
                backend = backend,
            )
        }

        val din = DataInputStream(serverToClientIn)
        // 1. Send guest hello (12-byte header) and read server hello (12-byte header).
        clientToServerOut.write(UsbRedirProtocol.encodeHello("qemu-test", UsbRedirProtocol.OUR_CAPS))
        clientToServerOut.flush()

        val srvHelloPkt = UsbRedirProtocol.readPacket(din, use64BitIds = false)!!
        assertEquals(UsbRedirProtocol.TYPE_HELLO, srvHelloPkt.type)

        // 2. Read interface_info (4), ep_info (5), and device_connect (1) with 64-bit IDs.
        val ifacePkt = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_INTERFACE_INFO, ifacePkt.type)
        assertEquals(132, ifacePkt.payload.size)
        assertEquals(1, UsbRedirProtocol.getIntLE(ifacePkt.payload, 0))
        assertEquals(0x08, ifacePkt.payload[36].toInt() and 0xFF) // interface_class[0] == Mass Storage

        val epPkt = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_EP_INFO, epPkt.type)
        assertEquals(288, epPkt.payload.size)
        assertEquals(UsbRedirProtocol.EP_TYPE_BULK, epPkt.payload[2].toInt() and 0xFF)  // EP 0x02 OUT
        assertEquals(UsbRedirProtocol.EP_TYPE_BULK, epPkt.payload[17].toInt() and 0xFF) // EP 0x81 IN

        val connectPkt = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_DEVICE_CONNECT, connectPkt.type)
        assertEquals(10, connectPkt.payload.size)
        assertEquals(UsbRedirProtocol.SPEED_SUPER, connectPkt.payload[0].toInt() and 0xFF)
        assertEquals(0x0781, UsbRedirProtocol.getUShortLE(connectPkt.payload, 4))
        assertEquals(0x5581, UsbRedirProtocol.getUShortLE(connectPkt.payload, 6))

        // 3. Send SET_CONFIGURATION(1) -> expect CONFIGURATION_STATUS(status=0, config=1).
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(
                UsbRedirProtocol.TYPE_SET_CONFIGURATION,
                id = 10L,
                payload = byteArrayOf(1),
                use64BitIds = true,
            ),
        )
        clientToServerOut.flush()
        val cfgReply = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_CONFIGURATION_STATUS, cfgReply.type)
        assertEquals(10L, cfgReply.id)
        assertArrayEquals(byteArrayOf(0, 1), cfgReply.payload)

        // 4. Send GET_DESCRIPTOR(DEVICE) control packet (id = 11).
        val ctrlPayload = ByteArray(10).also {
            it[0] = 0x80.toByte() // endpoint IN
            it[1] = 0x06          // bRequest = GET_DESCRIPTOR
            it[2] = 0x80.toByte() // bmRequestType
            it[3] = 0
            UsbRedirProtocol.putShortLE(it, 4, 0x0100) // wValue = DEVICE
            UsbRedirProtocol.putShortLE(it, 6, 0)
            UsbRedirProtocol.putShortLE(it, 8, 18)
        }
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(
                UsbRedirProtocol.TYPE_CONTROL_PACKET,
                id = 11L,
                payload = ctrlPayload,
                use64BitIds = true,
            ),
        )
        clientToServerOut.flush()
        val ctrlReplyRaw = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_CONTROL_PACKET, ctrlReplyRaw.type)
        assertEquals(11L, ctrlReplyRaw.id)
        val ctrlReply = UsbRedirProtocol.decodeControlPacket(ctrlReplyRaw.id, ctrlReplyRaw.payload)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, ctrlReply.status)
        assertEquals(18, ctrlReply.length)
        assertArrayEquals(rawDesc.copyOfRange(0, 18), ctrlReply.data)

        // 5. BOT sequence: CBW OUT (31 bytes on 0x02), INQUIRY IN (36 bytes on 0x81), CSW IN (13 bytes on 0x81).
        val cbw = ByteArray(31) { it.toByte() }
        val cbwHdr = ByteArray(10 + cbw.size).also {
            it[0] = 0x02 // EP 2 OUT
            it[1] = 0
            UsbRedirProtocol.putShortLE(it, 2, cbw.size)
            System.arraycopy(cbw, 0, it, 10, cbw.size)
        }
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(UsbRedirProtocol.TYPE_BULK_PACKET, id = 20L, payload = cbwHdr, use64BitIds = true),
        )
        clientToServerOut.flush()
        val cbwReplyRaw = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        val cbwReply = UsbRedirProtocol.decodeBulkPacket(cbwReplyRaw.id, cbwReplyRaw.payload, UsbRedirProtocol.OUR_CAPS)
        assertEquals(20L, cbwReply.id)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, cbwReply.status)
        assertEquals(31, cbwReply.length)
        assertEquals(0, cbwReply.data.size) // OUT reply carries no trailing payload
        assertArrayEquals(cbw, recordedCbw.single())

        val inqHdr = ByteArray(10).also {
            it[0] = 0x81.toByte() // EP 1 IN
            UsbRedirProtocol.putShortLE(it, 2, 36)
        }
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(UsbRedirProtocol.TYPE_BULK_PACKET, id = 21L, payload = inqHdr, use64BitIds = true),
        )
        clientToServerOut.flush()
        val inqReplyRaw = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        val inqReply = UsbRedirProtocol.decodeBulkPacket(inqReplyRaw.id, inqReplyRaw.payload, UsbRedirProtocol.OUR_CAPS)
        assertEquals(21L, inqReply.id)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, inqReply.status)
        assertArrayEquals(inquiryData, inqReply.data)

        val cswHdr = ByteArray(10).also {
            it[0] = 0x81.toByte()
            UsbRedirProtocol.putShortLE(it, 2, 13)
        }
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(UsbRedirProtocol.TYPE_BULK_PACKET, id = 22L, payload = cswHdr, use64BitIds = true),
        )
        clientToServerOut.flush()
        val cswReplyRaw = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        val cswReply = UsbRedirProtocol.decodeBulkPacket(cswReplyRaw.id, cswReplyRaw.payload, UsbRedirProtocol.OUR_CAPS)
        assertEquals(22L, cswReply.id)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, cswReply.status)
        assertArrayEquals(cswData, cswReply.data)

        clientToServerOut.close()
        serverThread.join(2_000)
        assertFalse(serverThread.isAlive)
    }

    @Test
    fun `pollBulkIn drops an already-cancelled packet without touching the device`() {
        var touched = false
        val backend = object : UsbIpBackend {
            override fun isAlive(): Boolean = true
            override fun control(requestType: Int, request: Int, value: Int, index: Int, out: ByteArray, length: Int, timeoutMs: Int) = ByteArray(0)
            override fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray {
                touched = true
                return byteArrayOf(1, 2, 3)
            }
        }
        val pkt = UsbRedirProtocol.BulkPacket(id = 99L, endpoint = 0x82, status = 0, length = 64, streamId = 0, data = ByteArray(0))
        val reply = UsbRedirServer.pollBulkIn(pkt, backend, setOf(99L), 10, UsbRedirProtocol.OUR_CAPS, use64BitIds = true)
        assertNull(reply)
        assertFalse(touched)
    }

    @Test
    fun `UsbRedirServer starts on loopback and stops cleanly`() {
        val broker = mockk<UsbBroker>(relaxed = true)
        every { broker.isOpen("/dev/bus/usb/001/002") } returns true
        val server = UsbRedirServer(broker)
        val port = server.start("/dev/bus/usb/001/002", port = 0, bindAddress = "127.0.0.1")
        try {
            assertTrue(port > 0)
            assertTrue(server.isRunning)
            assertEquals(port, server.boundPort)
            assertEquals("/dev/bus/usb/001/002", server.exportedDevice)
        } finally {
            server.stop()
        }
        assertFalse(server.isRunning)
        assertEquals(0, server.boundPort)
        assertNull(server.exportedDevice)
    }

    /**
     * Build a High-Speed UVC webcam descriptor set:
     *  - Interface 0, alt 0: VideoControl (`class=0x0E, subclass=0x01`), 0 endpoints
     *  - Interface 1, alt 0: VideoStreaming (`class=0x0E, subclass=0x02`), 0 endpoints (idle)
     *  - Interface 1, alt 1: VideoStreaming (`class=0x0E, subclass=0x02`), 1 isochronous IN
     *    endpoint `0x81` with high-bandwidth `wMaxPacketSize = 0x1400` (`1024 * 3 = 3072` bytes/uframe)
     */
    private fun sampleUvcWebcamDescriptors(): ByteArray {
        val dev = byteArrayOf(
            18, 0x01, 0x00, 0x02,
            0xEF.toByte(), 0x02, 0x01, 64,
            0x6D, 0x04, 0x25, 0x08, // 046d:0825
            0x10, 0x00, 1, 2, 3, 1,
        )
        val totalConfigLen = 9 + 9 + 9 + 9 + 7
        val cfg = byteArrayOf(
            // CONFIGURATION (9B)
            9, 0x02, totalConfigLen.toByte(), 0x00, 2, 1, 0, 0x80.toByte(), 250.toByte(),
            // INTERFACE 0 alt 0: VideoControl (9B)
            9, 0x04, 0, 0, 0, 0x0E, 0x01, 0x00, 0,
            // INTERFACE 1 alt 0: VideoStreaming idle (9B, 0 endpoints)
            9, 0x04, 1, 0, 0, 0x0E, 0x02, 0x00, 0,
            // INTERFACE 1 alt 1: VideoStreaming active (9B, 1 endpoint)
            9, 0x04, 1, 1, 1, 0x0E, 0x02, 0x00, 0,
            // ENDPOINT 0x81 IN ISO, wMaxPacketSize = 0x1400 (1024 * 3 = 3072), bInterval = 1 (7B)
            7, 0x05, 0x81.toByte(), 0x05, 0x00, 0x14, 1,
        )
        return dev + cfg
    }

    @Test
    fun `UVC webcam alt-setting switch updates ep_info and streams isochronous IN frames`() {
        val rawDesc = sampleUvcWebcamDescriptors()
        val parsed = UsbRedirProtocol.parseDescriptors(rawDesc)
        val isoEpIdx = UsbRedirProtocol.endpointIndex(0x81)
        // At alt 0 (idle), endpoint 0x81 is not active in the initial ep_info table...
        assertEquals(UsbRedirProtocol.EP_TYPE_INVALID, parsed.endpoints[isoEpIdx].type)
        // ...but switching interface 1 to alt 1 activates endpoint 0x81 with high-bandwidth 3072 MPS.
        val activeAlt1 = parsed.withAltSetting(interfaceNumber = 1, altSetting = 1)
        assertEquals(UsbRedirProtocol.EP_TYPE_ISO, activeAlt1.endpoints[isoEpIdx].type)
        assertEquals(3072, activeAlt1.endpoints[isoEpIdx].maxPacketSize)
        assertEquals(1, activeAlt1.endpoints[isoEpIdx].interval)
        assertEquals(1, activeAlt1.endpoints[isoEpIdx].interfaceNumber)

        val clientToServerIn = PipedInputStream(65536)
        val clientToServerOut = PipedOutputStream(clientToServerIn)
        val serverToClientIn = PipedInputStream(65536)
        val serverToClientOut = PipedOutputStream(serverToClientIn)

        val selectedAlts = ArrayList<Pair<Int, Int>>()
        var startedMps = 0
        var startedPkts = 0
        var startedUrbs = 0
        val streamClosed = AtomicBoolean(false)
        val frame1 = byteArrayOf(0x02, 0x82.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val frame2 = ByteArray(0) // 0-length UVC blanking microframe
        val frame3 = byteArrayOf(0x02, 0x83.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())

        val backend = object : UsbIpBackend {
            override fun isAlive(): Boolean = true
            override fun control(requestType: Int, request: Int, value: Int, index: Int, out: ByteArray, length: Int, timeoutMs: Int) = ByteArray(0)
            override fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int) = ByteArray(0)
            override fun setInterfaceAltSetting(interfaceNumber: Int, altSetting: Int) {
                selectedAlts.add(interfaceNumber to altSetting)
            }
            override fun startIsoInStream(
                endpointAddress: Int,
                packetSize: Int,
                pktsPerUrb: Int,
                noUrbs: Int,
                onFrame: (status: Int, data: ByteArray) -> Unit,
            ): AutoCloseable {
                startedMps = packetSize
                startedPkts = pktsPerUrb
                startedUrbs = noUrbs
                onFrame(UsbRedirProtocol.STATUS_SUCCESS, frame1)
                onFrame(UsbRedirProtocol.STATUS_SUCCESS, frame2)
                onFrame(UsbRedirProtocol.STATUS_SUCCESS, frame3)
                return AutoCloseable { streamClosed.set(true) }
            }
        }

        val serverThread = Thread {
            UsbRedirServer.serveStream(
                input = clientToServerIn,
                output = serverToClientOut,
                rawDescriptors = rawDesc,
                backend = backend,
            )
        }.apply { isDaemon = true; start() }

        val din = DataInputStream(serverToClientIn)
        // Handshake + initial announcement
        assertEquals(UsbRedirProtocol.TYPE_HELLO, UsbRedirProtocol.readPacket(din, use64BitIds = false)!!.type)
        clientToServerOut.write(UsbRedirProtocol.encodeHello(version = "qemu usb-redir guest", caps = UsbRedirProtocol.OUR_CAPS))
        clientToServerOut.flush()
        assertEquals(UsbRedirProtocol.TYPE_INTERFACE_INFO, UsbRedirProtocol.readPacket(din, use64BitIds = true)!!.type)
        val initialEpInfo = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_EP_INFO, initialEpInfo.type)
        assertEquals(UsbRedirProtocol.EP_TYPE_INVALID, initialEpInfo.payload[isoEpIdx].toInt() and 0xFF)
        assertEquals(UsbRedirProtocol.TYPE_DEVICE_CONNECT, UsbRedirProtocol.readPacket(din, use64BitIds = true)!!.type)

        // 1. Guest selects interface 1 alt 1 (UVC VideoStreaming high-bandwidth mode)
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(
                type = UsbRedirProtocol.TYPE_SET_ALT_SETTING,
                id = 50L,
                payload = byteArrayOf(1, 1),
                use64BitIds = true,
            ),
        )
        clientToServerOut.flush()

        // Server must emit updated TYPE_EP_INFO (5) followed by TYPE_ALT_SETTING_STATUS (11)
        val updatedEpInfo = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_EP_INFO, updatedEpInfo.type)
        assertEquals(UsbRedirProtocol.EP_TYPE_ISO, updatedEpInfo.payload[isoEpIdx].toInt() and 0xFF)
        assertEquals(3072, UsbRedirProtocol.getUShortLE(updatedEpInfo.payload, 96 + isoEpIdx * 2))

        val altStatus = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_ALT_SETTING_STATUS, altStatus.type)
        assertEquals(50L, altStatus.id)
        assertArrayEquals(byteArrayOf(UsbRedirProtocol.STATUS_SUCCESS.toByte(), 1, 1), altStatus.payload)
        assertEquals(listOf(1 to 1), selectedAlts)

        // 2. Guest starts isochronous stream on 0x81 (pkts_per_urb=16, no_urbs=4)
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(
                type = UsbRedirProtocol.TYPE_START_ISO_STREAM,
                id = 51L,
                payload = byteArrayOf(0x81.toByte(), 16, 4),
                use64BitIds = true,
            ),
        )
        clientToServerOut.flush()

        val isoStatus = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_ISO_STREAM_STATUS, isoStatus.type)
        assertEquals(51L, isoStatus.id)
        assertArrayEquals(byteArrayOf(UsbRedirProtocol.STATUS_SUCCESS.toByte(), 0x81.toByte()), isoStatus.payload)
        assertEquals(3072, startedMps)
        assertEquals(16, startedPkts)
        assertEquals(4, startedUrbs)

        // 3. Receive the 3 streamed TYPE_ISO_PACKET (102) microframes in order
        val recv1 = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_ISO_PACKET, recv1.type)
        val dec1 = UsbRedirProtocol.decodeIsoPacket(recv1.id, recv1.payload)
        assertEquals(0x81, dec1.endpoint)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, dec1.status)
        assertArrayEquals(frame1, dec1.data)

        val recv2 = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        val dec2 = UsbRedirProtocol.decodeIsoPacket(recv2.id, recv2.payload)
        assertEquals(0, dec2.length)
        assertEquals(0, dec2.data.size)

        val recv3 = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        val dec3 = UsbRedirProtocol.decodeIsoPacket(recv3.id, recv3.payload)
        assertArrayEquals(frame3, dec3.data)

        // 4. Guest stops the isochronous stream
        clientToServerOut.write(
            UsbRedirProtocol.encodePacket(
                type = UsbRedirProtocol.TYPE_STOP_ISO_STREAM,
                id = 52L,
                payload = byteArrayOf(0x81.toByte()),
                use64BitIds = true,
            ),
        )
        clientToServerOut.flush()

        val stopStatus = UsbRedirProtocol.readPacket(din, use64BitIds = true)!!
        assertEquals(UsbRedirProtocol.TYPE_ISO_STREAM_STATUS, stopStatus.type)
        assertEquals(52L, stopStatus.id)
        assertTrue(streamClosed.get())

        clientToServerOut.close()
        serverThread.join(2_000)
    }

    @Test
    fun `UsbfsIsoStreamer packs 64-bit usbdevfs_urb and drains reaped iso frames`() {
        val mps = 1024
        val numPackets = 3
        val urbMem = com.sun.jna.Memory((UsbfsIsoStreamer.URB_BASE_SIZE + numPackets * UsbfsIsoStreamer.ISO_PACKET_DESC_SIZE).toLong())
        val dataBuf = com.sun.jna.Memory((mps * numPackets).toLong())

        UsbfsIsoStreamer.initIsoUrb(urbMem, dataBuf, endpointAddress = 0x81, packetSize = mps, numPackets = numPackets, urbIndex = 2)
        assertEquals(UsbfsIsoStreamer.USBDEVFS_URB_TYPE_ISO, urbMem.getByte(UsbfsIsoStreamer.OFF_TYPE))
        assertEquals(0x81.toByte(), urbMem.getByte(UsbfsIsoStreamer.OFF_ENDPOINT))
        assertEquals(UsbfsIsoStreamer.USBDEVFS_URB_ISO_ASAP, urbMem.getInt(UsbfsIsoStreamer.OFF_FLAGS))
        assertEquals(mps * numPackets, urbMem.getInt(UsbfsIsoStreamer.OFF_BUFFER_LENGTH))
        assertEquals(numPackets, urbMem.getInt(UsbfsIsoStreamer.OFF_NUMBER_OF_PACKETS))

        // Simulate kernel filling frame 0 (4 bytes, status=0), frame 1 (0 bytes, status=-18 EXDEV), frame 2 (3 bytes, status=0)
        val p0 = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val p2 = byteArrayOf(0x55, 0x66, 0x77)
        dataBuf.write(0L, p0, 0, p0.size)
        dataBuf.write((2 * mps).toLong(), p2, 0, p2.size)

        val d0 = UsbfsIsoStreamer.OFF_ISO_FRAME_DESC
        urbMem.setInt(d0 + 4L, p0.size)
        urbMem.setInt(d0 + 8L, 0)

        val d1 = d0 + UsbfsIsoStreamer.ISO_PACKET_DESC_SIZE
        urbMem.setInt(d1 + 4L, 0)
        urbMem.setInt(d1 + 8L, -18) // -EXDEV missed microframe -> mapped to STATUS_SUCCESS (0 bytes)

        val d2 = d1 + UsbfsIsoStreamer.ISO_PACKET_DESC_SIZE
        urbMem.setInt(d2 + 4L, p2.size)
        urbMem.setInt(d2 + 8L, 0)

        val drained = ArrayList<Pair<Int, ByteArray>>()
        UsbfsIsoStreamer.drainReapedIsoUrb(urbMem, dataBuf, mps, numPackets) { st, bytes ->
            drained.add(st to bytes)
        }
        assertEquals(3, drained.size)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, drained[0].first)
        assertArrayEquals(p0, drained[0].second)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, drained[1].first)
        assertEquals(0, drained[1].second.size)
        assertEquals(UsbRedirProtocol.STATUS_SUCCESS, drained[2].first)
        assertArrayEquals(p2, drained[2].second)

        UsbfsIsoStreamer.prepareIsoUrbForResubmit(urbMem, mps, numPackets)
        assertEquals(0, urbMem.getInt(d0 + 4L))
        assertEquals(0, urbMem.getInt(d1 + 8L))
    }
}
