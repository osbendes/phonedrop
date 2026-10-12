import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Shows the same content as the menu bar popover in a regular window. The menu bar icon can be
/// hidden (e.g. behind the notch when the menu bar is full), so launching the app opens this window.
/// TR: Menü çubuğu penceresindeki içeriğin aynısını normal bir pencerede gösterir. Menü çubuğu simgesi
/// gizlenebildiği için (ör. çubuk doluyken çentiğin arkasında) uygulama açılınca bu pencere açılır.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Finder's right-click > Services > "Send with PhoneDrop" (declared in Info.plist)
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        showWindow()
        // Screenshot aid: `--demo --snapshot out.png [--pair|--pair-app]` saves the window as a PNG and quits
        if let out = Demo.arg("--snapshot") {
            let args = CommandLine.arguments
            let pair = args.contains("--pair") || args.contains("--pair-app")
            if pair { PairWindow.show(appQR: args.contains("--pair-app")) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                // The frame view, so the title bar is in the picture
                if let view = (pair ? PairWindow.window : self.window)?.contentView?.superview,
                   let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) {
                    view.cacheDisplay(in: view.bounds, to: rep)
                    try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: out))
                }
                exit(0)
            }
        }
        // Test aid: `open PhoneDrop.app --args --send` presses Send right after launch
        if CommandLine.arguments.contains("--send") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { AppState.shared.sendOutbox() }
        }
    }

    /// "Open With > PhoneDrop" and files dropped on the Dock icon.
    /// TR: "Birlikte Aç > PhoneDrop" ve Dock simgesine bırakılan dosyalar.
    func application(_ application: NSApplication, open urls: [URL]) {
        queueForPhone(urls)
    }

    /// Services menu entry point; the selector name matches NSMessage in Info.plist.
    /// TR: Hizmetler menüsünün giriş noktası; seçici adı Info.plist'teki NSMessage ile eşleşir.
    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let urls = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        queueForPhone(urls)
    }

    private func queueForPhone(_ urls: [URL]) {
        let files = urls.filter { $0.isFileURL }
        guard !files.isEmpty else { return }
        AppState.shared.addToOutbox(files)
        showWindow()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showWindow()
        return true
    }

    func showWindow() {
        if window == nil {
            let host = NSHostingController(rootView: MenuContent().environmentObject(AppState.shared))
            let w = NSWindow(contentViewController: host)
            w.title = "PhoneDrop"
            w.styleMask = [.titled, .closable, .miniaturizable]
            w.isReleasedWhenClosed = false
            w.center()
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

/// App entry point: a menu bar item, plus the regular window that AppDelegate manages.
/// TR: Uygulamanın giriş noktası: bir menü çubuğu öğesi ve AppDelegate'in yönettiği normal pencere.
@main
struct PhoneDropApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    // Created eagerly: the server must start even if the menu bar icon is never drawn.
    @StateObject private var state = AppState.shared

    init() {
        _ = AppState.shared
    }

    var body: some Scene {
        MenuBarExtra {
            MenuContent().environmentObject(state)
        } label: {
            Image(systemName: state.transfers.isEmpty ? "iphone.and.arrow.forward" : "arrow.down.circle.fill")
        }
        .menuBarExtraStyle(.window)
    }
}

/// The main view, shared by the menu bar popover and the window.
/// TR: Menü çubuğu penceresi ile normal pencerenin paylaştığı ana görünüm.
struct MenuContent: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            if let ssid = state.directSSID {
                HStack(spacing: 8) {
                    Image(systemName: "antenna.radiowaves.left.and.right").foregroundStyle(.blue)
                    Text(verbatim: String(format: L("Connected directly to your phone (%@)"), ssid)).font(.callout)
                }
            }
            // With no phone paired yet the code is the main thing to show; afterwards it lives in its own window
            if state.devices.isEmpty {
                PairView()
            } else {
                connectedRow
            }
            if !state.transfers.isEmpty {
                section(L("Transferring")) {
                    ForEach(state.transfers) { TransferRow(t: $0) }
                }
            }
            section(L("Save to")) { SaveLocationRow() }
            section(L("Received"), action: state.recent.isEmpty ? nil : (L("Clear"), { state.clearRecent() })) {
                if state.recent.isEmpty {
                    Text(verbatim: L("Files from your phone appear here.")).foregroundStyle(.secondary).font(.callout)
                } else {
                    ForEach(state.recent.prefix(5)) { f in
                        Button { state.reveal(f.path) } label: {
                            HStack {
                                Image(nsImage: NSWorkspace.shared.icon(for: UTType(filenameExtension: (f.name as NSString).pathExtension) ?? .data)).resizable().frame(width: 18, height: 18)
                                Text(verbatim: f.name).lineLimit(1).truncationMode(.middle)
                                Spacer()
                                Text(verbatim: Fmt.bytes(f.size)).foregroundStyle(.secondary).font(.caption).monospacedDigit()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help(L("Show in Finder"))
                    }
                }
            }
            OutboxView()
        }
        .padding(16)
        .frame(width: 360)
    }

    private var header: some View {
        HStack {
            Text(verbatim: "PhoneDrop").font(.headline)
            Spacer()
            Menu {
                Button(L("Pair a Phone…")) { PairWindow.show(appQR: false) }
                Button(L("Get the Android App…")) { PairWindow.show(appQR: true) }
                Button(L("Change Download Folder…")) { state.chooseSaveDir() }
                Toggle(L("Allow Direct Connection on Wi-Fi"), isOn: $state.allowDirectOnWiFi)
                Toggle(L("Open at Login"), isOn: Binding(get: { state.launchAtLogin }, set: { state.setLaunchAtLogin($0) }))
                Divider()
                Picker(L("Language"), selection: $state.language) {
                    Text(verbatim: L("System")).tag("system")
                    Text(verbatim: "English").tag("en")
                    Text(verbatim: "Türkçe").tag("tr")
                }
                .pickerStyle(.menu)
                Picker(L("Appearance"), selection: $state.theme) {
                    Text(verbatim: L("System")).tag("system")
                    Text(verbatim: L("Light")).tag("light")
                    Text(verbatim: L("Dark")).tag("dark")
                }
                .pickerStyle(.menu)
                Divider()
                Button(L("Reset Pairing…")) { confirmReset() }
                Divider()
                Button(L("Quit PhoneDrop")) { NSApp.terminate(nil) }
            } label: {
                Image(systemName: "gearshape")
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
        }
    }

    private var connectedRow: some View {
        HStack(spacing: 8) {
            Circle().fill(.green).frame(width: 8, height: 8)
            Text(verbatim: String(format: L("Ready · %@"), state.devices.first ?? ""))
                .font(.callout).lineLimit(1)
            Spacer()
            Button(L("Pair Another")) { PairWindow.show(appQR: false) }.controlSize(.small)
        }
    }

    private func confirmReset() {
        let alert = NSAlert()
        alert.messageText = L("Reset pairing?")
        alert.informativeText = L("All phones will be disconnected and need to scan the QR code again.")
        alert.addButton(withTitle: L("Reset"))
        alert.addButton(withTitle: L("Cancel"))
        alert.alertStyle = .warning
        NSApp.activate(ignoringOtherApps: true)
        if alert.runModal() == .alertFirstButtonReturn {
            state.resetPairing()
        }
    }

    @ViewBuilder
    private func section<Content: View>(_ title: String, action: (String, () -> Void)? = nil,
                                        @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(verbatim: title.uppercased()).font(.caption).fontWeight(.semibold).foregroundStyle(.secondary)
                Spacer()
                if let action {
                    Button(action.0, action: action.1).buttonStyle(.link).font(.caption)
                }
            }
            content()
        }
    }
}

/// Where incoming files go, with a menu to change it.
/// TR: Gelen dosyaların gideceği yer ve onu değiştirme menüsü.
struct SaveLocationRow: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "folder.fill").foregroundStyle(.blue)
            Button { state.openSaveDir() } label: {
                Text(verbatim: AppState.displayPath(state.saveDir.path)).lineLimit(1).truncationMode(.head)
            }
            .buttonStyle(.plain)
            .help(L("Open Folder"))
            Spacer()
            Menu(L("Change")) {
                Button(L("Choose Folder…")) { state.chooseSaveDir() }
                if state.saveDir.path != AppState.defaultSaveDir.path {
                    Button(String(format: L("Default (%@)"), AppState.displayPath(AppState.defaultSaveDir.path))) {
                        state.setSaveDir(AppState.defaultSaveDir)
                    }
                }
                if !state.recentDirs.isEmpty {
                    Divider()
                    ForEach(state.recentDirs, id: \.self) { path in
                        Button(AppState.displayPath(path)) { state.setSaveDir(URL(fileURLWithPath: path)) }
                    }
                }
                Divider()
                Button(L("Open Folder")) { state.openSaveDir() }
            }
            .controlSize(.small)
            .fixedSize()
        }
    }
}

/// The pairing / app-download codes in their own closable window, so the main view stays as it is.
/// TR: Eşleştirme / uygulama indirme kodları kendi kapatılabilir penceresinde durur; ana görünüm olduğu
/// gibi kalır.
@MainActor
enum PairWindow {
    private(set) static var window: NSWindow?

    static func show(appQR: Bool) {
        AppState.shared.qrShowsApp = appQR
        if window == nil {
            let host = NSHostingController(rootView: PairSheet().environmentObject(AppState.shared))
            let w = NSPanel(contentViewController: host)
            w.title = L("Pair a Phone")
            w.styleMask = [.titled, .closable]
            w.isReleasedWhenClosed = false
            w.center()
            window = w
        }
        window?.title = L("Pair a Phone")
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    static func close() { window?.close() }
}

struct PairSheet: View {
    var body: some View {
        VStack(alignment: .trailing, spacing: 12) {
            PairView()
            Button(L("Done")) { PairWindow.close() }.keyboardShortcut(.defaultAction)
        }
        .padding(16)
        .frame(width: 380)
    }
}

/// The pairing QR code or the app-download QR code, with instructions.
/// TR: Eşleştirme karekodu ya da uygulama indirme karekodu, yönergeleriyle birlikte.
struct PairView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Picker("", selection: $state.qrShowsApp) {
                Text(verbatim: L("Pair")).tag(false)
                Text(verbatim: L("Get the App")).tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            HStack(alignment: .top, spacing: 14) {
                if state.qrShowsApp, let img = AppState.qrImage(AppState.androidApkURL, size: 150) {
                    qr(img)
                } else if state.port != 0, let img = AppState.qrImage(state.pairURL, size: 150) {
                    qr(img)
                } else {
                    ProgressView().frame(width: 162, height: 162)
                }
                if state.qrShowsApp {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(verbatim: L("Get the Android app")).font(.headline).fixedSize(horizontal: false, vertical: true)
                        Text(verbatim: L("Scan this code with your phone's camera to download PhoneDrop, then install it."))
                            .font(.callout).fixedSize(horizontal: false, vertical: true)
                        Link(L("Open the download page"), destination: AppState.androidAppURL).font(.callout)
                    }
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(verbatim: L("Pair your phone")).font(.headline)
                        step(1, L("Install the PhoneDrop Android app."))
                        step(2, L("Open it and tap “Pair with Mac”."))
                        step(3, L("Scan this code."))
                    }
                }
            }
            if !state.qrShowsApp {
                Text(verbatim: L("Phone and Mac must be on the same Wi-Fi network. Anyone who scans this code can send files to this Mac."))
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }

    private func qr(_ img: NSImage) -> some View {
        Image(nsImage: img).interpolation(.none).resizable().frame(width: 150, height: 150)
            .padding(6).background(.white).clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(verbatim: "\(n).").foregroundStyle(.secondary).monospacedDigit()
            Text(verbatim: text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.callout)
    }
}

/// One transfer in progress: name, progress bar, speed and time left.
/// TR: Süren tek bir aktarım: ad, ilerleme çubuğu, hız ve kalan süre.
struct TransferRow: View {
    let t: TransferSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: t.direction == .incoming ? "arrow.down" : "arrow.up").foregroundStyle(.secondary)
                Text(verbatim: t.name).lineLimit(1).truncationMode(.middle)
                Spacer()
                Text(verbatim: speedText).font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
            ProgressView(value: Double(t.received), total: Double(max(t.size, 1)))
            Text(verbatim: "\(Fmt.bytes(t.received)) / \(Fmt.bytes(t.size))")
                .font(.caption).foregroundStyle(.secondary).monospacedDigit()
        }
    }

    private var speedText: String {
        guard t.speed > 1 else { return L("Waiting…") }
        let eta = Fmt.eta(Double(t.size - t.received) / t.speed)
        return "\(Fmt.bytes(Int64(t.speed)))/s" + (eta.isEmpty ? "" : " · \(eta)")
    }
}

/// The "Send to Phone" list: drop target, Add Files, Clear All and Send.
/// TR: "Telefona Gönder" listesi: bırakma alanı, Dosya Ekle, Tümünü Temizle ve Gönder.
struct OutboxView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: L("Send to Phone").uppercased()).font(.caption).fontWeight(.semibold).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 6) {
                if state.outbox.isEmpty {
                    Text(verbatim: L("Drop files here. They appear in the app on your phone and leave this list once downloaded."))
                        .font(.callout).foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 44)
                        .multilineTextAlignment(.center)
                } else {
                    ForEach(state.outbox) { f in
                        HStack {
                            Text(verbatim: f.name).lineLimit(1).truncationMode(.middle)
                            Spacer()
                            Button { state.removeFromOutbox(f) } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.plain).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 8)
                .strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [4]))
                .foregroundStyle(state.dropTargeted ? Color.accentColor : Color.secondary.opacity(0.5)))
            .onDrop(of: [.fileURL], isTargeted: $state.dropTargeted) { providers in
                for p in providers {
                    _ = p.loadObject(ofClass: URL.self) { url, _ in
                        if let url { Task { @MainActor in state.addToOutbox([url]) } }
                    }
                }
                return true
            }
            HStack {
                Button(L("Add Files…")) { state.addToOutbox() }
                if !state.outbox.isEmpty {
                    Button(L("Clear All")) { state.clearOutbox() }
                }
                Spacer()
                Button(state.sending ? L("Waiting for Phone…") : L("Send")) { state.sendOutbox() }
                    .buttonStyle(.borderedProminent)
                    .disabled(state.outbox.isEmpty || state.sending)
            }
            .controlSize(.small)
            if state.sending {
                Text(verbatim: L("Your phone downloads these by itself if Bluetooth is on. If nothing happens, open PhoneDrop on the phone."))
                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
