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
import java.util.Map;

import ai.picovoice.foodordering.Pair;
import ai.picovoice.rhino.RhinoInference;

public class OrderItem {
    public static final Map<String, String> PRONUNCIATION_MAP = Map.ofEntries(
        Map.entry("big mac", "{big|B IH G} {mac|M AE K}"),
        Map.entry("quarter pounder", "{quarter|K W AO R T ER} {pounder|P AW N D ER}"),
        Map.entry("double quarter pounder", "{double|D AH B AH L} {quarter|K W AO R T ER} {pounder|P AW N D ER}"),
        Map.entry("mc double", "{mc|M IH K} {double|D AH B AH L}"),
        Map.entry("cheeseburger", "{cheeseburger|CH IY Z B ER G ER}"),
        Map.entry("hamburger", "{hamburger|HH AE M B ER G ER}"),
        Map.entry("mc chicken", "{mc|M IH K} {chicken|CH IH K AH N}"),
        Map.entry("mc crispy", "{mc|M IH K} {crispy|K R IH S P IY}"),
        Map.entry("filet o fish", "{filet|F IH L EY} {o|OW} {fish|F IH SH}"),
        Map.entry("chicken mcnuggets", "{chicken|CH IH K AH N} {mc|M IH K}{nuggets|N AH G IH T S}"),
        Map.entry("mcnuggets", "{mc|M IH K}{nuggets|N AH G IH T S}"),
        Map.entry("nuggets", "{nuggets|N AH G IH T S}"),
        Map.entry("fries", "{fries|F R AY Z}"),
        Map.entry("french fries", "{french|F R EH N CH} {fries|F R AY Z}"),
        Map.entry("apple slices", "{apple|AE P AH L} {slices|S L AY S IH Z}"),
        Map.entry("coke", "{coke|K OW K}"),
        Map.entry("coca cola", "{coca|K OW K AH} {cola|K OW L AH}"),
        Map.entry("diet coke", "{diet|D AY AH T} {coke|K OW K}"),
        Map.entry("sprite", "{sprite|S P R AY T}"),
        Map.entry("fanta orange", "{fanta|F AE N T AH} {orange|AO R IH N JH}"),
        Map.entry("fanta", "{fanta|F AE N T AH}"),
        Map.entry("sweet tea", "{sweet|S W IY T} {tea|T IY}"),
        Map.entry("unsweet tea", "{unsweet|AH N S W IY T} {tea|T IY}"),
        Map.entry("iced tea", "{iced|AY S T} {tea|T IY}"),
        Map.entry("coffee", "{coffee|K AO F IY}"),
        Map.entry("iced coffee", "{iced|AY S T} {coffee|K AO F IY}"),
        Map.entry("water", "{water|W AO T ER}"),
        Map.entry("orange juice", "{orange|AO R IH N JH} {juice|JH UW S}"),
        Map.entry("milk", "{milk|M IH L K}"),
        Map.entry("oreo mc flurry", "{oreo|AO R IY OW} {mc|M IH K} {flurry|F L ER IY}"),
        Map.entry("m and m mc flurry", "{m|EH M} {and|AH N} {m|EH M} {mc|M IH K} {flurry|F L ER IY}"),
        Map.entry("mc flurry", "{mc|M IH K} {flurry|F L ER IY}"),
        Map.entry("apple pie", "{apple|AE P AH L} {pie|P AY}"),
        Map.entry("chocolate chip cookie", "{chocolate|CH AO K L AH T} {chip|CH IH P} {cookie|K UH K IY}"),
        Map.entry("cookie", "{cookie|K UH K IY}"),
        Map.entry("vanilla cone", "{vanilla|V AH N IH L AH} {cone|K OW N}"),
        Map.entry("ice cream cone", "{ice|AY S} {cream|K R IY M} {cone|K OW N}"),
        Map.entry("chocolate shake", "{chocolate|CH AO K L AH T} {shake|SH EY K}"),
        Map.entry("vanilla shake", "{vanilla|V AH N IH L AH} {shake|SH EY K}"),
        Map.entry("strawberry shake", "{strawberry|S T R AO B EH R IY} {shake|SH EY K}"),
        Map.entry("shake", "{shake|SH EY K}")
    );

    public String size;
    public String itemName;
    public String itemNamePronunciation;
    public int quantity;

    public OrderItem(String size, String itemName, int quantity) {
        this.size = size;
        this.itemName = itemName;
        this.itemNamePronunciation = OrderItem.PRONUNCIATION_MAP.getOrDefault(itemName, itemName);
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

    public String toPronunciationString() {
        if (this.size == null && this.quantity == -1) {
            return this.itemNamePronunciation;
        } else if (this.size == null) {
            return String.format("%d %s", this.quantity, this.itemNamePronunciation);
        } else {
            return String.format("%d %s %s", this.quantity, this.size, this.itemNamePronunciation);
        }
    }
}