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

import java.util.ArrayList;

import ai.picovoice.foodordering.Pair;
import ai.picovoice.rhino.RhinoInference;

public class OrderItem {
    public String size;
    public String itemName;
    public int quantity;

    public OrderItem(String size, String itemName, int quantity) {
        this.size = size;
        this.itemName = itemName;
        this.quantity = quantity;
    }

    public static OrderItem parseAddItemInference(RhinoInference inference) {
        String size = inference.getSlots().get("size");
        String itemName = inference.getSlots().get("item");
        String comboName = inference.getSlots().get("combo");
        String modifier = inference.getSlots().get("modifier");

        Integer quantity;
        if (inference.getSlots().get("quantity") == null) {
            quantity = 1;
        } else {
            quantity = Integer.parseInt(inference.getSlots().get("quantity"));
        }

        if (comboName != null) {
            return new ComboItem(size, itemName, quantity, comboName);
        } else {
            return new MenuItem(size, itemName, quantity, modifier);
        }
    }

    public static OrderItem parseRemoveItemInference(RhinoInference inference) {
        String size = inference.getSlots().get("size");
        String itemName = inference.getSlots().get("item");

        return new OrderItem(size, itemName, -1);
    }

    /// @brief returns null if last item
    public static Pair<OrderItem, OrderChange> parseChangeItemInference(RhinoInference inference) {
        String fromItem = inference.getSlots().get("fromItem");
        String toSize = inference.getSlots().get("toSize");
        String toItem = inference.getSlots().get("toItem");
        String toCombo = inference.getSlots().get("combo");

        return new Pair(
            (fromItem == null) ? null : new OrderItem(null, fromItem, -1),
            new OrderChange(toItem, toSize, toCombo)
        );
    }

    public Integer findFromEndIn(ArrayList<OrderItem> order) {
        for (int i = order.size() - 1; i >= 0; i--) {
            Boolean sameSize = (this.size == null) || (this.size.equals(order.get(i).size));
            Boolean sameItem = this.itemName.equals(order.get(i).itemName);

            if (sameSize && sameItem) {
                return i;
            }
        }

        return null;
    }

    public String toString() {
        if (this.size == null && this.quantity == -1) {
            return this.itemName;
        } else if (this.size == null) {
            return String.format("%d %s", this.quantity, this.itemName);
        } else {
            return String.format("%d %s %s", this.quantity, this.size, this.itemName);
        }
    }
}