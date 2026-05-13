export enum Action {
    GREET = "Greet",
    CONNECT_CALL = "Connect Call",
    DECLINE_CALL = "Decline Call",
    ASK_FOR_DETAILS = "Ask for Details",
    ASK_TO_TEXT = "Ask to Text",
    ASK_TO_EMAIL = "Ask to Email",
    ASK_TO_CALL_BACK = "Ask to Call Back",
    BLOCK_CALLER = "Block Caller"
};

export function promptFromAction(action: Action, username: string): string {
    switch (action) {
        case Action.GREET:
            return `Hi, ${username} can't answer right now. Please say your name and why you're calling.`;
        case Action.CONNECT_CALL:
            return "Okay, one moment while I connect you.";
        case Action.DECLINE_CALL:
            return `Sorry, ${username} is unavailable right now. Goodbye!`;
        case Action.ASK_FOR_DETAILS:
            return "Can you briefly say what this is regarding?";
        case Action.ASK_TO_TEXT:
            return `${username} can't talk right now. Please send a text message instead. Goodbye!`;
        case Action.ASK_TO_EMAIL:
            return "Please send the details by email. Thank you. Goodbye!";
        case Action.ASK_TO_CALL_BACK:
            return `${username} can't take your call right now. Please call back later. Goodbye!`;
        case Action.BLOCK_CALLER:
            return "This number is not accepting calls. Goodbye!";
        default:
            throw new Error("Got unexpected action.");
    }
}

export function isTerminalFromAction(action: Action): boolean {
    switch (action) {
        case Action.GREET: return false;
        case Action.CONNECT_CALL: return true;
        case Action.DECLINE_CALL: return true;
        case Action.ASK_FOR_DETAILS: return false;
        case Action.ASK_TO_TEXT: return true;
        case Action.ASK_TO_EMAIL: return true;
        case Action.ASK_TO_CALL_BACK: return true;
        case Action.BLOCK_CALLER: return true;
        default: return true;
    }
}

export function allActions(): string {
    let result = "";
    for (const key of Object.keys(Action)) {
        result += Action[key as keyof typeof Action] + ",";
    }
    return result;
}