package sh.haven.core.usb

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Direct Linux `usbfs` (`<linux/usbdevice_fs.h>`) isochronous URB streamer on top of
 * [android.hardware.usb.UsbDeviceConnection.getFileDescriptor] (#613).
 *
 * Android's `UsbDeviceConnection` Java API exposes only control, bulk, and interrupt
 * transfers (`bulkTransfer` returns `-1` for `USB_ENDPOINT_XFER_ISOC`). However,
 * `UsbDeviceConnection.getFileDescriptor()` returns the open `/dev/bus/usb/BBB/DDD`
 * `usbfs` file descriptor owned by the app process. Using the same technique as
 * `Peter-St/Android-UVC-Camera`, we issue `USBDEVFS_SETINTERFACE`,
 * `USBDEVFS_SUBMITURB`, `USBDEVFS_REAPURB`, and `USBDEVFS_DISCARDURB` ioctls via JNA
 * on that file descriptor so UVC webcams and USB audio devices can stream isochronous
 * microframes over `usbredir` without root.
 */
object UsbfsIsoStreamer {
    private const val TAG = "UsbfsIsoStreamer"

    // ---- <linux/usbdevice_fs.h> constants (64-bit arm64-v8a / x86_64) -----

    const val USBDEVFS_URB_TYPE_ISO: Byte = 0
    const val USBDEVFS_URB_ISO_ASAP: Int = 0x02

    /** `_IOR('U', 4, struct usbdevfs_setinterface)` (8 bytes: `u32 interface, u32 altsetting`). */
    const val USBDEVFS_SETINTERFACE: Long = 0x80085504L
    /** `_IOR('U', 15, unsigned int)` (4 bytes: `u32 interface`). */
    const val USBDEVFS_CLAIMINTERFACE: Long = 0x8004550fL
    /** `_IOR('U', 10, struct usbdevfs_urb)` (56-byte base header on LP64). */
    const val USBDEVFS_SUBMITURB: Long = 0x8038550aL
    /** `_IO('U', 11)`. */
    const val USBDEVFS_DISCARDURB: Long = 0x0000550bL
    /** `_IOW('U', 12, void *)`. */
    const val USBDEVFS_REAPURB: Long = 0x4008550cL
    /** `_IOW('U', 13, void *)`. */
    const val USBDEVFS_REAPURBNDELAY: Long = 0x4008550dL

    // ---- struct usbdevfs_urb field offsets on 64-bit Linux ----------------

    internal const val URB_BASE_SIZE = 56
    internal const val ISO_PACKET_DESC_SIZE = 12
    internal const val OFF_TYPE = 0L
    internal const val OFF_ENDPOINT = 1L
    internal const val OFF_STATUS = 4L
    internal const val OFF_FLAGS = 8L
    internal const val OFF_BUFFER = 16L
    internal const val OFF_BUFFER_LENGTH = 24L
    internal const val OFF_ACTUAL_LENGTH = 28L
    internal const val OFF_START_FRAME = 32L
    internal const val OFF_NUMBER_OF_PACKETS = 36L
    internal const val OFF_ERROR_COUNT = 40L
    internal const val OFF_SIGNR = 44L
    internal const val OFF_USERCONTEXT = 48L
    internal const val OFF_ISO_FRAME_DESC = 56L

    private interface LibC : Library {
        fun ioctl(fd: Int, request: NativeLong, arg: Pointer?): Int
    }

    private val libc: LibC by lazy {
        Native.load("c", LibC::class.java)
    }

    private fun ioctl(fd: Int, request: Long, arg: Pointer?): Int =
        libc.ioctl(fd, NativeLong(request), arg)

    /** Claim [interfaceNumber] on `usbfs` [fd] via `USBDEVFS_CLAIMINTERFACE`. */
    fun claimInterface(fd: Int, interfaceNumber: Int) {
        require(fd >= 0) { "invalid usbfs fd $fd" }
        val mem = Memory(4)
        mem.setInt(0, interfaceNumber)
        val rc = ioctl(fd, USBDEVFS_CLAIMINTERFACE, mem)
        if (rc < 0 && Native.getLastError() != 16 /* EBUSY: already claimed */) {
            Log.w(TAG, "USBDEVFS_CLAIMINTERFACE(iface=$interfaceNumber) rc=$rc errno=${Native.getLastError()}")
        }
    }

    /**
     * Select [altSetting] on [interfaceNumber] via `USBDEVFS_SETINTERFACE`.
     * Throws [IOException] if the kernel rejects the alternate setting.
     */
    fun setInterfaceAltSetting(fd: Int, interfaceNumber: Int, altSetting: Int) {
        require(fd >= 0) { "invalid usbfs fd $fd" }
        claimInterface(fd, interfaceNumber)
        val mem = Memory(8)
        mem.setInt(0, interfaceNumber)
        mem.setInt(4, altSetting)
        val rc = ioctl(fd, USBDEVFS_SETINTERFACE, mem)
        if (rc < 0) {
            val errno = Native.getLastError()
            throw IOException("USBDEVFS_SETINTERFACE(iface=$interfaceNumber, alt=$altSetting) failed: errno=$errno")
        }
    }

    /**
     * Populate a native `struct usbdevfs_urb` in [urbMem] for [numPackets] isochronous
     * frames of [packetSize] bytes each, backed by [dataBuf].
     */
    internal fun initIsoUrb(
        urbMem: Memory,
        dataBuf: Memory,
        endpointAddress: Int,
        packetSize: Int,
        numPackets: Int,
        urbIndex: Int,
    ) {
        urbMem.clear()
        urbMem.setByte(OFF_TYPE, USBDEVFS_URB_TYPE_ISO)
        urbMem.setByte(OFF_ENDPOINT, endpointAddress.toByte())
        urbMem.setInt(OFF_STATUS, 0)
        urbMem.setInt(OFF_FLAGS, USBDEVFS_URB_ISO_ASAP)
        urbMem.setPointer(OFF_BUFFER, dataBuf)
        urbMem.setInt(OFF_BUFFER_LENGTH, packetSize * numPackets)
        urbMem.setInt(OFF_ACTUAL_LENGTH, 0)
        urbMem.setInt(OFF_START_FRAME, 0)
        urbMem.setInt(OFF_NUMBER_OF_PACKETS, numPackets)
        urbMem.setInt(OFF_ERROR_COUNT, 0)
        urbMem.setInt(OFF_SIGNR, 0)
        urbMem.setPointer(OFF_USERCONTEXT, Pointer.createConstant(urbIndex.toLong() + 1L))
        for (i in 0 until numPackets) {
            val descOff = OFF_ISO_FRAME_DESC + i.toLong() * ISO_PACKET_DESC_SIZE
            urbMem.setInt(descOff, packetSize)      // length
            urbMem.setInt(descOff + 4L, 0)          // actual_length
            urbMem.setInt(descOff + 8L, 0)          // status
        }
    }

    /**
     * Reset the per-frame `length`, `actual_length`, and `status` descriptors in [urbMem]
     * before resubmitting a reaped isochronous URB.
     */
    internal fun prepareIsoUrbForResubmit(urbMem: Memory, packetSize: Int, numPackets: Int) {
        urbMem.setInt(OFF_STATUS, 0)
        urbMem.setInt(OFF_FLAGS, USBDEVFS_URB_ISO_ASAP)
        urbMem.setInt(OFF_ACTUAL_LENGTH, 0)
        urbMem.setInt(OFF_START_FRAME, 0)
        urbMem.setInt(OFF_ERROR_COUNT, 0)
        for (i in 0 until numPackets) {
            val descOff = OFF_ISO_FRAME_DESC + i.toLong() * ISO_PACKET_DESC_SIZE
            urbMem.setInt(descOff, packetSize)
            urbMem.setInt(descOff + 4L, 0)
            urbMem.setInt(descOff + 8L, 0)
        }
    }

    /**
     * Extract each completed isochronous microframe from [urbMem] + [dataBuf] and pass
     * `(usbRedirStatus, frameBytes)` to [onFrame].
     */
    internal fun drainReapedIsoUrb(
        urbMem: Memory,
        dataBuf: Memory,
        packetSize: Int,
        numPackets: Int,
        onFrame: (status: Int, data: ByteArray) -> Unit,
    ) {
        var offset = 0L
        for (i in 0 until numPackets) {
            val descOff = OFF_ISO_FRAME_DESC + i.toLong() * ISO_PACKET_DESC_SIZE
            val reqLen = urbMem.getInt(descOff).coerceAtLeast(0)
            val actualLen = urbMem.getInt(descOff + 4L).coerceIn(0, reqLen.coerceAtLeast(packetSize))
            val kernelStatus = urbMem.getInt(descOff + 8L)
            val redirStatus = mapKernelUrbStatus(kernelStatus)
            val bytes = if (actualLen > 0 && redirStatus == UsbRedirProtocol.STATUS_SUCCESS) {
                dataBuf.getByteArray(offset, actualLen)
            } else {
                ByteArray(0)
            }
            onFrame(redirStatus, bytes)
            offset += reqLen.toLong()
        }
    }

    /** Map a Linux kernel `-errno` URB frame status to `enum usb_redir_status`. */
    internal fun mapKernelUrbStatus(kernelStatus: Int): Int = when (kernelStatus) {
        0 -> UsbRedirProtocol.STATUS_SUCCESS
        -2 /* -ENOENT */, -104 /* -ECONNRESET */, -108 /* -ESHUTDOWN */ -> UsbRedirProtocol.STATUS_CANCELLED
        -22 /* -EINVAL */ -> UsbRedirProtocol.STATUS_INVAL
        -32 /* -EPIPE */ -> UsbRedirProtocol.STATUS_STALLED
        -75 /* -EOVERFLOW */ -> UsbRedirProtocol.STATUS_BABBLE
        -110 /* -ETIMEDOUT */ -> UsbRedirProtocol.STATUS_TIMEOUT
        // -EXDEV (-18) / -EPROTO (-71) / -EILSEQ (-84) on an empty or missed microframe:
        // treat -EXDEV (missed iso slot with 0 bytes) as a 0-length success frame so the
        // guest UVC driver doesn't abort the stream on a single skipped microframe.
        -18 /* -EXDEV */ -> UsbRedirProtocol.STATUS_SUCCESS
        else -> UsbRedirProtocol.STATUS_IOERROR
    }

    /**
     * Start a continuous isochronous IN URB ring on `usbfs` [fd] for [endpointAddress].
     *
     * Allocates [noUrbs] kernel URBs, each holding [pktsPerUrb] microframes of
     * [packetSize] bytes, submits them via `USBDEVFS_SUBMITURB`, and spawns a reaper
     * thread that loops on `USBDEVFS_REAPURB`, forwards each microframe to [onFrame],
     * and resubmits the URB until [AutoCloseable.close] is called.
     */
    fun startIsoInStream(
        fd: Int,
        endpointAddress: Int,
        packetSize: Int,
        pktsPerUrb: Int,
        noUrbs: Int,
        onFrame: (status: Int, data: ByteArray) -> Unit,
    ): AutoCloseable {
        require(fd >= 0) { "invalid usbfs fd $fd" }
        val safePkts = pktsPerUrb.coerceIn(1, 64)
        val safeUrbs = noUrbs.coerceIn(1, 16)
        val safeMps = packetSize.coerceIn(1, 3072)
        val urbStructSize = (URB_BASE_SIZE + safePkts * ISO_PACKET_DESC_SIZE).toLong()
        val urbBufferSize = (safeMps * safePkts).toLong()

        val urbMemories = Array(safeUrbs) { Memory(urbStructSize) }
        val dataMemories = Array(safeUrbs) { Memory(urbBufferSize) }
        val urbByAddress = HashMap<Long, Int>(safeUrbs * 2)

        for (u in 0 until safeUrbs) {
            initIsoUrb(urbMemories[u], dataMemories[u], endpointAddress or 0x80, safeMps, safePkts, u)
            urbByAddress[Pointer.nativeValue(urbMemories[u])] = u
        }

        var submittedCount = 0
        for (u in 0 until safeUrbs) {
            val rc = ioctl(fd, USBDEVFS_SUBMITURB, urbMemories[u])
            if (rc < 0) {
                val errno = Native.getLastError()
                for (prev in 0 until submittedCount) {
                    ioctl(fd, USBDEVFS_DISCARDURB, urbMemories[prev])
                }
                throw IOException(
                    "USBDEVFS_SUBMITURB(ep=0x${(endpointAddress or 0x80).toString(16)}, " +
                        "mps=$safeMps, pkts=$safePkts, urb=$u/$safeUrbs) failed: errno=$errno",
                )
            }
            submittedCount++
        }

        val running = AtomicBoolean(true)
        val reapThread = thread(
            name = "usbfs-iso-reap-0x${(endpointAddress or 0x80).toString(16)}",
            isDaemon = true,
        ) {
            val reapedPtrSlot = Memory(Native.POINTER_SIZE.toLong())
            while (running.get() && !Thread.currentThread().isInterrupted) {
                reapedPtrSlot.clear()
                val rc = ioctl(fd, USBDEVFS_REAPURB, reapedPtrSlot)
                if (rc < 0) {
                    val errno = Native.getLastError()
                    if (!running.get()) break
                    if (errno == 4 /* EINTR */ || errno == 11 /* EAGAIN */) continue
                    if (errno == 19 /* ENODEV */ || errno == 9 /* EBADF */) break
                    Log.w(TAG, "USBDEVFS_REAPURB failed: errno=$errno")
                    break
                }
                val reapedPtr = reapedPtrSlot.getPointer(0) ?: continue
                val idx = urbByAddress[Pointer.nativeValue(reapedPtr)] ?: continue
                if (!running.get()) break

                val urbMem = urbMemories[idx]
                val dataBuf = dataMemories[idx]
                runCatching {
                    drainReapedIsoUrb(urbMem, dataBuf, safeMps, safePkts, onFrame)
                }

                if (running.get()) {
                    prepareIsoUrbForResubmit(urbMem, safeMps, safePkts)
                    val subRc = ioctl(fd, USBDEVFS_SUBMITURB, urbMem)
                    if (subRc < 0 && running.get()) {
                        Log.w(TAG, "resubmit USBDEVFS_SUBMITURB failed: errno=${Native.getLastError()}")
                        break
                    }
                }
            }
            // Drain any remaining discarded URBs non-blockingly so kernel state is clean.
            while (true) {
                reapedPtrSlot.clear()
                if (ioctl(fd, USBDEVFS_REAPURBNDELAY, reapedPtrSlot) < 0) break
            }
        }

        return AutoCloseable {
            if (running.compareAndSet(true, false)) {
                for (u in 0 until safeUrbs) {
                    ioctl(fd, USBDEVFS_DISCARDURB, urbMemories[u])
                }
                reapThread.interrupt()
                runCatching { reapThread.join(500) }
            }
        }
    }

    /** Submit a single OUT isochronous packet on `usbfs` [fd] for [endpointAddress]. */
    fun submitIsoOut(fd: Int, endpointAddress: Int, data: ByteArray) {
        require(fd >= 0) { "invalid usbfs fd $fd" }
        if (data.isEmpty()) return
        val urbStructSize = (URB_BASE_SIZE + ISO_PACKET_DESC_SIZE).toLong()
        val urbMem = Memory(urbStructSize)
        val dataBuf = Memory(data.size.toLong())
        dataBuf.write(0, data, 0, data.size)
        initIsoUrb(urbMem, dataBuf, endpointAddress and 0x7F, data.size, 1, 0)
        val rc = ioctl(fd, USBDEVFS_SUBMITURB, urbMem)
        if (rc < 0) {
            throw IOException("USBDEVFS_SUBMITURB OUT failed: errno=${Native.getLastError()}")
        }
        val reapedPtrSlot = Memory(Native.POINTER_SIZE.toLong())
        ioctl(fd, USBDEVFS_REAPURB, reapedPtrSlot)
    }
}
