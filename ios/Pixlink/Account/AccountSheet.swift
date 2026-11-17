import SwiftUI

/// "Add from my account": sign in (optional) and pick a PC, no QR code needed.
struct AccountSheet: View {
    @ObservedObject private var account = Account.shared
    @Environment(\.dismiss) private var dismiss
    let onPick: (PairingInfo) -> Void

    @State private var pcs: [Account.PC]?
    @State private var typed = ""
    @State private var status: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            List {
                if account.signedIn {
                    Section {
                        if let pcs {
                            if pcs.isEmpty {
                                Text("No PCs in your account yet. On your PC, open Tethr Link → Settings → Account and sign in with the same account.")
                                    .foregroundStyle(.secondary)
                            }
                            ForEach(pcs) { pc in
                                Button {
                                    if let info = pc.pairingInfo { dismiss(); onPick(info) }
                                } label: {
                                    Label {
                                        VStack(alignment: .leading) {
                                            Text(pc.name)
                                            Text(pc.pairingInfo == nil ? "Open Tethr Link on this PC, then pull to refresh" : "Tap to add")
                                                .font(.caption).foregroundStyle(.secondary)
                                        }
                                    } icon: { Image(systemName: "desktopcomputer") }
                                }
                                .disabled(pc.pairingInfo == nil)
                            }
                        } else {
                            ProgressView()
                        }
                    } header: { Text("Your PCs") } footer: { Text("Signed in as \(account.email ?? "")") }
                    Section { Button("Sign out", role: .destructive) { Task { await account.signOut(); pcs = nil } } }
                } else {
                    Section {
                        Text("Optional. Tethr Link works fully without an account. Signing in lets you add a PC from your account, no QR code needed.")
                            .font(.callout).foregroundStyle(.secondary)
                        Button {
                            Task { await run { try await account.signIn(with: "google") } }
                        } label: { Label("Sign in with Google", systemImage: "person.crop.circle") }
                        Button {
                            Task { await run { try await account.signIn(with: "discord") } }
                        } label: { Label("Sign in with Discord", systemImage: "bubble.left.and.bubble.right") }
                        Button {
                            Task { await run { try await account.signIn(with: "github") } }
                        } label: { Label("Sign in with GitHub", systemImage: "chevron.left.forwardslash.chevron.right") }
                    }
                    // Email sign-in comes back once Tethr Link has its own email domain (Account.sendEmailLink).
                }
                if let status { Section { Text(status).font(.footnote).foregroundStyle(.secondary) } }
            }
            .disabled(busy)
            .navigationTitle("Add from my account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .refreshable { await load() }
            .task(id: account.email) { await load() }
        }
    }

    private func load() async {
        guard account.signedIn else { return }
        do { pcs = try await account.pcs() } catch { status = error.localizedDescription; pcs = [] }
    }

    private func run(_ work: () async throws -> Void) async {
        busy = true; status = nil
        do { try await work() } catch { status = error.localizedDescription }
        busy = false
    }
}
