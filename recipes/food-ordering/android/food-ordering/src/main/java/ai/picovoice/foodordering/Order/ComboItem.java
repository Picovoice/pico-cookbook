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

public class ComboItem extends OrderItem {
    public String comboName;

    public ComboItem(
            String size,
            String itemName,
            int quantity,
            String comboName) {
        super(size, itemName, quantity);
        this.comboName = comboName;
    }

    @Override
    public String toString() {
        String response = String.format("%s %s", super.toString(), this.comboName);

        if (this.quantity != 1 && response.charAt(response.length() - 1) != 's') {
            response += "s";
        }

        return response;
    }
}