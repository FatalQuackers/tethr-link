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
                            Task { await run { try await account.signInWithGoogle() } }
                        } label: { Label("Sign in with Google", systemImage: "person.crop.circle") }
                    }
                    Section {
                        TextField("Your email", text: $typed)
                            .keyboardType(.emailAddress).textContentType(.emailAddress).textInputAutocapitalization(.never)
                        Button("Email me a sign-in link") {
                            Task {
                                await run { try await account.sendEmailLink(typed) }
                                if status == nil { status = "We emailed a link to \(typed). Open it on this iPhone to finish." }
                            }
                        }
                        .disabled(!typed.contains("@"))
                    }
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
