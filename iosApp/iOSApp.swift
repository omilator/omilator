import SwiftUI
import UiShared

@main
struct iOSApp: App {
    init() {
        // Workaround: force TSM (Text Services Manager) initialization on the
        // main thread before Compose tries to render text from a background
        // thread. Without this, iOS simulator crashes with
        // _dispatch_assert_queue_fail in TSMGetInputSourceProperty.
        let _ = UITextInputMode.activeInputModes
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    handle(url: url)
                }
        }
    }

    /// URL scheme handler. Supports `omilator://play/<absolute-rom-path>`
    /// (URL-encoded). Drops the slot into PendingPlay so RootViewController's
    /// next composition launches the player directly. Used by
    /// `xcrun simctl openurl` for automated testing.
    private func handle(url: URL) {
        NSLog("[Omilator] openURL: %@", url.absoluteString)
        guard url.scheme?.lowercased() == "omilator" else {
            NSLog("[Omilator] rejected scheme: %@", url.scheme ?? "(nil)")
            return
        }
        let host = url.host?.lowercased() ?? ""
        NSLog("[Omilator] host='%@', path='%@'", host, url.path)
        // URL.host drops the trailing path when there's no authority "//",
        // so we also accept the path-only form: omilator://play/<path>
        var romPath: String
        if host == "play" {
            romPath = url.path
        } else if url.absoluteString.contains("://play/") {
            if let range = url.absoluteString.range(of: "://play/") {
                romPath = String(url.absoluteString[range.upperBound...])
            } else {
                return
            }
        } else {
            NSLog("[Omilator] not a play URL")
            return
        }
        // The payload is an absolute ROM path — keep the leading slash. The
        // old strip turned /Users/... into Users/..., which fopen then read
        // as a path relative to the CWD.
        if !romPath.hasPrefix("/") { romPath = "/" + romPath }
        // URL-decode
        romPath = romPath.removingPercentEncoding ?? romPath
        NSLog("[Omilator] decoded romPath='%@'", romPath)
        if !romPath.isEmpty {
            PendingPlayKt.setPendingPlayRom(path: romPath)
            NSLog("[Omilator] setPendingPlayRom called")
        }
    }
}

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        RootViewControllerKt.RootViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
