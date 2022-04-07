package conductor.drivers

import conductor.Conductor
import conductor.DeviceInfo
import conductor.Driver
import conductor.Point
import conductor.TreeNode
import conductor.android.DadbForwarder
import conductor.android.InstrumentationThread
import conductor_android.ConductorDriverGrpc
import conductor_android.deviceInfoRequest
import conductor_android.tapRequest
import conductor_android.viewHierarchyRequest
import dadb.AdbShellResponse
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import okio.buffer
import okio.sink
import okio.source
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeoutException
import javax.xml.parsers.DocumentBuilderFactory

class AndroidDriver(
    private val dadb: Dadb,
    private val allowlistedAttributes: List<String> = ALLOWLISTED_ATTRS,
) : Driver {

    private val channel = ManagedChannelBuilder.forAddress("localhost", 7001)
        .usePlaintext()
        .build()
    private val blockingStub = ConductorDriverGrpc.newBlockingStub(channel)
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance()

    private var instrumentationThread: Thread? = null
    private var forwarder: DadbForwarder? = null

    override fun open() {
        installConductorApks()
        instrumentationThread = InstrumentationThread(dadb)
        instrumentationThread?.start()

        try {
            awaitLaunch()
        } catch (ignored: InterruptedException) {
            instrumentationThread?.interrupt()
            return
        }

        forwarder = DadbForwarder(
            dadb,
            7001,
            7001
        )
        forwarder?.start()
    }

    private fun awaitLaunch() {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < SERVER_LAUNCH_TIMEOUT_MS) {
            try {
                dadb.open("tcp:7001").close()
                return
            } catch (ignored: Exception) {
                // Continue
            }

            Thread.sleep(100)
        }

        throw TimeoutException("Conductor Android driver did not start up in time")
    }

    override fun close() {
        forwarder?.close()
        forwarder = null
        uninstallConductorApks()
        instrumentationThread?.interrupt()
        instrumentationThread = null
        channel.shutdown()
    }

    override fun deviceInfo(): DeviceInfo {
        val response = blockingStub.deviceInfo(deviceInfoRequest {})

        return DeviceInfo(
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels
        )
    }

    override fun tap(point: Point) {
        blockingStub.tap(
            tapRequest {
                x = point.x
                y = point.y
            }
        ) ?: throw IllegalStateException("Response can't be null")
    }

    override fun contentDescriptor(): TreeNode {
        val response = blockingStub.viewHierarchy(viewHierarchyRequest {})

        val document = documentBuilderFactory
            .newDocumentBuilder()
            .parse(response.hierarchy.byteInputStream())

        return mapHierarchy(document)
    }

    override fun scrollVertical() {
        dadb.shell("input swipe 500 1000 700 -900 2000")
    }

    override fun backPress() {
        dadb.shell("input keyevent 4")
    }

    private fun mapHierarchy(node: Node): TreeNode {
        val attributes = if (node is Element) {
            allowlistedAttributes
                .mapNotNull {
                    if (node.hasAttribute(it)) {
                        it to node.getAttribute(it)
                    } else {
                        null
                    }
                }
                .toMap()
        } else {
            emptyMap()
        }

        val children = mutableListOf<TreeNode>()
        val childNodes = node.childNodes
        (0 until childNodes.length).forEach { i ->
            children += mapHierarchy(childNodes.item(i))
        }

        return TreeNode(
            attributes = attributes,
            children = children,
            clickable = (node as? Element)
                ?.getAttribute("clickable")
                ?.let { it == "true" }
                ?: false
        )
    }

    private fun installConductorApks() {
        val conductorAppApk = File.createTempFile("conductor-app", ".apk")
        val conductorServerApk = File.createTempFile("conductor-server", ".apk")
        Conductor::class.java.getResourceAsStream("/conductor-app.apk")?.let {
            val bufferedSink = conductorAppApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }
        Conductor::class.java.getResourceAsStream("/conductor-server.apk")?.let {
            val bufferedSink = conductorServerApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }
        install(conductorAppApk)
        if (!isPackageInstalled("dev.mobile.conductor")) {
            throw IllegalStateException("dev.mobile.conductor was not installed")
        }
        install(conductorServerApk)
    }

    private fun uninstallConductorApks() {
        if (isPackageInstalled("dev.mobile.conductor.test")) {
            uninstall("dev.mobile.conductor.test")
        }
        if (isPackageInstalled("dev.mobile.conductor")) {
            uninstall("dev.mobile.conductor")
        }
    }

    private fun install(apkFile: File) {
        try {
            dadb.install(apkFile)
        } catch (installError: IOException) {
            throw IOException("Failed to install apk " + apkFile + ": " + installError.message, installError)
        }
    }

    private fun uninstall(packageName: String) {
        try {
            dadb.uninstall(packageName)
        } catch (error: IOException) {
            throw IOException("Failed to uninstall package " + packageName + ": " + error.message, error)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        val output: String = shell("pm list packages $packageName")
        return output.split("\n".toRegex())
            .map { line -> line.split(":".toRegex()) }
            .filter { parts -> parts.size == 2 }
            .map { parts -> parts[1] }
            .any { linePackageName -> linePackageName == packageName }
    }

    private fun shell(command: String): String {
        val response: AdbShellResponse = try {
            dadb.shell(command)
        } catch (e: IOException) {
            throw IOException(command, e)
        }

        if (response.exitCode != 0) {
            throw IOException("$command: ${response.allOutput}")
        }
        return response.output
    }

    companion object {

        private val ALLOWLISTED_ATTRS = listOf(
            "text",
            "bounds",
            "clickable",
            "resource-id"
        )

        private const val SERVER_LAUNCH_TIMEOUT_MS = 5000
    }
}