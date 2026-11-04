// Stub of MLKitFaceDetection, for -typecheck only.
import Foundation
import UIKit
import CoreGraphics
import MLKitVision

public enum FaceDetectorPerformanceMode: Int { case fast, accurate }
public enum FaceDetectorClassificationMode: Int { case none, all }
public enum FaceDetectorLandmarkMode: Int { case none, all }
public enum FaceDetectorContourMode: Int { case none, all }

open class FaceDetectorOptions: NSObject {
    public override init() {}
    open var performanceMode: FaceDetectorPerformanceMode = .fast
    open var classificationMode: FaceDetectorClassificationMode = .none
    open var landmarkMode: FaceDetectorLandmarkMode = .none
    open var contourMode: FaceDetectorContourMode = .none
    open var minFaceSize: CGFloat = 0.1
    open var isTrackingEnabled: Bool = false
}

open class Face: NSObject {
    open var frame: CGRect { .zero }
    open var hasHeadEulerAngleX: Bool { false }
    open var hasHeadEulerAngleY: Bool { false }
    open var hasHeadEulerAngleZ: Bool { false }
    open var headEulerAngleX: CGFloat { 0 }
    open var headEulerAngleY: CGFloat { 0 }
    open var headEulerAngleZ: CGFloat { 0 }
    open var hasLeftEyeOpenProbability: Bool { false }
    open var hasRightEyeOpenProbability: Bool { false }
    open var hasSmilingProbability: Bool { false }
    open var leftEyeOpenProbability: CGFloat { 0 }
    open var rightEyeOpenProbability: CGFloat { 0 }
    open var smilingProbability: CGFloat { 0 }
    open var hasTrackingID: Bool { false }
    open var trackingID: Int { 0 }
}

open class FaceDetector: NSObject {
    public static func faceDetector(options: FaceDetectorOptions) -> FaceDetector { FaceDetector() }
    open func results(in image: VisionImage) throws -> [Face] { [] }
    open func process(_ image: VisionImage,
                      completion: @escaping ([Face]?, Error?) -> Void) {}
}
