import { OrcaAlignment } from '@picovoice/orca-web';

import { AINoiseSuppressedRecorder } from './ai_noise_suppressed_recorder';
import { AudioStream } from './audio_stream';
import { Step, Steps, StepOptions, OrcaStep, PorcupineStep, RhinoStep } from './steps';

type Transition = {
    outcome?: string,
    next: StateOptions,
} | null;

type Outcome = {
    state: RecipeStates,
    outcome?: string,
};

type PickTask = {
    location_name: string;
    check_digit: string;
    item_name: string;
    quantity: number;
};

export const TASKS: PickTask[] = [
    {
        location_name: "bin bravo",
        check_digit: "four two",
        item_name: "blue widgets",
        quantity: 3
    },
    {
        location_name: "bin delta",
        check_digit: "five seven",
        item_name: "battery packs",
        quantity: 5
    },
    {
        location_name: "zone one",
        check_digit: "one nine",
        item_name: "safety gloves",
        quantity: 1
    },
];

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
        tasks: PickTask[] = TASKS,
    }
    | {
        state: RecipeStates.TASK_LOCATION_PROMPT,
        tasks: PickTask[] = TASKS,
        task_index: number = 0,
        prompt?: string,
    }
    | {
        state: RecipeStates.TASK_LOCATION_REPORT,
        tasks: PickTask[] = TASKS,
        task_index: number = 0,
    }
    | {
        state: RecipeStates.TASK_PICK_PROMPT,
        tasks: PickTask[] = TASKS,
        task_index: number = 0,
        prompt?: string,
    }
    | {
        state: RecipeStates.TASK_PICK_REPORT,
        tasks: PickTask[] = TASKS,
        task_index: number = 0,
    }
    | {
        state: RecipeStates.COMPLETE_PROMPT,
        prompt: string = "Picking workflow complete.",
    };

export abstract class State {
    abstract toString(): string;

    // TODO: maybe this abstract class pattern is sucky, but at least it works for now
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
            // TODO: trigger status here
            console.log(`[OK] ${this.steps[uid]}`);
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

    run() {
        let current_state = this.start_state;

        while (true) {
            let transition;
            switch (current_state.state) {
                case RecipeStates.STANDBY: {
                    const state = this.states[current_state.state] as RecipeStandbyState;
                    transition = state.run(current_state.tasks);
                    break;
                } case RecipeStates.TASK_LOCATION_PROMPT: {
                    const state = this.states[current_state.state] as RecipeTaskLocationPromptState;
                    transition = state.run(current_state.tasks, current_state.task_index, current_state.prompt);
                    break;
                } case RecipeStates.TASK_LOCATION_REPORT: {
                    const state = this.states[current_state.state] as RecipeTaskLocationReportState;
                    transition = state.run(current_state.tasks, current_state.task_index);
                    break;
                } case RecipeStates.TASK_PICK_PROMPT: {
                    const state = this.states[current_state.state] as RecipeTaskPickPromptState;
                    transition = state.run(current_state.tasks, current_state.task_index, current_state.prompt);
                    break;
                } case RecipeStates.TASK_PICK_REPORT: {
                    const state = this.states[current_state.state] as RecipeTaskPickReportState;
                    transition = state.run(current_state.tasks, current_state.task_index);
                    break;
                } case RecipeStates.COMPLETE_PROMPT: {
                    const state = this.states[current_state.state] as RecipeCompletePromptState;
                    transition = state.run(current_state.prompt);
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

    release() {
        for (const step of reversed(this.steps.values()) {
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

class RecipePromptState extends State {
    private step: OrcaStep;

    constructor(step: OrcaStep) {
        super();
        this.step = step;
    }

    run_prompt(prompt: string) {
        text = ""
        lock = Lock()

        const get_text = (): string => {
            with lock:
                return f"[AI] {text}"
        }

        print_event, print_thread = print_async(get_text)

        const on_tick = (chunk: string) => {
            nonlocal text
            with lock:
                text += chunk
        }

        timer_thread = None

        const onSynthesis = (alignments: OrcaAlignment[]) => {
            nonlocal timer_thread
            timer_thread = time_async(alignments=alignments, on_tick=on_tick)
        }

        await this.step.run(prompt, onSynthesis);

        // noinspection PyUnresolvedReferences
        timer_thread.join()
        print_event.set()
        print_thread.join()
    }
}

class RecipeStandbyState extends State {
    private step: PorcupineStep;

    constructor(step: PorcupineStep) {
        super();
        this.step = step;
    }

    run(
        tasks: Optional[Sequence[PickTask]] = None,
    ): Transition {
        let text = "Listening for wake word";

        function get_text(): string {
            return text;
        }

        event, thread = print_async(get_text=get_text)
        await this.step.run();

        text = "Detected wake word. Starting picking workflow..."
        sleep(.1)
        event.set()
        thread.join()

        if (tasks is None) {
            tasks = TASKS;
        }

        if (len(tasks) == 0) {
            return {
                next: { state: RecipeStates.COMPLETE_PROMPT,
                // TODO: what did these do? Nothing? They were probably a bug
                // tasks,
                // task_index: 0
                }
            };
        }

        return { 
            next: { state: RecipeStates.TASK_LOCATION_PROMPT, tasks, task_index: 0 }
        };
    }
}

class RecipeTaskLocationPromptState extends State {
    private step: OrcaStep;

    constructor(step: OrcaStep) {
        super();
        this.step = step;
    }

    run(
        tasks: Sequence[PickTask] = TASKS,
        task_index: int = 0,
        prompt: Optional[str] = None,
    ): Transition {
        task = tasks[task_index]
        if prompt is None:
            prompt = (
                f"Go to {task.location_name}. "
                f"Confirm location. "
                f"Check digits are {task.check_digit}."
            )

        this._run_prompt(prompt=prompt)

        return {
            next: { state: RecipeStates.TASK_LOCATION_REPORT, tasks, task_index }
        };
    }
}

class RecipeTaskLocationReportState extends State {
    private step: RhinoStep;

    constructor(step: RhinoStep) {
        super();
        this.step = step;
    }

    run(
        tasks: Sequence[PickTask] = TASKS,
        task_index: int = 0,
    ): Transition {
        task = tasks[task_index]
        text = "Listening for location confirmation"

        function get_text(): string {
            return text;
        }

        event, thread = print_async(get_text=get_text)
        const inference = await this.step.run();

        is_valid_location = \
            inference is not None and \
            inference['is_understood'] and \
            inference['intent'] == 'confirmLocation' and \
            inference['slots'].get('checkDigit') == task.check_digit

        if (is_valid_location) {
            text = f"Location {inference['slots']['checkDigit']} confirmed."
            sleep(.1)
            event.set()
            thread.join()

            return {
                outcome: inference,
                next: { state: RecipeStates.TASK_PICK_PROMPT, tasks, task_index, }
            };
        }

        if (inference is not None and inference['is_understood'] and inference['intent'] == 'confirmLocation') {
            text = f"Location check digit {inference['slots'].get('checkDigit', '')} does not match. Retrying..."
        } else {
            text = "Failed to capture location confirmation. Retrying..."
        }

        sleep(.1)
        event.set()
        thread.join()

        return {
            outcome: inference,
            next: {
                state: RecipeStates.TASK_LOCATION_PROMPT,
                tasks,
                task_index,
                prompt: `Please confirm location for ${task.location_name}. Check digits are ${task.check_digit}.`,
            }
        };
    }
}

class RecipeTaskPickPromptState extends State {
    private step: OrcaStep;

    constructor(step: OrcaStep) {
        super();
        this.step = step;
    }

    run(
        tasks: Sequence[PickTask] = TASKS,
        task_index: int = 0,
        prompt: Optional[str] = None,
    ): Transition {
        task = tasks[task_index]
        if (prompt is None) {
            prompt = f"Pick {task.quantity} {task.item_name}."
        }

        this._run_prompt(prompt=prompt)

        return {
            next: { state: RecipeStates.TASK_PICK_REPORT, tasks, task_index }
        };
    }
}

class RecipeTaskPickReportState extends State {
    private step: RhinoStep;

    constructor(step: RhinoStep) {
        super();
        this.step = step;
    }

    static _next_location_prompt(task: PickTask): string {
        return (
            `Go to {task.location_name}. ` +
            `Confirm location. ` +
            `Check digits are {task.check_digit}.`
        )
    }

    run(
        tasks: Sequence[PickTask] = TASKS,
        task_index: int = 0,
    ): Transition {
        task = tasks[task_index]
        text = "Listening for pick result"

        function get_text(): string {
            return text
        }

        event, thread = print_async(get_text=get_text)
        const inference = await this.step.run();

        valid_intents = {
            'confirmPickedQuantity',
            'reportShortPick',
            'reportDamagedItem',
            'reportLocationEmpty',
            'exitWorkflow',
        }

        if (inference is not None and inference['is_understood'] and inference['intent'] in valid_intents) {
            intent = inference['intent']
            slots = inference['slots']

            if (intent == 'exitWorkflow') {
                text = "Ending picking workflow."
                sleep(.1)
                event.set()
                thread.join()

                return {
                    outcome: inference,
                    next: {
                        state: RecipeStates.COMPLETE_PROMPT,
                        // TODO: were these a bug?
                        // tasks,
                        // task_index,
                        prompt: "Picking workflow ended.",
                    }
                };
            }

            next_task_index = task_index + 1

            if (next_task_index >= len(tasks)) {
                if (intent == 'confirmPickedQuantity') {
                    text = f"Recorded picked {slots['quantity']}."
                } else if (intent == 'reportShortPick') {
                    text = f"Recorded short pick {slots['quantity']}."
                } else if (intent == 'reportDamagedItem') {
                    text = "Recorded damaged item."
                } else {
                    text = "Recorded empty location."
                }

                sleep(.1)
                event.set()
                thread.join()

                return {
                    outcome: inference,
                    next: { state: RecipeStates.COMPLETE_PROMPT },
                    // 'tasks': tasks,
                    // 'task_index': next_task_index,
                };
            }

            next_task = tasks[next_task_index]

            if (intent == 'confirmPickedQuantity') {
                text = `Recorded picked {slots['quantity']}.`
                next_prompt = this._next_location_prompt(next_task)
            } else if (intent == 'reportShortPick') {
                text = `Recorded short pick {slots['quantity']}.`
                next_prompt = (
                    `Short pick recorded. `
                    `Proceed to {next_task.location_name}. `
                    `Confirm location. `
                    `Check digits are {next_task.check_digit}.`
                )
            } else if (intent == 'reportDamagedItem') {
                text = "Recorded damaged item."
                next_prompt = (
                    `Damaged item recorded. Set it aside. `
                    `Then proceed to {next_task.location_name}. `
                    `Confirm location. `
                    `Check digits are {next_task.check_digit}.`
                )
            } else {
                text = "Recorded empty location."
                next_prompt = (
                    `Empty location recorded. `
                    `Proceed to {next_task.location_name}. `
                    `Confirm location. `
                    `Check digits are {next_task.check_digit}.`
                )
            }

            sleep(.1)
            event.set()
            thread.join()

            return {
                outcome: inference,
                next: {
                    state: RecipeStates.TASK_LOCATION_PROMPT,
                    tasks,
                    task_index: next_task_index,
                    prompt: next_prompt,
                }
            };
        }

        text = "Failed to capture pick result. Retrying..."
        sleep(.1)
        event.set()
        thread.join()

        return {
            outcome: inference,
            next: {
                state: RecipeStates.TASK_PICK_PROMPT,
                tasks,
                task_index,
                prompt: `Please report the result for picking ${task.quantity} ${task.item_name}.`,
            }
        };
    }
}

class RecipeCompletePromptState extends RecipePromptState {
    run(
        prompt: string = "Picking workflow complete.",
    ): Transition {
        this.run_prompt(prompt=prompt)
        // TODO: what happens if the transition is empty?
        return null;
    }
}