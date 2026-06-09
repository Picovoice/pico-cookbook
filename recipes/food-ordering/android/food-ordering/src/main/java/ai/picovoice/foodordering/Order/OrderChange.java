/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.foodordering.Order;

public class OrderChange {
    public String toItem;
    public String toSize;
    public String toCombo;

    public OrderChange(String toItem, String toSize, String toCombo) {
        this.toItem = toItem;
        this.toSize = toSize;
        this.toCombo = toCombo;
    }
}
