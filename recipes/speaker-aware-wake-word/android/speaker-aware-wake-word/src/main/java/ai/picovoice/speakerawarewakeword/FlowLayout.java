/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.speakerawarewakeword;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class FlowLayout extends ViewGroup {
    private int lineSpacing;
    private int itemSpacing;

    public FlowLayout(Context context) {
        super(context);
        init();
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        lineSpacing = (int) (12 * density);
        itemSpacing = (int) (8 * density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int height = MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom();
        int count = getChildCount();
        int xpos = getPaddingLeft();
        int ypos = getPaddingTop();
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);

        int currentLineHeight = 0;

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), childHeightMeasureSpec);
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();

                if (xpos + childWidth > width) {
                    xpos = getPaddingLeft();
                    ypos += currentLineHeight + lineSpacing;
                    currentLineHeight = childHeight;
                } else {
                    currentLineHeight = Math.max(currentLineHeight, childHeight);
                }
                xpos += childWidth + itemSpacing;
            }
        }

        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            height = ypos + currentLineHeight;
        } else if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            height = Math.min(ypos + currentLineHeight + getPaddingBottom(), height);
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        int width = r - l;
        int xpos = getPaddingLeft();
        int ypos = getPaddingTop();
        int currentLineHeight = 0;

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                int childWidth = child.getMeasuredWidth();
                int childHeight = child.getMeasuredHeight();

                if (xpos + childWidth > width) {
                    xpos = getPaddingLeft();
                    ypos += currentLineHeight + lineSpacing;
                    currentLineHeight = childHeight;
                } else {
                    currentLineHeight = Math.max(currentLineHeight, childHeight);
                }
                child.layout(xpos, ypos, xpos + childWidth, ypos + childHeight);
                xpos += childWidth + itemSpacing;
            }
        }
    }
}