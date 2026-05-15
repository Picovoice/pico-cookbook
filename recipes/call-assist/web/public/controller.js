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
  const name = document.getElementById("name");
  const startButton = document.getElementById("start-button");
  const statusContainer = document.getElementById("status-container");
  const status = document.getElementById("status");
  const statusSpinner = document.getElementById("status-spinner");

  const mainContainerParent = document.getElementById("main-container-parent");
  const mainContainer = document.getElementById("main-container");

  const chatBlock = document.getElementById("chat-block");
  const volumeMeterCaller = document.getElementById('volume-meter-caller');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');
  const aiState = document.getElementById("ai-state-text");
  const llmSpinner = document.getElementById("llm-spinner");

  const report = document.getElementById("report");

  const hudContainer = document.getElementById("hud-container");
  const hudOptions = document.getElementById("hud-options");
  const volumeMeterUser = document.getElementById('volume-meter-user');
  const bar4 = document.getElementById('bar4');
  const bar5 = document.getElementById('bar5');
  const bar6 = document.getElementById('bar6');

  const writeError = (errorString) => {
    error.classList.remove('hidden');
    error.innerText = `Error: ${errorString}`;
    startButton.disabled = false;
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

    bar4.style.height = `${baseHeight + volume * 20}px`;
    bar5.style.height = `${baseHeight + volume * 32}px`;
    bar6.style.height = `${baseHeight + volume * 24}px`;
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

    report.innerHTML = "";

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

    } else if (message === "NEW_CALLER_BUBBLE") {
      let text = obj;
      let bubble = document.createElement("div");
      bubble.classList.add("caller-bubble");
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
      volumeMeterCaller.style.opacity = "1";
      startBubbleDot();

    } else if (message === "STOP_LISTENING") {
      volumeMeterCaller.style.opacity = "0";
      stopBubbleDot();

    } else if (message === "GIVE_USER_OPTIONS") {
      volumeMeterUser.style.opacity = "1";
      let text = obj;

      hudOptions.replaceChildren();
      for (text of text.split(",")) {
        if (text.length === 0)
          continue;

        let option = document.createElement("div");
        option.innerHTML = text;
        option.id = "option-" + text.replace(" ", "_").toLowerCase();

        let tooltip = document.createElement("span");
        tooltip.classList.add("tooltip");
        tooltip.innerHTML = "To select this voice command, speak into your microphone"
        
        option.appendChild(tooltip);
        
        hudOptions.appendChild(option);
      }

      hudContainer.style.opacity = "1";

    } else if (message === "SELECT_OPTION") {
      volumeMeterUser.style.opacity = "0";
      let text = obj;

      let element = document.getElementById("option-" + text.replace(" ", "_").toLowerCase());
      if (element) {
        element.setAttribute("coloured", "");

        hudFadeoutEndStateFunction = () => {
            hudContainer.style.opacity = "0";
            element.removeAttribute("coloured");
        };
        hudFadeoutTimeoutHandle = setTimeout(hudFadeoutEndStateFunction, 2000);
      }

    } else if (message === "SET_AI_STATE") {
      let text = obj;
      aiState.innerHTML = text;

    } else if (message === "RESTART_DEMO") {
      restartDemo();
    
    } else if (message === "ADD_TO_AI_REPORT") {
      let text = obj;
      report.innerHTML += text + "\n";
    
    } else if (message === "START_LLM_SPINNER") {
      llmSpinner.style.display = "inline";
      llmSpinner.style.opacity = "1";

    } else if (message === "STOP_LLM_SPINNER") {
      llmSpinner.style.opacity = "0";
      await Picovoice.sleep(400);

      llmSpinner.style.display = "none";
    }
  };

  const makeRequest = (message) => {
    if (message === "BUBBLE_LENGTH") {
      return chatBlock.lastElementChild.innerHTML.length;
    } else if (message === "BUBBLE_CONTENTS") {
      return chatBlock.lastElementChild.innerHTML;
    }
  };

  if (typeof Picovoice === 'undefined') {
    writeError("You must run `yarn build` before running yarn start");
    return;
  }

  let startFunction = null;

  startButton.addEventListener("click", async () => {
    if (accessKey.value.length <= 0) {
      writeError("Please input your accessKey");
      return;
    } else if (sanitizeForOrca(name.value).replaceAll(" ", "").length <= 0) {
      writeError("Please input your name");
      return;
    }

    startButton.disabled = true;
    startButton.setAttribute("disabled", "");
    startButton.removeAttribute("coloured");

    await Picovoice.updateStartParameters(sanitizeForOrca(name.value));

    try {
      writeStatus("Loading");
      statusSpinner.style.display = "inline";
      statusSpinner.style.opacity = "1";

      startFunction = await Picovoice.init(
        accessKey.value,
        sanitizeForOrca(name.value),
        sendMessage,
        makeRequest,
        onVolumeCallback,
      );
    } catch (e) {
      writeError(e.message);
      return;
    }

    if (startFunction === null) {
      writeError("Loading failed");
      return;
    } else {
      writeStatus("");

      statusSpinner.style.opacity = "0";
      await Picovoice.sleep(400);

      statusSpinner.style.display = "none";
    }

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
};