function sanitizeForOrca(str) {
  replacements = {
    "\n": " ",
    "\r": " ",
    "\t": " ",
    "“": '"',
    "”": '"',
    "‘": "'",
    "’": "'",
    "—": "-",
    "–": "-",
    "…": "...",
  };

  for (const [k,v] of Object.entries(replacements)) {
    str = str.replaceAll(k, v);
  }

  return str;
}

window.onload = () => {
  const ANIMATION_FRAMES = ["   ", ".  ", ".. ", "...", " ..", "  ."];

  const loadContainerParent = document.getElementById("load-container-parent");
  const loadContainer = document.getElementById("load-container");
  const error = document.getElementById("error");
  const accessKey = document.getElementById("access-key");
  const documentFile = document.getElementById("document-file");
  const startButton = document.getElementById("start-button");
  const statusContainer = document.getElementById("status-container");
  const status = document.getElementById("status");
  const statusSpinner = document.getElementById("status-spinner");

  const mainContainerParent = document.getElementById("main-container-parent");
  const mainContainer = document.getElementById("main-container");

  const chatBlock = document.getElementById("chat-block");
  const volumeMeterUser = document.getElementById('volume-meter-user');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');
  const aiState = document.getElementById("ai-state-text");

  const skipAudioButton = document.getElementById("skip-audio");
  const resetDemoButton = document.getElementById("reset-demo");

  const hudContainer = document.getElementById("hud-container");
  const hudOptions = document.getElementById("hud-options");

  const writeError = async (errorString) => {
    error.classList.remove('hidden');
    error.innerText = `Error: ${errorString}`;
    startButton.disabled = false;

    status.innerHTML = "";
    statusSpinner.style.opacity = "0";
    await Picovoice.sleep(400);

    statusSpinner.style.display = "none";
  };
  const clearError = () => {
    error.classList.add('hidden');
    error.innerText = "";
  };

  const writeStatus = (statusString) => {
    statusContainer.style.display = 'flex';
    status.innerText = statusString;
  };

  const onVolumeCallback = volume => {
    const baseHeight = 8;
    bar1.style.height = `${baseHeight + volume * 20}px`;
    bar2.style.height = `${baseHeight + volume * 32}px`;
    bar3.style.height = `${baseHeight + volume * 24}px`;
  };

  let hudFadeoutTimeoutHandle = null;
  let hudFadeoutEndStateFunction = null;

  const restartDemo = async () => {
    startButton.removeAttribute("disabled");
    startButton.setAttribute("coloured", "");

    await Picovoice.sleep(200);
    startButton.disabled = false;

    loadContainer.style.opacity = "1";
    mainContainer.style.opacity = "0";
    loadContainerParent.style.visibility = "visible";

    await Picovoice.sleep(700);

    mainContainerParent.style.visibility = "hidden";

    chatBlock.replaceChildren();

    if (hudFadeoutTimeoutHandle && hudFadeoutEndStateFunction) {
      clearTimeout(hudFadeoutTimeoutHandle);
      hudFadeoutEndStateFunction();
    }
  };

  let currentBubbleText = "";
  let currentBubbleDots = "";

  let dotIdx = 0;
  let bubbleDotInterval = null;

  const startBubbleDot = () => {
    if (bubbleDotInterval) {
      clearInterval(bubbleDotInterval);
    }
    bubbleDotInterval = setInterval(() => {
      dotIdx = (dotIdx + 1) % ANIMATION_FRAMES.length;
      currentBubbleDots = ANIMATION_FRAMES[dotIdx];

      chatBlock.lastElementChild.innerHTML = currentBubbleText + currentBubbleDots;
    }, 200);
  }
  const stopBubbleDot = () => {
    if (bubbleDotInterval) {
      clearInterval(bubbleDotInterval);
    }
    dotIdx = 0;
    currentBubbleDots = '';

    chatBlock.lastElementChild.innerHTML = currentBubbleText;
  }

  const sendMessage = async (message, obj) => {
    if (message === "SET_STATUS") {
      let text = obj;
      writeStatus(text);

    } else if (message === "ADD_TO_BUBBLE") {
      let text = obj;
      if (text.length === 0)
        return;

      currentBubbleText += text;
      chatBlock.lastElementChild.innerHTML = currentBubbleText + currentBubbleDots;
      if (chatBlock.lastElementChild.innerHTML.length > 0) {
        chatBlock.lastElementChild.style.opacity = "1";
      }

    } else if (message === "NEW_USER_BUBBLE") {
      let text = obj;
      let bubble = document.createElement("div");
      bubble.classList.add("user-bubble");
      bubble.style.opacity = "0";
      bubble.innerHTML += text;
      if (bubble.innerHTML.length > 0) {
        bubble.style.opacity = "1";
      }

      currentBubbleText = text;
      chatBlock.appendChild(bubble);

    } else if (message === "NEW_AI_BUBBLE") {
      let text = obj;
      let bubble = document.createElement("div");
      bubble.classList.add("ai-bubble");
      bubble.style.opacity = "0";
      bubble.innerHTML += text;
      if (bubble.innerHTML.length > 0) {
        bubble.style.opacity = "1";
      }

      currentBubbleText = text;
      chatBlock.appendChild(bubble);

    } else if (message === "START_LISTENING") {
      volumeMeterUser.style.opacity = "1";
      startBubbleDot();

    } else if (message === "STOP_LISTENING") {
      volumeMeterUser.style.opacity = "0";
      stopBubbleDot();

    } else if (message === "START_SPEAKING") {
      stopBubbleDot();
      skipAudioButton.disabled = false;
      skipAudioButton.setAttribute("coloured", "");

    } else if (message === "STOP_SPEAKING") {
      skipAudioButton.disabled = true;
      skipAudioButton.removeAttribute("coloured");

    } else if (message === "START_LLM_SPINNER") {
      startBubbleDot();
      resetDemoButton.disabled = true;
      resetDemoButton.removeAttribute("coloured");

    } else if (message === "STOP_LLM_SPINNER") {
      stopBubbleDot();
      resetDemoButton.disabled = false;
      resetDemoButton.setAttribute("coloured", "");

    } else if (message === "SET_AI_STATE") {
      let text = obj;
      aiState.innerHTML = text;

    } else if (message === "RESTART_DEMO") {
      restartDemo();
    }
  };

  const makeRequest = (message) => {
    if (message === "BUBBLE_CONTENTS") {
      return chatBlock.lastElementChild.innerHTML;
    }
  };

  if (typeof Picovoice === 'undefined') {
    writeError("You must run `yarn build` before running yarn start");
    return;
  }

  let startFunction = null;
  let resetDemo = null;

  startButton.addEventListener("click", async () => {
    if (accessKey.value.length <= 0) {
      writeError("Please input your accessKey");
      return;
    } else if (documentFile.value.length <= 0) {
      writeError("Please select a document");
      return;
    }

    startButton.disabled = true;

    try {
      await Picovoice.updateStartParameters(documentFile.files[0]);

      writeStatus("Loading");
      statusSpinner.style.display = "inline";
      statusSpinner.style.opacity = "1";

      const functions = await Picovoice.init(
        accessKey.value,
        documentFile.files[0],
        sendMessage,
        makeRequest,
        onVolumeCallback,
      );
      startFunction = functions.startFunction;
      resetDemo = functions.resetDemo;
    } catch (e) {
      writeError(e.message);
      return;
    }

    if (startFunction === null) {
      writeError("Loading failed");
      return;
    }

    writeStatus("");
    statusSpinner.style.opacity = "0";
    await Picovoice.sleep(400);

    statusSpinner.style.display = "none";
    clearError();

    loadContainer.style.opacity = "0";
    mainContainer.style.opacity = "1";
    mainContainerParent.style.visibility = "visible";

    await Picovoice.sleep(700);

    loadContainerParent.style.visibility = "hidden";
    accessKey.style.display = "none";

    try {
      await startFunction();
    } catch (e) {
      writeError(e.message);
    }
  });

  resetDemoButton.addEventListener("click", async () => {
    await resetDemo();
  });

  skipAudioButton.addEventListener("click", async () => {
    await Picovoice.skipAudio();
  });
};
