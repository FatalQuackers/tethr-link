import AuthenticationServices
import CryptoKit
import Foundation
import Security
import UIKit

/// Optional Tethr Link account (Supabase Auth). Nothing needs it; signing in lets this iPhone
/// add a PC from the same account without scanning its QR code. The session is kept in the Keychain.
@MainActor
final class Account: NSObject, ObservableObject {
    static let shared = Account()

    static let base = "https://zljbvimcfrpeouscccvw.supabase.co"
    /// Publishable key: safe to ship. Every table only shows rows to their owner (row-level security).
    private static let key = "sb_publishable_0Rk8-U2mMEexU9uivUjlIA_242OB-YU"
    static let callback = "pixlink://auth-callback"

    struct PC: Identifiable {
        let id: String, name: String, fingerprint: String, host: String?, port: Int, token: String?
        var pairingInfo: PairingInfo? {
            guard let host, let token else { return nil }
            var c = URLComponents()
            c.scheme = "pixlink"; c.host = "pair"
            c.queryItems = [
                .init(name: "v", value: "1"), .init(name: "host", value: host), .init(name: "port", value: String(port)),
                .init(name: "token", value: token), .init(name: "fp", value: "SHA256:\(fingerprint)"), .init(name: "name", value: name),
            ]
            return c.url.flatMap { PairingInfo($0.absoluteString) }
        }
    }

    struct Failure: LocalizedError { let errorDescription: String? }

    @Published private(set) var email: String?
    var signedIn: Bool { email != nil }

    private var session: [String: String] = [:]
    private var verifier: String?
    private var webSession: ASWebAuthenticationSession?
    private var anchor: AnchorProvider?

    override init() {
        super.init()
        session = Keychain.load() ?? [:]
        if session["refresh"] != nil { email = session["email"] ?? "your account" }
    }

    // MARK: Sign in

    func signInWithGoogle() async throws {
        let v = Self.newVerifier()
        verifier = v
        let url = URL(string: "\(Self.base)/auth/v1/authorize?provider=google&redirect_to=\(Self.enc(Self.callback))&code_challenge=\(Self.challenge(v))&code_challenge_method=s256")!
        let back: URL = try await withCheckedThrowingContinuation { cont in
            let s = ASWebAuthenticationSession(url: url, callbackURLScheme: "pixlink") { url, error in
                if let url { cont.resume(returning: url) }
                else { cont.resume(throwing: Failure(errorDescription: (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin ? "Sign-in was cancelled." : error?.localizedDescription ?? "Sign-in was cancelled.")) }
            }
            let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
            anchor = AnchorProvider(window: window ?? ASPresentationAnchor())
            s.presentationContextProvider = anchor
            s.prefersEphemeralWebBrowserSession = false
            webSession = s
            s.start()
        }
        try await finish(back)
    }

    /// Emails a sign-in link. Opening it on this iPhone comes back to Tethr Link (onOpenURL → finish).
    func sendEmailLink(_ email: String) async throws {
        let v = Self.newVerifier()
        verifier = v
        UserDefaults.standard.set(v, forKey: "account.verifier")
        _ = try await request("POST", "/auth/v1/otp?redirect_to=\(Self.enc(Self.callback))", body: [
            "email": email.trimmingCharacters(in: .whitespaces), "create_user": true,
            "code_challenge": Self.challenge(v), "code_challenge_method": "s256",
        ], token: nil)
    }

    /// Finishes with the pixlink://auth-callback link.
    func finish(_ url: URL) async throws {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let value = { (k: String) in items.first { $0.name == k }?.value }
        guard let code = value("code") else { throw Failure(errorDescription: value("error_description") ?? value("error") ?? "Sign-in was cancelled.") }
        guard let v = verifier ?? UserDefaults.standard.string(forKey: "account.verifier") else {
            throw Failure(errorDescription: "Start signing in again from Tethr Link.")
        }
        let t = try await request("POST", "/auth/v1/token?grant_type=pkce", body: ["auth_code": code, "code_verifier": v], token: nil)
        verifier = nil
        UserDefaults.standard.removeObject(forKey: "account.verifier")
        save(t)
    }

    func signOut() async {
        let token = session["access"]
        session = [:]
        Keychain.save([:])
        email = nil
        if let token { _ = try? await request("POST", "/auth/v1/logout", body: [:], token: token) }
    }

    // MARK: Data

    func pcs() async throws -> [PC] {
        guard let token = try await accessToken() else { return [] }
        let data = try await raw("GET", "/rest/v1/pcs?select=pc_id,name,fingerprint,host,port,pair_token&order=updated_at.desc", body: nil, token: token)
        let rows = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] ?? []
        return rows.compactMap { r in
            guard let id = r["pc_id"] as? String, let fp = r["fingerprint"] as? String else { return nil }
            return PC(id: id, name: r["name"] as? String ?? "PC", fingerprint: fp.uppercased(), host: r["host"] as? String,
                      port: r["port"] as? Int ?? 47100, token: r["pair_token"] as? String)
        }
    }

    // MARK: Tokens

    private func accessToken() async throws -> String? {
        guard let access = session["access"] else { return nil }
        if let exp = Double(session["expires"] ?? ""), exp > Date().timeIntervalSince1970 + 60 { return access }
        guard let refresh = session["refresh"] else { return nil }
        do {
            save(try await request("POST", "/auth/v1/token?grant_type=refresh_token", body: ["refresh_token": refresh], token: nil))
            return session["access"]
        } catch {
            await signOut() // signed out elsewhere, or the account was deleted
            return nil
        }
    }

    private func save(_ t: [String: Any]) {
        let user = t["user"] as? [String: Any]
        let mail = user?["email"] as? String ?? session["email"]
        session = [
            "access": t["access_token"] as? String ?? "",
            "refresh": t["refresh_token"] as? String ?? "",
            "expires": String(Date().timeIntervalSince1970 + (t["expires_in"] as? Double ?? 3600)),
            "email": mail ?? "",
        ]
        Keychain.save(session)
        email = (mail?.isEmpty == false ? mail : nil) ?? "your account"
    }

    // MARK: HTTP

    private func request(_ method: String, _ path: String, body: [String: Any]?, token: String?) async throws -> [String: Any] {
        let data = try await raw(method, path, body: body, token: token)
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private func raw(_ method: String, _ path: String, body: [String: Any]?, token: String?) async throws -> Data {
        var req = URLRequest(url: URL(string: Self.base + path)!, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue(Self.key, forHTTPHeaderField: "apikey")
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let data: Data, res: URLResponse
        do { (data, res) = try await URLSession.shared.data(for: req) }
        catch { throw Failure(errorDescription: "Couldn't reach the sign-in server. Check your internet connection.") }
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            if code == 429 { throw Failure(errorDescription: "Too many tries. Wait a minute and try again.") }
            let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let msg = ["msg", "error_description", "message", "error"].compactMap { j?[$0] as? String }.first
            throw Failure(errorDescription: msg ?? "The sign-in server said \(code).")
        }
        return data
    }

    // MARK: Helpers

    private static func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s }

    private static func b64(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    private static func newVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return b64(Data(bytes))
    }

    private static func challenge(_ v: String) -> String { b64(Data(SHA256.hash(data: Data(v.utf8)))) }
}

/// Tells the sign-in sheet which window to appear over.
private final class AnchorProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    let window: ASPresentationAnchor
    init(window: ASPresentationAnchor) { self.window = window }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor { window }
}

/// The account session in the Keychain (this device only).
private enum Keychain {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "app.pixlink.account",
        kSecAttrAccount as String: "session",
    ]

    static func save(_ value: [String: String]) {
        SecItemDelete(query as CFDictionary)
        guard !value.isEmpty, let data = try? JSONEncoder().encode(value) else { return }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func load() -> [String: String]? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode([String: String].self, from: data)
    }
}
