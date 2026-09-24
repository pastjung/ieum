package expo.modules.itdatactile

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 6점 촉각 모듈(아두이노 + HC-06)과의 블루투스 연결.
 *
 * HC-06은 BLE가 아니라 **블루투스 클래식 SPP**라 RFCOMM 소켓으로 붙는다.
 * 폰 설정에서 먼저 페어링(기본 PIN 1234)돼 있어야 하고, 페어링 목록에서 이름으로 고른다.
 * 검색(discovery)은 하지 않는다 — 시간이 오래 걸리고 연결을 방해한다.
 *
 * 프로토콜은 `iot/braille_solenoid/braille_solenoid.ino`와 짝이다.
 * 판정 로직(무엇을 보낼지)은 전부 `@ieum/itda`에 있고, 여기서는 **통신만** 한다.
 */
class ItdaTactileModule : Module() {

  private var socket: BluetoothSocket? = null
  private var input: InputStream? = null
  private var output: OutputStream? = null
  private var connectedName: String? = null

  override fun definition() = ModuleDefinition {
    Name("ItdaTactile")

    /** 페어링된 기기 이름 목록. 모듈 이름이 HC-06이 아닐 때 사용자에게 보여 준다. */
    AsyncFunction("pairedDevices") {
      requireConnectPermission()
      adapter().bondedDevices.orEmpty().map { it.name ?: it.address }
    }

    /** @return 연결된 기기 이름 */
    AsyncFunction("connect") { nameHint: String ->
      openSocket(nameHint)
    }

    /** 한 줄을 보내고 모듈 응답 한 줄을 받는다. 연결이 없으면 먼저 붙는다. */
    AsyncFunction("send") { line: String, nameHint: String ->
      if (!isConnected()) openSocket(nameHint)
      try {
        writeLine(line)
        readLine(REPLY_TIMEOUT_MS)
      } catch (e: IOException) {
        closeQuietly()
        throw TactileException("촉각 모듈과 연결이 끊어졌어요. 다시 말씀해 주세요.", e)
      }
    }

    Function("isConnected") { isConnected() }

    Function("connectedDeviceName") { connectedName }

    Function("disconnect") { closeQuietly() }

    OnDestroy { closeQuietly() }
  }

  // region 소켓

  private fun isConnected(): Boolean = socket?.isConnected == true

  private fun openSocket(nameHint: String): String {
    requireConnectPermission()

    val adapter = adapter()
    if (!adapter.isEnabled) {
      throw TactileException("블루투스가 꺼져 있어요. 켜고 다시 말씀해 주세요.")
    }

    val bonded = adapter.bondedDevices.orEmpty()
    Log.i(TAG, "페어링된 기기: ${bonded.joinToString { "${it.name}(${it.address})" }}")

    val device = bonded.firstOrNull { it.name?.contains(nameHint, ignoreCase = true) == true }
      ?: throw TactileException(
        // 모듈 이름이 HC-06이 아닐 수 있으므로 무엇이 등록돼 있는지 그대로 보여 준다.
        "페어링된 기기 중 이름에 '$nameHint' 가 들어간 걸 찾지 못했어요. " +
          if (bonded.isEmpty()) "폰 블루투스 설정에서 먼저 페어링해 주세요."
          else "등록된 기기: ${bonded.joinToString { it.name ?: it.address }}",
      )

    // 검색 중이면 연결이 느려지거나 실패한다. (BLUETOOTH_SCAN 권한이 없으면 예외 — 필수가 아니라 무시)
    runCatching { adapter.cancelDiscovery() }

    val opened = connectWithFallback(device)
    socket = opened
    input = opened.inputStream
    output = opened.outputStream
    connectedName = device.name ?: nameHint

    Log.i(TAG, "connected to ${device.name} (${device.address})")
    return connectedName ?: nameHint
  }

  /**
   * HC-06은 SDP 서비스 레코드 응답이 부실한 클론이 많아서, 표준 경로(보안 RFCOMM)만으로는
   * `read failed, socket might closed or timeout` 으로 자주 실패한다.
   * 어느 경로가 먹히는지는 폰·모듈 조합마다 달라 세 가지를 순서대로 시도한다.
   *
   * 실패한 소켓은 **반드시 닫아야** 한다. 열어 둔 채 다음 시도를 하면 그것도 같이 막힌다.
   */
  private fun connectWithFallback(device: BluetoothDevice): BluetoothSocket {
    val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
      "보안 RFCOMM" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
      "비보안 RFCOMM" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
      "채널 1 직접" to { rfcommChannelOne(device) },
    )

    val failures = mutableListOf<String>()
    for ((label, open) in attempts) {
      val candidate = try {
        open()
      } catch (e: Exception) {
        failures += "$label 소켓 생성 실패(${e.message})"
        continue
      }

      try {
        candidate.connect()
        Log.i(TAG, "$label 로 연결 성공")
        return candidate
      } catch (e: Exception) {
        runCatching { candidate.close() }
        Log.w(TAG, "$label 연결 실패", e)
        failures += "$label(${e.message})"
        SystemClock.sleep(RETRY_GAP_MS)
      }
    }

    throw TactileException(
      "${device.name} 에 연결하지 못했어요. 모듈 전원이 켜져 있는지, " +
        "다른 기기와 이미 연결돼 있지 않은지 확인해 주세요. — ${failures.joinToString(" / ")}",
    )
  }

  /**
   * 마지막 수단 — SDP 조회를 건너뛰고 RFCOMM 채널 1로 바로 연다.
   * 공개 API가 없어 리플렉션을 쓴다. HC-06 계열에서 이 경로만 되는 경우가 흔하다.
   */
  private fun rfcommChannelOne(device: BluetoothDevice): BluetoothSocket {
    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
    return method.invoke(device, 1) as BluetoothSocket
  }

  private fun adapter() =
    context().getSystemService(BluetoothManager::class.java)?.adapter
      ?: throw TactileException("이 기기에는 블루투스가 없어요.")

  private fun context(): Context =
    appContext.reactContext ?: throw TactileException("앱 컨텍스트를 찾을 수 없어요.")

  private fun requireConnectPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val granted = context().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) {
      throw TactileException("블루투스 권한이 없어요. 설정에서 '근처 기기' 권한을 허용해 주세요.")
    }
  }

  private fun writeLine(line: String) {
    val stream = output ?: throw IOException("not connected")
    stream.write((line + "\n").toByteArray(Charsets.US_ASCII))
    stream.flush()
  }

  /**
   * 모듈 응답 한 줄을 읽는다. 블루투스 소켓 읽기는 중간에 끊을 수 없어
   * `available()`을 폴링하며 [timeoutMs]까지만 기다린다. 못 받으면 지금까지 받은 것.
   */
  private fun readLine(timeoutMs: Long): String {
    val stream = input ?: return ""
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    val line = StringBuilder()
    while (SystemClock.elapsedRealtime() < deadline) {
      if (stream.available() <= 0) {
        SystemClock.sleep(POLL_MS)
        continue
      }
      val byte = stream.read()
      if (byte < 0) throw IOException("stream closed")
      val char = byte.toChar()
      if (char == '\n') break
      if (char != '\r') line.append(char)
    }
    return line.toString().trim()
  }

  private fun closeQuietly() {
    runCatching { input?.close() }
    runCatching { output?.close() }
    runCatching { socket?.close() }
    input = null
    output = null
    socket = null
    connectedName = null
  }

  // endregion

  private companion object {
    const val TAG = "ItdaTactile"

    /** 블루투스 SPP(Serial Port Profile) 표준 UUID — HC-05/HC-06 계열 모두 이 값이다. */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val REPLY_TIMEOUT_MS = 1_500L
    const val POLL_MS = 20L

    /** 한 경로가 실패한 뒤 모듈이 정리할 틈을 준다. 바로 다시 붙으면 또 실패한다. */
    const val RETRY_GAP_MS = 400L
  }
}

/** 사용자에게 그대로 읽어 줄 수 있는 메시지를 담는다. JS에서는 `error.message`로 온다. */
class TactileException(message: String, cause: Throwable? = null) :
  CodedException("ERR_ITDA_TACTILE", message, cause)
