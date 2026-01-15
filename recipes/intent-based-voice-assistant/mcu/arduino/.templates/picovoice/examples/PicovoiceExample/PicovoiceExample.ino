/*
    Copyright 2025 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
    file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
    specific language governing permissions and limitations under the License.
*/

#include <Rhino_{LANGUAGE_CODE}.h>
#include <Porcupine_{LANGUAGE_CODE}.h>

#include "pv_params.h"

#define MEMORY_BUFFER_SIZE (50 * 1024)

static const char *ACCESS_KEY = "${ACCESS_KEY}"; // AccessKey string obtained from Picovoice Console (https://picovoice.ai/console/)

pv_porcupine_t *ppn_handle = NULL;
pv_rhino_t *rhn_handle = NULL;

static int8_t ppn_memory_buffer[MEMORY_BUFFER_SIZE] __attribute__((aligned(16)));
static int8_t rhn_memory_buffer[MEMORY_BUFFER_SIZE] __attribute__((aligned(16)));

static const float PORCUPINE_SENSITIVITY = 0.75f;
static const float RHINO_SENSITIVITY = 0.5f;
static const float RHINO_ENDPOINT_DURATION_SEC = 1.0f;
static const bool RHINO_REQUIRE_ENDPOINT = true;

static const int32_t KEYWORD_MODEL_SIZES = sizeof(KEYWORD_ARRAY);
static const void *KEYWORD_MODELS = KEYWORD_ARRAY;

static void wake_word_callback(void) {
    Serial.println("Wake word detected!");
}

static void inference_callback(bool is_understood, const char *intent, int32_t num_slots, const char **slots, const char **values) {
    Serial.println("{");
    Serial.print("    is_understood : '"); Serial.print(is_understood ? "true" : "false"); Serial.println("',");
    if (is_understood) {
        Serial.print("    intent : '"); Serial.print(intent); Serial.println("',");
        if (num_slots > 0) {
            Serial.println("    slots : {");
            for (int32_t i = 0; i < num_slots; i++) {
                Serial.print("        '"); Serial.print(slots[i]); Serial.print("' : '"); Serial.print(values[i]); Serial.println("',");
            }
            Serial.println("    }");
        }
    }
    Serial.println("}");
    Serial.println("");
}

static void print_error_message(char **message_stack, int32_t message_stack_depth) {
    for (int32_t i = 0; i < message_stack_depth; i++) {
        Serial.println(message_stack[i]);
    }
}

static void error_handler() {
    char **message_stack = NULL;
    int32_t message_stack_depth = 0;
    pv_status_t error_status = pv_get_error_stack(&message_stack, &message_stack_depth);
    if (error_status != PV_STATUS_SUCCESS) {
        Serial.println("Unable to get Rhino error state");
        while (1);
    }
    print_error_message(message_stack, message_stack_depth);
    pv_free_error_stack(message_stack);
    while (1);
}


static bool process_porcupine(pv_porcupine_t *ppn_handle, const int16_t *buffer) {
    int32_t keyword_index = -1;
    const pv_status_t status = pv_porcupine_process(ppn_handle, buffer, &keyword_index);
    if (status != PV_STATUS_SUCCESS) {
        Serial.print("Porcupine process failed with ");
        Serial.println(pv_status_to_string(status));
        error_handler();
    }

    if (keyword_index != -1) {
        wake_word_callback();
    }

    return (keyword_index != -1);
}

static bool process_rhino(pv_rhino_t *rhn_handle, const int16_t *buffer) {
    bool is_finalized = false;
    pv_status_t status = pv_rhino_process(rhn_handle, buffer, &is_finalized);
    if (status != PV_STATUS_SUCCESS) {
        Serial.print("Rhino process failed with ");
        Serial.println(pv_status_to_string(status));
        error_handler();
    }

    if (is_finalized) {
        bool is_understood = false;
        status = pv_rhino_is_understood(rhn_handle, &is_understood);
        if (status != PV_STATUS_SUCCESS) {
            Serial.print("Rhino is_understood failed with ");
            Serial.println(pv_status_to_string(status));
            error_handler();
        }

        if (is_understood) {
            const char *intent = NULL;
            int32_t num_slots = 0;
            const char **slots = NULL;
            const char **values = NULL;

            status = pv_rhino_get_intent(
                    rhn_handle,
                    &intent,
                    &num_slots,
                    &slots,
                    &values);
            if (status != PV_STATUS_SUCCESS) {
                Serial.print("Rhino get_intent failed with ");
                Serial.println(pv_status_to_string(status));
                error_handler();
            }

            inference_callback(is_understood, intent, num_slots, slots, values);

            status = pv_rhino_free_slots_and_values(rhn_handle, slots, values);
            if (status != PV_STATUS_SUCCESS) {
                Serial.print("Rhino free_slots_and_values failed with ");
                Serial.println(pv_status_to_string(status));
                error_handler();
            }
        } else {
            inference_callback(is_understood, NULL, 0, NULL, NULL);
        }

        status = pv_rhino_reset(rhn_handle);
        if (status != PV_STATUS_SUCCESS) {
            Serial.print("Rhino reset failed with ");
            Serial.println(pv_status_to_string(status));
            error_handler();
        }
    }

    return is_finalized;
}

void setup() {
    Serial.begin(9600);
    while (!Serial);

    pv_status_t status = picovoice::porcupine::pv_audio_rec_init();
    if (status != PV_STATUS_SUCCESS) {
        Serial.print("Audio init failed with ");
        Serial.println(pv_status_to_string(status));
        while (1);
    }

    char **message_stack = NULL;
    int32_t message_stack_depth = 0;
    pv_status_t error_status;

    status = pv_porcupine_init(
            ACCESS_KEY,
            MEMORY_BUFFER_SIZE,
            ppn_memory_buffer,
            1,
            &KEYWORD_MODEL_SIZES,
            &KEYWORD_MODELS,
            &PORCUPINE_SENSITIVITY,
            &ppn_handle);
    if (status != PV_STATUS_SUCCESS) {
        Serial.print("Porcupine init failed with ");
        Serial.println(pv_status_to_string(status));
        error_handler();
    }

    status = pv_rhino_init(
            ACCESS_KEY,
            rhn_memory_buffer,
            MEMORY_BUFFER_SIZE,
            CONTEXT_ARRAY,
            sizeof(CONTEXT_ARRAY),
            RHINO_SENSITIVITY,
            RHINO_ENDPOINT_DURATION_SEC,
            RHINO_REQUIRE_ENDPOINT,
            &rhn_handle);
    if (status != PV_STATUS_SUCCESS) {
        Serial.print("Rhino init failed with ");
        Serial.println(pv_status_to_string(status));
        error_handler();
    }

    const char *rhino_context = NULL;
    status = pv_rhino_context_info(rhn_handle, &rhino_context);
    if (status != PV_STATUS_SUCCESS) {
        Serial.print("Retrieving context info failed with");
        Serial.println(pv_status_to_string(status));
        error_handler();
    }
    Serial.println("Wake word: 'hey computer'");
    Serial.println(rhino_context);
}

bool wakeword_heard = false;
bool inference_finalized = false;

void loop() {
    const int16_t *buffer = picovoice::porcupine::pv_audio_rec_get_new_buffer();
    if (buffer) {
        if (!wakeword_heard) {
            wakeword_heard = process_porcupine(ppn_handle, buffer);
        }
        else if (!inference_finalized) {
            inference_finalized = process_rhino(rhn_handle, buffer);
        }
    }
    if (wakeword_heard && inference_finalized) {
        wakeword_heard = false;
        inference_finalized = false;
    }
}
