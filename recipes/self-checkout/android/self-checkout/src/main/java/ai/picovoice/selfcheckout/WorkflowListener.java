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

import java.util.ArrayList;

public interface WorkflowListener {
    void onInitProgress(String status);
    void onStatusChanged(String status);
    void addCard(String title, String contents);
    void addOptionCard(String title, ArrayList<String> options);
    void removeCard(int index);
    void onWorkflowComplete();
    void onVolumeFrame(short[] frame);
    void setListeningUI(boolean isListening, String listeningPrompt);

    ai.picovoice.selfcheckout.Steps.OrcaStep getOrcaStep();
    boolean isRunning();
}