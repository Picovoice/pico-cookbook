/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.selfcheckout;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;

import java.util.ArrayList;

class CardUI {
    View root;
    View leftContainer;
    TextView titleView;
    TextView valueView;

    public static CardUI create(LayoutInflater inflater, LinearLayout container, String title) {
        View theroot;
        theroot = inflater.inflate(R.layout.item_report_card, container, false);

        CardUI card = new CardUI();
        card.root = theroot;
        card.leftContainer = theroot;
        card.titleView = null;
        card.valueView = theroot.findViewById(R.id.cardValue);
        card.valueView.setText(title);

        container.addView(theroot);
        return card;
    }

    public static CardUI fromOptions(LayoutInflater inflater, LinearLayout container, String title, ArrayList<String> options) {
        View theroot;
        theroot = inflater.inflate(R.layout.item_report_card_options, container, false);

        CardUI card = new CardUI();
        card.root = theroot;
        card.leftContainer = theroot;
        card.titleView = theroot.findViewById(R.id.cardTitle);
        card.titleView.setText(title);

        card.valueView = theroot.findViewById(R.id.cardValue);
        String optionsStr = "";
        for (String option : options) {
            optionsStr += (optionsStr.length() == 0) ? option : (", " + option);
        }
        card.valueView.setText(optionsStr);

        container.addView(theroot);
        return card;
    }
}