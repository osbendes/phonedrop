import AppKit
import SwiftUI
import UniformTypeIdentifiers

@main
struct PhoneDropApp: App {
    @StateObject private var state = AppState()

    init() {
        // Debug aid: `PhoneDrop --snapshot out.png` renders the menu window to a PNG and exits.
        if let i = CommandLine.arguments.firstIndex(of: "--snapshot"), i + 1 < CommandLine.arguments.count {
            let out = CommandLine.arguments[i + 1]
            let state = AppState()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                let renderer = ImageRenderer(content: MenuContent().environmentObject(state)
                    .background(Color(nsColor: .windowBackgroundColor)))
                renderer.scale = 2
                if let img = renderer.nsImage, let tiff = img.tiffRepresentation,
                   let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                    try? png.write(to: URL(fileURLWithPath: out))
                }
                exit(0)
            }
        }
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

struct MenuContent: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            if state.devices.isEmpty || state.showPair {
                PairView()
            } else {
                connectedRow
            }
            if !state.transfers.isEmpty {
                section(L("Transferring")) {
                    ForEach(state.transfers) { TransferRow(t: $0) }
                }
            }
            section(L("Received")) {
                if state.recent.isEmpty {
                    Text(verbatim: L("Files from your phone appear here.")).foregroundStyle(.secondary).font(.callout)
                } else {
                    ForEach(state.recent.prefix(5)) { f in
                        Button { state.reveal(f.path) } label: {
                            HStack {
                                Image(nsImage: NSWorkspace.shared.icon(forFile: f.path)).resizable().frame(width: 18, height: 18)
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
                Button(L("Open Folder")) { state.openSaveDir() }.controlSize(.small)
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
                Button(L("Pair a Phone…")) { state.showPair.toggle() }
                Button(L("Change Download Folder…")) { state.chooseSaveDir() }
                Toggle(L("Open at Login"), isOn: Binding(get: { state.launchAtLogin }, set: { state.setLaunchAtLogin($0) }))
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
            Button(L("Pair Another")) { state.showPair = true }.controlSize(.small)
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
            state.showPair = true
        }
    }

    @ViewBuilder
    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: title.uppercased()).font(.caption).fontWeight(.semibold).foregroundStyle(.secondary)
            content()
        }
    }
}

struct PairView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 14) {
                if state.port != 0, let img = AppState.qrImage(state.pairURL, size: 150) {
                    Image(nsImage: img).interpolation(.none).resizable().frame(width: 150, height: 150)
                        .padding(6).background(.white).clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    ProgressView().frame(width: 162, height: 162)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: L("Pair your phone")).font(.headline)
                    step(1, L("Install the PhoneDrop Android app."))
                    step(2, L("Open it and tap “Pair with Mac”."))
                    step(3, L("Scan this code."))
                    Link(L("Get the Android app"), destination: AppState.androidAppURL).font(.callout)
                }
            }
            Text(verbatim: L("Phone and Mac must be on the same Wi-Fi network. Anyone who scans this code can send files to this Mac."))
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(verbatim: "\(n).").foregroundStyle(.secondary).monospacedDigit()
            Text(verbatim: text).fixedSize(horizontal: false, vertical: true)
        }
        .font(.callout)
    }
}

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

struct OutboxView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: L("Send to Phone").uppercased()).font(.caption).fontWeight(.semibold).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 6) {
                if state.outbox.isEmpty {
                    Text(verbatim: L("Drop files here. They appear in the app on your phone."))
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
            Button(L("Add Files…")) { state.addToOutbox() }.controlSize(.small)
        }
    }
}
