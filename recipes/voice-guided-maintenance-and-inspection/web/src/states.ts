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
    CHECK_OIL_PROMPT = "CheckOilPrompt",
    CHECK_OIL_REPORT = "CheckOilReport",
    CHECK_TIRE_PROMPT = "CheckTirePrompt",
    CHECK_TIRE_REPORT = "CheckTireReport",
    CHECK_SERVICE_STATUS_PROMPT = "CheckServiceStatusPrompt",
    CHECK_SERVICE_STATUS_REPORT = "CheckServiceStatusReport",
    FINAL_NOTE_PROMPT = "FinalNotePrompt",
    FINAL_NOTE_REPORT = "FinalNoteReport",
    REPORT_COMPILATION = "ReportCompilation",
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
        state: RecipeStates.CHECK_OIL_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.CHECK_OIL_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.CHECK_TIRE_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.CHECK_TIRE_REPORT,
        tasks: PickTask[],
        taskIndex: number,
    }
    | {
        state: RecipeStates.CHECK_SERVICE_STATUS_PROMPT,
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    }
    | {
        state: RecipeStates.CHECK_SERVICE_STATUS_REPORT,
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
        state: RecipeStates.REPORT_COMPILATION,
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
            case RecipeStates.CHECK_OIL_PROMPT:
                return new RecipeCheckOilPromptState(step as OrcaStep);
            case RecipeStates.CHECK_OIL_REPORT:
                return new RecipeCheckOilReportState(step as RhinoStep);
            case RecipeStates.CHECK_TIRE_PROMPT:
                return new RecipeCheckTirePromptState(step as OrcaStep);
            case RecipeStates.CHECK_TIRE_REPORT:
                return new RecipeCheckTireReportState(step as RhinoStep);
            case RecipeStates.CHECK_SERVICE_STATUS_PROMPT:
                return new RecipeCheckServiceStatusPromptState(step as OrcaStep);
            case RecipeStates.CHECK_SERVICE_STATUS_REPORT:
                return new RecipeCheckServiceStatusReportState(step as RhinoStep);
            case RecipeStates.FINAL_NOTE_PROMPT:
                return new RecipeFinalNotePromptState(step as OrcaStep);
            case RecipeStates.FINAL_NOTE_REPORT:
                return new RecipeFinalNoteRecordState(step as CheetahStep);
            case RecipeStates.REPORT_COMPILATION:
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
                } case RecipeStates.CHECK_OIL_PROMPT: {
                    const state = this.states[current_state.state] as RecipeCheckOilPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.CHECK_OIL_REPORT: {
                    const state = this.states[current_state.state] as RecipeCheckOilReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.CHECK_TIRE_PROMPT: {
                    const state = this.states[current_state.state] as RecipeCheckTirePromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.CHECK_TIRE_REPORT: {
                    const state = this.states[current_state.state] as RecipeCheckTireReportState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex);
                    break;
                } case RecipeStates.CHECK_SERVICE_STATUS_PROMPT: {
                    const state = this.states[current_state.state] as RecipeCheckServiceStatusPromptState;
                    transition = await state.run(current_state.tasks, current_state.taskIndex, current_state.inputPrompt);
                    break;
                } case RecipeStates.CHECK_SERVICE_STATUS_REPORT: {
                    const state = this.states[current_state.state] as RecipeCheckServiceStatusReportState;
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
                } case RecipeStates.REPORT_COMPILATION: {
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
        failureNextStateParams: any;
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
            failureNextStateParams
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
                ...failureNextStateParams
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
            successNextState: RecipeStates.CHECK_OIL_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture unit ID. Retrying...`,
            failureNextState: RecipeStates.IDENTIFY_UNIT_PROMPT,
            failureNextStateParams: {
                inputPrompt: "I'm sorry, I didn't catch that. What is the unit ID again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeCheckOilPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What's the oil level?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.CHECK_OIL_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeCheckOilReportState extends RecipeReportState {
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

            listeningPrompt: "Listening for oil status...",
            expectedIntent: 'reportOilCondition',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Oil level is ${x.slots!.fluidCondition}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.fluidCondition}`,
            successNextState: RecipeStates.CHECK_TIRE_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture oil condition. Retrying...`,
            failureNextState: RecipeStates.CHECK_OIL_PROMPT,
            failureNextStateParams: {
                inputPrompt: "I'm sorry, I didn't catch that. What is the oil level again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeCheckTirePromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What is the tire condition?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.CHECK_TIRE_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeCheckTireReportState extends RecipeReportState {
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

            listeningPrompt: "Listening for tire condition...",
            expectedIntent: 'reportTireCondition',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Tire condition is ${x.slots!.tireCondition}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.tireCondition}`,
            successNextState: RecipeStates.CHECK_SERVICE_STATUS_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture tire condition. Retrying...`,
            failureNextState: RecipeStates.CHECK_TIRE_PROMPT,
            failureNextStateParams: {
                inputPrompt: "I'm sorry, I didn't catch that. What is the tire condition again?"
            }
        };
        return await this.runReport(params);
    }
}

class RecipeCheckServiceStatusPromptState extends RecipePromptState {
    async run(
        tasks: PickTask[],
        taskIndex: number,
        inputPrompt?: string | string[],
    ): Promise<Transition> {
        const task = tasks[taskIndex];
        const cardId = task.cardId;
        callbacks.setActiveCard(cardId);

        if (!inputPrompt) {
            inputPrompt = ["What's the vehicle status?"];
        } else if (!Array.isArray(inputPrompt)) {
            inputPrompt = [inputPrompt];
        }

        for (const prompt of inputPrompt) {
            if (!isRunning)
                break;

            await this.runPrompt(prompt);
        }

        return {
            next: { state: RecipeStates.CHECK_SERVICE_STATUS_REPORT, tasks, taskIndex }
        };
    }
}

class RecipeCheckServiceStatusReportState extends RecipeReportState {
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

            listeningPrompt: "Listening for vehicle status...",
            expectedIntent: 'reportServiceStatus',
            validator: (x: RhinoInference) => true,
            successPrompt: (x: RhinoInference) => `Destination is ${x.slots!.serviceStatus}.`,
            successOutcome: (x: RhinoInference) => `${x.slots!.serviceStatus}`,
            successNextState: RecipeStates.FINAL_NOTE_PROMPT,
            failurePrompt: (x: RhinoInference | undefined) => `Failed to capture vehicle status. Retrying...`,
            failureNextState: RecipeStates.CHECK_SERVICE_STATUS_PROMPT,
            failureNextStateParams: {
                inputPrompt: "I'm sorry, I didn't catch that. What is the vehicle status again?"
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
            next: { state: RecipeStates.REPORT_COMPILATION, tasks, taskIndex: taskIndex + 1 },
            outcome: finalTranscript,
        };
    }
}

class RecipeCompletePromptState extends RecipePromptState {
    async run(
        prompt: string = "Inspection report recorded.",
    ): Promise<Transition> {
        await this.runPrompt(prompt)

        await sleep(1000);

        return null;
    }
}