package com.thisisseanxu.hs_disconnect

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LocalSocks5Proxy(private val port: Int) : Closeable {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val blocked = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val blockGate = Object()
    private val executor = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap.newKeySet<Closeable>()
    private var server: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, port))
        }
        Log.i(TAG, "SOCKS listening on 127.0.0.1:$port")
        executor.execute {
            while (running.get()) {
                runCatching { server?.accept() }.getOrNull()?.let { client ->
                    sessions += client
                    executor.execute { handleClient(client) }
                }
            }
        }
    }

    fun setBlocked(value: Boolean) {
        blocked.set(value)
        val snapshot = sessions.toList()
        Log.w(TAG, "blocked=$value activeSessions=${snapshot.size}")
        if (value) snapshot.forEach(::closeForBlock)
        else synchronized(blockGate) { blockGate.notifyAll() }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            if (input.readUnsignedByte() != VERSION) return
            val methodCount = input.readUnsignedByte()
            input.skipFully(methodCount)
            output.write(byteArrayOf(VERSION.toByte(), 0))
            output.flush()

            if (input.readUnsignedByte() != VERSION) return
            val command = input.readUnsignedByte()
            input.readUnsignedByte()
            val address = readAddress(input)
            val destinationPort = input.readUnsignedShort()
            Log.d(TAG, "request command=$command destination=${address.hostAddress}:$destinationPort")
            if (!awaitUnblocked(address, destinationPort)) return
            when (command) {
                COMMAND_CONNECT -> handleConnect(client, input, output, address, destinationPort)
                COMMAND_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
                else -> writeReply(output, 7, null)
            }
        } catch (error: Exception) {
            Log.d(TAG, "client session ended: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            sessions -= client
            runCatching { client.close() }
        }
    }

    private fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        address: InetAddress,
        port: Int
    ) {
        val remote = Socket()
        sessions += remote
        try {
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(address, port), 10_000)
            Log.d(TAG, "TCP connected ${address.hostAddress}:$port")
            writeReply(output, 0, remote.localSocketAddress as InetSocketAddress)
            val upstream = executor.submit {
                runCatching { remote.getInputStream().copyTo(client.getOutputStream()) }
                runCatching { client.shutdownOutput() }
            }
            input.copyTo(remote.getOutputStream())
            runCatching { remote.shutdownOutput() }
            upstream.get()
        } finally {
            sessions -= remote
            runCatching { remote.close() }
        }
    }

    private fun handleUdpAssociate(
        client: Socket,
        input: DataInputStream,
        output: DataOutputStream
    ) {
        val relay = DatagramSocket(InetSocketAddress(loopback, 0))
        val upstream = DatagramSocket()
        Log.d(TAG, "UDP association opened relayPort=${relay.localPort}")
        sessions += relay
        sessions += upstream
        val clientAddress = AtomicReference<InetSocketAddress?>()
        try {
            writeReply(output, 0, relay.localSocketAddress as InetSocketAddress)
            executor.execute { relayUdpRequests(relay, upstream, clientAddress) }
            executor.execute { relayUdpResponses(upstream, relay, clientAddress) }
            while (!blocked.get() && input.read() >= 0) {
                // The TCP control connection owns the UDP association.
            }
        } finally {
            sessions -= relay
            sessions -= upstream
            relay.close()
            upstream.close()
        }
    }

    private fun relayUdpRequests(
        relay: DatagramSocket,
        upstream: DatagramSocket,
        clientAddress: AtomicReference<InetSocketAddress?>
    ) {
        val buffer = ByteArray(65535)
        while (running.get() && !blocked.get() && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { relay.receive(packet) }.getOrElse { return }
            clientAddress.set(InetSocketAddress(packet.address, packet.port))
            forwardUdpRequest(upstream, packet)
        }
    }

    private fun relayUdpResponses(
        upstream: DatagramSocket,
        relay: DatagramSocket,
        clientAddress: AtomicReference<InetSocketAddress?>
    ) {
        val buffer = ByteArray(65535)
        while (running.get() && !blocked.get() && !upstream.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { upstream.receive(packet) }.getOrElse { return }
            clientAddress.get()?.let { forwardUdpResponse(relay, packet, it) }
        }
    }

    private fun forwardUdpRequest(upstream: DatagramSocket, packet: DatagramPacket) {
        val data = packet.data
        var offset = packet.offset
        if (packet.length < 10 || data[offset].toInt() != 0 || data[offset + 1].toInt() != 0) return
        if (data[offset + 2].toInt() != 0) return
        offset += 3
        val (address, next) = readAddress(data, offset)
        offset = next
        val port = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        offset += 2
        upstream.send(DatagramPacket(data, offset, packet.offset + packet.length - offset, address, port))
    }

    private fun forwardUdpResponse(
        relay: DatagramSocket,
        packet: DatagramPacket,
        client: InetSocketAddress
    ) {
        val address = packet.address.address
        val header = ByteArray(4 + address.size + 2)
        header[3] = if (address.size == 4) ADDRESS_IPV4.toByte() else ADDRESS_IPV6.toByte()
        address.copyInto(header, 4)
        header[header.size - 2] = (packet.port shr 8).toByte()
        header[header.size - 1] = packet.port.toByte()
        val response = header + packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        relay.send(DatagramPacket(response, response.size, client))
    }

    private fun readAddress(input: DataInputStream): InetAddress {
        return when (input.readUnsignedByte()) {
            ADDRESS_IPV4 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully))
            ADDRESS_IPV6 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully))
            ADDRESS_DOMAIN -> {
                val name = ByteArray(input.readUnsignedByte()).also(input::readFully)
                InetAddress.getByName(name.toString(Charsets.UTF_8))
            }
            else -> throw IllegalArgumentException("Unsupported SOCKS address")
        }
    }

    private fun readAddress(data: ByteArray, start: Int): Pair<InetAddress, Int> {
        var offset = start
        return when (data[offset++].toInt() and 0xff) {
            ADDRESS_IPV4 -> InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)) to offset + 4
            ADDRESS_IPV6 -> InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)) to offset + 16
            ADDRESS_DOMAIN -> {
                val length = data[offset++].toInt() and 0xff
                InetAddress.getByName(data.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)) to
                    offset + length
            }
            else -> throw IllegalArgumentException("Unsupported SOCKS address")
        }
    }

    private fun writeReply(output: DataOutputStream, result: Int, bound: InetSocketAddress?) {
        val address = bound?.address?.address ?: byteArrayOf(0, 0, 0, 0)
        val port = bound?.port ?: 0
        output.writeByte(VERSION)
        output.writeByte(result)
        output.writeByte(0)
        output.writeByte(if (address.size == 4) ADDRESS_IPV4 else ADDRESS_IPV6)
        output.write(address)
        output.writeShort(port)
        output.flush()
    }

    private fun closeForBlock(session: Closeable) {
        runCatching {
            if (session is Socket) session.setSoLinger(true, 0)
            session.close()
        }
    }

    private fun awaitUnblocked(address: InetAddress, port: Int): Boolean {
        if (!blocked.get()) return true
        val started = System.currentTimeMillis()
        Log.i(TAG, "holding new session ${address.hostAddress}:$port")
        synchronized(blockGate) {
            while (running.get() && blocked.get()) blockGate.wait(250)
        }
        Log.i(TAG, "released held session ${address.hostAddress}:$port after ${System.currentTimeMillis() - started}ms")
        return running.get()
    }

    override fun close() {
        Log.i(TAG, "closing SOCKS activeSessions=${sessions.size}")
        running.set(false)
        synchronized(blockGate) { blockGate.notifyAll() }
        sessions.toList().forEach { runCatching { it.close() } }
        sessions.clear()
        runCatching { server?.close() }
        executor.shutdownNow()
    }

    private fun DataInputStream.skipFully(length: Int) {
        val bytes = ByteArray(length)
        readFully(bytes)
    }

    companion object {
        private const val VERSION = 5
        private const val COMMAND_CONNECT = 1
        private const val COMMAND_UDP_ASSOCIATE = 3
        private const val ADDRESS_IPV4 = 1
        private const val ADDRESS_DOMAIN = 3
        private const val ADDRESS_IPV6 = 4
        private const val TAG = "HSSocks"
    }
}
