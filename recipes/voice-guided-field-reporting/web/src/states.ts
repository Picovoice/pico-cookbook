import { RhinoInference } from '@picovoice/rhino-web';

import { AINoiseSuppressedRecorder } from './ai_noise_suppressed_recorder';
import { AudioStream } from './audio_stream';
import { createStep, Step, StepOptions, CheetahStep, OrcaStep, PorcupineStep, RhinoStep } from './steps';

import { callbacks, isRunning, sleep } from './config';

type Transition = {
    outcome?: RhinoInference | string,
    next: StateOptions,
} | null;

type Outcome = {
    state: RecipeStates,
    outcome?: RhinoInference | string,
};

export type PickTask = {
    cardId: string;
    cardTitle: string;
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

export type StateOptions =
    | {
        state: RecipeStates.STANDBY,
        tasks: PickTask[],
    }
    | {
        state: RecipeStates.IDENTIFY_UNIT_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.IDENTIFY_UNIT_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.INCIDENT_TYPE_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.INCIDENT_TYPE_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.PATIENT_CONDITION_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.PATIENT_CONDITION_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.DESTINATION_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.DESTINATION_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.HANDOFF_STATUS_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.HANDOFF_STATUS_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.HANDOFF_TIME_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.HANDOFF_TIME_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.FINAL_NOTE_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.FINAL_NOTE_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.COMPLETE_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
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
            case RecipeStates.IDENTIFY_UNIT_PROMPT:
                return new RecipeIdentifyUnitPromptState(step as OrcaStep);
            case RecipeStates.IDENTIFY_UNIT_REPORT:
                return new RecipeIdentifyUnitReportState(step as RhinoStep);
            case RecipeStates.INCIDENT_TYPE_PROMPT:
                return new RecipeIncidentTypePromptState(step as OrcaStep);
            case RecipeStates.INCIDENT_TYPE_REPORT:
                return new RecipeIncidentTypeReportState(step as RhinoStep);
            case RecipeStates.PATIENT_CONDITION_PROMPT:
                return new RecipePatientConditionPromptState(step as OrcaStep);
            case RecipeStates.PATIENT_CONDITION_REPORT:
                return new RecipePatientConditionReportState(step as RhinoStep);
            case RecipeStates.DESTINATION_PROMPT:
                return new RecipeDestinationPromptState(step as OrcaStep);
            case RecipeStates.DESTINATION_REPORT:
                return new RecipeDestinationReportState(step as RhinoStep);
            case RecipeStates.HANDOFF_STATUS_PROMPT:
                return new RecipeHandoffStatusPromptState(step as OrcaStep);
            case RecipeStates.HANDOFF_STATUS_REPORT:
                return new RecipeHandoffStatusReportState(step as RhinoStep);
            case RecipeStates.HANDOFF_TIME_PROMPT:
                return new RecipeHandoffTimePromptState(step as OrcaStep);
            case RecipeStates.HANDOFF_TIME_REPORT:
                return new RecipeHandoffTimeReportState(step as RhinoStep);
            case RecipeStates.FINAL_NOTE_PROMPT:
                return new RecipeFinalNotePromptState(step as OrcaStep);
            case RecipeStates.FINAL_NOTE_REPORT:
                return new RecipeFinalNoteRecordState(step as CheetahStep);
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
                    const state = this.states[current_state.state] as RecipeIdentifyUnitPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.IDENTIFY_UNIT_REPORT: {
                    const state = this.states[current_state.state] as RecipeIdentifyUnitReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.INCIDENT_TYPE_PROMPT: {
                    const state = this.states[current_state.state] as RecipeIncidentTypePromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.INCIDENT_TYPE_REPORT: {
                    const state = this.states[current_state.state] as RecipeIncidentTypeReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.PATIENT_CONDITION_PROMPT: {
                    const state = this.states[current_state.state] as RecipePatientConditionPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.PATIENT_CONDITION_REPORT: {
                    const state = this.states[current_state.state] as RecipePatientConditionReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.DESTINATION_PROMPT: {
                    const state = this.states[current_state.state] as RecipeDestinationPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.DESTINATION_REPORT: {
                    const state = this.states[current_state.state] as RecipeDestinationReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.HANDOFF_STATUS_PROMPT: {
                    const state = this.states[current_state.state] as RecipeHandoffStatusPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.HANDOFF_STATUS_REPORT: {
                    const state = this.states[current_state.state] as RecipeHandoffStatusReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.HANDOFF_TIME_PROMPT: {
                    const state = this.states[current_state.state] as RecipeHandoffTimePromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.HANDOFF_TIME_REPORT: {
                    const state = this.states[current_state.state] as RecipeHandoffTimeReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.FINAL_NOTE_PROMPT: {
                    const state = this.states[current_state.state] as RecipeFinalNotePromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.FINAL_NOTE_REPORT: {
                    const state = this.states[current_state.state] as RecipeFinalNoteRecordState;
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
            next: { state: RecipeStates.IDENTIFY_UNIT_PROMPT, tasks, taskIndex: 0 }
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

type RecipeReportStateParams = {
        tasks: PickTask[];
        taskIndex: number;

        listeningPrompt: string;
        expectedIntent: string;
        validator: (x: RhinoInference) => boolean;
        successPrompt: (x: RhinoInference) => string;
        successOutcome: (x: RhinoInference) => string;
        successNextState: RecipeStates;
        failurePrompt: (x: RhinoInference | undefined) => string;
        failureNextState: RecipeStates;
        failureNextStateKwargs: any;
};

class RecipeReportState extends State {
    private step: RhinoStep;

    constructor(step: RhinoStep) {
        super();
        this.step = step;
    }

    async runReport(params: RecipeReportStateParams) {
        const {
            tasks,
            taskIndex,
            listeningPrompt,
            expectedIntent,
            validator,
            successPrompt,
            successOutcome,
            successNextState,
            failurePrompt,
            failureNextState,
            failureNextStateKwargs
        } = params;

        const task = tasks[taskIndex];
        const cardId = task.cardId;

        callbacks.setCardValue(cardId, "...");
        const inference = await this.step.run(listeningPrompt);

        const isValidInference =
            inference &&
            inference.isUnderstood &&
            inference.intent == expectedIntent &&
            validator(inference!);

        if (isValidInference) {
            callbacks.setStatusText(successPrompt(inference!));
            callbacks.setCompletedCard(cardId);
            callbacks.setCardValue(cardId, successOutcome(inference!));

            await sleep(1000);

            return {
                outcome: inference,
                next: { state: successNextState, tasks, taskIndex: taskIndex + 1 }
            };
        }

        callbacks.setStatusText(failurePrompt(inference));
        return {
            next: {
                state: failureNextState,
                tasks,
                taskIndex,
                ...failureNextStateKwargs
            }
        };
    }
}

class RecipeIdentifyUnitPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
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
    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for unit ID",
            expectedIntent: 'identifyUnit',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Unit ID is ${x.slots!.unitId}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.unitId}`,
            successNextState: RecipeStates.INCIDENT_TYPE_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture unit ID. Retrying...`,
            failureNextState: RecipeStates.IDENTIFY_UNIT_PROMPT,
            failureNextStateKwargs: {
                inputPrompt: "I'm sorry, I didn't catch that. What is the unit ID again?"
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
        const cardId = task.cardId;
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
    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for incident type",
            expectedIntent: 'reportIncidentType',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Incident type is ${x.slots!.incidentType}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.incidentType}`,
            successNextState: RecipeStates.PATIENT_CONDITION_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture incident type. Retrying...`,
            failureNextState: RecipeStates.INCIDENT_TYPE_PROMPT,
            failureNextStateKwargs: {
                inputPrompt: "I'm sorry, I didn't catch that. What was the incident type again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipePatientConditionPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What is the patient condition?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.PATIENT_CONDITION_REPORT, tasks, taskIndex }
        };
    }
}

class RecipePatientConditionReportState extends RecipeReportState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for patient condition",
            expectedIntent: 'reportPatientCondition',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Patient condition is ${x.slots!.patientCondition}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.patientCondition}`,
            successNextState: RecipeStates.DESTINATION_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture patient condition. Retrying...`,
            failureNextState: RecipeStates.PATIENT_CONDITION_PROMPT,
            failureNextStateKwargs: {
                inputPrompt: "I'm sorry, I didn't catch that. What is the patient condition again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeDestinationPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What was the destination?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.DESTINATION_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeDestinationReportState extends RecipeReportState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for destination",
            expectedIntent: 'reportDestination',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Destination is ${x.slots!.destination}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.destination}`,
            successNextState: RecipeStates.HANDOFF_STATUS_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture destination. Retrying...`,
            failureNextState: RecipeStates.DESTINATION_PROMPT,
            failureNextStateKwargs: {
                inputPrompt: "I'm sorry, I didn't catch that. What was the destination again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeHandoffStatusPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What is the handoff status?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.HANDOFF_STATUS_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeHandoffStatusReportState extends RecipeReportState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for handoff status",
            expectedIntent: 'reportHandoffStatus',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Handoff status is ${x.slots!.handoffStatus}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.handoffStatus}`,
            successNextState: RecipeStates.HANDOFF_TIME_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture handoff status. Retrying...`,
            failureNextState: RecipeStates.HANDOFF_STATUS_PROMPT,
            failureNextStateKwargs: {
                inputPrompt: "I'm sorry, I didn't catch that. What is the handoff status again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeHandoffTimePromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What is the handoff time?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.HANDOFF_TIME_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeHandoffTimeReportState extends RecipeReportState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        const HOUR_MAP: { [id: string] : number } = {
            "one": 1,
            "two": 2,
            "three": 3,
            "four": 4,
            "five": 5,
            "six": 6,
            "seven": 7,
            "eight": 8,
            "nine": 9,
            "ten": 10,
            "eleven": 11,
            "twelve": 12,
        };

        const validator = (x: RhinoInference): boolean => {
            const hour = HOUR_MAP[x.slots!.hour]
            const minute = Number(x.slots!.minute)
            const meridiem = x.slots!.meridiem

            return (1 <= hour) && (hour <= 12) &&
                    (0 <= minute) && (minute <= 59) &&
                    (meridiem === 'am' || meridiem === 'pm');
        };

        const successOutcome = (x: RhinoInference): string => {
            const hour = HOUR_MAP[x.slots!.hour]
            const minute = Number(x.slots!.minute)
            const meridiem = x.slots!.meridiem

            return `${hour}:${minute} ${meridiem}`;
        };

        const params = {
            tasks,
            taskIndex,

            listeningPrompt: "Listening for handoff time",
            expectedIntent: 'reportHandoffTime',
            validator,
            successPrompt: (x: RhinoInference) => `Handoff time is ${successOutcome(x)}`,
            successOutcome,
            successNextState: RecipeStates.FINAL_NOTE_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture handoff time. Retrying...`,
            failureNextState: RecipeStates.HANDOFF_TIME_PROMPT,
            failureNextStateKwargs: {
                inputPrompt: "I'm sorry, I didn't catch that. What was the handoff time again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeFinalNotePromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["Please provide additional notes."];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.FINAL_NOTE_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeFinalNoteRecordState extends State {
    private step: CheetahStep;

    constructor(step: CheetahStep) {
        super();
        this.step = step;
    }

    async run(tasks: PickTask[], taskIndex: number): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;

        let transcript = ""

        const onPartial = (partial: string) => {
            transcript += partial
            callbacks.setCardValue(cardId, transcript);
        }

        const finalTranscript = await this.step.run("Listening for additional notes", onPartial);

        callbacks.setCompletedCard(cardId);
        callbacks.setCardValue(cardId, finalTranscript);

        await sleep(1000);

        return {
            next: { state: RecipeStates.COMPLETE_PROMPT, tasks, taskIndex: taskIndex + 1 },
            outcome: finalTranscript,
        };
    }
}

class RecipeCompletePromptState extends RecipePromptState {
    async run(
        prompt: string = "Field report recorded.",
    ): Promise<Transition> {
        await this.runPrompt(prompt)

        await sleep(1000);

        return null;
    }
}