package ai.picovoice.callscreen;

public enum Action {
    GREET("Greet"),
    CONNECT_CALL("Connect Call"),
    DECLINE_CALL("Decline Call"),
    ASK_FOR_DETAILS("Ask for Details"),
    ASK_TO_TEXT("Ask to Text"),
    ASK_TO_EMAIL("Ask to Email"),
    ASK_TO_CALL_BACK("Ask to Call Back"),
    BLOCK_CALLER("Block Caller");

    private final String value;

    Action(String value) {
        this.value = value;
    }

    public String prompt(String username) {
        switch (this) {
            case GREET:
                return String.format(
                    "Hi, %s can't answer right now. Please say your name and why you're calling.",
                    username);
            case CONNECT_CALL:
                return "Okay, one moment while I connect you.";
            case DECLINE_CALL:
                return String.format(
                    "Sorry, %s is unavailable right now. Goodbye!",
                    username);
            case ASK_FOR_DETAILS:
                return "Can you briefly say what this is regarding?";
            case ASK_TO_TEXT:
                return String.format(
                    "%s can't talk right now. Please send a text message instead. Goodbye!",
                    username);
            case ASK_TO_EMAIL:
                return "Please send the details by email. Thank you. Goodbye!";
            case ASK_TO_CALL_BACK:
                return String.format(
                    "%s can't take your call right now. Please call back later. Goodbye!",
                    username);
            case BLOCK_CALLER:
                return "This number is not accepting calls. Goodbye!";
            default: throw new IllegalArgumentException(String.format("Got unexpected action: %d", this));
        }
    }

    /// @brief Terminal, as in final.
    public Boolean isTerminal() {
        switch(this) {
            case GREET: return false;
            case CONNECT_CALL: return true;
            case DECLINE_CALL: return true;
            case ASK_FOR_DETAILS: return false;
            case ASK_TO_TEXT: return true;
            case ASK_TO_EMAIL: return true;
            case ASK_TO_CALL_BACK: return true;
            case BLOCK_CALLER: return true;
            default: return true;
        }
    }

    public static String all() {
        String result = "";
        for (Action action : values()) {
            result += "  " + action.value + "\n";
        }
        return result;
    }

    public static Action fromString(String s) {
        for (Action action : values()) {
            if (action.value.equals(s)) {
                return action;
            }
        }

        throw new IllegalArgumentException("Provided string is not an Action: " + s);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
