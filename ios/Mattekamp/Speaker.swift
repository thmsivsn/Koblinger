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
        utterance.voice = bestVoice()
        utterance.rate = 0.5
        utterance.pitchMultiplier = 1.08
        synth.speak(utterance)
    }

    /// Velger den beste norske stemmen som er lastet ned på telefonen
    /// (Premium > Forbedret > Standard). Bedre stemmer lastes ned under
    /// Innstillinger > Tilgjengelighet > Opplest innhold > Stemmer > Norsk bokmål.
    private func bestVoice() -> AVSpeechSynthesisVoice? {
        let norwegian = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("nb") || $0.language.hasPrefix("no") }
        return norwegian.max { $0.quality.rawValue < $1.quality.rawValue } ?? AVSpeechSynthesisVoice(language: "nb-NO")
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.send("window.nativeTTSDone && window.nativeTTSDone()") }
    }
}
