//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Foundation

@globalActor
actor BackgroundActor: GlobalActor {
    static let shared = BackgroundActor()

    func run(_ f: @Sendable () async -> Void) async -> Void {
        await f()
    }

    func run(_ f: @Sendable () async throws -> Void) async throws -> Void {
        try await f()
    }
}
