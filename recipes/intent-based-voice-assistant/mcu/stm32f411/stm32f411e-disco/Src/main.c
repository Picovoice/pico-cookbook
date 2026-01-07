/*
    Copyright 2025 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
    file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
    specific language governing permissions and limitations under the License.
*/

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "stm32f411e_discovery.h"

#include "pv_rhino_mcu.h"
#include "pv_porcupine_mcu.h"

#include "pv_audio_rec.h"
#include "pv_params.h"
#include "pv_st_f411.h"

#define MEMORY_BUFFER_SIZE (50 * 1024)

static int8_t ppn_memory_buffer[MEMORY_BUFFER_SIZE] __attribute__((aligned(16)));
static int8_t rhn_memory_buffer[MEMORY_BUFFER_SIZE] __attribute__((aligned(16)));

static const char *ACCESS_KEY = "${ACCESS_KEY}"; //AccessKey string obtained from Picovoice Console (https://picovoice.ai/console/)

static const float PORCUPINE_SENSITIVITY = 0.75f;
static const float RHINO_SENSITIVITY = 0.5f;
static const float RHINO_ENDPOINT_DURATION_SEC = 1.0f;
static const bool RHINO_REQUIRE_ENDPOINT = true;

static const int32_t KEYWORD_MODEL_SIZES = sizeof(KEYWORD_ARRAY);
static const void *KEYWORD_MODELS = KEYWORD_ARRAY;

static void turn_lights_on(void) {
	BSP_LED_On(LED3);
	BSP_LED_On(LED4);
	BSP_LED_On(LED5);
	BSP_LED_On(LED6);
}

static void turn_lights_off(void) {
	BSP_LED_Off(LED3);
	BSP_LED_Off(LED4);
	BSP_LED_Off(LED5);
	BSP_LED_Off(LED6);
}

static void turn_lights_color(const char *color) {
	turn_lights_off();

	if (strcmp(color, "orange") == 0) {
		BSP_LED_On(LED3);
	}
	else if (strcmp(color, "green") == 0) {
		BSP_LED_On(LED4);
	}
	else if (strcmp(color, "red") == 0) {
		BSP_LED_On(LED5);
	}
	else if (strcmp(color, "blue") == 0) {
		BSP_LED_On(LED6);
	}
}

static void update_lights(const char *intent, int32_t num_slots, const char **slots, const char **values) {
	if (strcmp(intent, "changeLightState") == 0) {
		for (int32_t i = 0; i < num_slots; i++) {
			const char *slot = slots[i];
			const char *value = values[i];

			if (strcmp(slot, "state") == 0 && strcmp(value, "on") == 0) {
				turn_lights_on();
			}
			else if (strcmp(slot, "state") == 0 && strcmp(value, "off") == 0) {
				turn_lights_off();
			}
		}
	}
	else if (strcmp(intent, "changeLightStateOff") == 0) {
		turn_lights_off();
	}
	else if (strcmp(intent, "changeColor") == 0) {
		for (int32_t i = 0; i < num_slots; i++) {
			const char *slot = slots[i];
			const char *value = values[i];

			if (strcmp(slot, "color") == 0) {
				turn_lights_color(value);
			}
		}
	}
}

#define WW_LED_DELAY (30)
#define WW_LED_LOOPS (2)
static void wake_word_callback(void) {
    printf("[wake word]\n");

    for (int32_t i = 0; i < (2 * WW_LED_LOOPS); i++) {
    	BSP_LED_Toggle(LED3);
    	HAL_Delay(WW_LED_DELAY);
    	BSP_LED_Toggle(LED4);
    	HAL_Delay(WW_LED_DELAY);
    	BSP_LED_Toggle(LED5);
    	HAL_Delay(WW_LED_DELAY);
    	BSP_LED_Toggle(LED6);
    	HAL_Delay(WW_LED_DELAY);
    }
}

static void inference_callback(bool is_understood, const char *intent, int32_t num_slots, const char **slots, const char **values) {
    printf("{\n");
    printf("    is_understood : '%s',\n", (is_understood ? "true" : "false"));
    if (is_understood) {
        printf("    intent : '%s',\n", intent);
        if (num_slots > 0) {
            printf("    slots : {\n");
            for (int32_t i = 0; i < num_slots; i++) {
                printf("        '%s' : '%s',\n", slots[i], values[i]);
            }
            printf("    }\n");
        }
    }
    printf("}\n\n");

    if (is_understood) {
    	update_lights(intent, num_slots, slots, values);
    }
}

static void error_handler(void) {
    while (true) {}
}

static void print_error_message(char **message_stack, int32_t message_stack_depth) {
    for (int32_t i = 0; i < message_stack_depth; i++) {
        printf("[%ld] %s\n", i, message_stack[i]);
    }
}

static bool process_porcupine(pv_porcupine_t *ppn_handle, const int16_t *buffer) {
    int32_t keyword_index = -1;
    const pv_status_t status = pv_porcupine_process(ppn_handle, buffer, &keyword_index);
    if (status != PV_STATUS_SUCCESS) {
        printf("Porcupine process failed with '%s'", pv_status_to_string(status));
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
        printf("Rhino process failed with '%s'", pv_status_to_string(status));
        error_handler();
    }

    if (is_finalized) {
        bool is_understood = false;
        status = pv_rhino_is_understood(rhn_handle, &is_understood);
        if (status != PV_STATUS_SUCCESS) {
            printf("Rhino is_understood failed with '%s'", pv_status_to_string(status));
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
                printf("Rhino get_intent failed with '%s'", pv_status_to_string(status));
                error_handler();
            }

            inference_callback(is_understood, intent, num_slots, slots, values);

            status = pv_rhino_free_slots_and_values(rhn_handle, slots, values);
            if (status != PV_STATUS_SUCCESS) {
                printf("Rhino free_slots_and_values failed with '%s'", pv_status_to_string(status));
                error_handler();
            }
        } else {
            inference_callback(is_understood, NULL, 0, NULL, NULL);
        }

        status = pv_rhino_reset(rhn_handle);
        if (status != PV_STATUS_SUCCESS) {
            printf("Rhino reset failed with '%s'", pv_status_to_string(status));
            error_handler();
        }
    }

    return is_finalized;
}

int main(void) {
    pv_status_t status = pv_board_init();
    if (status != PV_STATUS_SUCCESS) {
        error_handler();
    }

    const uint8_t *board_uuid = pv_get_uuid();
    printf("UUID: ");
    for (uint32_t i = 0; i < pv_get_uuid_size(); i++) {
        printf(" %.2x", board_uuid[i]);
    }
    printf("\r\n");

    status = pv_audio_rec_init();
    if (status != PV_STATUS_SUCCESS) {
        printf("Audio init failed with '%s'", pv_status_to_string(status));
        error_handler();
    }

    status = pv_audio_rec_start();
    if (status != PV_STATUS_SUCCESS) {
        printf("Recording audio failed with '%s'", pv_status_to_string(status));
        error_handler();
    }

    char **message_stack = NULL;
    int32_t message_stack_depth = 0;
    pv_status_t error_status;

    pv_porcupine_t *ppn_handle = NULL;
    pv_rhino_t *rhn_handle = NULL;

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
        printf("Porcupine init failed with '%s':\n", pv_status_to_string(status));

        error_status = pv_get_error_stack(&message_stack, &message_stack_depth);
        if (error_status != PV_STATUS_SUCCESS) {
            printf("Unable to get Porcupine error state with '%s':\n", pv_status_to_string(error_status));
            error_handler();
        }

        print_error_message(message_stack, message_stack_depth);
        pv_free_error_stack(message_stack);

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
        printf("Rhino init failed with '%s':\n", pv_status_to_string(status));

        error_status = pv_get_error_stack(&message_stack, &message_stack_depth);
        if (error_status != PV_STATUS_SUCCESS) {
            printf("Unable to get Rhino error state with '%s':\n", pv_status_to_string(error_status));
            error_handler();
        }

        print_error_message(message_stack, message_stack_depth);
        pv_free_error_stack(message_stack);

        error_handler();
    }

    const char *context_info = NULL;
    status = pv_rhino_context_info(rhn_handle, &context_info);
    if (status != PV_STATUS_SUCCESS) {
        printf("Rhino context_info failed with '%s'", pv_status_to_string(status));
        error_handler();
    }
    printf("%s\n", context_info);

    bool wakeword_heard = false;
    bool inference_finalized = false;

    while (true) {
        const int16_t *buffer = pv_audio_rec_get_new_buffer();
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

    pv_porcupine_delete(ppn_handle);
    pv_rhino_delete(rhn_handle);
    pv_audio_rec_deinit();
    pv_board_deinit();
}
