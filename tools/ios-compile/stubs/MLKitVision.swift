// Stub of MLKitVision, for -typecheck only.
import Foundation
import UIKit
import CoreMedia

open class VisionImage: NSObject {
    public init(image: UIImage) {}
    public init(buffer sampleBuffer: CMSampleBuffer) {}
    open var orientation: UIImage.Orientation = .up
}
