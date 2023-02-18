package com.duydatpham.chiptool

import android.util.Log
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.NetworkCredentials
import chip.setuppayload.SetupPayload
import chip.setuppayload.SetupPayloadParser
import com.duydatpham.chiptool.bluetooth.BluetoothManager
import com.duydatpham.chiptool.util.DeviceIdUtil
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ChiptoolModule(
    public val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {


    private val STATUS_PAIRING_SUCCESS = 0
    private val NUM_CHANNEL_BYTES = 3
    private val NUM_PANID_BYTES = 2
    private val NUM_XPANID_BYTES = 8
    private val NUM_MASTER_KEY_BYTES = 16
    private val TYPE_CHANNEL = 0 // Type of Thread Channel TLV.
    private val TYPE_PANID = 1 // Type of Thread PAN ID TLV.
    private val TYPE_XPANID = 2 // Type of Thread Extended PAN ID TLV.
    private val TYPE_MASTER_KEY = 5 // Type of Thread Network Master Key TLV.

    final var TAG: String = "ChiptoolModule"
    override fun getName() = "Chiptool"

    private var deviceController: ChipDeviceController? = null;

    @ReactMethod
    fun checkBarcode(
        barcode: String,
        promise: Promise
    ) {
        try {
            var payload = SetupPayloadParser().parseQrCode(barcode)
            Log.e(TAG, "barcode.displayValue=" + barcode)
            if(payload != null)
                promise.resolve(true)
            else
                promise.resolve(false)
        } catch (ex: SetupPayloadParser.UnrecognizedQrCodeException) {
            Log.e(TAG, "Unrecognized QR Code", ex)
            promise.resolve(false)
        }
    }


    @ReactMethod
    fun pairDevice(
        barcode: String,
        hubInfo: ReadableMap,
        id: String
    ) {
        Log.d(TAG, "id=" + id)
        lateinit var payload: SetupPayload
        try {
            payload = SetupPayloadParser().parseQrCode(barcode)
            Log.e(TAG, "barcode.displayValue=" + barcode)

            val operationalDataset = makeThreadOperationalDataset(
                hubInfo.getInt("channel"),
                hubInfo.getInt("panId"),
                hubInfo.getString("xpanId")?.hexToByteArray() ?: "".hexToByteArray(),
                hubInfo.getString("masterKey")?.hexToByteArray() ?: "".hexToByteArray()
            )

            val networkCredentials =
                NetworkCredentialsParcelable.forThread(
                    NetworkCredentialsParcelable.ThreadCredentials(
                        operationalDataset
                    )
                )


            startConnectingToDevice(
                CHIPDeviceInfo.fromSetupPayload(payload),
                networkCredentials,
                id
            )
        } catch (ex: SetupPayloadParser.UnrecognizedQrCodeException) {
            Log.e(TAG, "Unrecognized QR Code", ex)
            return
        }
    }

    private fun emitEvent(name: String, id: String ){
        val payloadEvent = Arguments.createMap()
        payloadEvent.putString("requestId", id)
        Log.d(TAG, "Event: $name, id=$id")
        reactContext
            .getJSModule<RCTDeviceEventEmitter>(RCTDeviceEventEmitter::class.java)
            .emit(name, payloadEvent)
    }

    private fun startConnectingToDevice(
        deviceInfo: CHIPDeviceInfo,
        networkCredentialsParcelable: NetworkCredentialsParcelable,
        id: String
    ) {
        if(deviceController == null)
            deviceController = ChipClient.getDeviceController(this.reactContext)
        else {
            deviceController?.close()
        }
        val bluetoothManager = BluetoothManager()

        GlobalScope.launch(Dispatchers.Main) {
            //TODO:start Scanning
            emitEvent("onStartScanning", id)

            val device =
                bluetoothManager.getBluetoothDevice(reactContext, deviceInfo.discriminator)
                    ?: run {
                        emitEvent("onScanFail", id)
                        return@launch
                    }


            emitEvent("onStartConnecting", id)
            var gatt = bluetoothManager.connect(reactContext, device)

            //TODO:start pairing
            emitEvent("onStartPairing", id)
            deviceController?.setCompletionListener(ConnectionCallback().apply {
                _id = id
            })

            val deviceId = DeviceIdUtil.getNextAvailableId(reactContext)
            val connId = bluetoothManager.connectionId
            var network: NetworkCredentials? = null
            var networkParcelable = checkNotNull(networkCredentialsParcelable)

            val wifi = networkParcelable.wiFiCredentials
            if (wifi != null) {
                network = NetworkCredentials.forWiFi(
                    NetworkCredentials.WiFiCredentials(
                        wifi.ssid,
                        wifi.password
                    )
                )
            }
            val thread = networkParcelable.threadCredentials
            if (thread != null) {
                network =
                    NetworkCredentials.forThread(NetworkCredentials.ThreadCredentials(thread.operationalDataset))
            }

            deviceController?.pairDevice(gatt, connId, deviceId, deviceInfo.setupPinCode, network)
            DeviceIdUtil.setNextAvailableId(reactContext, deviceId + 1)
        }


    }


    private fun makeThreadOperationalDataset(
        channel: Int,
        panId: Int,
        xpanId: ByteArray,
        masterKey: ByteArray
    ): ByteArray {
        // channel
        var dataset = byteArrayOf(TYPE_CHANNEL.toByte(), NUM_CHANNEL_BYTES.toByte())
        dataset += 0x00.toByte() // Channel Page 0.
        dataset += (channel.shr(8) and 0xFF).toByte()
        dataset += (channel and 0xFF).toByte()

        // PAN ID
        dataset += TYPE_PANID.toByte()
        dataset += NUM_PANID_BYTES.toByte()
        dataset += (panId.shr(8) and 0xFF).toByte()
        dataset += (panId and 0xFF).toByte()

        // Extended PAN ID
        dataset += TYPE_XPANID.toByte()
        dataset += NUM_XPANID_BYTES.toByte()
        dataset += xpanId

        // Network Master Key
        dataset += TYPE_MASTER_KEY.toByte()
        dataset += NUM_MASTER_KEY_BYTES.toByte()
        dataset += masterKey

        Log.d("TAG", "dataset=" + dataset.toString());
        return dataset
    }

    private fun String.hexToByteArray(): ByteArray {
        return chunked(2).map { byteStr -> byteStr.toUByte(16).toByte() }.toByteArray()
    }


    inner class ConnectionCallback : GenericChipDeviceListener() {
        var _id: String = "";

        override fun onConnectDeviceComplete() {
            Log.d(TAG, "onConnectDeviceComplete")
        }

        override fun onStatusUpdate(status: Int) {
            Log.d(TAG, "Pairing status update: $status")
        }

        override fun onCommissioningComplete(nodeId: Long, errorCode: Int) {
            if (errorCode == STATUS_PAIRING_SUCCESS) {
                //TODO: pairing success
                emitEvent("onPairingSuccess", _id)
            } else {
                //TODO: pairing failed
                emitEvent("onPairingFail", _id)
            }
        }

        override fun onPairingComplete(code: Int) {
            Log.d(TAG, "onPairingComplete: $code")

            if (code != STATUS_PAIRING_SUCCESS) {
                //TODO: pairing failed
            }
        }

        override fun onOpCSRGenerationComplete(csr: ByteArray) {
            Log.d(TAG, String(csr))
        }

        override fun onPairingDeleted(code: Int) {
            Log.d(TAG, "onPairingDeleted: $code")
        }

        override fun onCloseBleComplete() {
            Log.d(TAG, "onCloseBleComplete")
        }

        override fun onError(error: Throwable?) {
            Log.d(TAG, "onError: $error")
        }
    }

}
