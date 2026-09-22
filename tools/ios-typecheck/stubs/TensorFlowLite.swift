// Stub of TensorFlowLiteSwift — only what FaceMatcher uses.
import Foundation

public struct Tensor {
    public var data: Data = Data()
    public var shape: TensorShape = TensorShape([])
}
public struct TensorShape {
    public var dimensions: [Int]
    public init(_ dimensions: [Int]) { self.dimensions = dimensions }
}
public final class Interpreter {
    public init(modelPath: String) throws {}
    public func allocateTensors() throws {}
    public func copy(_ data: Data, toInputAt index: Int) throws {}
    public func invoke() throws {}
    public func output(at index: Int) throws -> Tensor { return Tensor() }
    public func input(at index: Int) throws -> Tensor { return Tensor() }
}
