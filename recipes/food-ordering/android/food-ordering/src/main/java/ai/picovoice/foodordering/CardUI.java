/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.foodordering;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;

class CardUI {
    View root;
    View leftContainer;
    TextView valueView;

    public static CardUI create(LayoutInflater inflater, LinearLayout container, String title) {
        View root;
        root = inflater.inflate(R.layout.item_report_card, container, false);

        CardUI card = new CardUI();
        card.root = root;
        card.leftContainer = root;
        card.valueView = root.findViewById(R.id.cardValue);
        card.valueView.setText(title);

        container.addView(root);
        return card;
    }
}