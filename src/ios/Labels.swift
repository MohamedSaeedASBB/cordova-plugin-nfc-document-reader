import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The text every plugin screen shows, supplied by the host application.
///
/// The bundle arrives complete: `resolveLabels` in NfcDocumentReader.js fills every key from its
/// English defaults before the call crosses the bridge, so there are no defaults here and no
/// fallback chain. That is deliberate. A second copy of this text in Swift, and a third in Java,
/// would drift — this project has already watched smaller strings drift between the platforms —
/// and a label that drifts in a language nobody on the team reads goes unnoticed until a customer
/// reports it.
///
/// A key that is missing renders as the key itself rather than as English. Silent English in an
/// Arabic screen is the failure that reaches production; a visible `mrz.title` is the one that
/// gets fixed on the first run.
struct Labels {

    private let values: [String: String]

    /// Right-to-left layout, set by the caller rather than guessed from the characters in the
    /// bundle. Translating without it produces a screen that reads correctly and behaves
    /// backwards: the back arrow, the step counter and the buttons all sit where the reader
    /// finishes rather than where they start.
    let isRightToLeft: Bool

    init(_ dictionary: [String: Any]?, direction: String?) {
        var values: [String: String] = [:]
        for (key, value) in dictionary ?? [:] {
            if let text = value as? String { values[key] = text }
        }
        self.values = values
        self.isRightToLeft = (direction == "rtl")
    }

    /// The text for a key.
    func text(_ key: String) -> String {
        return values[key] ?? key
    }

    /// The text for a key with `{name}` placeholders replaced.
    ///
    /// A translation may reorder the placeholders — Arabic frequently does — so they are matched
    /// by name and not by position. One that a translation dropped simply does not appear, which
    /// is the translator's decision to make; one it invented is left in place, visibly, rather
    /// than blanked.
    func text(_ key: String, _ substitutions: [String: String]) -> String {
        var out = text(key)
        for (name, value) in substitutions {
            out = out.replacingOccurrences(of: "{\(name)}", with: value)
        }
        return out
    }

    #if canImport(UIKit)
    /// Applies the reading direction to a screen. UIKit mirrors the whole subtree from here, so
    /// one call on the root view is enough for stacks, insets and system controls alike.
    func applyDirection(to view: UIView) {
        view.semanticContentAttribute = isRightToLeft ? .forceRightToLeft : .forceLeftToRight
    }
    #endif
}
