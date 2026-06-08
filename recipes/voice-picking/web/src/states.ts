import { RhinoInference } from '@picovoice/rhino-web';

import { AINoiseSuppressedRecorder } from './ai_noise_suppressed_recorder';
import { AudioStream } from './audio_stream';
import { createStep, Step, StepOptions, OrcaStep, PorcupineStep, RhinoStep } from './steps';

import { callbacks, isRunning, sleep } from './config';

type Transition = {
    outcome?: RhinoInference,
    next: StateOptions,
} | null;

type Outcome = {
    state: RecipeStates,
    outcome?: RhinoInference,
};

export type PickTask = {
    locationName: string;
    checkDigit: string;
    itemName: string;
    quantity: number;
};

export enum RecipeSteps {
    STANDBY = "Standby",
    PROMPT_USER = "PromptUser",
    RECORD_USER = "RecordUser",
};

export enum RecipeStates {
    STANDBY = "Standby",
    TASK_LOCATION_PROMPT = "TaskLocationPrompt",
    TASK_LOCATION_REPORT = "TaskLocationReport",
    TASK_PICK_PROMPT = "TaskPickPrompt",
    TASK_PICK_REPORT = "TaskPickReport",
    COMPLETE_PROMPT = "CompletePrompt",
};

export type StateOptions =
    | {
        state: RecipeStates.STANDBY,
        tasks: PickTask[],
    }
    | {
        state: RecipeStates.TASK_LOCATION_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.TASK_LOCATION_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.TASK_PICK_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.TASK_PICK_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.COMPLETE_PROMPT,
        prompt: string,
    };

export abstract class State {
    toString(): string {
        return self.constructor.name;
    }

    static create(state: RecipeStates, step: Step): State {
        switch (state) {
            case RecipeStates.STANDBY:
                return new RecipeStandbyState(step as PorcupineStep);
            case RecipeStates.TASK_LOCATION_PROMPT:
                return new RecipeTaskLocationPromptState(step as OrcaStep);
            case RecipeStates.TASK_LOCATION_REPORT:
                return new RecipeTaskLocationReportState(step as RhinoStep);
            case RecipeStates.TASK_PICK_PROMPT:
                return new RecipeTaskPickPromptState(step as OrcaStep);
            case RecipeStates.TASK_PICK_REPORT:
                return new RecipeTaskPickReportState(step as RhinoStep);
            case RecipeStates.COMPLETE_PROMPT:
                return new RecipeCompletePromptState(step as OrcaStep);
        }
    }
}

type WorkflowOptions = {
    steps: Record<RecipeSteps, StepOptions>,
    all_states: RecipeStates[],
    state_creator: (rs: RecipeStates, step: Step) => State,
    state_steps: Record<RecipeStates, RecipeSteps>,
    start_state: StateOptions,
};

export class Workflow {
    private recorder: AINoiseSuppressedRecorder;
    private audio: AudioStream;

    private steps: Record<string, Step>;
    private states: Record<string, State>;
    private state_uids: Record<string, RecipeStates>;

    private start_state: StateOptions;

    private outcomes: Outcome[];

    private constructor(
        recorder: AINoiseSuppressedRecorder,
        audio: AudioStream,
        steps: Record<string, Step>,
        options: WorkflowOptions,
    ) {
        this.recorder = recorder;
        this.audio = audio;
        this.steps = steps;

        this.states = {};
        this.state_uids = {};
        for (const state of options.all_states) {
            const uid = options.state_steps[state];
            this.states[state] = options.state_creator(state, this.steps[uid]);

            this.state_uids[this.states[state].toString()] = state;
        }

        this.start_state = options.start_state;

        this.outcomes = [];
    }

    static async create(accessKey: string, options: WorkflowOptions) {
        const recorder = await AINoiseSuppressedRecorder.create(accessKey);
        const audio = new AudioStream(22050);

        const steps: Record<string, Step> = {};
        for (const [uid, stepOptions] of Object.entries(options.steps)) {
            steps[uid] = await createStep(
                accessKey,
                recorder,
                audio,
                stepOptions);
        }

        return new Workflow(recorder, audio, steps, options);
    }

    async run() {
        let current_state = this.start_state;

        while (isRunning) {
            let transition;
            switch (current_state.state) {
                case RecipeStates.STANDBY: {
                    const state = this.states[current_state.state] as RecipeStandbyState;
                    transition = await state.run(current_state.tasks);
                    break;
                } case RecipeStates.TASK_LOCATION_PROMPT: {
                    const state = this.states[current_state.state] as RecipeTaskLocationPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.TASK_LOCATION_REPORT: {
                    const state = this.states[current_state.state] as RecipeTaskLocationReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.TASK_PICK_PROMPT: {
                    const state = this.states[current_state.state] as RecipeTaskPickPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.TASK_PICK_REPORT: {
                    const state = this.states[current_state.state] as RecipeTaskPickReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.COMPLETE_PROMPT: {
                    const state = this.states[current_state.state] as RecipeCompletePromptState;
                    transition = await state.run(current_state.prompt);
                    break;
                }
            };

            if (transition == null) {
                break;
            }

            this.outcomes.push({
                state: this.state_uids[current_state.toString()],
                outcome: transition.outcome
            });
            current_state = transition.next;
        }
    }

    reset() {
        this.outcomes = [];
    }

    async release() {
        for (const [_, step] of Object.entries(this.steps).toReversed()) {
            await step.release();
        }

        this.audio.clear();

        await this.recorder.stop();
        await this.recorder.release();
    }

    toString(): string {
        return this.constructor.name;
    }
}

class RecipeStandbyState extends State {
    private step: PorcupineStep;

    constructor(step: PorcupineStep) {
        super();
        this.step = step;
    }

    async run(tasks: PickTask[]): Promise<Transition> {
        await this.step.run("Listening for wake word...");

        return {
            next: { state: RecipeStates.TASK_LOCATION_PROMPT, tasks, taskIndex: 0 }
        };
    }
}

class RecipePromptState extends State {
    private step: OrcaStep;

    constructor(step: OrcaStep) {
        super();
        this.step = step;
    }

    async runPrompt(prompt: string) {
        callbacks.setStatusText(prompt);
        await this.step.run(prompt);
    }
}

class RecipeTaskLocationPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = `location-${taskIndex}`;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = [
                `Go to ${task.locationName}. ` +
                `Confirm location. ` +
                `Check digits are ${task.checkDigit}.`
            ];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.TASK_LOCATION_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeTaskLocationReportState extends State {
    private step: RhinoStep;

    constructor(step: RhinoStep) {
        super();
        this.step = step;
    }

    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = `location-${taskIndex}`;

        callbacks.setCardValue(cardId, "...");
        const inference = await this.step.run("Listening for location confirmation...");

        const isValidLocation =
            inference &&
            inference.isUnderstood &&
            inference.intent == 'confirmLocation' &&
            inference.slots!.checkDigit == task.checkDigit;

        if (isValidLocation) {
            callbacks.setStatusText(`Location ${inference.slots!.checkDigit} confirmed.`);
            callbacks.setCompletedCard(cardId, false);
            callbacks.setCardValue(cardId, `${inference.slots!.checkDigit}`);

            await sleep(1000);

            return {
                outcome: inference,
                next: { state: RecipeStates.TASK_PICK_PROMPT, tasks, taskIndex, }
            };
        }

        let promptList = [];
        if (inference && inference.isUnderstood && inference.intent == 'confirmLocation') {
            promptList.push(`Location check digit ${inference.slots!.checkDigit} does not match. Retrying...`);
        } else {
            promptList.push("Failed to capture location confirmation. Retrying...");
        }

        promptList.push(`Please confirm location for ${task.locationName}. Check digits are ${task.checkDigit}.`);

        return {
            outcome: inference,
            next: {
                state: RecipeStates.TASK_LOCATION_PROMPT,
                tasks,
                taskIndex,
                inputPrompt: promptList,
            }
        };
    }
}

class RecipeTaskPickPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = `pick-${taskIndex}`;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = [ `Pick ${task.quantity} ${task.itemName}.` ];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.TASK_PICK_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeTaskPickReportState extends State {
    private step: RhinoStep;

    constructor(step: RhinoStep) {
        super();
        this.step = step;
    }

    private static VALID_INTENTS = [
        'confirmPickedQuantity',
        'reportShortPick',
        'reportDamagedItem',
        'reportLocationEmpty',
        'exitWorkflow',
    ];

    static nextLocationPrompt(task: PickTask): string {
        return (
            `Go to ${task.locationName}. ` +
            `Confirm location. ` +
            `Check digits are ${task.checkDigit}.`
        );
    }

    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = `pick-${taskIndex}`;

        callbacks.setCardValue(cardId, "...");

        const inference = await this.step.run("Listening for pick result");

        if (inference && inference.isUnderstood && RecipeTaskPickReportState.VALID_INTENTS.includes(inference.intent!)) {
            if (inference.intent == 'exitWorkflow') {
                callbacks.setCompletedCard(cardId, true);
                await sleep(400);

                return {
                    outcome: inference,
                    next: {
                        state: RecipeStates.COMPLETE_PROMPT,
                        prompt: "Picking workflow ended.",
                    }
                };
            }

            const nextTaskIndex = taskIndex + 1;
            callbacks.setCompletedCard(cardId, false);

            let nextPrompt;
            if (nextTaskIndex >= tasks.length) {
                if (inference.intent == 'confirmPickedQuantity') {
                    callbacks.setStatusText(`Recorded picked ${inference.slots!.quantity}.`);
                    callbacks.setCardValue(cardId, `pick ${inference.slots!.quantity}`);
                    nextPrompt = "Picking workflow complete.";
                } else if (inference.intent == 'reportShortPick') {
                    callbacks.setStatusText(`Recorded short pick ${inference.slots!.quantity}.`);
                    callbacks.setCardValue(cardId, `short pick ${inference.slots!.quantity}`);
                    nextPrompt = "Short pick recorded. " +
                                 "Picking workflow complete.";
                } else if (inference.intent == 'reportDamagedItem') {
                    callbacks.setStatusText("Recorded damaged item.");
                    callbacks.setCardValue(cardId, "damaged item");
                    nextPrompt = "Damaged item recorded. Set it aside. " +
                                 "Picking workflow complete.";
                } else {
                    callbacks.setStatusText("Recorded empty location.");
                    callbacks.setCardValue(cardId, "empty location");
                    nextPrompt = "Empty location recorded. " +
                                 "Picking workflow complete.";
                }

                await sleep(500);

                return {
                    outcome: inference,
                    next: {
                        state: RecipeStates.COMPLETE_PROMPT,
                        "prompt": nextPrompt
                    },
                };
            }

            const nextTask = tasks[nextTaskIndex];

            if (inference.intent == 'confirmPickedQuantity') {
                callbacks.setStatusText(`Recorded picked ${inference.slots!.quantity}.`);
                callbacks.setCardValue(cardId, `pick ${inference.slots!.quantity}`);
                nextPrompt = RecipeTaskPickReportState.nextLocationPrompt(nextTask);
            } else if (inference.intent == 'reportShortPick') {
                callbacks.setStatusText(`Recorded short pick ${inference.slots!.quantity}.`);
                callbacks.setCardValue(cardId, `short pick ${inference.slots!.quantity}`);
                nextPrompt = (
                    `Short pick recorded. ` +
                    `Proceed to ${nextTask.locationName}. ` +
                    `Confirm location. ` +
                    `Check digits are ${nextTask.checkDigit}.`
                );
            } else if (inference.intent == 'reportDamagedItem') {
                callbacks.setStatusText("Recorded damaged item.");
                callbacks.setCardValue(cardId, "damaged item");
                nextPrompt = (
                    `Damaged item recorded. Set it aside. ` +
                    `Then proceed to ${nextTask.locationName}. ` +
                    `Confirm location. ` +
                    `Check digits are ${nextTask.checkDigit}.`
                );
            } else {
                callbacks.setStatusText("Recorded empty location.");
                callbacks.setCardValue(cardId, "empty location");
                nextPrompt = (
                    `Empty location recorded. ` +
                    `Proceed to ${nextTask.locationName}. ` +
                    `Confirm location. ` +
                    `Check digits are ${nextTask.checkDigit}.`
                );
            }

            await sleep(500);

            return {
                outcome: inference,
                next: {
                    state: RecipeStates.TASK_LOCATION_PROMPT,
                    tasks,
                    taskIndex: nextTaskIndex,
                    inputPrompt: nextPrompt,
                }
            };
        }

        return {
            outcome: inference,
            next: {
                state: RecipeStates.TASK_PICK_PROMPT,
                tasks,
                taskIndex,
                inputPrompt: [
                    "Failed to capture pick result. Retrying...",
                    `Please report the result for picking ${task.quantity} ${task.itemName}.`
                ],
            }
        };
    }
}

class RecipeCompletePromptState extends RecipePromptState {
    async run(
        prompt: string
    ): Promise<Transition> {
        await this.runPrompt(prompt)
        return null;
    }
}