import { AINoiseSuppressedRecorder } from './ai_noise_suppressed_recorder';
import { AudioStream } from './audio_stream';
import { Step, StepOptions, OrcaStep, PorcupineStep, RhinoStep } from './steps';

import { callbacks, isRunning, sleep } from './index';

type Transition = {
    outcome?: string,
    next: StateOptions,
} | null;

type Outcome = {
    state: RecipeStates,
    outcome?: string,
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
}

export enum RecipeStates {
    STANDBY = "Standby",
    TASK_LOCATION_PROMPT = "TaskLocationPrompt",
    TASK_LOCATION_REPORT = "TaskLocationReport",
    TASK_PICK_PROMPT = "TaskPickPrompt",
    TASK_PICK_REPORT = "TaskPickReport",
    COMPLETE_PROMPT = "CompletePrompt",
}

export type StateOptions =
    | {
        state: RecipeStates.STANDBY,
        tasks: PickTask[],
    }
    | {
        state: RecipeStates.TASK_LOCATION_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        prompt?: string,
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
        prompt?: string,
    }
    | {
        state: RecipeStates.TASK_PICK_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.COMPLETE_PROMPT,
        prompt?: string,
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

    constructor(
        access_key: string,
        options: WorkflowOptions,
    ) {
        this.recorder = new AINoiseSuppressedRecorder(access_key);
        // TODO: why fixed sample rate?
        // this._speaker = PvSpeaker(sample_rate=22050, bits_per_sample=16)
        this.audio = new AudioStream(22050); // orca.sampleRate ? 

        this.steps = {};
        for (const [uid, stepOptions] of Object.entries(options.steps)) {
            this.steps[uid] = Step.create(
                access_key,
                this.recorder,
                this.audio,
                stepOptions);

            callbacks.onUpdateStatus(`Loading ${this.steps[uid]}`);
        }

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
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.prompt);
                    break;
                } case RecipeStates.TASK_LOCATION_REPORT: {
                    const state = this.states[current_state.state] as RecipeTaskLocationReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.TASK_PICK_PROMPT: {
                    const state = this.states[current_state.state] as RecipeTaskPickPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.prompt);
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

        callbacks.onUpdateStatus("Detected wake word. Starting picking workflow...");
        await sleep(100);

        if (tasks.length == 0) {
            return {
                next: { state: RecipeStates.COMPLETE_PROMPT }
            };
        }

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
        callbacks.onUpdateStatus(prompt);

        await this.step.run(prompt);
    }
}

class RecipeTaskLocationPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        prompt?: string,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        if (!prompt) {
            prompt = (
                `Go to ${task.locationName}. ` +
                `Confirm location. ` +
                `Check digits are ${task.checkDigit}.`
            );
        }

        await this.runPrompt(prompt);

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

        callbacks.onUpdateCard(`location-${taskIndex}`, "...", false);
        const inference = await this.step.run("Listening for location confirmation...");

        const isValidLocation =
            inference &&
            inference.isUnderstood &&
            inference.intent == 'confirmLocation' &&
            inference.slots.checkDigit == task.checkDigit;

        if (isValidLocation) {
            callbacks.onUpdateStatus(`Location ${inference.slots.checkDigit} confirmed.`);
            callbacks.onUpdateCard(`location-${taskIndex}`, `${inference.slots.checkDigit}`, true);

            await sleep(100);

            return {
                outcome: inference,
                next: { state: RecipeStates.TASK_PICK_PROMPT, tasks, taskIndex, }
            };
        }

        if (inference && inference.isUnderstood && inference.intent == 'confirmLocation') {
            callbacks.onUpdateStatus(`Location check digit ${inference.slots.checkDigit} does not match. Retrying...`);
        } else {
            callbacks.onUpdateStatus("Failed to capture location confirmation. Retrying...");
        }

        await sleep(100);

        return {
            outcome: inference,
            next: {
                state: RecipeStates.TASK_LOCATION_PROMPT,
                tasks,
                taskIndex,
                prompt: `Please confirm location for ${task.locationName}. Check digits are ${task.checkDigit}.`,
            }
        };
    }
}

class RecipeTaskPickPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        prompt?: string,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        if (!prompt) {
            prompt = `Pick ${task.quantity} ${task.itemName}.`;
        }

        await this.runPrompt(prompt);

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

        callbacks.onUpdateCard(`pick-${taskIndex}`, "...", false);

        const inference = await this.step.run("Listening for pick result");

        if (inference && inference.isUnderstood && inference.intent in RecipeTaskPickReportState.VALID_INTENTS) {
            if (inference.intent == 'exitWorkflow') {
                callbacks.onUpdateStatus("Ending picking workflow.");
                await sleep(100);

                return {
                    outcome: inference,
                    next: {
                        state: RecipeStates.COMPLETE_PROMPT,
                        prompt: "Picking workflow ended.",
                    }
                };
            }

            const nextTaskIndex = taskIndex + 1;

            if (nextTaskIndex >= tasks.length) {
                if (inference.intent == 'confirmPickedQuantity') {
                    callbacks.onUpdateStatus(`Recorded picked ${inference.slots.quantity}.`);
                    callbacks.onUpdateCard(`pick-${taskIndex}`, `pick ${inference.slots.quantity}`, true);
                } else if (inference.intent == 'reportShortPick') {
                    callbacks.onUpdateStatus(`Recorded short pick ${inference.slots.quantity}.`);
                    callbacks.onUpdateCard(`pick-${taskIndex}`, `short pick ${inference.slots.quantity}`, true);
                } else if (inference.intent == 'reportDamagedItem') {
                    callbacks.onUpdateStatus("Recorded damaged item.");
                    callbacks.onUpdateCard(`pick-${taskIndex}`, "damaged item", true);
                } else if (inference.intent == 'reportLocationEmpty') {
                    callbacks.onUpdateStatus("Recorded empty location.");
                    callbacks.onUpdateCard(`pick-${taskIndex}`, "empty location", true);
                }

                await sleep(100);

                return {
                    outcome: inference,
                    next: { state: RecipeStates.COMPLETE_PROMPT },
                };
            }

            const nextTask = tasks[nextTaskIndex];

            let nextPrompt;
            if (inference.intent == 'confirmPickedQuantity') {
                callbacks.onUpdateStatus(`Recorded picked ${inference.slots.quantity}.`);
                callbacks.onUpdateCard(`pick-${taskIndex}`, `pick ${inference.slots.quantity}`, true);
                nextPrompt = RecipeTaskPickReportState.nextLocationPrompt(nextTask);
            } else if (inference.intent == 'reportShortPick') {
                callbacks.onUpdateStatus(`Recorded short pick ${inference.slots.quantity}.`);
                callbacks.onUpdateCard(`pick-${taskIndex}`, `short pick ${inference.slots.quantity}`, true);
                nextPrompt = (
                    `Short pick recorded. ` +
                    `Proceed to ${nextTask.locationName}. ` +
                    `Confirm location. ` +
                    `Check digits are ${nextTask.checkDigit}.`
                );
            } else if (inference.intent == 'reportDamagedItem') {
                callbacks.onUpdateStatus("Recorded damaged item.");
                callbacks.onUpdateCard(`pick-${taskIndex}`, "damaged item", true);
                nextPrompt = (
                    `Damaged item recorded. Set it aside. ` +
                    `Then proceed to ${nextTask.locationName}. ` +
                    `Confirm location. ` +
                    `Check digits are ${nextTask.checkDigit}.`
                );
            } else {
                callbacks.onUpdateStatus("Recorded empty location.");
                callbacks.onUpdateCard(`pick-${taskIndex}`, "empty location", true);
                nextPrompt = (
                    `Empty location recorded. ` +
                    `Proceed to ${nextTask.locationName}. ` +
                    `Confirm location. ` +
                    `Check digits are ${nextTask.checkDigit}.`
                );
            }

            await sleep(100);

            return {
                outcome: inference,
                next: {
                    state: RecipeStates.TASK_LOCATION_PROMPT,
                    tasks,
                    taskIndex: nextTaskIndex,
                    prompt: nextPrompt,
                }
            };
        }

        callbacks.onUpdateStatus("Failed to capture pick result. Retrying...");
        await sleep(100);

        return {
            outcome: inference,
            next: {
                state: RecipeStates.TASK_PICK_PROMPT,
                tasks,
                taskIndex,
                prompt: `Please report the result for picking ${task.quantity} ${task.itemName}.`,
            }
        };
    }
}

class RecipeCompletePromptState extends RecipePromptState {
    run(
        prompt: string = "Picking workflow complete.",
    ): Transition {
        this.runPrompt(prompt)
        return null;
    }
}