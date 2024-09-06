import AVFoundation
import Foundation

class RecorderStreamDelegate: NSObject, AudioRecordingStreamDelegate {
  private var audioEngine: AVAudioEngine?
  private var amplitude: Float = -160.0
  private let bus = 0

  func start(config: RecordConfig, recordEventHandler: RecordStreamHandler) throws {
    let audioEngine = AVAudioEngine()

    #if os(iOS)
      print("calling initAVAudioSession")
      try initAVAudioSession(config: config)
    #else
      // set input device to the node
      if let deviceId = config.device?.id,
         let inputDeviceId = getAudioDeviceIDFromUID(uid: deviceId)
      {
        do {
          try audioEngine.inputNode.auAudioUnit.setDeviceID(inputDeviceId)
        } catch {
          throw RecorderError.error(
            message: "Failed to start recording",
            details: "Setting input device: \(deviceId) \(error)"
          )
        }
      }
    #endif

    // Set up AGC & echo cancel
    initEffects(config: config, audioEngine: audioEngine)

    let srcFormat = audioEngine.inputNode.outputFormat(forBus: 0)

    let dstFormat = AVAudioFormat(
      commonFormat: .pcmFormatInt16,
      sampleRate: Double(config.sampleRate),
      channels: AVAudioChannelCount(config.numChannels),
      interleaved: true
    )

    guard let dstFormat = dstFormat else {
      throw RecorderError.error(
        message: "Failed to start recording",
        details: "Format is not supported: \(config.sampleRate)Hz - \(config.numChannels) channels."
      )
    }

    guard let converter = AVAudioConverter(from: srcFormat, to: dstFormat) else {
      throw RecorderError.error(
        message: "Failed to start recording",
        details: "Format conversion is not possible."
      )
    }
    converter.sampleRateConverterQuality = AVAudioQuality.high.rawValue

    audioEngine.inputNode.installTap(onBus: bus, bufferSize: 320, format: srcFormat) { buffer, _ in
      self.stream(
        buffer: buffer,
        dstFormat: dstFormat,
        converter: converter,
        recordEventHandler: recordEventHandler
      )
    }

    audioEngine.prepare()
    try audioEngine.start()

    self.audioEngine = audioEngine
  }

  func stop(completionHandler: @escaping (String?) -> Void) {
    audioEngine?.inputNode.removeTap(onBus: bus)
    audioEngine?.stop()
    audioEngine = nil
    clearAVAudioSession()

    completionHandler(nil)
  }

  func pause() {
    audioEngine?.pause()
  }

  func resume() throws {
    try audioEngine?.start()
  }

  func cancel() throws {
    stop { _ in }
  }

  func getAmplitude() -> Float {
    return amplitude
  }

  private func updateAmplitude(_ samples: [Int16]) {
    var maxSample: Float = -160.0

    for sample in samples {
      let curSample = abs(Float(sample))
      if curSample > maxSample {
        maxSample = curSample
      }
    }

    amplitude = 20 * (log(maxSample / 32767.0) / log(10))
  }

  func dispose() {
    stop { _ in }
  }

  // Little endian
  private func convertInt16toUInt8(_ samples: [Int16]) -> [UInt8] {
    var bytes: [UInt8] = []

    for sample in samples {
      bytes.append(UInt8(sample & 0x00FF))
      bytes.append(UInt8(sample >> 8 & 0x00FF))
    }

    return bytes
  }

  private func stream(
    buffer: AVAudioPCMBuffer,
    dstFormat: AVAudioFormat,
    converter: AVAudioConverter,
    recordEventHandler: RecordStreamHandler
  ) {
    let inputCallback: AVAudioConverterInputBlock = { count, outStatus in
      print("inputCallback count \(count)")
      outStatus.pointee = .haveData
      return buffer
    }

    // Determine frame capacity
    let capacity = (UInt32(dstFormat.sampleRate) * dstFormat.channelCount * buffer.frameLength) / (UInt32(buffer.format.sampleRate) * buffer.format.channelCount)

    // Destination buffer
    guard let convertedBuffer = AVAudioPCMBuffer(pcmFormat: dstFormat, frameCapacity: capacity) else {
      print("Unable to create output buffer")
      stop { _ in }
      return
    }

    // Convert input buffer (resample, num channels)
    var error: NSError? = nil
    converter.convert(to: convertedBuffer, error: &error, withInputFrom: inputCallback)
    if let error = error {
      print(error.localizedDescription)
      return
    }

    if let channelData = convertedBuffer.int16ChannelData {
      // Fill samples
      let channelDataPointer = channelData.pointee
      let samples = stride(from: 0,
                           to: Int(convertedBuffer.frameLength),
                           by: buffer.stride).map { channelDataPointer[$0] }

      // Update current amplitude
      updateAmplitude(samples)

      // Send bytes
      if let eventSink = recordEventHandler.eventSink {
        let bytes = Data(_: convertInt16toUInt8(samples))

        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        let dateString: String = dateFormatter.string(from: Date())
        print("\(Date()) sink bytes: \(bytes)")

        DispatchQueue.main.async {
          eventSink(FlutterStandardTypedData(bytes: bytes))
        }
      }
    }
  }

  private func initEffects(config: RecordConfig, audioEngine: AVAudioEngine) {
    let propsize = UInt32(MemoryLayout<Bool>.size)
    var autoGain = config.autoGain
    var echoCancel = config.echoCancel

    AudioUnitSetProperty(audioEngine.inputNode.audioUnit!,
                         kAUVoiceIOProperty_BypassVoiceProcessing,
                         kAudioUnitScope_Global,
                         AudioUnitElement(bus),
                         &echoCancel,
                         propsize)

    AudioUnitSetProperty(audioEngine.inputNode.audioUnit!,
                         kAUVoiceIOProperty_VoiceProcessingEnableAGC,
                         kAudioUnitScope_Global,
                         AudioUnitElement(bus),
                         &autoGain,
                         propsize)
  }
}
