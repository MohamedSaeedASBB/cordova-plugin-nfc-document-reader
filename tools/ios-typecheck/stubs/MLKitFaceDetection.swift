// Stub of MLKitFaceDetection — only what this plugin uses.
import Foundation
import UIKit
import MLKitVision

public enum FaceDetectorPerformanceMode: Int { case fast, accurate }
public enum FaceDetectorLandmarkMode: Int { case none, all }
public enum FaceDetectorContourMode: Int { case none, all }
public enum FaceDetectorClassificationMode: Int { case none, all }

public class FaceDetectorOptions {
    public var performanceMode: FaceDetectorPerformanceMode = .fast
    public var landmarkMode: FaceDetectorLandmarkMode = .none
    public var contourMode: FaceDetectorContourMode = .none
    public var classificationMode: FaceDetectorClassificationMode = .none
    public var minFaceSize: CGFloat = 0.1
    public var isTrackingEnabled: Bool = false
    public init() {}
}

public class Face {
    public var frame: CGRect = .zero
    public var trackingID: Int = 0
    public var hasTrackingID: Bool = false
    public var headEulerAngleX: CGFloat = 0
    public var headEulerAngleY: CGFloat = 0
    public var headEulerAngleZ: CGFloat = 0
    public var smilingProbability: CGFloat = 0
    public var leftEyeOpenProbability: CGFloat = 0
    public var rightEyeOpenProbability: CGFloat = 0
    public var hasHeadEulerAngleX: Bool = false
    public var hasHeadEulerAngleY: Bool = false
    public var hasHeadEulerAngleZ: Bool = false
    public var hasSmilingProbability: Bool = false
    public var hasLeftEyeOpenProbability: Bool = false
    public var hasRightEyeOpenProbability: Bool = false
}

public class FaceDetector {
    public static func faceDetector(options: FaceDetectorOptions) -> FaceDetector { return FaceDetector() }
    public func process(_ image: VisionImage, completion: @escaping ([Face]?, Error?) -> Void) {}
    public func results(in image: VisionImage) throws -> [Face] { return [] }
}
