import AVFoundation
import Foundation
import Speech

/// Talegjenkjenning med SFSpeechRecognizer.
/// Spillet velger språk per spiller (`setLanguages`). Har spilleren flere språk,
/// lytter vi på opptil to samtidig – hver i sin egen «slot» som mates med samme lyd.
/// Sender tekst til siden som `window.nativeSpeech(tekst, ferdig, alternativ)`
/// og status som `window.nativeSpeechState("listening" | "idle" | "denied" | "unavailable" | "error")`.
final class SpeechBridge {
    private let send: (String) -> Void
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var slots: [Slot] = []
    private var locales: [String] = ["nb-NO"]
    private var wanted = false

    init(send: @escaping (String) -> Void) {
        self.send = send
        rebuildSlots()
    }

    // MARK: Kommandoer fra siden

    func setLanguages(_ codes: [String]) {
        let wantedCodes = Array(codes.filter { !$0.isEmpty }.prefix(2))
        guard !wantedCodes.isEmpty, wantedCodes != locales else { return }
        locales = wantedCodes
        slots.forEach { $0.end() }
        rebuildSlots()
        if wanted && engine.isRunning { startSlots() }
    }

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
        slots.forEach { $0.end() }
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        state("idle")
    }

    /// Ny oppgave: start en fersk gjenkjenning så gammel tale ikke henger igjen.
    func reset() {
        guard wanted, engine.isRunning else { return }
        slots.forEach { $0.end() }
        startSlots()
    }

    // MARK: Internt

    private func rebuildSlots() {
        let built = locales.compactMap { code -> Slot? in
            guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: code)) else { return nil }
            return Slot(recognizer: recognizer, owner: self)
        }
        lock.lock(); slots = built; lock.unlock()
    }

    private func begin() {
        guard wanted else { return }
        guard slots.contains(where: { $0.recognizer.isAvailable }) else { state("unavailable"); return }
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
                    let requests = self.slots.compactMap { $0.request }
                    self.lock.unlock()
                    requests.forEach { $0.append(buffer) }
                }
                engine.prepare()
                try engine.start()
            }
            slots.forEach { $0.end() }
            startSlots()
        } catch {
            state("error")
        }
    }

    private func startSlots() {
        var any = false
        for slot in slots where slot.recognizer.isAvailable {
            slot.start()
            any = true
        }
        state(any ? "listening" : "unavailable")
    }

    fileprivate var isWanted: Bool { wanted && engine.isRunning }

    fileprivate func setRequest(_ request: SFSpeechAudioBufferRecognitionRequest?, for slot: Slot) {
        lock.lock(); slot.request = request; lock.unlock()
    }

    fileprivate func emit(_ text: String, final: Bool, alt: Bool) {
        guard let data = try? JSONEncoder().encode(text), let json = String(data: data, encoding: .utf8) else { return }
        send("window.nativeSpeech && window.nativeSpeech(\(json), \(final), \(alt))")
    }

    private func state(_ s: String) {
        send("window.nativeSpeechState && window.nativeSpeechState('\(s)')")
    }

    /// Én gjenkjenner for ett språk.
    fileprivate final class Slot {
        let recognizer: SFSpeechRecognizer
        private weak var owner: SpeechBridge?
        var request: SFSpeechAudioBufferRecognitionRequest?
        private var task: SFSpeechRecognitionTask?
        private var generation = 0

        init(recognizer: SFSpeechRecognizer, owner: SpeechBridge) {
            self.recognizer = recognizer
            self.owner = owner
        }

        func start() {
            guard let owner = owner else { return }
            generation += 1
            let gen = generation
            let req = SFSpeechAudioBufferRecognitionRequest()
            req.shouldReportPartialResults = true
            if #available(iOS 16, *) { req.addsPunctuation = false }
            owner.setRequest(req, for: self)

            task = recognizer.recognitionTask(with: req) { [weak self] result, error in
                DispatchQueue.main.async {
                    guard let self = self, gen == self.generation, let owner = self.owner else { return }
                    if let result = result {
                        owner.emit(result.bestTranscription.formattedString, final: result.isFinal, alt: false)
                        for alt in result.transcriptions.dropFirst().prefix(4) {
                            owner.emit(alt.formattedString, final: false, alt: true)
                        }
                        if result.isFinal { self.restartSoon() }
                    } else if error != nil {
                        self.restartSoon()
                    }
                }
            }
        }

        func end() {
            generation += 1 // gjør tilbakekall fra den gamle oppgaven ugyldige
            owner?.setRequest(nil, for: self)
            task?.cancel()
            task = nil
        }

        private func restartSoon() {
            guard let owner = owner, owner.isWanted else { return }
            end()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                guard let self = self, let owner = self.owner, owner.isWanted, self.task == nil else { return }
                self.start()
            }
        }
    }
}
