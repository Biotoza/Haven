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
}
