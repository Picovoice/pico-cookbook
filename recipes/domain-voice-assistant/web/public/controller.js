window.onload = () => {
  const SCROLL_STICK_PX = 40;

  const loadContainerParent = document.getElementById("load-container-parent");
  const error = document.getElementById("error");
  const accessKey = document.getElementById("access-key");
  const startButton = document.getElementById("start-button");
  const statusContainer = document.getElementById("status-container");
  const status = document.getElementById("status");

  const mainContainerParent = document.getElementById("main-container-parent");
  const mainError = document.getElementById("main-error");
  const statePill = document.getElementById("state-pill");
  const stateText = document.getElementById("state-text");
  const chatBlock = document.getElementById("chat-block");
  const emptyState = document.getElementById("empty-state");
  const hint = document.getElementById("hint");
  const volumeMeter = document.getElementById("volume-meter");
  const bar1 = document.getElementById("bar1");
  const bar2 = document.getElementById("bar2");
  const bar3 = document.getElementById("bar3");

  const questionForm = document.getElementById("question-form");
  const question = document.getElementById("question");
  const stopButton = document.getElementById("stop-button");
  const resetButton = document.getElementById("reset-button");
  const exampleButtons = document.querySelectorAll(".example");

  let humanElem = null;
  let answer = null;
  let isListening = false;
  let currentState = null;
  let stateBeforeBargeIn = null;

  const writeError = (errorString) => {
    error.classList.remove("hidden");
    error.innerText = `Error: ${errorString}`;
    statusContainer.classList.add("hidden");
    startButton.disabled = accessKey.value.length === 0;
  };

  const writeMainError = (errorString) => {
    mainError.classList.remove("hidden");
    mainError.innerText = `Error: ${errorString}`;
  };

  const setState = (state, text, hintText) => {
    currentState = [state, text, hintText];
    statePill.dataset.state = state;
    stateText.innerText = text;
    hint.innerText = hintText;
    isListening = state === "listening";
    volumeMeter.style.opacity = isListening ? "1" : "0.3";
    if (!isListening) {
      [bar1, bar2, bar3].forEach((bar) => {
        bar.style.height = "8px";
      });
    }
  };

  const scrollToBottom = () => {
    chatBlock.scrollTop = chatBlock.scrollHeight;
  };

  const isNearBottom = () =>
    chatBlock.scrollHeight - chatBlock.scrollTop - chatBlock.clientHeight < SCROLL_STICK_PX;

  const addBubble = (isUser) => {
    emptyState.classList.add("hidden");

    const label = document.createElement("div");
    label.className = isUser ? "bubble-label user" : "bubble-label";
    label.innerText = isUser ? "You" : "Assistant";

    const bubble = document.createElement("div");
    bubble.className = isUser ? "user-bubble" : "ai-bubble";

    chatBlock.appendChild(label);
    chatBlock.appendChild(bubble);
    scrollToBottom();
    return bubble;
  };

  const removeBubble = (bubble) => {
    bubble.previousElementSibling.remove();
    bubble.remove();
    if (chatBlock.querySelector(".user-bubble, .ai-bubble") === null) {
      emptyState.classList.remove("hidden");
    }
  };

  const renderAnswer = (target) => {
    const stick = isNearBottom();
    target.elem.innerHTML = Picovoice.toHtml(target.text);
    if (stick) {
      scrollToBottom();
    }
  };

  if (typeof Picovoice === "undefined") {
    writeError("You must run `yarn build` before running `yarn start`.");
    return;
  }

  const callbacks = {
    onLoading: (engine) => {
      status.innerText = `Loading ${engine}...`;
    },
    onWaiting: () => {
      humanElem = null;
      setState("waiting", "Say the wake word", "Or type a question below.");
    },
    onWakeWord: () => {
      if (humanElem !== null) {
        humanElem.previousElementSibling.remove();
        humanElem.remove();
        humanElem = null;
        if (chatBlock.querySelector(".user-bubble, .ai-bubble") === null) {
          emptyState.classList.remove("hidden");
        }
      }
      setState("listening", "Listening…", "Ask your question.");
    },
    onListening: () => {
      humanElem = null;
      setState("listening", "Listening…", "Ask a follow-up, no need to say the wake word.");
    },
    onTranscript: (transcript) => {
      if (humanElem === null) {
        humanElem = addBubble(true);
      }
      humanElem.textContent += transcript;
      scrollToBottom();
    },
    onQuestion: (questionText) => {
      if (humanElem === null) {
        humanElem = addBubble(true);
      }
      humanElem.textContent = questionText;
      humanElem = null;
      scrollToBottom();
    },
    onAnswerStart: () => {
      setState("thinking", "Thinking…", "Talk over it or press Stop to cancel.");
      const elem = addBubble(false);
      elem.innerHTML = "<span class='typing'><span></span><span></span><span></span></span>";
      answer = { elem, text: "" };
      stopButton.disabled = false;
    },
    onText: (text) => {
      answer.text += text;
      renderAnswer(answer);
    },
    onSpeaking: () => {
      setState("speaking", "Speaking", "Talk over it or press Stop to interrupt.");
    },
    onBargeIn: (isActive) => {
      if (isActive) {
        stateBeforeBargeIn = currentState;
        setState("listening", "Listening to you…", "");
      } else if (stateBeforeBargeIn !== null) {
        setState(...stateBeforeBargeIn);
        stateBeforeBargeIn = null;
      }
    },
    onAnswerEnd: (interrupted) => {
      stopButton.disabled = true;
      if (answer !== null) {
        if (answer.text.trim().length === 0) {
          removeBubble(answer.elem);
        } else if (interrupted) {
          answer.elem.classList.add("interrupted");
        }
      }
      answer = null;
    },
    onVolume: (volume) => {
      if (!isListening) {
        return;
      }
      const baseHeight = 8;
      bar1.style.height = `${baseHeight + volume * 20}px`;
      bar2.style.height = `${baseHeight + volume * 32}px`;
      bar3.style.height = `${baseHeight + volume * 24}px`;
    },
    onError: (errorString) => {
      writeMainError(errorString);
    },
  };

  accessKey.oninput = () => {
    startButton.disabled = accessKey.value.length === 0;
  };

  startButton.onclick = async () => {
    startButton.disabled = true;
    error.classList.add("hidden");
    statusContainer.classList.remove("hidden");
    status.innerText = "Loading...";

    try {
      await Picovoice.init(accessKey.value, callbacks);
    } catch (e) {
      writeError(e.message);
      return;
    }

    loadContainerParent.classList.add("hidden");
    mainContainerParent.classList.remove("hidden");

    try {
      await Picovoice.start();
    } catch (e) {
      writeMainError(e.message);
    }
  };

  const ask = (text) => {
    if (text.trim().length > 0) {
      mainError.classList.add("hidden");
      Picovoice.ask(text);
    }
  };

  questionForm.onsubmit = (event) => {
    event.preventDefault();
    ask(question.value);
    question.value = "";
  };

  exampleButtons.forEach((button) => {
    button.onclick = () => ask(button.innerText);
  });

  stopButton.onclick = () => {
    Picovoice.stop();
  };

  resetButton.onclick = () => {
    chatBlock.querySelectorAll(".bubble-label, .user-bubble, .ai-bubble").forEach((elem) => elem.remove());
    emptyState.classList.remove("hidden");
    mainError.classList.add("hidden");
    humanElem = null;
    answer = null;
    Picovoice.resetDialog();
  };
};
