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
    TRANSCRIBE_USER = "TranscribeUser",
};

export enum RecipeStates {
    STANDBY = "Standby",
    IDENTIFY_UNIT_PROMPT = "IdentifyUnitPrompt",
    IDENTIFY_UNIT_REPORT = "IdentifyUnitReport",
    INCIDENT_TYPE_PROMPT = "IncidentTypePrompt",
    INCIDENT_TYPE_REPORT = "IncidentTypeReport",
    PATIENT_CONDITION_PROMPT = "PatientConditionPrompt",
    PATIENT_CONDITION_REPORT = "PatientConditionReport",
    DESTINATION_PROMPT = "DestinationPrompt",
    DESTINATION_REPORT = "DestinationReport",
    HANDOFF_STATUS_PROMPT = "HandoffStatusPrompt",
    HANDOFF_STATUS_REPORT = "HandoffStatusReport",
    HANDOFF_TIME_PROMPT = "HandoffTimePrompt",
    HANDOFF_TIME_REPORT = "HandoffTimeReport",
    FINAL_NOTE_PROMPT = "FinalNotePrompt",
    FINAL_NOTE_REPORT = "FinalNoteReport",
    COMPLETE_PROMPT = "CompletePrompt",
};

export type StateOptions = any;
    // | {
    //     state: RecipeStates.STANDBY,
    //     tasks: PickTask[],
    // }
    // | {
    //     state: RecipeStates.TASK_LOCATION_PROMPT,
    //     tasks: PickTask[],
    //     taskIndex: number,
    //     inputPrompt?: string | string[],
    // }
    // | {
    //     state: RecipeStates.TASK_LOCATION_REPORT,
    //     tasks: PickTask[],
    //     taskIndex: number,
    // }
    // | {
    //     state: RecipeStates.TASK_PICK_PROMPT,
    //     tasks: PickTask[],
    //     taskIndex: number,
    //     inputPrompt?: string | string[],
    // }
    // | {
    //     state: RecipeStates.TASK_PICK_REPORT,
    //     tasks: PickTask[],
    //     taskIndex: number,
    // }
    // | {
    //     state: RecipeStates.COMPLETE_PROMPT,
    //     prompt?: string,
    // };

export abstract class State {
    toString(): string {
        return self.constructor.name;
    }

    static create(state: RecipeStates, step: Step): State {
        switch (state) {
            case RecipeStates.STANDBY:
                return new RecipeStandbyState(step as PorcupineStep);
            case RecipeStates.IDENTIFY_UNIT_PROMPT:
                return new IdentifyUnitPromptState(step as OrcaStep);
            case RecipeStates.IDENTIFY_UNIT_REPORT:
                return new IdentifyUnitReportState(step as RhinoStep);
            case RecipeStates.INCIDENT_TYPE_PROMPT:
                return new IncidentTypePromptState(step as OrcaStep);
            case RecipeStates.INCIDENT_TYPE_REPORT:
                return new IncidentTypeReportState(step as RhinoStep);
            case RecipeStates.PATIENT_CONDITION_PROMPT:
                return new PatientConditionPromptState(step as OrcaStep);
            case RecipeStates.PATIENT_CONDITION_REPORT:
                return new PatientConditionReportState(step as RhinoStep);
            case RecipeStates.DESTINATION_PROMPT:
                return new DestinationPromptState(step as OrcaStep);
            case RecipeStates.DESTINATION_REPORT:
                return new DestinationReportState(step as RhinoStep);
            case RecipeStates.HANDOFF_STATUS_PROMPT:
                return new HandoffStatusPromptState(step as OrcaStep);
            case RecipeStates.HANDOFF_STATUS_REPORT:
                return new HandoffStatusReportState(step as RhinoStep);
            case RecipeStates.HANDOFF_TIME_PROMPT:
                return new HandoffTimePromptState(step as OrcaStep);
            case RecipeStates.HANDOFF_TIME_REPORT:
                return new HandoffTimeReportState(step as RhinoStep);
            case RecipeStates.FINAL_NOTE_PROMPT:
                return new FinalNotePromptState(step as OrcaStep);
            case RecipeStates.FINAL_NOTE_REPORT:
                return new FinalNoteReportState(step as CheetahStep);
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
                } case RecipeStates.IDENTIFY_UNIT_PROMPT: {
                    const state = this.states[current_state.state] as IdentifyUnitPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.IDENTIFY_UNIT_REPORT: {
                    const state = this.states[current_state.state] as IdentifyUnitReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.INCIDENT_TYPE_PROMPT: {
                    const state = this.states[current_state.state] as IncidentTypePrompt;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.INCIDENT_TYPE_REPORT: {
                    const state = this.states[current_state.state] as IncidentTypeReport;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.PATIENT_CONDITION_PROMPT: {
                    const state = this.states[current_state.state] as PatientConditionPrompt;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.PATIENT_CONDITION_REPORT: {
                    const state = this.states[current_state.state] as PatientConditionReport;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.DESTINATION_PROMPT: {
                    const state = this.states[current_state.state] as DestinationPrompt;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.DESTINATION_REPORT: {
                    const state = this.states[current_state.state] as DestinationReport;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.HANDOFF_STATUS_PROMPT: {
                    const state = this.states[current_state.state] as HandoffStatusPrompt;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.HANDOFF_STATUS_REPORT: {
                    const state = this.states[current_state.state] as HandoffStatusReport;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.HANDOFF_TIME_PROMPT: {
                    const state = this.states[current_state.state] as HandoffTimePrompt;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.HANDOFF_TIME_REPORT: {
                    const state = this.states[current_state.state] as HandoffTimeReport;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.FINAL_NOTE_PROMPT: {
                    const state = this.states[current_state.state] as FinalNotePrompt;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.FINAL_NOTE_REPORT: {
                    const state = this.states[current_state.state] as FinalNoteReport;
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
        callbacks.setStatusText(prompt);
        await this.step.run(prompt);
    }
}

class RecipeReportState extends State {
    private step: RhinoStep;

    constructor(step: RhinoStep) {
        super();
        this.step = step;
    }

    async runReport(prompt: string) {

    //     const task = tasks[taskIndex];
    //     const cardId = `location-${taskIndex}`;

    //     callbacks.setCardValue(cardId, "...");
    //     const inference = await this.step.run("Listening for location confirmation...");

    //     const isValidLocation =
    //         inference &&
    //         inference.isUnderstood &&
    //         inference.intent == 'confirmLocation' &&
    //         inference.slots!.checkDigit == task.checkDigit;

    //     if (isValidLocation) {
    //         callbacks.setStatusText(`Location ${inference.slots!.checkDigit} confirmed.`);
    //         callbacks.setCompletedCard(cardId);
    //         callbacks.setCardValue(cardId, `${inference.slots!.checkDigit}`);

    //         await sleep(1000);

    //         return {
    //             outcome: inference,
    //             next: { state: RecipeStates.TASK_PICK_PROMPT, tasks, taskIndex, }
    //         };
    //     }

    //     let promptList = [];
    //     if (inference && inference.isUnderstood && inference.intent == 'confirmLocation') {
    //         promptList.push(`Location check digit ${inference.slots!.checkDigit} does not match. Retrying...`);
    //     } else {
    //         promptList.push("Failed to capture location confirmation. Retrying...");
    //     }

    //     promptList.push(`Please confirm location for ${task.locationName}. Check digits are ${task.checkDigit}.`);

    //     return {
    //         outcome: inference,
    //         next: {
    //             state: RecipeStates.TASK_LOCATION_PROMPT,
    //             tasks,
    //             taskIndex,
    //             inputPrompt: promptList,
    //         }
    //     };
    // }


        callbacks.setStatusText(prompt);
        await this.step.run(prompt);
    }
}

class RecipeIdentifyUnitPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = `location-${taskIndex}`;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What is the unit ID?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.IDENTIFY_UNIT_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeIdentifyUnitReportState extends RecipeReportState {
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
        callbacks.setActiveCard(cardId);

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for unit ID",
            expectedIntent: 'identifyUnit',
            successPrompt: (x: RhinoInference) => `Unit ID is ${x['slots']['unitId']}.`,
            successNextState: RecipeStates.INCIDENT_TYPE_PROMPT,
            failurePrompt: (x: RhinoInference) => `Failed to capture unit ID. Retrying...`,
            failureNextState: RecipeStates.IDENTIFY_UNIT_PROMPT,
            failureNextStateKwargs: {
                prompt: "I'm sorry, I didn't catch that. What is the unit ID again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeIncidentTypePromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = `location-${taskIndex}`;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What was the incident type?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.INCIDENT_TYPE_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeIncidentTypeReportState extends RecipeReportState {
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
        callbacks.setActiveCard(cardId);

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for incident type",
            expectedIntent: 'reportIncidentType',
            successPrompt: (x: RhinoInference) => `Incident type is ${x['slots']['incidentType']}.`,
            successNextState: RecipeStates.PATIENT_CONDITION_PROMPT,
            failurePrompt: (x: RhinoInference) => `Failed to capture incident type. Retrying...`,
            failureNextState: RecipeStates.INCIDENT_TYPE_PROMPT,
            failureNextStateKwargs: {
                prompt: "I'm sorry, I didn't catch that. What was the incident type again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeCompletePromptState extends RecipePromptState {
    async run(
        prompt: string = "Field report recorded.",
    ): Promise<Transition> {
        await this.runPrompt(prompt)
        return null;
    }
}