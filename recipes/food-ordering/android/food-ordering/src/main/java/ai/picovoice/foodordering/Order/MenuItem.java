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

public class MenuItem extends OrderItem {
    public String modifier;

    public MenuItem(
            String size,
            String itemName,
            int quantity,
            String modifier) {
        super(size, itemName, quantity);
        this.modifier = modifier;
    }

    @Override
    public String toString() {
        String response = super.toString();

        if (this.quantity != 1 && response.charAt(response.length() - 1) != 's') {
            response += "s";
        }

        if (this.modifier != null) {
            response += String.format(", %s", this.modifier);
        }

        return response;
    }

    @Override
    public String toPronunciationString() {
        String response = super.toPronunciationString();

        if (this.modifier != null) {
            response += String.format(", %s", this.modifier);
        }

        return response;
    }
}