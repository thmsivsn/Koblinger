import AVFoundation
import Foundation
import Speech

/// Norsk talegjenkjenning (nb-NO) med SFSpeechRecognizer.
/// Sender tekst til siden som `window.nativeSpeech(tekst, ferdig, alternativ)`
/// og status som `window.nativeSpeechState("listening" | "idle" | "denied" | "unavailable" | "error")`.
final class SpeechBridge {
    private let send: (String) -> Void
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "nb-NO"))
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var wanted = false
    private var generation = 0

    init(send: @escaping (String) -> Void) {
        self.send = send
    }

    // MARK: Kommandoer fra siden

    func start() {
        wanted = true
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                guard status == .authorized else { self.state("denied"); return }
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        guard granted else { self.state("denied"); return }
                        self.begin()
                    }
                }
            }
        }
    }

    func stop() {
        wanted = false
        endTask()
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        state("idle")
    }

    /// Ny oppgave: start en fersk gjenkjenning så gammel tale ikke henger igjen.
    func reset() {
        guard wanted, engine.isRunning else { return }
        endTask()
        startTask()
    }

    // MARK: Internt

    private func begin() {
        guard wanted else { return }
        guard let recognizer = recognizer, recognizer.isAvailable else { state("unavailable"); return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            if !engine.isRunning {
                let input = engine.inputNode
                let format = input.outputFormat(forBus: 0)
                input.removeTap(onBus: 0)
                input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                    guard let self = self else { return }
                    self.lock.lock()
                    let req = self.request
                    self.lock.unlock()
                    req?.append(buffer)
                }
                engine.prepare()
                try engine.start()
            }
            endTask()
            startTask()
        } catch {
            state("error")
        }
    }

    private func startTask() {
        guard let recognizer = recognizer else { return }
        generation += 1
        let gen = generation

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if #available(iOS 16, *) { req.addsPunctuation = false }
        req.contextualStrings = ["seksti fire", "minus", "hundre", "tusen"]
        lock.lock(); request = req; lock.unlock()

        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self = self, gen == self.generation else { return }
                if let result = result {
                    self.emit(result.bestTranscription.formattedString, final: result.isFinal, alt: false)
                    for alt in result.transcriptions.dropFirst().prefix(4) {
                        self.emit(alt.formattedString, final: false, alt: true)
                    }
                    if result.isFinal { self.restartSoon() }
                } else if error != nil {
                    self.restartSoon()
                }
            }
        }
        state("listening")
    }

    private func endTask() {
        generation += 1 // gjør tilbakekall fra den gamle oppgaven ugyldige
        lock.lock()
        request?.endAudio()
        request = nil
        lock.unlock()
        task?.cancel()
        task = nil
    }

    private func restartSoon() {
        guard wanted else { return }
        endTask()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            guard let self = self, self.wanted, self.engine.isRunning, self.task == nil else { return }
            self.startTask()
        }
    }

    private func emit(_ text: String, final: Bool, alt: Bool) {
        guard let data = try? JSONEncoder().encode(text), let json = String(data: data, encoding: .utf8) else { return }
        send("window.nativeSpeech && window.nativeSpeech(\(json), \(final), \(alt))")
    }

    private func state(_ s: String) {
        send("window.nativeSpeechState && window.nativeSpeechState('\(s)')")
    }
}
