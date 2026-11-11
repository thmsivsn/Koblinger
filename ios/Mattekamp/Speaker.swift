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
    func speak(_ text: String, language: String) {
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        guard !text.isEmpty else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = bestVoice(for: language)
        utterance.rate = 0.5
        utterance.pitchMultiplier = 1.08
        synth.speak(utterance)
    }

    /// Velger den beste stemmen for språket som er lastet ned på telefonen
    /// (Premium > Forbedret > Standard). Bedre stemmer lastes ned under
    /// Innstillinger > Tilgjengelighet > Opplest innhold > Stemmer > Norsk bokmål.
    private func bestVoice(for language: String) -> AVSpeechSynthesisVoice? {
        let prefix = String(language.prefix(2))
        let all = AVSpeechSynthesisVoice.speechVoices()
        // Først nøyaktig språk og land (f.eks. zh-CN), deretter samme språk. Norsk heter både «nb» og «no».
        let exact = all.filter { $0.language == language }
        let same = all.filter { $0.language.hasPrefix(prefix) || (prefix == "nb" && $0.language.hasPrefix("no")) }
        let pool = exact.isEmpty ? same : exact
        return pool.max { $0.quality.rawValue < $1.quality.rawValue } ?? AVSpeechSynthesisVoice(language: language)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.send("window.nativeTTSDone && window.nativeTTSDone()") }
    }
}
