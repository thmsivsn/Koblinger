import UIKit
import WebKit

/// Viser spillet (mattekamp.html) i en WKWebView og kobler det til
/// iOS sin egen talegjenkjenning via meldingskanalen «speech».
final class GameViewController: UIViewController, WKScriptMessageHandler {
    private var webView: WKWebView!
    private var speech: SpeechBridge!
    private var speaker: Speaker!

    override func loadView() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.userContentController.add(self, name: "speech")
        config.userContentController.add(self, name: "tts")

        webView = WKWebView(frame: .zero, configuration: config)
        let bg = UIColor(red: 15 / 255, green: 18 / 255, blue: 32 / 255, alpha: 1)
        webView.backgroundColor = bg
        webView.scrollView.backgroundColor = bg
        webView.isOpaque = false
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        view = webView

        speech = SpeechBridge { [weak self] js in
            self?.webView.evaluateJavaScript(js, completionHandler: nil)
        }
        speaker = Speaker { [weak self] js in
            self?.webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        if let url = Bundle.main.url(forResource: "mattekamp", withExtension: "html") {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard let command = message.body as? String else { return }
        if message.name == "tts" { speaker.speak(command); return }
        switch command {
        case "start": speech.start()
        case "stop": speech.stop()
        case "reset": speech.reset()
        default: break
        }
    }
}
