import AVFoundation
import Foundation

/// Leser opp tekst på norsk (hvem sin tur det er, og stykket).
/// Sier fra til siden med `window.nativeTTSDone()` når den er ferdig.
final class Speaker: NSObject, AVSpeechSynthesizerDelegate {
    private let synth = AVSpeechSynthesizer()
    private let send: (String) -> Void

    init(send: @escaping (String) -> Void) {
        self.send = send
        super.init()
        synth.delegate = self
    }

    /// Tom tekst stopper opplesingen.
    func speak(_ text: String) {
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        guard !text.isEmpty else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "nb-NO") ?? AVSpeechSynthesisVoice(language: "no-NO")
        utterance.rate = 0.52
        synth.speak(utterance)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.send("window.nativeTTSDone && window.nativeTTSDone()") }
    }
}
